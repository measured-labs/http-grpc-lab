# 확인 실행 — default(동시 50·200, 3회 × 15초, check-matched-v2 바로 뒤에 이어서 잼)

> 원자료 `results/2026-11/check-default/concurrency-default` · 이 표는 `scripts/remeasure/summarize.py`가 뽑았다(손으로 고치지 않는다).

## 측정 조건

- 커밋: aa22b9d524d140b91d835f4e2e3a757b048082bd
- 작업 트리: 깨끗함(추적 파일 변경 없음)
- 축 concurrency · 설정 default · 회차 3 · 한 번 15s · 워밍업 대상마다 200회(동시 10)
- openjdk version "21.0.5" 2024-10-15 LTS · k6 v2.4.0 (commit/dd9395906d, go1.26.11, linux/amd64) · 코어 24 · k6와 두 서버(apiServer, dataServer)가 같은 머신에서 돈다
- 1분 부하 평균: 시작 전 3.70~3.99, 끝난 뒤 38.40~50.29
- 회차별 1분 부하(시작 → 끝, 기준 4.0): r1 3.99 → 44.74 (기다림 280초/상한 600초) · r2 3.89 → 38.40 (기다림 201초/상한 600초) · r3 3.70 → 50.29 (기다림 170초/상한 600초)
- 회차별 시각: r1 2026-11-21T20:26:46+09:00 ~ 2026-11-21T20:29:42+09:00 · r2 2026-11-21T20:33:03+09:00 ~ 2026-11-21T20:35:58+09:00 · r3 2026-11-21T20:38:49+09:00 ~ 2026-11-21T20:41:43+09:00
- 실행 방식: host-jar
- 다른 컨테이너(회차 시작 때 실행 중인 수, 회차별): r1 기록 없음 · r2 기록 없음 · r3 기록 없음
- BENCH_CONFIG:

```
BENCH_CONFIG server=dataServer profile=default read: tomcatMaxThreads=200 tomcatMaxKeepAliveRequests=100 tomcatKeepAliveTimeoutMs=60000 | set: grpcServerExecutor=grpc-default(cached,unbounded) grpcHandlerDispatcher=Dispatchers.Default(24)
BENCH_CONFIG server=apiServer profile=default read: tomcatMaxThreads=200 httpPoolMaxConnections=500 httpClientAcceptGzip=true | set: httpPoolPendingAcquireMaxCount=1000 httpPoolPendingAcquireTimeoutMs=45000 httpPoolMaxIdleTimeMs=-1 httpPoolMaxLifeTimeMs=-1 grpcChannels=1
```

- 실행 순서(회차별): r1 http-json http-bytes http-proto grpc-unary · r2 http-bytes http-proto grpc-unary http-json · r3 http-proto grpc-unary http-json http-bytes
- 처리량 = k6 `http_reqs.rate`. 지연 = k6 `http_req_duration`의 중앙값과 p95(회차 값들의 중앙값). 서버 하위 호출 = apiServer 응답의 `latencyMs`(dataServer 호출 구간) 평균(회차 값들의 중앙값). 두 서버 사이의 본문 크기 = dataServer → apiServer 응답 본문의 바이트 수(`sizes.json`, 조건과 경로마다 한 번 잰 값 — HTTP 머리·청크 머리 제외). `grpc-unary`는 재지 않고 `http-proto`와 같은 Protobuf 메시지 + gRPC 메시지 머리 5바이트로 적었다(HTTP/2 프레임 제외, † 표시). k6가 받은 바이트 = k6 `data_received` ÷ 요청 수(apiServer → k6의 작은 JSON 응답, 머리 포함).
- 본문 크기 표: `results/2026-11/sizes.json` (커밋 ac84c78, 2026-11-21T04:32:56+09:00)
- ⚠ 표시 = 실패로 판정된 측정(k6 종료 코드 ≠ 0, 요청 실패율 ≠ 0, check 실패). 값은 그대로 표에 넣었다.

## 요약 — 처리량 중앙값 req/s [최소~최대], 회차별 1위

| 조건 | `http-json` | `http-bytes` | `http-proto` | `grpc-unary` | 1위(회차 수) | 1위가 모든 회차에서 같음 |
|------|------|------|------|------|------|------|
| vus50 | 6,247 [2,981~6,521] | 6,440 [3,618~8,085] | 7,061 [3,954~7,396] | 6,001 [5,628~6,281] | `http-proto` 2, `http-bytes` 1 | 아니오 |
| vus200 | 7,581 [7,000~7,591] | 9,052 [8,712~9,261] | 9,577 [8,960~9,755] | 9,362 [8,225~9,440] | `http-proto` 3 | 예 |

## 요약 — 경로끼리 견줌: 처리량 중앙값의 비율 %(회차마다 같은 경로가 앞섰는지)

| 조건 | 직렬화: `http-proto` ÷ `http-json` − 1 | 전송 방식: `grpc-unary` ÷ `http-proto` − 1 | 메시지로 감쌈: `http-proto` ÷ `http-bytes` − 1 |
|------|------|------|------|
| vus50 | +13.0% (앞선 회차 2/3) | -15.0% (앞선 회차 1/3) | +9.6% (앞선 회차 2/3) |
| vus200 | +26.3% (앞선 회차 3/3) | -2.2% (앞선 회차 0/3) | +5.8% (앞선 회차 3/3) |

## 결과

### vus50

| 경로 | r1 req/s | r2 req/s | r3 req/s | 중앙값 | 최소 | 최대 | 1위와 구간 | 지연 중앙값 ms | 지연 p95 ms | 서버 하위 호출 평균 ms | 두 서버 사이의 본문 크기 | k6가 받은 바이트(apiServer의 응답)/요청 |
|------|------|------|------|------|------|------|------|------|------|------|------|------|
| `http-json` | 2,980.7 | 6,520.5 | 6,247.0 | 6,247.0 | 2,980.7 | 6,520.5 | 겹침 | 6.797 | 13.617 | 4.849 | 13,767 | 241.1 |
| `http-bytes` | 6,440.1 | 3,617.8 | 8,085.5 | 6,440.1 | 3,617.8 | 8,085.5 | 겹침 | 6.805 | 12.208 | 4.271 | 10,240 | 243.1 |
| `http-proto` | 7,061.3 | 7,395.7 | 3,953.6 | 7,061.3 | 3,953.6 | 7,395.7 | (1위) | 6.220 | 11.087 | 3.803 | 10,291 | 245.1 |
| `grpc-unary` | 5,627.7 | 6,000.8 | 6,281.1 | 6,000.8 | 5,627.7 | 6,281.1 | 겹침 | 6.068 | 21.133 | 5.938 | 10,296 † | 242.2 |

- 회차별 1위: r1 `http-proto`, r2 `http-proto`, r3 `http-bytes` → **회차마다 갈림**
- 회차별 p95가 가장 낮은 경로: r1 `http-proto`, r2 `http-proto`, r3 `http-bytes`

### vus200

| 경로 | r1 req/s | r2 req/s | r3 req/s | 중앙값 | 최소 | 최대 | 1위와 구간 | 지연 중앙값 ms | 지연 p95 ms | 서버 하위 호출 평균 ms | 두 서버 사이의 본문 크기 | k6가 받은 바이트(apiServer의 응답)/요청 |
|------|------|------|------|------|------|------|------|------|------|------|------|------|
| `http-json` | 7,000.3 | 7,591.3 | 7,581.2 | 7,581.2 | 7,000.3 | 7,591.3 | 안 겹침 | 23.942 | 43.791 | 15.213 | 13,767 | 241.8 |
| `http-bytes` | 8,711.7 | 9,260.6 | 9,051.6 | 9,051.6 | 8,711.7 | 9,260.6 | 겹침 | 20.010 | 36.072 | 11.341 | 10,240 | 243.6 |
| `http-proto` | 8,960.4 | 9,755.4 | 9,577.2 | 9,577.2 | 8,960.4 | 9,755.4 | (1위) | 18.652 | 34.486 | 11.017 | 10,291 | 245.6 |
| `grpc-unary` | 8,224.6 | 9,440.3 | 9,362.3 | 9,362.3 | 8,224.6 | 9,440.3 | 겹침 | 20.258 | 29.238 | 17.794 | 10,296 † | 243.1 |

- 회차별 1위: r1 `http-proto`, r2 `http-proto`, r3 `http-proto` → **모든 회차에서 같음**
- 회차별 p95가 가장 낮은 경로: r1 `grpc-unary`, r2 `grpc-unary`, r3 `grpc-unary`
