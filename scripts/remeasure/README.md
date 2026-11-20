# 다시 재기(2026-11) 실행 스크립트

경로 이름과 설정(`default`, `matched`)은 `docs/remeasure/PATHS.md`에 있다.

## 준비

```bash
(cd dataServer && ./gradlew test) && (cd apiServer && ./gradlew test)
```

jar는 `run.sh`가 시작할 때 지금 커밋에서 `./gradlew bootJar`(두 모듈)로 빌드한다(`--no-build`로 건너뛴다).
추적 파일에 커밋하지 않은 변경이 있으면(`docs/plans/` 제외) 시작하지 않는다(`--allow-dirty`로 풀고, `conditions.json`의 `allowDirty`에 남는다).

서버는 스크립트가 띄운다. 8080·8081·9091이 비어 있어야 한다.
회차를 시작하기 전마다(첫 회차 포함) 1분 부하 평균이 기준(기본 4.0) 아래로 내려갈 때까지 10초마다 보며 기다린다.
최대 대기(기본 600초)를 넘으면 멈추지 않고 시작하고, `conditions.json`의 `loadWait`에 기다린 시간과
`startedAboveMax: true`를 적는다(그때의 값은 `load1Before`). 처음 시작할 때 부하가 기준의 3배를 넘으면 CPU 상위 프로세스를 출력에 보여 준다.

## 두 서버 사이의 본문 크기 — `sizes.sh`

```bash
scripts/remeasure/sizes.sh          # → results/2026-11/sizes.json (커밋 대상) + 표준 출력에 표
```

k6의 받은 바이트(`data_received`)는 apiServer → k6의 작은 JSON이라 재려는 구간(dataServer → apiServer)의 전송량이 아니다.
데이터가 고정이라 조건마다 크기가 일정하므로, dataServer만 띄워 세 축의 조건마다 HTTP 경로의 dataServer 엔드포인트를 한 번씩 직접 부르고
본문 바이트 수와 `Content-Length` 유무를 적는다. `requestId`는 apiServer가 보내는 UUID와 같은 36자 고정 값으로 보낸다(길이가 다르면 본문이 그만큼 달라진다).
`grpc-unary`는 재지 않는다 — `http-proto`의 본문과 같은 Protobuf 메시지(테스트가 확인) + gRPC 메시지 머리 5바이트로 적고, HTTP/2 프레임은 따로다.
같은 빌드·더러운 트리 규칙(`--no-build`, `--allow-dirty`)을 따르고 8081·9091이 비어 있어야 한다.

## 한 축 돌리기

```bash
scripts/remeasure/run.sh --axis size --config default          # 5회, 30초, 이름표 nodelay
scripts/remeasure/run.sh --axis concurrency --config matched --rounds 5 --duration 30s
scripts/remeasure/run.sh --axis complexity --config default --label nodelay --max-load 4.0
```

| 인자 | 기본 | 뜻 |
|------|------|----|
| `--axis` | (필수) | `size` 7개 크기(동시 10) · `concurrency` 동시 10/50/100/200/500(10kb) · `complexity` 5단계(동시 10) |
| `--config` | (필수) | `default` 또는 `matched` |
| `--rounds` | 5 | 반복 횟수 |
| `--duration` | 30s | 측정 한 번의 길이 |
| `--label` | nodelay | 결과 이름표 |
| `--max-load` | 4.0 | 회차를 시작할 1분 부하 평균의 기준 — 넘으면 내려가길 기다린다 |
| `--max-wait` | 600 | 부하를 기다리는 최대 초. 넘으면 그대로 시작하고 기록한다 |
| `--conditions` | (전체) | 축의 조건 중 일부만, 쉼표로(예: `1kb,1mb`). 짧은 확인용 — 본 측정은 전체 |
| `--no-build` | (빌드함) | 시작할 때 `./gradlew bootJar`를 돌리지 않는다 |
| `--allow-dirty` | (멈춤) | 추적 파일에 커밋하지 않은 변경이 있어도 시작한다 |
| `--warmup` / `--warmup-vus` | 200 / 10 | 회차마다 조건 × 경로 하나를 몇 번, 동시 몇으로 부를지 |
| `--pause` | 3 | 측정 사이에 쉬는 초 |

회차마다: 부하가 기준 아래로 내려가길 기다림 → 두 서버를 새로 띄움 → 워밍업 → 조건 순서대로, 경로는 회차 r에서 r−1만큼 돌린 순서로 k6 실행 → 서버를 끔.
Ctrl-C나 오류로 멈춰도 스크립트가 띄운 서버만 끄고, 그 회차를 `status: interrupted`로 적는다.

## 결과

`results/2026-11/<이름표>/<축>-<설정>/round<r>/`

- `<조건>__<경로>.json` — k6 `--summary-export`. `<조건>__<경로>.k6.txt` — k6 출력
- `conditions.json` — 커밋, 작업 트리(`allowDirty`, `jarsBuiltAtStart`), JDK, k6, 코어 수, `BENCH_CONFIG` 두 줄, 실행 순서, 시각, 전후 부하 평균, 부하 대기(`loadWait`), 워밍업, 측정마다 실패 판정(`failed`, `failReasons`)
- `_warmup.json` — 워밍업의 k6 요약
- `dataServer.log`, `apiServer.log` — 기동 로그(`.gitignore`의 `*.log`라 커밋되지 않는다)

이미 있는 회차 폴더는 덮어쓰지 않고 멈춘다. 실패한 측정도 지우지 않는다.

## 표 뽑기

```bash
python3 scripts/remeasure/summarize.py results/2026-11/nodelay/size-default            # 표준 출력
python3 scripts/remeasure/summarize.py results/2026-11/nodelay/size-default -o table.md
```

조건마다 경로별 회차 처리량과 중앙값·최소·최대, k6 지연(중앙값, p95), 서버가 잰 하위 호출 지연,
두 서버 사이의 본문 크기(`results/2026-11/sizes.json`, `--sizes`로 바꿈 — 없으면 열을 비우고 경고),
k6가 받은 바이트(apiServer의 응답)/요청, 회차별 1위와 "모든 회차에서 같음/회차마다 갈림", 1위와 처리량 구간이 겹치는지를 적는다.
