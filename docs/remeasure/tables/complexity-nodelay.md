# 복잡도 축 — 호스트 직접 실행, 기본 설정, 동시 10

> 원자료 `results/2026-11/nodelay/complexity-default` · 이 표는 `scripts/remeasure/summarize.py`가 뽑았다(손으로 고치지 않는다).

## 측정 조건

- 커밋: efd3882e5c16afebb42b7e23d5d9cfe68560acbe
- 작업 트리: 깨끗함(추적 파일 변경 없음)
- 축 complexity · 설정 default · 회차 5 · 한 번 30s · 워밍업 대상마다 200회(동시 10)
- openjdk version "21.0.5" 2024-10-15 LTS · k6 v2.4.0 (commit/dd9395906d, go1.26.11, linux/amd64) · 코어 24 · k6와 두 서버(apiServer, dataServer)가 같은 머신에서 돈다
- 1분 부하 평균: 시작 전 3.64~3.94, 끝난 뒤 11.29~16.38
- 회차별 1분 부하(시작 → 끝, 기준 4.0): r1 3.87 → 12.76 (기다림 20초/상한 3600초) · r2 3.64 → 12.43 (기다림 120초/상한 3600초) · r3 3.94 → 13.72 (기다림 130초/상한 3600초) · r4 3.78 → 11.29 (기다림 110초/상한 3600초) · r5 3.71 → 16.38 (기다림 91초/상한 3600초)
- 회차별 시각: r1 2026-11-22T03:48:44+09:00 ~ 2026-11-22T03:57:40+09:00 · r2 2026-11-22T03:59:41+09:00 ~ 2026-11-22T04:08:36+09:00 · r3 2026-11-22T04:10:47+09:00 ~ 2026-11-22T04:19:43+09:00 · r4 2026-11-22T04:21:34+09:00 ~ 2026-11-22T04:30:27+09:00 · r5 2026-11-22T04:31:58+09:00 ~ 2026-11-22T04:40:52+09:00
- 실행 방식: host-jar
- 다른 컨테이너(회차 시작 때 실행 중인 수, 회차별): r1 0 · r2 0 · r3 0 · r4 0 · r5 0
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
| simple | 3,732 [3,460~4,333] | 4,228 [3,900~4,520] | 4,487 [4,080~4,578] | `grpc-unary` 3, `http-proto` 2 | 아니오 |
| medium | 4,064 [3,957~4,121] | 4,340 [3,840~4,558] | 4,808 [4,440~4,844] | `grpc-unary` 5 | 예 |
| complex | 3,490 [3,446~3,905] | 4,137 [3,875~4,209] | 4,306 [1,978~4,412] | `grpc-unary` 3, `http-proto` 2 | 아니오 |
| ultra | 2,192 [1,987~2,210] | 3,100 [2,781~3,205] | 3,195 [2,833~3,268] | `grpc-unary` 4, `http-proto` 1 | 아니오 |
| extreme | 361 [332~366] | 861 [740~874] | 886 [769~898] | `grpc-unary` 4, `http-proto` 1 | 아니오 |

## 요약 — 경로끼리 견줌: 처리량 중앙값의 비율 %(회차마다 같은 경로가 앞섰는지)

| 조건 | 직렬화: `http-proto` ÷ `http-json` − 1 | 전송 방식: `grpc-unary` ÷ `http-proto` − 1 |
|------|------|------|
| simple | +13.3% (앞선 회차 3/5) | +6.1% (앞선 회차 3/5) |
| medium | +6.8% (앞선 회차 3/5) | +10.8% (앞선 회차 5/5) |
| complex | +18.5% (앞선 회차 5/5) | +4.1% (앞선 회차 3/5) |
| ultra | +41.5% (앞선 회차 5/5) | +3.1% (앞선 회차 4/5) |
| extreme | +138.2% (앞선 회차 5/5) | +2.9% (앞선 회차 4/5) |

## 결과

### simple

| 경로 | r1 req/s | r2 req/s | r3 req/s | r4 req/s | r5 req/s | 중앙값 | 최소 | 최대 | 1위와 구간 | 지연 중앙값 ms | 지연 p95 ms | 서버 하위 호출 평균 ms | 두 서버 사이의 본문 크기 | k6가 받은 바이트(apiServer의 응답)/요청 |
|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|
| `http-json` | 3,460.5 | 4,332.9 | 3,705.3 | 3,732.4 | 4,148.2 | 3,732.4 | 3,460.5 | 4,332.9 | 겹침 | 2.041 | 4.028 | 1.393 | 226 | 264.1 |
| `http-proto` | 4,400.2 | 3,937.2 | 4,228.1 | 4,519.7 | 3,900.0 | 4,228.1 | 3,900.0 | 4,519.7 | 겹침 | 1.881 | 2.866 | 1.111 | 89 | 275.1 |
| `grpc-unary` | 4,079.8 | 4,533.1 | 4,110.5 | 4,577.8 | 4,487.2 | 4,487.2 | 4,079.8 | 4,577.8 | (1위) | 1.752 | 3.360 | 1.145 | 94 † | 264.1 |

- 회차별 1위: r1 `http-proto`, r2 `grpc-unary`, r3 `http-proto`, r4 `grpc-unary`, r5 `grpc-unary` → **회차마다 갈림**
- 회차별 p95가 가장 낮은 경로: r1 `http-proto`, r2 `http-json`, r3 `http-proto`, r4 `http-proto`, r5 `http-json`

### medium

| 경로 | r1 req/s | r2 req/s | r3 req/s | r4 req/s | r5 req/s | 중앙값 | 최소 | 최대 | 1위와 구간 | 지연 중앙값 ms | 지연 p95 ms | 서버 하위 호출 평균 ms | 두 서버 사이의 본문 크기 | k6가 받은 바이트(apiServer의 응답)/요청 |
|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|
| `http-json` | 4,079.9 | 3,957.1 | 4,064.3 | 4,031.8 | 4,121.3 | 4,064.3 | 3,957.1 | 4,121.3 | 안 겹침 | 2.091 | 2.847 | 1.259 | 728 | 264.1 |
| `http-proto` | 3,840.4 | 4,557.6 | 4,039.1 | 4,340.4 | 4,386.7 | 4,340.4 | 3,840.4 | 4,557.6 | 겹침 | 1.945 | 2.645 | 1.116 | 446 | 276.1 |
| `grpc-unary` | 4,439.6 | 4,831.2 | 4,617.1 | 4,843.9 | 4,807.5 | 4,807.5 | 4,439.6 | 4,843.9 | (1위) | 1.759 | 2.526 | 1.031 | 451 † | 264.1 |

- 회차별 1위: r1 `grpc-unary`, r2 `grpc-unary`, r3 `grpc-unary`, r4 `grpc-unary`, r5 `grpc-unary` → **모든 회차에서 같음**
- 회차별 p95가 가장 낮은 경로: r1 `grpc-unary`, r2 `grpc-unary`, r3 `grpc-unary`, r4 `grpc-unary`, r5 `grpc-unary`

### complex

| 경로 | r1 req/s | r2 req/s | r3 req/s | r4 req/s | r5 req/s | 중앙값 | 최소 | 최대 | 1위와 구간 | 지연 중앙값 ms | 지연 p95 ms | 서버 하위 호출 평균 ms | 두 서버 사이의 본문 크기 | k6가 받은 바이트(apiServer의 응답)/요청 |
|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|
| `http-json` | 3,468.8 | 3,905.0 | 3,490.0 | 3,724.6 | 3,446.4 | 3,490.0 | 3,446.4 | 3,905.0 | 겹침 | 2.304 | 3.281 | 1.557 | 3,630 | 266.1 |
| `http-proto` | 4,006.4 | 4,208.7 | 4,136.6 | 3,875.3 | 4,164.5 | 4,136.6 | 3,875.3 | 4,208.7 | 겹침 | 2.052 | 2.779 | 1.206 | 2,073 | 278.1 |
| `grpc-unary` | 4,411.6 | 4,119.2 | 1,977.7 | 4,410.0 | 4,306.5 | 4,306.5 | 1,977.7 | 4,411.6 | (1위) | 1.971 | 2.829 | 1.194 | 2,078 † | 265.1 |

- 회차별 1위: r1 `grpc-unary`, r2 `http-proto`, r3 `http-proto`, r4 `grpc-unary`, r5 `grpc-unary` → **회차마다 갈림**
- 회차별 p95가 가장 낮은 경로: r1 `grpc-unary`, r2 `http-proto`, r3 `http-proto`, r4 `grpc-unary`, r5 `http-proto`

### ultra

| 경로 | r1 req/s | r2 req/s | r3 req/s | r4 req/s | r5 req/s | 중앙값 | 최소 | 최대 | 1위와 구간 | 지연 중앙값 ms | 지연 p95 ms | 서버 하위 호출 평균 ms | 두 서버 사이의 본문 크기 | k6가 받은 바이트(apiServer의 응답)/요청 |
|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|
| `http-json` | 2,209.9 | 2,170.3 | 2,206.5 | 2,191.6 | 1,987.5 | 2,191.6 | 1,987.5 | 2,209.9 | 안 겹침 | 4.132 | 5.420 | 3.287 | 36,079 | 265.1 |
| `http-proto` | 3,171.9 | 3,205.0 | 2,981.9 | 3,100.3 | 2,780.6 | 3,100.3 | 2,780.6 | 3,205.0 | 겹침 | 2.846 | 3.690 | 2.002 | 21,397 | 277.1 |
| `grpc-unary` | 3,124.2 | 3,241.5 | 3,195.1 | 3,267.7 | 2,832.8 | 3,195.1 | 2,832.8 | 3,267.7 | (1위) | 2.763 | 3.748 | 1.966 | 21,402 † | 263.1 |

- 회차별 1위: r1 `http-proto`, r2 `grpc-unary`, r3 `grpc-unary`, r4 `grpc-unary`, r5 `grpc-unary` → **회차마다 갈림**
- 회차별 p95가 가장 낮은 경로: r1 `http-proto`, r2 `http-proto`, r3 `grpc-unary`, r4 `grpc-unary`, r5 `grpc-unary`

### extreme

| 경로 | r1 req/s | r2 req/s | r3 req/s | r4 req/s | r5 req/s | 중앙값 | 최소 | 최대 | 1위와 구간 | 지연 중앙값 ms | 지연 p95 ms | 서버 하위 호출 평균 ms | 두 서버 사이의 본문 크기 | k6가 받은 바이트(apiServer의 응답)/요청 |
|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|
| `http-json` | 349.1 | 362.0 | 361.5 | 366.2 | 332.5 | 361.5 | 332.5 | 366.2 | 안 겹침 | 27.052 | 32.529 | 26.241 | 421,477 | 269.1 |
| `http-proto` | 799.4 | 874.4 | 871.3 | 860.9 | 739.9 | 860.9 | 739.9 | 874.4 | 겹침 | 11.171 | 13.554 | 10.278 | 219,522 | 280.6 |
| `grpc-unary` | 890.1 | 897.5 | 848.3 | 886.0 | 769.0 | 886.0 | 769.0 | 897.5 | (1위) | 10.755 | 13.598 | 9.949 | 219,527 † | 265.5 |

- 회차별 1위: r1 `grpc-unary`, r2 `grpc-unary`, r3 `http-proto`, r4 `grpc-unary`, r5 `grpc-unary` → **회차마다 갈림**
- 회차별 p95가 가장 낮은 경로: r1 `grpc-unary`, r2 `http-proto`, r3 `http-proto`, r4 `http-proto`, r5 `http-proto`
