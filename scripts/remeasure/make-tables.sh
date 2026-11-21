#!/usr/bin/env bash
# 다시 재기(2026-11) — results/2026-11/의 원자료에서 docs/remeasure/tables/*.md를 모두 다시 만든다.
# 표는 summarize.py의 출력 그대로다(손으로 고치지 않는다). 측정은 하지 않는다.
#
# 사용: scripts/remeasure/make-tables.sh [출력 폴더]   (기본 docs/remeasure/tables)
set -euo pipefail

cd "$(dirname "$0")/../.."
OUT="${1:-docs/remeasure/tables}"
R=results/2026-11
S=scripts/remeasure/summarize.py
mkdir -p "$OUT"

# 이름 | 원자료 | 견줄 폴더(없으면 -) | 제목
TABLES=$(cat <<EOF
size-nodelay|$R/nodelay/size-default|-|크기 축 — 호스트 직접 실행, 기본 설정, 동시 10
concurrency-default-nodelay|$R/nodelay/concurrency-default|-|동시 사용자 축 — 호스트 직접 실행, 기본 설정(default), 10kb
concurrency-matched-nodelay|$R/nodelay/concurrency-matched|-|동시 사용자 축 — 호스트 직접 실행, 맞춘 설정(matched, 고친 구성), 10kb
complexity-nodelay|$R/nodelay/complexity-default|-|복잡도 축 — 호스트 직접 실행, 기본 설정, 동시 10
size-delay0ms|$R/delay0ms/size-default|-|크기 축 — 컨테이너, 지연 0ms, 기본 설정, 동시 10
size-delay1ms|$R/delay1ms/size-default|$R/delay0ms/size-default|크기 축 — 컨테이너, 지연 1ms(delay0ms와 견줌), 기본 설정, 동시 10
size-delay10ms|$R/delay10ms/size-default|$R/delay0ms/size-default|크기 축 — 컨테이너, 지연 10ms(delay0ms와 견줌), 기본 설정, 동시 10
size-delay0ms-vs-nodelay|$R/delay0ms/size-default|$R/nodelay/size-default|참고 — 컨테이너 지연 0ms를 호스트 직접 실행과 견줌(워밍업 1,000회 대 200회, JDK 21.0.13 대 21.0.5 — 지연의 효과로 읽지 않는다)
record-complexity-v1-above-load|$R/nodelay/complexity-default-v1-above-load|-|기록 — 복잡도 축의 첫 측정(회차 1·3이 부하 기준 4.0을 넘은 채 시작). 결과로 쓰지 않는다
record-concurrency-matched-v1-fixed-executor|$R/nodelay/concurrency-matched-v1-fixed-executor|-|기록 — 맞춘 설정의 잘못된 구성(v1, gRPC 서버 실행기를 고정 512 풀로). grpc-unary 값은 쓰지 않는다
check-concurrency-matched-v2|$R/check-matched-v2/concurrency-matched|$R/check-default/concurrency-default|확인 실행 — 고친 matched를 바로 뒤에 이어서 잰 default와 견줌(동시 50·200, 3회 × 15초)
check-concurrency-default|$R/check-default/concurrency-default|-|확인 실행 — default(동시 50·200, 3회 × 15초, check-matched-v2 바로 뒤에 이어서 잼)
EOF
)

while IFS='|' read -r name dir base title; do
  [ -n "$name" ] || continue
  args=("$dir" -o "$OUT/$name.md" --title "$title")
  [ "$base" = "-" ] || args+=(--baseline "$base")
  python3 "$S" "${args[@]}"
  echo "만듦: $OUT/$name.md"
done <<< "$TABLES"
