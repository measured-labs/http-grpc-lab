#!/usr/bin/env bash
# 다시 재기(2026-11) 지연 조건 — 세 지연(0, 1, 10ms)을 회차마다 번갈아 잰다.
# 회차 r마다 run.sh --only-round r --delay X 를 세 지연에 한 번씩 부르고, 지연의 순서를 회차마다 돌린다
# (1회차 0→1→10, 2회차 1→10→0, 3회차 10→0→1, …). 몇 시간에 걸친 머신 부하의 변화가 한 지연에 몰리지 않게.
# 쓰는 법: scripts/remeasure/README.md "지연 조건"
set -uo pipefail

usage() {
  cat <<'EOF'
사용: scripts/remeasure/run-delays.sh [옵션]
  --axis A           size|concurrency|complexity (기본 size)
  --config C         default|matched (기본 default)
  --rounds N         회차 수 (기본 5)
  --duration D       측정 한 번의 길이 (run.sh 기본 30s)
  --max-load X       run.sh에 그대로
  --max-wait S       run.sh에 그대로
  --conditions A,B   run.sh에 그대로(짧은 확인용 — 본 측정은 전체)
  --label-prefix P   이름표 앞에 붙인다(기본 없음 → delay0ms, delay1ms, delay10ms). 확인 실행은 smoke- 등으로 본 측정 폴더와 나눈다
실행 순서와 시각: results/2026-11/<P>delay-run-order-<축>-<설정>.json
EOF
}

AXIS=size; CONFIG=default; ROUNDS=5; PREFIX=""; PASS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --axis) AXIS="$2"; shift 2 ;;
    --config) CONFIG="$2"; shift 2 ;;
    --rounds) ROUNDS="$2"; shift 2 ;;
    --duration|--max-load|--max-wait|--conditions) PASS+=("$1" "$2"); shift 2 ;;
    --label-prefix) PREFIX="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "모르는 인자: $1" >&2; usage >&2; exit 2 ;;
  esac
done

die() { echo "중단: $*" >&2; exit 1; }
[[ "$ROUNDS" =~ ^[1-9][0-9]*$ ]] || die "회차 수가 잘못됨: $ROUNDS"
[[ "$PREFIX" =~ ^[A-Za-z0-9._-]*$ ]] || die "이름표 접두가 잘못됨: $PREFIX"

DELAYS=(0 1 10)
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RUN="$ROOT/scripts/remeasure/run.sh"
RES="$ROOT/results/2026-11"
ORDER_FILE="$RES/${PREFIX}delay-run-order-$AXIS-$CONFIG.json"
[ -e "$ORDER_FILE" ] && die "이미 있다: $ORDER_FILE (덮어쓰지 않는다)"
# run.sh도 회차마다 거절하지만, 몇 시간 뒤에 멈추지 않게 처음에 모두 본다
for d in "${DELAYS[@]}"; do for r in $(seq 1 "$ROUNDS"); do
  f="$RES/${PREFIX}delay${d}ms/$AXIS-$CONFIG/round$r"
  [ -e "$f" ] && die "이미 있다: $f"
done; done
mkdir -p "$RES"

RUNS="$(mktemp)"; trap 'rm -f "$RUNS"' EXIT
STARTED_AT="$(date -Iseconds)"
COMMIT="$(git -C "$ROOT" rev-parse HEAD)"
# 실행이 하나 끝날 때마다 다시 쓴다(중간에 멈춰도 그때까지의 순서가 남는다). $1 = running | complete | failed
write_order() {
  STATUS="$1" AXIS="$AXIS" CONFIG="$CONFIG" ROUNDS="$ROUNDS" PREFIX="$PREFIX" STARTED_AT="$STARTED_AT" COMMIT="$COMMIT" \
    PASSED_ARGS="${PASS[*]}" python3 - "$RUNS" "$ORDER_FILE" <<'EOF'
import json, os, sys
e = os.environ
runs = []
for line in open(sys.argv[1]).read().splitlines():
    r, k, d, label, t0, t1, rc, rdir = line.split("\t")
    runs.append({"round": int(r), "orderInRound": int(k), "delayMs": int(d), "label": label,
                 "startedAt": t0, "endedAt": t1, "exitCode": int(rc), "roundDir": rdir})
doc = {"status": e["STATUS"], "axis": e["AXIS"], "config": e["CONFIG"], "rounds": int(e["ROUNDS"]),
       "labelPrefix": e["PREFIX"], "delaysMs": [0, 1, 10],
       "rule": "회차 r의 지연 순서 = (0, 1, 10)을 r−1만큼 돌린 것. 실행마다 run.sh --only-round r --delay X",
       "passedArgs": e["PASSED_ARGS"], "commitAtStart": e["COMMIT"], "startedAt": e["STARTED_AT"], "runs": runs}
tmp = sys.argv[2] + ".tmp"
open(tmp, "w").write(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
os.replace(tmp, sys.argv[2])
EOF
}

# Ctrl-C는 지금 도는 run.sh가 받아 정리한다. 여기서는 멈추고 순서 파일에 남긴다.
STOP=0
trap 'STOP=1; echo "신호를 받았다 — 지금 실행이 정리되면 멈춘다" >&2' INT TERM
write_order running
NDELAY=${#DELAYS[@]}
first=1
for r in $(seq 1 "$ROUNDS"); do
  ORDER=(); for i in $(seq 0 $((NDELAY - 1))); do ORDER+=("${DELAYS[$(( (i + r - 1) % NDELAY ))]}"); done
  echo "== 회차 $r/$ROUNDS — 지연 순서: ${ORDER[*]} (ms)"
  k=0
  for d in "${ORDER[@]}"; do
    k=$((k + 1))
    label="${PREFIX}delay${d}ms"
    BUILD_ARGS=(); [ "$first" -eq 1 ] || BUILD_ARGS=(--no-build)  # jar와 이미지는 처음 한 번만 만든다
    t0="$(date -Iseconds)"
    "$RUN" --axis "$AXIS" --config "$CONFIG" --rounds "$ROUNDS" --only-round "$r" --delay "$d" --label "$label" \
      "${PASS[@]}" "${BUILD_ARGS[@]}"
    rc=$?
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$r" "$k" "$d" "$label" "$t0" "$(date -Iseconds)" "$rc" \
      "results/2026-11/$label/$AXIS-$CONFIG/round$r" >> "$RUNS"
    first=0
    if [ "$rc" -ne 0 ]; then
      write_order failed
      die "회차 $r 지연 ${d}ms 실행이 종료 코드 $rc로 끝났다 — 여기서 멈춘다(원자료는 남긴다). 순서: $ORDER_FILE"
    fi
    if [ "$STOP" -eq 1 ]; then
      write_order interrupted
      die "신호를 받아 회차 $r 지연 ${d}ms 뒤에 멈춘다(원자료는 남긴다). 순서: $ORDER_FILE"
    fi
    write_order running
  done
done
write_order complete
echo "끝 — 순서: $ORDER_FILE"
for d in "${DELAYS[@]}"; do echo "표: python3 scripts/remeasure/summarize.py results/2026-11/${PREFIX}delay${d}ms/$AXIS-$CONFIG"; done
