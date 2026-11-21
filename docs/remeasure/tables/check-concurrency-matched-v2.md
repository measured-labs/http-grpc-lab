# 확인 실행 — 고친 matched를 바로 뒤에 이어서 잰 default와 견줌(동시 50·200, 3회 × 15초)

> 원자료 `results/2026-11/check-matched-v2/concurrency-matched` · 이 표는 `scripts/remeasure/summarize.py`가 뽑았다(손으로 고치지 않는다). 견준 폴더 `results/2026-11/check-default/concurrency-default`.

## 측정 조건

- 커밋: aa22b9d524d140b91d835f4e2e3a757b048082bd
- 작업 트리: 깨끗함(추적 파일 변경 없음)
- 축 concurrency · 설정 matched · 회차 3 · 한 번 15s · 워밍업 대상마다 200회(동시 10)
- openjdk version "21.0.5" 2024-10-15 LTS · k6 v2.4.0 (commit/dd9395906d, go1.26.11, linux/amd64) · 코어 24 · k6와 두 서버(apiServer, dataServer)가 같은 머신에서 돈다
- 1분 부하 평균: 시작 전 3.89~3.99, 끝난 뒤 47.20~55.47
- 회차별 1분 부하(시작 → 끝, 기준 4.0): r1 3.91 → 47.25 (기다림 471초/상한 600초) · r2 3.89 → 47.20 (기다림 521초/상한 600초) · r3 3.99 → 55.47 (기다림 451초/상한 600초)
- 회차별 시각: r1 2026-11-21T19:56:50+09:00 ~ 2026-11-21T19:59:48+09:00 · r2 2026-11-21T20:08:29+09:00 ~ 2026-11-21T20:11:27+09:00 · r3 2026-11-21T20:18:58+09:00 ~ 2026-11-21T20:21:58+09:00
- 실행 방식: host-jar
- 다른 컨테이너(회차 시작 때 실행 중인 수, 회차별): r1 기록 없음 · r2 기록 없음 · r3 기록 없음
- BENCH_CONFIG:

```
BENCH_CONFIG server=dataServer profile=matched read: tomcatMaxThreads=512 tomcatMaxKeepAliveRequests=-1 tomcatKeepAliveTimeoutMs=-1 | set: grpcServerExecutor=grpc-default(cached,unbounded) grpcHandlerDispatcher=fixed(512)
BENCH_CONFIG server=apiServer profile=matched read: tomcatMaxThreads=512 httpPoolMaxConnections=512 httpClientAcceptGzip=true | set: httpPoolPendingAcquireMaxCount=512 httpPoolPendingAcquireTimeoutMs=45000 httpPoolMaxIdleTimeMs=-1 httpPoolMaxLifeTimeMs=-1 grpcChannels=1
```

- 실행 순서(회차별): r1 http-json http-bytes http-proto grpc-unary · r2 http-bytes http-proto grpc-unary http-json · r3 http-proto grpc-unary http-json http-bytes
- 처리량 = k6 `http_reqs.rate`. 지연 = k6 `http_req_duration`의 중앙값과 p95(회차 값들의 중앙값). 서버 하위 호출 = apiServer 응답의 `latencyMs`(dataServer 호출 구간) 평균(회차 값들의 중앙값). 두 서버 사이의 본문 크기 = dataServer → apiServer 응답 본문의 바이트 수(`sizes.json`, 조건과 경로마다 한 번 잰 값 — HTTP 머리·청크 머리 제외). `grpc-unary`는 재지 않고 `http-proto`와 같은 Protobuf 메시지 + gRPC 메시지 머리 5바이트로 적었다(HTTP/2 프레임 제외, † 표시). k6가 받은 바이트 = k6 `data_received` ÷ 요청 수(apiServer → k6의 작은 JSON 응답, 머리 포함).
- 본문 크기 표: `results/2026-11/sizes.json` (커밋 ac84c78, 2026-11-21T04:32:56+09:00)
- ⚠ 표시 = 실패로 판정된 측정(k6 종료 코드 ≠ 0, 요청 실패율 ≠ 0, check 실패). 값은 그대로 표에 넣었다.

## 요약 — 처리량 중앙값 req/s [최소~최대], 회차별 1위

| 조건 | `http-json` | `http-bytes` | `http-proto` | `grpc-unary` | 1위(회차 수) | 1위가 모든 회차에서 같음 |
|------|------|------|------|------|------|------|
| vus50 | 5,815 [2,204~5,825] | 5,639 [3,270~7,289] | 6,059 [3,091~7,497] | 5,347 [4,455~5,705] | `http-proto` 2, `http-bytes` 1 | 아니오 |
| vus200 | 7,291 [6,570~7,352] | 8,518 [8,490~8,632] | 8,468 [7,377~8,820] | 8,218 [6,807~8,650] | `http-bytes` 2, `http-proto` 1 | 아니오 |

## 요약 — 견준 폴더 대비 처리량 중앙값의 변화 %(= 이 폴더 ÷ 견준 폴더 − 1)

| 조건 | `http-json` | `http-bytes` | `http-proto` | `grpc-unary` |
|------|------|------|------|------|
| vus50 | -6.9% | -12.4% | -14.2% | -10.9% |
| vus200 | -3.8% | -5.9% | -11.6% | -12.2% |

## 요약 — 견준 폴더 대비 서버 하위 호출 평균의 차이 ms(= 이 폴더 − 견준 폴더, 회차 값들의 중앙값끼리)

| 조건 | `http-json` | `http-bytes` | `http-proto` | `grpc-unary` |
|------|------|------|------|------|
| vus50 | +0.35 | +0.63 | +0.71 | +0.91 |
| vus200 | +0.36 | +0.55 | +0.96 | +2.85 |

## 결과

### vus50

| 경로 | r1 req/s | r2 req/s | r3 req/s | 중앙값 | 최소 | 최대 | 1위와 구간 | 지연 중앙값 ms | 지연 p95 ms | 서버 하위 호출 평균 ms | 두 서버 사이의 본문 크기 | k6가 받은 바이트(apiServer의 응답)/요청 | 견준 폴더 중앙값 | 처리량 변화 | 견준 폴더 서버 하위 호출 ms | 서버 하위 호출 차이 ms |
|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|
| `http-json` | 2,203.9 | 5,824.7 | 5,814.9 | 5,814.9 | 2,203.9 | 5,824.7 | 겹침 | 7.417 | 14.331 | 5.203 | 13,767 | 241.1 | 6,247.0 | -6.9% | 4.849 | +0.354 |
| `http-bytes` | 5,639.1 | 3,269.7 | 7,289.4 | 5,639.1 | 3,269.7 | 7,289.4 | 겹침 | 7.618 | 14.953 | 4.897 | 10,240 | 243.1 | 6,440.1 | -12.4% | 4.271 | +0.626 |
| `http-proto` | 6,059.5 | 7,497.3 | 3,090.6 | 6,059.5 | 3,090.6 | 7,497.3 | (1위) | 7.084 | 13.794 | 4.517 | 10,291 | 245.1 | 7,061.3 | -14.2% | 3.803 | +0.714 |
| `grpc-unary` | 4,454.9 | 5,704.5 | 5,347.2 | 5,347.2 | 4,454.9 | 5,704.5 | 겹침 | 6.565 | 25.381 | 6.853 | 10,296 † | 242.2 | 6,000.8 | -10.9% | 5.938 | +0.915 |

- 회차별 1위: r1 `http-proto`, r2 `http-proto`, r3 `http-bytes` → **회차마다 갈림**
- 회차별 p95가 가장 낮은 경로: r1 `http-proto`, r2 `http-proto`, r3 `http-bytes`

### vus200

| 경로 | r1 req/s | r2 req/s | r3 req/s | 중앙값 | 최소 | 최대 | 1위와 구간 | 지연 중앙값 ms | 지연 p95 ms | 서버 하위 호출 평균 ms | 두 서버 사이의 본문 크기 | k6가 받은 바이트(apiServer의 응답)/요청 | 견준 폴더 중앙값 | 처리량 변화 | 견준 폴더 서버 하위 호출 ms | 서버 하위 호출 차이 ms |
|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|------|
| `http-json` | 6,570.4 | 7,291.0 | 7,351.6 | 7,291.0 | 6,570.4 | 7,351.6 | 안 겹침 | 24.879 | 45.273 | 15.571 | 13,767 | 241.8 | 7,581.2 | -3.8% | 15.213 | +0.357 |
| `http-bytes` | 8,518.0 | 8,631.6 | 8,489.6 | 8,518.0 | 8,489.6 | 8,631.6 | (1위) | 20.967 | 38.787 | 11.892 | 10,240 | 243.6 | 9,051.6 | -5.9% | 11.341 | +0.551 |
| `http-proto` | 7,376.9 | 8,820.2 | 8,468.2 | 8,468.2 | 7,376.9 | 8,820.2 | 겹침 | 21.217 | 39.774 | 11.980 | 10,291 | 245.6 | 9,577.2 | -11.6% | 11.017 | +0.963 |
| `grpc-unary` | 6,807.3 | 8,650.1 | 8,218.4 | 8,218.4 | 6,807.3 | 8,650.1 | 겹침 | 22.555 | 34.505 | 20.644 | 10,296 † | 243.1 | 9,362.3 | -12.2% | 17.794 | +2.850 |

- 회차별 1위: r1 `http-bytes`, r2 `http-proto`, r3 `http-bytes` → **회차마다 갈림**
- 회차별 p95가 가장 낮은 경로: r1 `http-bytes`, r2 `grpc-unary`, r3 `grpc-unary`
