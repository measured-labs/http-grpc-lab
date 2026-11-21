# 복잡도 축 — 호스트 직접 실행, 기본 설정, 동시 10

> 원자료 `results/2026-11/nodelay/complexity-default` · 이 표는 `scripts/remeasure/summarize.py`가 뽑았다(손으로 고치지 않는다).

## 측정 조건

- 커밋: f4aa61f61ad23b175279be2ae70ed7cdf4746170
- 작업 트리: 깨끗함(추적 파일 변경 없음)
- 축 complexity · 설정 default · 회차 5 · 한 번 30s · 워밍업 대상마다 200회(동시 10)
- openjdk version "21.0.5" 2024-10-15 LTS · k6 v2.4.0 (commit/dd9395906d, go1.26.11, linux/amd64) · 코어 24 · k6와 두 서버(apiServer, dataServer)가 같은 머신에서 돈다
- 1분 부하 평균: 시작 전 3.73~14.58, 끝난 뒤 11.99~20.39
- 회차별 1분 부하(시작 → 끝, 기준 4.0): r1 14.58 → 16.78 (기다림 601초/상한 600초) **기준을 넘은 채 시작(startedAboveMax)** · r2 3.80 → 20.39 (기다림 431초/상한 600초) · r3 11.09 → 17.20 (기다림 601초/상한 600초) **기준을 넘은 채 시작(startedAboveMax)** · r4 3.73 → 11.99 (기다림 110초/상한 600초) · r5 3.98 → 13.71 (기다림 161초/상한 600초)
- 회차별 시각: r1 2026-11-21T15:58:03+09:00 ~ 2026-11-21T16:07:03+09:00 · r2 2026-11-21T16:14:14+09:00 ~ 2026-11-21T16:23:10+09:00 · r3 2026-11-21T16:33:12+09:00 ~ 2026-11-21T16:42:13+09:00 · r4 2026-11-21T16:44:04+09:00 ~ 2026-11-21T16:52:58+09:00 · r5 2026-11-21T16:55:39+09:00 ~ 2026-11-21T17:04:36+09:00
- 실행 방식: host-jar
- 다른 컨테이너(회차 시작 때 실행 중인 수, 회차별): r1 기록 없음 · r2 기록 없음 · r3 기록 없음 · r4 기록 없음 · r5 기록 없음
- BENCH_CONFIG:

```
BENCH_CONFIG server=dataServer profile=default read: tomcatMaxThreads=200 tomcatMaxKeepAliveRequests=100 tomcatKeepAliveTimeoutMs=60000 | set: grpcServerExecutor=grpc-default(cached,unbounded) grpcHandlerDispatcher=Dispatchers.Default(24)
BENCH_CONFIG server=apiServer profile=default read: tomcatMaxThreads=200 httpPoolMaxConnections=500 httpClientAcceptGzip=true | set: httpPoolPendingAcquireMaxCount=1000 httpPoolPendingAcquireTimeoutMs=45000 httpPoolMaxIdleTimeMs=-1 httpPoolMaxLifeTimeMs=-1 grpcChannels=1
```

- 실행 순서(회차별): r1 http-json http-proto grpc-unary · r2 http-proto grpc-unary http-json · r3 grpc-unary http-json http-proto · r4 http-json http-proto grpc-unary · r5 http-proto grpc-unary http-json
- 처리량 = k6 `http_reqs.rate`. 지연 = k6 `http_req_duration`의 중앙값과 p95(회차 값들의 중앙값). 서버 하위 호출 = apiServer 응답의 `latencyMs`(dataServer 호출 구간) 평균(회차 값들의 중앙값). 두 서버 사이의 본문 크기 = dataServer → apiServer 응답 본문의 바이트 수(`sizes.json`, 조건과 경로마다 한 번 잰 값 — HTTP 머리·청크 머리 제외). `grpc-unary`는 재지 않고 `http-proto`와 같은 Protobuf 메시지 + gRPC 메시지 머리 5바이트로 적었다(HTTP/2 프레임 제외, † 표시). k6가 받은 바이트 = k6 `data_received` ÷ 요청 수(apiServer → k6의 작은 JSON 응답, 머리 포함).
- 본문 크기 표: `results/2026-11/sizes.json` (커밋 ac84c78, 2026-11-21T04:32:56+09:00)
- ⚠ 표시 = 실패로 판정된 측정(k6 종료 코드 ≠ 0, 요청 실패율 ≠ 0, check 실패). 값은 그대로 표에 넣었다.

## 요약 — 처리량 중앙값 req/s [최소~최대], 회차별 1위

| 조건 | `http-json` | `http-proto` | `grpc-unary` | 1위(회차 수) | 1위가 모든 회차에서 같음 |
|------|------|------|------|------|------|
| simple | 3,489 [3,102~4,208] | 4,197 [3,443~4,352] | 3,840 [3,537~4,047] | `http-proto` 3, `http-json` 2 | 아니오 |
| medium | 3,866 [3,735~4,121] | 4,408 [4,277~4,521] | 4,536 [3,972~4,829] | `grpc-unary` 4, `http-proto` 1 | 아니오 |
| complex | 3,711 [3,709~3,809] | 4,083 [3,949~4,201] | 4,303 [3,888~4,363] | `grpc-unary` 3, `http-proto` 2 | 아니오 |
| ultra | 2,093 [1,922~2,155] | 3,061 [2,776~3,124] | 3,149 [2,369~3,218] | `grpc-unary` 4, `http-proto` 1 | 아니오 |
| extreme | 343 [299~345] | 828 [756~845] | 806 [762~889] | `grpc-unary` 3, `http-proto` 2 | 아니오 |

## 결과

### simple

| 경로 | r1 req/s | r2 req/s | r3 req/s | r4 req/s | r5 req/s | 중앙값 | 최소 | 최대 | 1위와 구간 | 지연 중앙값 ms | 지연 p95 ms | 서버 하위 호출 평균 ms | 두 서버 사이의 본문 크기 | k6가 받은 바이트(apiServer의 응답)/요청 |
|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|
| `http-json` | 3,102.4 | 3,802.2 | 3,253.4 | 3,489.2 | 4,207.7 | 3,489.2 | 3,102.4 | 4,207.7 | 겹침 | 2.141 | 5.287 | 1.485 | 226 | 264.1 |
| `http-proto` | 4,351.9 | 3,442.9 | 4,228.8 | 4,196.6 | 3,739.4 | 4,196.6 | 3,442.9 | 4,351.9 | (1위) | 1.942 | 2.951 | 1.113 | 89 | 275.1 |
| `grpc-unary` | 3,948.0 | 3,536.7 | 3,593.6 | 3,839.6 | 4,047.3 | 3,839.6 | 3,536.7 | 4,047.3 | 겹침 | 2.019 | 4.089 | 1.357 | 94 † | 264.1 |

- 회차별 1위: r1 `http-proto`, r2 `http-json`, r3 `http-proto`, r4 `http-proto`, r5 `http-json` → **회차마다 갈림**
- 회차별 p95가 가장 낮은 경로: r1 `http-proto`, r2 `http-json`, r3 `http-proto`, r4 `http-proto`, r5 `http-json`

### medium

| 경로 | r1 req/s | r2 req/s | r3 req/s | r4 req/s | r5 req/s | 중앙값 | 최소 | 최대 | 1위와 구간 | 지연 중앙값 ms | 지연 p95 ms | 서버 하위 호출 평균 ms | 두 서버 사이의 본문 크기 | k6가 받은 바이트(apiServer의 응답)/요청 |
|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|
| `http-json` | 3,866.3 | 3,791.0 | 3,734.9 | 3,902.1 | 4,120.5 | 3,866.3 | 3,734.9 | 4,120.5 | 겹침 | 2.146 | 3.363 | 1.339 | 728 | 264.1 |
| `http-proto` | 4,455.9 | 4,408.2 | 4,521.0 | 4,277.0 | 4,400.8 | 4,408.2 | 4,277.0 | 4,521.0 | 겹침 | 1.882 | 2.722 | 1.087 | 446 | 276.1 |
| `grpc-unary` | 4,828.7 | 4,535.9 | 3,972.4 | 4,400.3 | 4,778.7 | 4,535.9 | 3,972.4 | 4,828.7 | (1위) | 1.846 | 2.729 | 1.091 | 451 † | 264.1 |

- 회차별 1위: r1 `grpc-unary`, r2 `grpc-unary`, r3 `http-proto`, r4 `grpc-unary`, r5 `grpc-unary` → **회차마다 갈림**
- 회차별 p95가 가장 낮은 경로: r1 `grpc-unary`, r2 `grpc-unary`, r3 `http-proto`, r4 `http-proto`, r5 `grpc-unary`

### complex

| 경로 | r1 req/s | r2 req/s | r3 req/s | r4 req/s | r5 req/s | 중앙값 | 최소 | 최대 | 1위와 구간 | 지연 중앙값 ms | 지연 p95 ms | 서버 하위 호출 평균 ms | 두 서버 사이의 본문 크기 | k6가 받은 바이트(apiServer의 응답)/요청 |
|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|
| `http-json` | 3,809.3 | 3,749.8 | 3,710.8 | 3,711.2 | 3,709.5 | 3,711.2 | 3,709.5 | 3,809.3 | 안 겹침 | 2.286 | 3.163 | 1.458 | 3,630 | 266.1 |
| `http-proto` | 4,158.0 | 3,949.1 | 4,200.5 | 4,082.9 | 4,079.9 | 4,082.9 | 3,949.1 | 4,200.5 | 겹침 | 2.058 | 2.853 | 1.220 | 2,073 | 278.1 |
| `grpc-unary` | 3,895.8 | 4,302.8 | 3,887.8 | 4,306.3 | 4,363.0 | 4,302.8 | 3,887.8 | 4,363.0 | (1위) | 1.970 | 2.867 | 1.196 | 2,078 † | 265.1 |

- 회차별 1위: r1 `http-proto`, r2 `grpc-unary`, r3 `http-proto`, r4 `grpc-unary`, r5 `grpc-unary` → **회차마다 갈림**
- 회차별 p95가 가장 낮은 경로: r1 `http-proto`, r2 `grpc-unary`, r3 `http-proto`, r4 `grpc-unary`, r5 `grpc-unary`

### ultra

| 경로 | r1 req/s | r2 req/s | r3 req/s | r4 req/s | r5 req/s | 중앙값 | 최소 | 최대 | 1위와 구간 | 지연 중앙값 ms | 지연 p95 ms | 서버 하위 호출 평균 ms | 두 서버 사이의 본문 크기 | k6가 받은 바이트(apiServer의 응답)/요청 |
|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|
| `http-json` | 1,921.8 | 2,092.6 | 1,970.2 | 2,155.2 | 2,113.6 | 2,092.6 | 1,921.8 | 2,155.2 | 안 겹침 | 4.301 | 6.026 | 3.485 | 36,079 | 265.1 |
| `http-proto` | 2,775.8 | 3,058.5 | 3,066.1 | 3,124.1 | 3,060.7 | 3,060.7 | 2,775.8 | 3,124.1 | 겹침 | 2.844 | 3.875 | 2.018 | 21,397 | 277.1 |
| `grpc-unary` | 2,819.5 | 2,369.2 | 3,149.0 | 3,217.8 | 3,153.9 | 3,149.0 | 2,369.2 | 3,217.8 | (1위) | 2.806 | 3.803 | 2.001 | 21,402 † | 263.1 |

- 회차별 1위: r1 `grpc-unary`, r2 `http-proto`, r3 `grpc-unary`, r4 `grpc-unary`, r5 `grpc-unary` → **회차마다 갈림**
- 회차별 p95가 가장 낮은 경로: r1 `grpc-unary`, r2 `http-proto`, r3 `grpc-unary`, r4 `grpc-unary`, r5 `grpc-unary`

### extreme

| 경로 | r1 req/s | r2 req/s | r3 req/s | r4 req/s | r5 req/s | 중앙값 | 최소 | 최대 | 1위와 구간 | 지연 중앙값 ms | 지연 p95 ms | 서버 하위 호출 평균 ms | 두 서버 사이의 본문 크기 | k6가 받은 바이트(apiServer의 응답)/요청 |
|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|
| `http-json` | 308.3 | 299.1 | 345.4 | 344.0 | 343.2 | 343.2 | 299.1 | 345.4 | 안 겹침 | 28.437 | 34.538 | 27.698 | 421,477 | 269.1 |
| `http-proto` | 755.5 | 828.3 | 823.1 | 842.3 | 844.6 | 828.3 | 755.5 | 844.6 | (1위) | 11.466 | 14.458 | 10.678 | 219,522 | 280.7 |
| `grpc-unary` | 762.0 | 785.4 | 806.1 | 888.9 | 878.2 | 806.1 | 762.0 | 888.9 | 겹침 | 11.613 | 15.990 | 10.911 | 219,527 † | 265.7 |

- 회차별 1위: r1 `grpc-unary`, r2 `http-proto`, r3 `http-proto`, r4 `grpc-unary`, r5 `grpc-unary` → **회차마다 갈림**
- 회차별 p95가 가장 낮은 경로: r1 `http-proto`, r2 `http-proto`, r3 `http-proto`, r4 `grpc-unary`, r5 `grpc-unary`
