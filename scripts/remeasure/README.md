# 다시 재기(2026-11) 실행 스크립트

경로 이름과 설정(`default`, `matched`)은 `docs/remeasure/PATHS.md`에 있다.

## 준비

```bash
(cd dataServer && ./gradlew test) && (cd apiServer && ./gradlew test)
```

jar는 `run.sh`가 시작할 때 지금 커밋에서 `./gradlew bootJar`(두 모듈)로 빌드한다(`--no-build`로 건너뛴다).
추적 파일에 커밋하지 않은 변경이 있으면(`docs/plans/` 제외) 시작하지 않는다(`--allow-dirty`로 풀고, `conditions.json`의 `allowDirty`에 남는다).

서버는 스크립트가 띄운다. 호스트 직접 실행은 8080·8081·9091이, 지연 조건(`--delay`, 컨테이너)은 8080만 비어 있어야 한다.
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
| `--only-round` | (모두) | 회차 N 하나만 돈다. 폴더는 `roundN`, 경로 순서도 회차 N의 것(N−1만큼 돌린 것). 그 폴더가 있으면 멈춘다. `run-delays.sh`가 쓴다 |
| `--duration` | 30s | 측정 한 번의 길이 |
| `--label` | nodelay (`--delay X`면 `delayXms`) | 결과 이름표 |
| `--delay` | (없음 — 호스트 직접) | `0`, `1`, `10`: 두 서버를 컨테이너로 띄우고 dataServer에서 나가는 쪽에 그만큼 지연(아래 "지연 조건") |
| `--max-load` | 4.0 | 회차를 시작할 1분 부하 평균의 기준 — 넘으면 내려가길 기다린다 |
| `--max-wait` | 600 | 부하를 기다리는 최대 초. 넘으면 그대로 시작하고 기록한다 |
| `--conditions` | (전체) | 축의 조건 중 일부만, 쉼표로(예: `1kb,1mb`). 짧은 확인용 — 본 측정은 전체 |
| `--no-build` | (빌드함) | 시작할 때 `./gradlew bootJar`를 돌리지 않는다(`--delay`면 이미지도 만들지 않는다) |
| `--allow-dirty` | (멈춤) | 추적 파일에 커밋하지 않은 변경이 있어도 시작한다 |
| `--warmup` / `--warmup-vus` | 200 / 10 | 회차마다 조건 × 경로 하나를 몇 번, 동시 몇으로 부를지 |
| `--pause` | 3 | 측정 사이에 쉬는 초 |

회차마다: 부하가 기준 아래로 내려가길 기다림 → 두 서버를 새로 띄움 → 워밍업 → 조건 순서대로, 경로는 회차 r에서 r−1만큼 돌린 순서로 k6 실행 → 서버를 끔.
Ctrl-C나 오류로 멈춰도 스크립트가 띄운 서버만 끄고, 그 회차를 `status: interrupted`로 적는다.

## 지연 조건 — `--delay 0|1|10`

본 측정은 `run-delays.sh`로 세 지연을 회차마다 번갈아 잰다. 지연 조건을 하나씩 통째로 차례로 돌리면
몇 시간에 걸친 머신 부하의 변화가 지연의 효과에 섞이기 때문이다.

```bash
scripts/remeasure/run-delays.sh --axis size --config default        # 5회 × 지연 0·1·10ms, 30초 → delay0ms, delay1ms, delay10ms
scripts/remeasure/run.sh --axis size --config default --delay 10    # 한 지연만(이름표 delay10ms)
```

`run-delays.sh`: 회차 r = 1…R마다 `run.sh --rounds R --only-round r --delay X`를 세 지연에 한 번씩 부른다.
지연의 순서는 회차마다 돌린다(1회차 0→1→10, 2회차 1→10→0, 3회차 10→0→1, …). 경로 순서 돌리기는 회차 r 기준 그대로다.

| 인자 | 기본 | 뜻 |
|------|------|----|
| `--axis` / `--config` | size / default | `run.sh`와 같다 |
| `--rounds` | 5 | 회차 수 |
| `--duration`, `--max-load`, `--max-wait`, `--conditions` | (`run.sh` 기본) | `run.sh`에 그대로 넘긴다 |
| `--label-prefix` | (없음) | 이름표 앞에 붙인다. 확인 실행은 `smoke-` 등을 붙여 본 측정 폴더(`delay0ms` 등)에 쓰지 않는다 |

- 처음 실행만 jar와 이미지를 만들고 나머지는 `--no-build`로 돈다.
- 걸리는 시간: 실행 하나 = 측정 수 × (길이 + 쉼 3초) + 띄우기·워밍업·왕복 확인 약 40초. 크기 축(28측정 × 33초)이면 약 16분, 5회 × 세 지연 = 15번 ≈ 4시간(부하 대기 제외).
- 시작할 때 모든 지연 × 회차의 폴더가 없는지 먼저 본다. 어느 한 실행이 실패하면(Ctrl-C 포함) 멈추고 0이 아닌 코드로 끝난다. 그때까지의 원자료는 남는다.
- 실행 순서와 시각: `results/2026-11/<접두>delay-run-order-<축>-<설정>.json`(실행이 하나 끝날 때마다 다시 쓴다 — `status`, 실행마다 회차·지연·시각·종료 코드).

`--delay`를 주면 jar를 호스트에서 직접 띄우는 대신 `docker-compose.remeasure.yml`(compose 프로젝트 `rm-http-grpc`)로
두 서버를 컨테이너로 띄우고 내린다. 회차, 경로 순서 돌리기, 워밍업, 부하 대기, 원자료는 위와 같다. 구성이 호스트 직접 실행과
무엇이 다른지는 `docs/remeasure/PATHS.md` "지연 조건의 구성".

- 지연: dataServer 컨테이너의 `eth0`에 `tc qdisc replace dev eth0 root netem delay Xms limit 100000`.
  **dataServer에서 나가는 쪽(dataServer → apiServer)에만 X ms — 왕복이 X ms 늘어난다.** apiServer → dataServer 방향과 k6 → apiServer 구간에는 걸리지 않는다.
  지연 0도 `netem delay 0ms`를 같은 자리에 건다(지연만 다르고 큐 구성은 같게).
- 이미지: `scripts/remeasure/container/Dockerfile`(`eclipse-temurin:21-jre` + `iproute2`, `iputils-ping`) → `rm-http-grpc/jre21-nettools:1`.
  jar는 이미지에 넣지 않고 호스트에서 빌드한 jar(`./gradlew bootJar`, 위와 같음)를 읽기 전용으로 붙인다. 첫 빌드에는 apt 내려받기가 필요하다.
- 회차마다 두 서버를 띄운 뒤, 측정 전에 dataServer 컨테이너의 `tc qdisc show dev eth0`과, apiServer 컨테이너에서 dataServer로 `ping` 20번과
  TCP 연결 10번(curl `time_connect − time_namelookup`)을 재 `scripts/remeasure/rtt_check.py`로 판정한다:
  세 명령의 종료 코드 0 · qdisc가 root `netem`, 지연 X ms(tc는 지연 0이면 `delay`를 출력하지 않는다 — 없으면 0으로 읽는다), `limit 100000` ·
  ping 응답 20개, 손실 0 · ping 최솟값 ≥ X − 0.05ms, ping 중앙값 ≤ X + (1.0 + 0.1·X)ms · TCP 연결 10줄(형식 밖의 줄 없음), 최솟값 ≥ X − 0.05ms.
  명령의 stderr는 값과 섞지 않는다. 맞지 않으면 그 회차를 `status: failed-rtt-check`로 적고(이유는 `container.rttCheck.failReasons`) 멈춘다.
- 호스트에서는 8080만 비어 있으면 된다(dataServer 포트는 열지 않으므로 8080만 본다). 이전 실행의 `rm-http-grpc` 컨테이너가 남아 있으면 시작하지 않는다.
- Ctrl-C나 오류로 멈추면 `docker compose -p rm-http-grpc down`으로 이 프로젝트의 컨테이너와 네트워크만 내린다(이미지는 남긴다).
  내리지 못하면 그 회차를 `status: failed-teardown`으로 적고, 남은 컨테이너 이름을 보여 주고 0이 아닌 코드로 끝난다.
  `docker compose` 호출은 stdin을 읽지 않는다(터미널에서 멈추지 않게).
  스크립트가 정리하지 못하고 죽어도 컨테이너는 `RM_MAX_LIFETIME_S`(기본 10800초)가 지나면 서버를 끄고 멈춘다.
  직접 내릴 때: `DELAY_MS=0 docker compose -f docker-compose.remeasure.yml down`.
- `conditions.json`에 더해지는 것: `execution`(`host-jar` | `docker-compose`), `container` — 지연 값과 그 뜻, 실제 qdisc, 왕복 확인(`rttCheck`: ping·TCP 값, 판정),
  이미지와 바탕 이미지, 이미지의 JDK, Docker·compose 버전. `jdk`는 이미지의 JDK다(호스트 JDK와 다를 수 있다).
  `onlyRound`(`--only-round`로 돈 회차).
- `otherContainers`(호스트 직접 실행에서도): 회차 시작 때(서버를 띄우기 전) 이 머신에서 돌고 있던 컨테이너 수와 compose 프로젝트 이름별 수.
  컨테이너 이름이나 환경 변수는 적지 않는다(`docker ps`를 읽기만 한다).
- 서버 로그(`dataServer.log`, `apiServer.log`)는 `docker compose logs`에서 받아 같은 자리에 둔다. dataServer 로그 첫 줄의 `NETEM`이 컨테이너가 건 qdisc다.

## 결과

`results/2026-11/<이름표>/<축>-<설정>/round<r>/` (지연 조건의 이름표 기본값: `delay0ms`, `delay1ms`, `delay10ms`)

- `<조건>__<경로>.json` — k6 `--summary-export`. `<조건>__<경로>.k6.txt` — k6 출력
- `conditions.json` — 커밋, 작업 트리(`allowDirty`, `jarsBuiltAtStart`), JDK, k6, 코어 수, `BENCH_CONFIG` 두 줄, 실행 순서, 시각, 전후 부하 평균, 부하 대기(`loadWait`), 워밍업, 측정마다 실패 판정(`failed`, `failReasons`)
- `_warmup.json` — 워밍업의 k6 요약
- `dataServer.log`, `apiServer.log` — 기동 로그(`.gitignore`의 `*.log`라 커밋되지 않는다)

이미 있는 회차 폴더는 덮어쓰지 않고 멈춘다. 실패한 측정도 지우지 않는다.

## 표 뽑기

```bash
python3 scripts/remeasure/summarize.py results/2026-11/nodelay/size-default            # 표준 출력
python3 scripts/remeasure/summarize.py results/2026-11/nodelay/size-default -o table.md
python3 scripts/remeasure/summarize.py results/2026-11/delay10ms/size-default \
        --baseline results/2026-11/delay0ms/size-default                      # delay0ms와 견준 변화를 더한다
scripts/remeasure/make-tables.sh                                               # docs/remeasure/tables/*.md를 모두 다시 만든다
```

`make-tables.sh`는 `results/2026-11/`의 축 폴더마다 `summarize.py`를 불러 `docs/remeasure/tables/`에 표를 쓴다(어느 폴더를 어느 폴더와 견주는지는 스크립트 안의 목록).
같은 원자료에서 두 번 돌리면 같은 파일이 나온다. `docs/remeasure/RESULT.md`의 수치는 이 표에서 옮긴다.

조건마다 경로별 회차 처리량과 중앙값·최소·최대, k6 지연(중앙값, p95), 서버가 잰 하위 호출 지연,
두 서버 사이의 본문 크기(`results/2026-11/sizes.json`, `--sizes`로 바꿈 — 없으면 열을 비우고 경고),
k6가 받은 바이트(apiServer의 응답)/요청, 회차별 1위와 "모든 회차에서 같음/회차마다 갈림", 1위와 처리량 구간이 겹치는지, 회차별 p95가 가장 낮은 경로를 적는다.
맨 앞의 "요약"은 조건 × 경로의 처리량 중앙값 [최소~최대]와 1위가 모든 회차에서 같았는지다. `--baseline`을 주면 조건과 경로마다 견준 폴더의 중앙값 대비 처리량 변화(%)와 서버 하위 호출 평균의 차이(ms)를 더한다.
"측정 조건"에는 회차별 시작·끝 부하, 기다린 시간, 기준을 넘은 채 시작했는지(`startedAboveMax`), 회차별 시각도 적는다.
"측정 조건"에는 실행 방식(`host-jar` / `docker-compose`), 다른 컨테이너 수(회차별)를 적고, 지연 조건이면 지연 값과 그 뜻, 회차별 왕복 확인값(ping 중앙값),
이미지 id, 이미지의 JDK를 더한다(호스트 실행의 원자료에서는 없는 값을 "기록 없음"으로 둔다).
