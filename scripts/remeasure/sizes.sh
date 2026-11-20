#!/usr/bin/env bash
# 다시 재기(2026-11) — 두 서버 사이(dataServer → apiServer)의 응답 본문 크기를 조건과 경로마다 한 번 잰다.
# 데이터가 고정이라(크기 축은 서버 시작 때 만든 바이트, 복잡도 축은 시드를 고정한 객체) 조건마다 크기가 일정하다.
# k6의 data_received는 apiServer → k6의 작은 JSON이라 이 구간의 전송량이 아니다.
# 쓰는 법: scripts/remeasure/README.md
set -uo pipefail

usage() {
  cat <<'EOF'
사용: scripts/remeasure/sizes.sh [옵션]
  -o FILE          결과 JSON (기본 results/2026-11/sizes.json)
  --no-build       ./gradlew bootJar를 돌리지 않는다(있는 jar로 잰다)
  --allow-dirty    추적 파일에 커밋하지 않은 변경이 있어도 잰다(JSON에 표시)
EOF
}

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/scripts/remeasure"
OUT_JSON="$ROOT/results/2026-11/sizes.json"
BUILD=1; ALLOW_DIRTY=0
while [ $# -gt 0 ]; do
  case "$1" in
    -o) OUT_JSON="$2"; shift 2 ;;
    --no-build) BUILD=0; shift ;;
    --allow-dirty) ALLOW_DIRTY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "모르는 인자: $1" >&2; usage >&2; exit 2 ;;
  esac
done

die() { echo "중단: $*" >&2; exit 1; }
DATA_JAR="$ROOT/dataServer/build/libs/dataServer-0.0.1-SNAPSHOT.jar"
DATA="http://localhost:8081"
# apiServer가 보내는 requestId는 UUID 문자열(36자)이다. 같은 길이의 고정 값으로 잰다.
REQUEST_ID="00000000-0000-0000-0000-000000000000"

for t in java curl ss python3; do command -v "$t" >/dev/null || die "$t 가 없다"; done
for p in 8081 9091; do ss -Htln "sport = :$p" | grep -q . && die "포트 $p 가 쓰이고 있다"; done

DIRTY_CODE="$(git -C "$ROOT" status --porcelain --untracked-files=no -- . ':(exclude)docs/plans')"
[ -z "$DIRTY_CODE" ] || [ "$ALLOW_DIRTY" -eq 1 ] || die "추적 파일에 커밋하지 않은 변경이 있다(--allow-dirty로 풀 수 있다):
$DIRTY_CODE"
if [ "$BUILD" -eq 1 ]; then
  echo "빌드: dataServer ./gradlew bootJar"
  (cd "$ROOT/dataServer" && ./gradlew -q bootJar) || die "dataServer 빌드 실패"
fi
[ -f "$DATA_JAR" ] || die "jar가 없다: $DATA_JAR"

WORK="$(mktemp -d)"
DATA_PID=""
cleanup() {
  local rc=$?
  if [ -n "$DATA_PID" ]; then
    kill -TERM "$DATA_PID" 2>/dev/null
    for _ in $(seq 1 30); do kill -0 "$DATA_PID" 2>/dev/null || break; sleep 0.5; done
    kill -KILL "$DATA_PID" 2>/dev/null; wait "$DATA_PID" 2>/dev/null
  fi
  rm -rf "$WORK"
  exit $rc
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# 응답 본문은 설정(default/matched)과 무관하다 — default로 띄운다
env -u SPRING_PROFILES_ACTIVE -u BENCH_MAX_CONCURRENCY java -jar "$DATA_JAR" > "$WORK/dataServer.log" 2>&1 &
DATA_PID=$!
echo "dataServer 시작 (pid $DATA_PID)"
for i in $(seq 1 120); do
  kill -0 "$DATA_PID" 2>/dev/null || die "dataServer가 죽었다: $(tail -5 "$WORK/dataServer.log")"
  curl -sf -o /dev/null "$DATA/actuator/health" && break
  [ "$i" -eq 120 ] && die "60초 안에 뜨지 않았다"
  sleep 0.5
done

# 한 줄 = 축 조건 경로 엔드포인트 (조건의 순서는 run.sh와 같다)
: > "$WORK/targets"
for s in 1kb 10kb 50kb 100kb 200kb 500kb 1mb; do
  echo "size $s http-json /data/json?size=$s"
  echo "size $s http-bytes /data/binary?size=$s"
  echo "size $s http-proto /data/proto?size=$s"
done >> "$WORK/targets"
for v in 10 50 100 200 500; do
  echo "concurrency vus$v http-json /data/json?size=10kb"
  echo "concurrency vus$v http-bytes /data/binary?size=10kb"
  echo "concurrency vus$v http-proto /data/proto?size=10kb"
done >> "$WORK/targets"
for c in simple medium complex ultra extreme; do
  echo "complexity $c http-json /data/complex/json?complexity=$c"
  echo "complexity $c http-proto /data/complex/binary?complexity=$c"
done >> "$WORK/targets"

# 한 번 불러 본문 바이트 수(압축을 풀지 않은 그대로)와 머리를 남긴다.
# apiServer의 클라이언트처럼 Accept-Encoding: gzip을 보낸다(dataServer는 압축하지 않는다 — 머리로 확인).
: > "$WORK/rows.tsv"
n=0
while read -r axis cond path ep; do
  n=$((n + 1))
  hdr="$WORK/h$n"
  bytes="$(curl -sS -o /dev/null -D "$hdr" -H 'Accept-Encoding: gzip' -w '%{size_download}' \
    "$DATA$ep&requestId=$REQUEST_ID")" || die "부르지 못함: $ep"
  status="$(head -1 "$hdr" | awk '{print $2}')"
  [ "$status" = 200 ] || die "$ep → HTTP $status"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$axis" "$cond" "$path" "$ep" "$bytes" "$hdr" >> "$WORK/rows.tsv"
done < "$WORK/targets"
echo "$n개를 쟀다"

COMMIT="$(git -C "$ROOT" rev-parse HEAD)"
JAR_ID="$(basename "$DATA_JAR") sha256=$(sha256sum "$DATA_JAR" | cut -c1-16) built=$(date -Iseconds -r "$DATA_JAR")"
mkdir -p "$(dirname "$OUT_JSON")"
COMMIT="$COMMIT" JAR_ID="$JAR_ID" REQUEST_ID="$REQUEST_ID" DIRTY_CODE="$DIRTY_CODE" ALLOW_DIRTY="$ALLOW_DIRTY" BUILD="$BUILD" \
  python3 - "$WORK/rows.tsv" "$OUT_JSON" <<'PY'
import json, os, sys, datetime
rows, out = sys.argv[1], sys.argv[2]
env = os.environ

def headers(path):
    h = {}
    for line in open(path, encoding="latin-1").read().splitlines()[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            h[k.strip().lower()] = v.strip()
    return h

axes = {}
for line in open(rows):
    axis, cond, path, ep, nbytes, hdr = line.rstrip("\n").split("\t")
    h = headers(hdr)
    cl = h.get("content-length")
    entry = {
        "endpoint": ep,
        "bodyBytes": int(nbytes),
        "contentLength": cl is not None,
        "contentType": h.get("content-type"),
        "transferEncoding": h.get("transfer-encoding"),
        "contentEncoding": h.get("content-encoding"),
    }
    if cl is not None and int(cl) != int(nbytes):
        sys.exit(f"Content-Length {cl} ≠ 받은 바이트 {nbytes}: {ep}")
    if h.get("content-encoding"):
        sys.exit(f"응답이 압축됐다({h['content-encoding']}): {ep} — 이 표의 가정과 다르다")
    axes.setdefault(axis, {}).setdefault(cond, {})[path] = entry

for axis, conds in axes.items():
    for cond, paths in conds.items():
        proto = paths["http-proto"]["bodyBytes"]
        paths["grpc-unary"] = {
            "measured": False,
            "protobufMessageBytes": proto,
            "grpcLengthPrefixedBytes": proto + 5,
            "note": "직접 재지 않았다. http-proto의 본문과 같은 Protobuf 메시지다(PathEquivalenceTest가 두 메시지가 같음을 확인). "
                    "gRPC는 그 앞에 메시지 머리 5바이트(압축 표시 1 + 길이 4)를 붙인다. "
                    "HTTP/2 프레임 머리(DATA 프레임마다 9바이트)와 HEADERS 프레임은 따로이고 여기에 넣지 않았다.",
        }

doc = {
    "what": "dataServer → apiServer 응답 본문의 바이트 수(조건과 경로마다 한 번). HTTP 머리, 청크 머리(chunked), TCP/IP는 넣지 않았다.",
    "measuredAt": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
    "commit": env["COMMIT"],
    "trackedFilesDirty": [x for x in env.get("DIRTY_CODE", "").splitlines() if x],
    "allowDirty": env.get("ALLOW_DIRTY") == "1",
    "jarBuiltAtStart": env.get("BUILD") == "1",
    "dataJar": env["JAR_ID"],
    "requestId": env["REQUEST_ID"],
    "requestIdNote": "본문에 requestId가 들어간다. apiServer는 UUID 문자열(36자)을 보내므로 같은 길이의 고정 값으로 쟀다. "
                     "길이가 다르면 그 차이만큼(JSON은 문자 수, Protobuf는 문자 수와 길이 바이트) 본문이 달라진다. "
                     "timestamp(밀리초, 13자리 / varint 6바이트)도 본문에 들어가지만 길이는 같다.",
    "notes": [
        "크기 축과 동시 사용자 축은 같은 엔드포인트다. 동시 사용자 축은 모든 조건이 10kb라 값이 같다.",
        "http-json(크기 축)의 payload는 Base64라 원래 크기의 약 1.33배다.",
        "JSON 응답은 Content-Length 없이 chunked로 간다(남긴 차이 H). bodyBytes는 청크 머리를 뺀 본문이다.",
        "복잡도 축 데이터는 난수 시드를 고정해 서버를 다시 띄워도 같다.",
    ],
    "axes": axes,
}
tmp = out + ".tmp"
with open(tmp, "w") as f:
    f.write(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
os.replace(tmp, out)

# 사람이 읽는 표
for axis, conds in axes.items():
    paths = [p for p in ("http-json", "http-bytes", "http-proto") if p in next(iter(conds.values()))]
    print(f"\n## {axis}\n")
    print("| 조건 | " + " | ".join(f"`{p}`" for p in paths) + " | `grpc-unary` (proto + 5) |")
    print("|" + "------|" * (len(paths) + 2))
    for cond, ps in conds.items():
        cells = [f"{ps[p]['bodyBytes']:,}" + ("" if ps[p]["contentLength"] else " (chunked)") for p in paths]
        print(f"| {cond} | " + " | ".join(cells) + f" | {ps['grpc-unary']['grpcLengthPrefixedBytes']:,} |")
print(f"\n→ {out}")
PY
