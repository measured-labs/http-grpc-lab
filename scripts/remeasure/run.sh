#!/usr/bin/env bash
# 다시 재기(2026-11) — 한 축을 정해진 횟수만큼 반복해 재고 원자료를 남긴다.
# 쓰는 법: scripts/remeasure/README.md
set -uo pipefail

usage() {
  cat <<'EOF'
사용: scripts/remeasure/run.sh --axis size|concurrency|complexity --config default|matched [옵션]
  --rounds N         반복 횟수 (기본 5)               [ROUNDS]
  --duration D       측정 한 번의 길이 (기본 30s)     [DURATION]
  --label NAME       결과 이름표 (기본 nodelay, --delay X 이면 delayXms) [LABEL]
  --delay MS         두 서버를 컨테이너로 띄우고 dataServer에서 나가는 쪽에 MS 밀리초 지연(0|1|10) [DELAY]
                     (docker-compose.remeasure.yml — 없으면 호스트에서 jar를 직접 띄운다)
  --max-load X       회차를 시작할 1분 부하 평균의 기준 (기본 4.0) [MAX_LOAD]
  --max-wait S       부하가 기준 아래로 내려가길 기다리는 최대 초 (기본 600) [MAX_WAIT]
                     넘으면 멈추지 않고 시작하고 conditions.json에 적는다
  --conditions A,B   축의 조건 중 일부만 (기본 전체 — 본 측정은 전체) [ONLY_CONDITIONS]
  --no-build         시작할 때 ./gradlew bootJar를 돌리지 않는다(있는 jar로 잰다)
  --allow-dirty      추적 파일에 커밋하지 않은 변경이 있어도 시작한다(conditions.json에 표시)
  --warmup N         워밍업: 조건 × 경로마다 부를 횟수 (기본 200) [WARMUP]
  --warmup-vus N     워밍업의 동시 사용자 수 (기본 10) [WARMUP_VUS]
  --pause S          측정 사이에 쉬는 초 (기본 3)     [PAUSE]
  축과 설정도 환경 변수 AXIS, CONFIG로 줄 수 있다.
EOF
}

AXIS="${AXIS:-}"; CONFIG="${CONFIG:-}"
ROUNDS="${ROUNDS:-5}"; DURATION="${DURATION:-30s}"; LABEL="${LABEL:-}"; DELAY="${DELAY:-}"
MAX_LOAD="${MAX_LOAD:-4.0}"; WARMUP="${WARMUP:-200}"; WARMUP_VUS="${WARMUP_VUS:-10}"; PAUSE="${PAUSE:-3}"
MAX_WAIT="${MAX_WAIT:-600}"; ONLY_CONDITIONS="${ONLY_CONDITIONS:-}"; BUILD=1; ALLOW_DIRTY=0
LOAD_CHECK_INTERVAL=10

while [ $# -gt 0 ]; do
  case "$1" in
    --axis) AXIS="$2"; shift 2 ;;
    --config) CONFIG="$2"; shift 2 ;;
    --rounds) ROUNDS="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    --delay) DELAY="$2"; shift 2 ;;
    --max-load) MAX_LOAD="$2"; shift 2 ;;
    --warmup) WARMUP="$2"; shift 2 ;;
    --warmup-vus) WARMUP_VUS="$2"; shift 2 ;;
    --pause) PAUSE="$2"; shift 2 ;;
    --max-wait) MAX_WAIT="$2"; shift 2 ;;
    --conditions) ONLY_CONDITIONS="$2"; shift 2 ;;
    --no-build) BUILD=0; shift ;;
    --allow-dirty) ALLOW_DIRTY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "모르는 인자: $1" >&2; usage >&2; exit 2 ;;
  esac
done

die() { echo "중단: $*" >&2; exit 1; }

case "$CONFIG" in default|matched) ;; *) usage >&2; die "설정은 default 또는 matched";; esac
[[ "$ROUNDS" =~ ^[1-9][0-9]*$ ]] || die "반복 횟수가 잘못됨: $ROUNDS"
# --delay: 있으면 컨테이너 방식(CONTAINER=1)
CONTAINER=0
if [ -n "$DELAY" ]; then
  case "$DELAY" in 0|1|10) CONTAINER=1 ;; *) die "지연은 0, 1, 10(밀리초) 중 하나: $DELAY" ;; esac
fi
if [ -z "$LABEL" ]; then
  if [ "$CONTAINER" -eq 1 ]; then LABEL="delay${DELAY}ms"; else LABEL="nodelay"; fi
fi
[[ "$WARMUP" =~ ^[0-9]+$ ]] || die "워밍업 횟수가 잘못됨: $WARMUP"
[[ "$LABEL" =~ ^[A-Za-z0-9._-]+$ ]] || die "이름표가 잘못됨: $LABEL"
[[ "$MAX_WAIT" =~ ^[0-9]+$ ]] || die "최대 대기 시간이 잘못됨: $MAX_WAIT"

# 축의 조건: "조건이름|PARAM|VUS" (조건의 순서는 고정)
case "$AXIS" in
  size)
    CONDITIONS=(); for s in 1kb 10kb 50kb 100kb 200kb 500kb 1mb; do CONDITIONS+=("$s|$s|10"); done
    PATHS=(http-json http-bytes http-proto grpc-unary) ;;
  concurrency)
    CONDITIONS=(); for v in 10 50 100 200 500; do CONDITIONS+=("vus$v|10kb|$v"); done
    PATHS=(http-json http-bytes http-proto grpc-unary) ;;
  complexity)
    CONDITIONS=(); for c in simple medium complex ultra extreme; do CONDITIONS+=("$c|$c|10"); done
    PATHS=(http-json http-proto grpc-unary) ;;
  *) usage >&2; die "축은 size, concurrency, complexity 중 하나" ;;
esac

# --conditions: 축의 조건 중 일부만 (순서는 축의 순서 그대로)
if [ -n "$ONLY_CONDITIONS" ]; then
  IFS=',' read -r -a want <<< "$ONLY_CONDITIONS"
  for w in "${want[@]}"; do
    printf '%s\n' "${CONDITIONS[@]}" | grep -q "^$w|" || die "이 축에 없는 조건: $w"
  done
  KEEP=(); for c in "${CONDITIONS[@]}"; do
    for w in "${want[@]}"; do [ "${c%%|*}" = "$w" ] && KEEP+=("$c"); done
  done
  CONDITIONS=("${KEEP[@]}")
fi

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/scripts/remeasure"
DATA_JAR="$ROOT/dataServer/build/libs/dataServer-0.0.1-SNAPSHOT.jar"
API_JAR="$ROOT/apiServer/build/libs/apiServer-0.0.1-SNAPSHOT.jar"
OUT="$ROOT/results/2026-11/$LABEL/$AXIS-$CONFIG"
PORTS=(8080 8081 9091)
COMPOSE_FILE="$ROOT/docker-compose.remeasure.yml"
COMPOSE_PROJECT=rm-http-grpc
# 컨테이너 방식에서는 dataServer의 포트를 호스트에 열지 않으므로 8080만 본다
[ "$CONTAINER" -eq 1 ] && PORTS=(8080)

# ---------- 실행 전 확인 ----------
for t in java k6 curl ss python3; do command -v "$t" >/dev/null || die "$t 가 없다"; done
if [ "$CONTAINER" -eq 1 ]; then
  command -v docker >/dev/null || die "docker 가 없다"
  docker compose version >/dev/null 2>&1 || die "docker compose 가 없다"
  [ -f "$COMPOSE_FILE" ] || die "없다: $COMPOSE_FILE"
fi
# 이 프로젝트(rm-http-grpc)의 compose만 다룬다. 시간 제한을 건다.
export DELAY_MS="$DELAY"
dc() { timeout 180 docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" "$@"; }
if [ "$CONTAINER" -eq 1 ]; then
  left="$(docker ps -a -q --filter "label=com.docker.compose.project=$COMPOSE_PROJECT")"
  [ -z "$left" ] || die "이전 실행의 $COMPOSE_PROJECT 컨테이너가 남아 있다 — 확인 뒤 DELAY_MS=0 docker compose -f docker-compose.remeasure.yml down"
fi

busy_ports() { local p out=""; for p in "${PORTS[@]}"; do ss -Htln "sport = :$p" | grep -q . && out+="$p "; done; echo "$out"; }
b="$(busy_ports)"; [ -z "$b" ] || die "포트가 쓰이고 있다: $b(이 실험의 서버는 8080·8081·9091이 비어 있을 때만 띄운다)"

# 추적 파일에 커밋하지 않은 변경이 있으면 시작하지 않는다(docs/plans/는 작업 기록이라 뺀다)
DIRTY_CODE="$(git -C "$ROOT" status --porcelain --untracked-files=no -- . ':(exclude)docs/plans')"
if [ -n "$DIRTY_CODE" ] && [ "$ALLOW_DIRTY" -ne 1 ]; then
  die "추적 파일에 커밋하지 않은 변경이 있다(--allow-dirty로 풀 수 있다):
$DIRTY_CODE"
fi

load1() { cut -d' ' -f1 /proc/loadavg; }
above() { awk -v l="$1" -v m="$2" 'BEGIN{exit !(l>m)}'; }  # $1 > $2 이면 참
L="$(load1)"
if above "$L" "$(awk -v m="$MAX_LOAD" 'BEGIN{print m*3}')"; then
  echo "1분 부하 평균 $L 이 기준 $MAX_LOAD 의 3배를 넘는다 — 다른 작업이 돌고 있을 수 있다. CPU 상위 프로세스:"
  ps -eo pid,pcpu,etime,comm --sort=-pcpu | head -8
fi

for r in $(seq 1 "$ROUNDS"); do
  [ -e "$OUT/round$r" ] && die "이미 있다: $OUT/round$r (덮어쓰지 않는다 — 다른 이름표를 쓰거나 직접 옮긴다)"
done

# 지금 커밋에서 jar를 빌드한다
if [ "$BUILD" -eq 1 ]; then
  for m in dataServer apiServer; do
    echo "빌드: $m ./gradlew bootJar"
    (cd "$ROOT/$m" && ./gradlew -q bootJar) || die "$m 빌드 실패"
  done
fi
[ -f "$DATA_JAR" ] && [ -f "$API_JAR" ] || die "jar가 없다 — --no-build를 빼고 돌리거나 두 서버에서 ./gradlew bootJar"

# 컨테이너 방식: 이미지(JDK 21 런타임 + tc, ping)를 만든다. jar는 이미지에 넣지 않고 compose가 읽기 전용으로 붙인다.
if [ "$CONTAINER" -eq 1 ]; then
  echo "이미지: docker compose build (rm-http-grpc/jre21-nettools:1)"
  dc build -q || die "이미지를 만들지 못했다"
  IMAGE_TAG="rm-http-grpc/jre21-nettools:1"
  IMAGE_ID="$(docker image inspect "$IMAGE_TAG" --format '{{.Id}}')"
  BASE_IMAGE="eclipse-temurin:21-jre $(docker image inspect eclipse-temurin:21-jre --format '{{index .RepoDigests 0}} {{.Id}}' 2>/dev/null)"
  DOCKER_VERSION="$(docker version --format 'server {{.Server.Version}} client {{.Client.Version}}')"
  COMPOSE_VERSION="$(docker compose version --short)"
  CONTAINER_JDK="$(dc run --rm --no-deps -T --entrypoint java dataServer -version 2>&1 | head -1)"
  [ -n "$CONTAINER_JDK" ] || die "이미지의 java 버전을 읽지 못했다"
  export IMAGE_TAG IMAGE_ID BASE_IMAGE DOCKER_VERSION COMPOSE_VERSION CONTAINER_JDK
fi

# ---------- 회차 공통 조건 ----------
COMMIT="$(git -C "$ROOT" rev-parse HEAD)"
TRACKED_DIRTY="$(git -C "$ROOT" status --porcelain --untracked-files=no)"
JDK="$(java -version 2>&1 | head -1)"
[ "$CONTAINER" -eq 1 ] && JDK="$CONTAINER_JDK"
K6V="$(k6 version 2>&1 | head -1)"
CORES="$(nproc)"
CPU_MODEL="$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ *//')"
# jar가 커밋과 따로 빌드될 수 있으므로 jar 자체를 식별할 값도 남긴다
JARS="$(for j in "$DATA_JAR" "$API_JAR"; do echo "$(basename "$j") sha256=$(sha256sum "$j" | cut -c1-16) built=$(date -Iseconds -r "$j")"; done)"
export JARS COMMIT TRACKED_DIRTY JDK K6V CORES CPU_MODEL AXIS CONFIG DURATION WARMUP WARMUP_VUS PAUSE ROUNDS LABEL MAX_LOAD
export MAX_WAIT LOAD_CHECK_INTERVAL BUILD ALLOW_DIRTY ONLY_CONDITIONS CONTAINER DELAY COMPOSE_PROJECT

# ---------- 서버와 정리 ----------
STARTED_PIDS=()
COMPOSE_UP=0
K6_PID=""
ROUND_DIR=""
ROUND_STATUS=""

stop_servers() {
  local pid i
  if [ "$CONTAINER" -eq 1 ]; then
    [ "$COMPOSE_UP" -eq 1 ] || return 0
    save_container_logs
    dc down --timeout 15 >/dev/null 2>&1 || echo "경고: docker compose down 실패 — docker ps로 $COMPOSE_PROJECT 컨테이너를 확인한다" >&2
    COMPOSE_UP=0
    return 0
  fi
  for pid in "${STARTED_PIDS[@]}"; do kill -TERM "$pid" 2>/dev/null; done
  for i in $(seq 1 30); do
    local alive=0
    for pid in "${STARTED_PIDS[@]}"; do kill -0 "$pid" 2>/dev/null && alive=1; done
    [ $alive -eq 0 ] && break
    sleep 0.5
  done
  for pid in "${STARTED_PIDS[@]}"; do kill -0 "$pid" 2>/dev/null && kill -KILL "$pid" 2>/dev/null; done
  for pid in "${STARTED_PIDS[@]}"; do wait "$pid" 2>/dev/null; done
  STARTED_PIDS=()
}

STATE_ROOT="$(mktemp -d)"
STATE=""

write_conditions() {  # $1 = 상태 (running | complete | interrupted | failed-start)
  [ -n "$ROUND_DIR" ] && [ -d "$ROUND_DIR" ] || return 0
  ROUND_STATUS="$1" python3 "$HERE/conditions.py" "$STATE" "$ROUND_DIR" || echo "경고: conditions.json을 쓰지 못함" >&2
}

on_exit() {
  local rc=$?
  trap - EXIT INT TERM
  if [ -n "$K6_PID" ]; then kill -TERM "$K6_PID" 2>/dev/null; wait "$K6_PID" 2>/dev/null; fi
  if [ ${#STARTED_PIDS[@]} -gt 0 ]; then
    echo "정리: 띄운 서버를 끈다 (pid ${STARTED_PIDS[*]})" >&2
    stop_servers
  fi
  if [ "$COMPOSE_UP" -eq 1 ]; then
    echo "정리: $COMPOSE_PROJECT 컨테이너를 내린다" >&2
    stop_servers
  fi
  # 회차가 끝나지 않은 채 나가면 그 회차를 interrupted로 적는다(지우지 않는다)
  if [ -n "$ROUND_DIR" ] && [ -d "$ROUND_DIR" ] && [ ! -e "$STATE/final" ]; then
    load1 > "$STATE/load_after"
    cat /proc/loadavg > "$STATE/loadavg_after"
    date -Iseconds > "$STATE/ended_at"
    write_conditions interrupted
    echo "회차 기록: $ROUND_DIR/conditions.json (status=interrupted)" >&2
  fi
  rm -rf "$STATE_ROOT"
  exit $rc
}
trap on_exit EXIT
trap 'echo "신호를 받아 멈춘다" >&2; exit 130' INT
trap 'echo "신호를 받아 멈춘다" >&2; exit 143' TERM

start_server() {  # $1=이름 $2=jar $3=로그
  if [ "$CONFIG" = matched ]; then
    env SPRING_PROFILES_ACTIVE=matched java -jar "$2" > "$3" 2>&1 &
  else
    env -u SPRING_PROFILES_ACTIVE -u BENCH_MAX_CONCURRENCY java -jar "$2" > "$3" 2>&1 &
  fi
  STARTED_PIDS+=($!)
  echo "  $1 시작 (pid $!)"
}

wait_ready() {  # $1=pid $2=로그 $3..=확인 명령
  local pid="$1" log="$2"; shift 2
  local i
  for i in $(seq 1 120); do
    kill -0 "$pid" 2>/dev/null || { echo "서버가 죽었다 (pid $pid) — $log" >&2; return 1; }
    if grep -q '^BENCH_CONFIG' "$log" && "$@"; then return 0; fi
    sleep 0.5
  done
  echo "60초 안에 뜨지 않았다 — $log" >&2; return 1
}
data_ready() { curl -sf -o /dev/null http://localhost:8081/actuator/health && ss -Htln "sport = :9091" | grep -q .; }
api_ready() { curl -sf -o /dev/null http://localhost:8080/api/health; }

# ---------- 컨테이너 방식 ----------
save_container_logs() {
  [ -n "$ROUND_DIR" ] && [ -d "$ROUND_DIR" ] || return 0
  local s; for s in dataServer apiServer; do
    dc logs --no-color --no-log-prefix "$s" > "$ROUND_DIR/$s.log" 2>/dev/null
  done
}
container_up() {  # $1=서비스. 설정은 호스트 방식(start_server)과 같은 환경 변수로 넘긴다
  COMPOSE_UP=1
  if [ "$CONFIG" = matched ]; then
    env SPRING_PROFILES_ACTIVE=matched timeout 180 docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" up -d --no-build --no-deps "$1" >/dev/null 2>&1
  else
    env -u SPRING_PROFILES_ACTIVE -u BENCH_MAX_CONCURRENCY timeout 180 docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" up -d --no-build --no-deps "$1" >/dev/null 2>&1
  fi || { echo "docker compose up $1 실패" >&2; return 1; }
  echo "  $1 컨테이너 시작 ($(dc ps -q "$1" | cut -c1-12), 지연 ${DELAY}ms)"
}
container_wait_ready() {  # $1=서비스 $2=확인 명령
  local svc="$1" cid i; shift
  cid="$(dc ps -q "$svc")"
  for i in $(seq 1 120); do
    [ "$(docker inspect -f '{{.State.Running}}' "$cid" 2>/dev/null)" = true ] || {
      save_container_logs; echo "컨테이너가 멈췄다: $svc — $ROUND_DIR/$svc.log" >&2; return 1; }
    dc logs --no-color --no-log-prefix "$svc" > "$ROUND_DIR/$svc.log" 2>/dev/null
    if grep -q '^BENCH_CONFIG' "$ROUND_DIR/$svc.log" && "$@"; then return 0; fi
    sleep 0.5
  done
  echo "60초 안에 뜨지 않았다 — $ROUND_DIR/$svc.log" >&2; return 1
}
data_ready_c() { dc exec -T dataServer sh -c 'curl -sf -o /dev/null http://localhost:8081/actuator/health && ss -Htln "sport = :9091" | grep -q .' 2>/dev/null; }
# 측정 전에 apiServer → dataServer 왕복 시간을 재 기대값(바탕 + 지연)과 맞는지 본다. 결과: $STATE/rtt_check.json
container_rtt_check() {
  dc exec -T dataServer tc qdisc show dev eth0 > "$STATE/qdisc" 2>&1
  dc exec -T apiServer ping -c 20 -i 0.2 dataServer > "$STATE/ping.txt" 2>&1
  dc exec -T apiServer sh -c 'for i in $(seq 1 10); do curl -s -o /dev/null -w "%{time_namelookup} %{time_connect}\n" http://dataServer:8081/actuator/health; sleep 0.1; done' \
    > "$STATE/tcp_connect.txt" 2>&1
  python3 "$HERE/rtt_check.py" "$DELAY" "$STATE/ping.txt" "$STATE/tcp_connect.txt" "$STATE/rtt_check.json"
}

run_k6() {  # $1=요약 JSON $2=k6 출력 로그, 나머지 = -e 인자들. 종료 코드를 돌려준다
  local summary="$1" log="$2"; shift 2
  k6 run --quiet --no-color --summary-export "$summary" \
    --summary-trend-stats 'avg,min,med,max,p(90),p(95),p(99)' \
    "$@" "$HERE/bench.js" > "$log" 2>&1 &
  K6_PID=$!
  wait "$K6_PID"; local rc=$?
  K6_PID=""
  return $rc
}

# ---------- 회차 ----------
NPATH=${#PATHS[@]}
TOTAL_START=$(date +%s)
echo "축 $AXIS · 설정 $CONFIG · ${ROUNDS}회 · 한 번 $DURATION · 결과 $OUT"
[ "$CONTAINER" -eq 1 ] && echo "컨테이너 방식($COMPOSE_PROJECT) · dataServer에서 나가는 쪽 지연 ${DELAY}ms"
mkdir -p "$OUT"

# 1분 부하 평균이 기준 아래로 내려갈 때까지 기다린다. 최대 대기를 넘으면 그대로 시작하고 적는다.
# 결과: $STATE/load_wait_seconds, $STATE/load_wait_exceeded (1|0)
wait_for_load() {
  local t0 now l
  t0=$(date +%s)
  while :; do
    l="$(load1)"
    now=$(date +%s)
    if ! above "$l" "$MAX_LOAD"; then
      echo $(( now - t0 )) > "$STATE/load_wait_seconds"; echo 0 > "$STATE/load_wait_exceeded"; return 0
    fi
    if [ $(( now - t0 )) -ge "$MAX_WAIT" ]; then
      echo $(( now - t0 )) > "$STATE/load_wait_seconds"; echo 1 > "$STATE/load_wait_exceeded"
      echo "  경고: ${MAX_WAIT}초를 기다려도 부하 $l 이 기준 $MAX_LOAD 보다 높다 — 그대로 시작한다(conditions.json에 적음)" >&2
      return 0
    fi
    echo "  부하 $l > 기준 $MAX_LOAD — ${LOAD_CHECK_INTERVAL}초 뒤 다시 본다 ($(( now - t0 ))/${MAX_WAIT}초)"
    sleep "$LOAD_CHECK_INTERVAL"
  done
}

for r in $(seq 1 "$ROUNDS"); do
  STATE="$STATE_ROOT/round$r"; mkdir -p "$STATE"
  wait_for_load
  b="$(busy_ports)"; [ -z "$b" ] || die "회차 $r 시작 전에 포트가 쓰이고 있다: $b"
  mkdir "$OUT/round$r" || die "회차 폴더를 만들 수 없다: $OUT/round$r"
  ROUND_DIR="$OUT/round$r"

  # 회차 r의 경로 순서 = PATHS를 (r-1)만큼 돌린 것
  ORDER=(); for i in $(seq 0 $((NPATH - 1))); do ORDER+=("${PATHS[$(( (i + r - 1) % NPATH ))]}"); done
  printf '%s\n' "${ORDER[@]}" > "$STATE/order"
  printf '%s\n' "${CONDITIONS[@]}" > "$STATE/conditions"
  : > "$STATE/measurements.tsv"
  date -Iseconds > "$STATE/started_at"
  load1 > "$STATE/load_before"
  cat /proc/loadavg > "$STATE/loadavg_before"
  ps -eo pid,pcpu,comm --sort=-pcpu | head -6 > "$STATE/top_before"
  echo "회차 $r/$ROUNDS — 경로 순서: ${ORDER[*]} (부하 $(cat "$STATE/load_before"), 기다린 시간 $(cat "$STATE/load_wait_seconds")초)"

  if [ "$CONTAINER" -eq 1 ]; then
    if ! { container_up dataServer && container_wait_ready dataServer data_ready_c; }; then write_conditions failed-start; touch "$STATE/final"; exit 1; fi
    if ! { container_up apiServer && container_wait_ready apiServer api_ready; }; then write_conditions failed-start; touch "$STATE/final"; exit 1; fi
    # 지연이 기대대로 걸리지 않았으면 이 회차를 재지 않고 멈춘다
    if ! container_rtt_check; then
      write_conditions failed-rtt-check; touch "$STATE/final"
      die "왕복 시간이 기대(${DELAY}ms 더함)와 맞지 않아 측정을 시작하지 않는다 — $ROUND_DIR/conditions.json"
    fi
  else
    start_server dataServer "$DATA_JAR" "$ROUND_DIR/dataServer.log"
    DATA_PID=${STARTED_PIDS[-1]}
    if ! wait_ready "$DATA_PID" "$ROUND_DIR/dataServer.log" data_ready; then write_conditions failed-start; touch "$STATE/final"; exit 1; fi
    start_server apiServer "$API_JAR" "$ROUND_DIR/apiServer.log"
    API_PID=${STARTED_PIDS[-1]}
    if ! wait_ready "$API_PID" "$ROUND_DIR/apiServer.log" api_ready; then write_conditions failed-start; touch "$STATE/final"; exit 1; fi
  fi
  grep -h '^BENCH_CONFIG' "$ROUND_DIR/dataServer.log" "$ROUND_DIR/apiServer.log" > "$STATE/bench_config"
  write_conditions running

  # 워밍업: 모든 조건 × 모든 경로를 같은 횟수만큼 (한 번의 k6 실행 안에서 번갈아)
  if [ "$WARMUP" -gt 0 ]; then
    TARGETS=""
    for c in "${CONDITIONS[@]}"; do IFS='|' read -r _ param _ <<< "$c"
      for p in "${PATHS[@]}"; do TARGETS+="$p|$param,"; done; done
    # 동시 사용자 축은 조건이 동시 사용자 수뿐이라 같은 대상이 겹친다 — 겹친 대상은 한 번만
    TARGETS="$(tr ',' '\n' <<< "$TARGETS" | awk 'NF && !seen[$0]++' | paste -sd,)"
    echo "  워밍업: 대상 $(tr ',' '\n' <<< "$TARGETS" | wc -l)개 × ${WARMUP}회, 동시 $WARMUP_VUS"
    run_k6 "$ROUND_DIR/_warmup.json" "$ROUND_DIR/_warmup.k6.txt" -e MODE=warmup -e AXIS="$AXIS" \
      -e TARGETS="$TARGETS" -e WARMUP_ITERATIONS="$WARMUP" -e VUS="$WARMUP_VUS"
    echo "$?" > "$STATE/warmup_exit"
    echo "$TARGETS" > "$STATE/warmup_targets"
    sleep "$PAUSE"
  fi

  seq_no=0
  for c in "${CONDITIONS[@]}"; do
    IFS='|' read -r cond param vus <<< "$c"
    for p in "${ORDER[@]}"; do
      seq_no=$((seq_no + 1))
      f="${cond}__${p}.json"
      t0="$(date -Iseconds)"
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$seq_no" "$cond" "$p" "$param" "$vus" "$t0" > "$STATE/current"
      run_k6 "$ROUND_DIR/$f" "$ROUND_DIR/${cond}__${p}.k6.txt" -e MODE=measure -e AXIS="$AXIS" \
        -e PATH_NAME="$p" -e PARAM="$param" -e VUS="$vus" -e DURATION="$DURATION"
      rc=$?
      t1="$(date -Iseconds)"
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$seq_no" "$cond" "$p" "$param" "$vus" "$t0" "$t1" "$rc" >> "$STATE/measurements.tsv"
      rm -f "$STATE/current"
      echo "  [$seq_no] $cond $p — k6 종료 코드 $rc"
      write_conditions running
      sleep "$PAUSE"
    done
  done

  stop_servers
  date -Iseconds > "$STATE/ended_at"
  load1 > "$STATE/load_after"
  cat /proc/loadavg > "$STATE/loadavg_after"
  write_conditions complete
  touch "$STATE/final"
  ROUND_DIR=""
  for i in $(seq 1 30); do [ -z "$(busy_ports)" ] && break; sleep 0.5; done
done

echo "끝 — $(( $(date +%s) - TOTAL_START ))초. 표: python3 scripts/remeasure/summarize.py $OUT"
