#!/bin/sh
# 컨테이너 시작: NETEM_DELAY_MS가 있으면 이 컨테이너의 eth0에서 나가는 패킷에 그만큼 지연을 건 뒤 jar를 띄운다.
# tc가 실패하면 서버를 띄우지 않고 끝난다(지연 없이 재는 일이 없게).
# RM_MAX_LIFETIME_S(기본 10800초)가 지나면 서버를 끈다 — run.sh가 정리하지 못하고 죽어도 컨테이너가 남아 돌지 않게.
set -eu
if [ -n "${NETEM_DELAY_MS:-}" ]; then
  # limit: netem 큐의 패킷 수 상한(기본 1000). 지연 동안 큐에 머무는 패킷이 이를 넘으면 버려지므로 크게 둔다.
  tc qdisc replace dev eth0 root netem delay "${NETEM_DELAY_MS}ms" limit 100000
  echo "NETEM dev=eth0 $(tc qdisc show dev eth0 | head -1)"
fi
exec timeout -s TERM "${RM_MAX_LIFETIME_S:-10800}" java -jar /app/app.jar
