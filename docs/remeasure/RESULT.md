# 단순 통신 다시 재기 — 결과 (2026-11-21~22)

이 문서는 요청 하나에 응답 하나인 통신(`http-json`, `http-bytes`, `http-proto`, `grpc-unary`)을 같은 조건으로 5회씩 다시 잰 결과다. 2026-01의 Phase 1~7 결과(`docs/phase/`)가 조건당 1~3회였고 경로마다 하는 일이 달라서 다시 쟀다. 예전 문서의 불일치는 [ERRATA.md](ERRATA.md)에 모았다.

결론부터 적는다. 지연이 없는 한 머신에서 경로 사이의 차이가 회차의 변동보다 분명히 큰 것은 세 가지다. 첫째, 바이트를 JSON(Base64)으로 보내면 크기가 커질수록 느려진다(1MB에서 `http-proto`의 약 1/4.5). 둘째, 100KB 이상의 큰 응답에서는 `http-bytes`가 다섯 번 모두 1위이고 `grpc-unary`가 같은 Protobuf 메시지를 HTTP/1.1로 보내는 `http-proto`보다 느리다(1MB에서 −45%). 셋째, 10KB에서는 `grpc-unary`가 다섯 번 모두 1위지만 차이는 `http-proto`보다 7.8%다. 나머지 조건(1KB, 50KB, 동시 사용자 축 전부, 복잡도 축의 Protobuf 두 경로)은 1위가 회차마다 바뀌어 "차이 없음"이다. 10KB의 gRPC 우위도 같은 조건을 다른 시간대에 잰 동시 사용자 축에서는 나오지 않았다. 예전 가이드의 "100KB 이하에서 gRPC가 30~67% 빠르다", "200명 이상이면 gRPC", "필드 150개 이상이면 HTTP"는 재현되지 않았다.

## 읽는 법

모든 수치는 `results/2026-11/`의 원자료에서 `scripts/remeasure/make-tables.sh`가 뽑은 `docs/remeasure/tables/*.md`에서 옮겼다. 표마다 회차별 값, 중앙값, 최소, 최대, p95, 서버가 잰 하위 호출 시간, 측정 조건(커밋, JDK, 회차별 부하, 컨테이너와 지연, 왕복 확인값)이 있다. 이 문서의 수치 옆에 어느 표인지 적었다. 처리량은 k6의 `http_reqs.rate`(req/s)이고, 적힌 값은 5회의 중앙값이다. 대괄호 안은 최소~최대다.

"1위"는 회차마다 처리량이 가장 높은 경로다. 이 문서는 회차 5번 모두 같은 경로가 1위면 그 경로가 앞선다고 적고, 회차마다 바뀌면 "차이 없음"으로 적는다. 1위가 5번 같아도 차이가 몇 % 안이면 그 폭을 함께 적는다. 경로 순서는 회차마다 돌렸으므로(회차 r에서 r−1만큼), 같은 경로가 늘 먼저 재진 것은 아니다.

경로 네 개가 하는 일은 `docs/remeasure/PATHS.md`에 있다. 짧게 다시 쓰면, `http-json`은 바이트를 Base64 문자열로 JSON에 담아 HTTP/1.1로 보내고, `http-bytes`는 원시 바이트를 HTTP/1.1로 보내고, `http-proto`는 Protobuf 메시지를 HTTP/1.1로 보내고, `grpc-unary`는 같은 Protobuf 메시지를 gRPC(HTTP/2)로 보낸다. apiServer는 네 경로 모두 응답을 쓸 수 있는 객체까지 푼 뒤 시간을 잰다. 복잡도 축에는 `http-bytes`가 없다(구조가 있는 데이터에는 해당하지 않는다). 스트림과 TLS는 이번 측정 밖이다.

## 측정 환경

k6와 두 서버(apiServer, dataServer)가 한 머신(24코어)에서 함께 돈다. 그래서 k6의 부하 생성도 서버와 같은 CPU를 쓰고, 두 서버 사이의 통신은 루프백(호스트 실행)이나 같은 머신의 Docker 브리지(지연 조건)를 지난다. 서버는 회차마다 새로 띄우고 워밍업을 다시 했다(호스트 실행 200회, 지연 조건 1,000회).

측정 동안 배경 부하가 있었다. 데스크톱 앱(WebKit, IDEA), k3s, 그리고 저녁에는 다른 프로젝트의 Gradle 작업이 같은 머신을 썼다. 스크립트는 회차마다 1분 부하 평균이 4.0 아래로 내려가기를 기다린 뒤 시작했고, 시작과 끝의 부하, 기다린 시간, CPU 상위 프로세스를 `conditions.json`에 남겼다. 표의 "회차별 1분 부하" 줄이 그 값이다. 측정이 시작되면 측정 자체가 부하를 올린다(호스트 크기 축의 회차 끝 10.4~13.3, 동시 사용자 축의 회차 끝 43.7~85.9).

이 기록에서 읽을 때 조심할 곳은 세 군데다.

- 복잡도 축의 회차 1과 3은 10분을 기다려도 부하가 내려가지 않아 기준을 넘은 채 시작했다(14.58, 11.09 — 표의 `startedAboveMax`, `tables/complexity-nodelay.md`). 복잡도 축의 범위가 넓은 것은 이 때문일 수 있다(예: simple의 `http-json` 3,102~4,208).
- 동시 사용자 축의 기본 설정은 회차 사이의 범위가 넓다. 동시 200에서 `grpc-unary` 6,052~9,979, `http-proto` 6,773~10,095다(`tables/concurrency-default-nodelay.md`).
- 같은 조건이라도 재는 시간대가 다르면 값이 다르다. 10KB·동시 10은 크기 축(04:36~06:02)에서 `grpc-unary` 4,862로 5/5 1위였지만, 동시 사용자 축의 동시 10(13:19~)에서는 `grpc-unary` 4,129, `http-proto` 4,398이고 1위가 회차마다 바뀌었다(`tables/size-nodelay.md`, `tables/concurrency-default-nodelay.md`). 서로 다른 축의 표끼리는 견주지 않는다.

호스트 실행의 회차에는 다른 컨테이너 수의 기록이 없다(기록 기능이 지연 조건 작업에서 더해졌다). 측정 log에는 복잡도 축이 끝난 17:04에 다른 프로젝트의 java 프로세스와 컨테이너가 돌고 있었다고 적혀 있다. 지연 조건 15번의 실행은 모두 다른 컨테이너 0개에서 시작했다.

## 크기 축 — 호스트 직접 실행, 동시 10

표: `tables/size-nodelay.md`. 커밋 `8ae5c8f`, JDK 21.0.5, 기본 설정, 5회 × 30초.

| 크기 | `http-json` | `http-bytes` | `http-proto` | `grpc-unary` | 1위 |
|---|---|---|---|---|---|
| 1kb | 3,958 [3,763~4,271] | 4,460 [4,117~4,503] | 4,600 [4,177~4,615] | 4,584 [4,494~4,657] | 차이 없음 (gRPC 3, proto 2) |
| 10kb | 3,758 [3,738~3,792] | 4,365 [4,326~4,389] | 4,508 [4,495~4,557] | 4,862 [4,846~4,908] | `grpc-unary` 5/5 |
| 50kb | 2,439 [2,426~2,464] | 4,078 [4,065~4,101] | 4,082 [4,048~4,111] | 4,088 [4,081~4,098] | 차이 없음 (세 경로가 0.3% 안) |
| 100kb | 1,713 [1,707~1,733] | 3,794 [3,786~3,796] | 3,679 [3,610~3,706] | 3,413 [3,158~3,431] | `http-bytes` 5/5 |
| 200kb | 1,080 [1,075~1,090] | 3,229 [3,222~3,251] | 3,054 [3,035~3,066] | 2,417 [2,400~2,456] | `http-bytes` 5/5 |
| 500kb | 524 [519~528] | 2,224 [2,213~2,233] | 2,018 [2,009~2,020] | 1,256 [1,230~1,293] | `http-bytes` 5/5 |
| 1mb | 277 [273~280] | 1,429 [1,424~1,434] | 1,251 [1,251~1,255] | 689 [671~713] | `http-bytes` 5/5 |

10KB에서 `grpc-unary`는 `http-proto`보다 7.8%, `http-bytes`보다 11.4% 높다(직접 계산: 4,862 ÷ 4,365 − 1). 1KB와 50KB에서는 `http-proto`, `http-bytes`, `grpc-unary`가 3.1% 안에 있고 1위가 바뀐다. 100KB부터는 순위가 다섯 번 모두 `http-bytes` > `http-proto` > `grpc-unary` > `http-json`이다. p95도 같은 쪽을 가리킨다. 10KB에서는 `grpc-unary`가 5/5로 가장 낮고(2.46ms 대 `http-proto` 2.54ms), 50KB 이상에서는 `http-bytes`가 5/5로 가장 낮다.

서버가 잰 하위 호출 시간(apiServer가 dataServer를 불러 객체를 받기까지, 회차 평균의 중앙값)은 처리량과 같은 순서다. 1MB에서 `http-bytes` 5.69ms, `http-proto` 6.61ms, `grpc-unary` 13.07ms, `http-json` 34.55ms다.

## 전송 방식과 직렬화를 나눠 본 결과

`http-json`과 `http-proto`는 둘 다 HTTP/1.1이고 형식만 다르므로 그 차이가 직렬화의 효과다. `http-proto`와 `grpc-unary`는 같은 Protobuf 메시지이고 전송만 다르므로(HTTP/1.1 대 HTTP/2 + gRPC) 그 차이가 전송 방식의 효과다. 아래 표는 그 두 비율과, 메시지로 감싸는 비용(`http-proto` ÷ `http-bytes`)을 보여 준다. 괄호는 앞의 경로가 다섯 회차 중 몇 번 앞섰는지다(`tables/size-nodelay.md`의 "경로끼리 견줌").

| 크기 | 직렬화: proto ÷ json − 1 | 전송 방식: gRPC ÷ proto − 1 | 메시지로 감쌈: proto ÷ bytes − 1 |
|---|---|---|---|
| 1kb | +16.2% (4/5) | −0.3% (3/5) | +3.1% (4/5) |
| 10kb | +20.0% (5/5) | +7.8% (5/5) | +3.3% (5/5) |
| 50kb | +67.4% (5/5) | +0.2% (3/5) | +0.1% (3/5) |
| 100kb | +114.8% (5/5) | −7.2% (0/5) | −3.0% (0/5) |
| 200kb | +182.7% (5/5) | −20.8% (0/5) | −5.4% (0/5) |
| 500kb | +285.1% (5/5) | −37.8% (0/5) | −9.3% (0/5) |
| 1mb | +351.0% (5/5) | −45.0% (0/5) | −12.5% (0/5) |

큰 응답에서 `grpc-unary`가 느린 것은 Protobuf 직렬화 때문이 아니다. 같은 메시지를 HTTP/1.1로 보내는 `http-proto`는 `http-bytes`보다 3~12.5% 느릴 뿐이고, `grpc-unary`는 `http-proto`보다 7~45% 느리다. 느려지는 곳은 gRPC 전송 쪽이다. 그 안의 무엇 때문인지는 확인하지 못했다(아래 "확인하지 못한 것").

두 서버 사이의 본문 크기는 `results/2026-11/sizes.json`에 조건과 경로마다 한 번 잰 값이다(표의 "두 서버 사이의 본문 크기" 열). `http-bytes`는 원래 크기 그대로(1MB = 1,048,576바이트), `http-proto`는 그보다 51~53바이트 크고(1,048,629), `http-json`은 Base64 때문에 약 1.33배다(1,398,217). `grpc-unary`는 재지 않고 `http-proto`의 메시지에 gRPC 메시지 머리 5바이트를 더한 값으로 적었다(1,048,634, HTTP/2 프레임 제외). 따라서 `http-proto`와 `grpc-unary`의 차이는 본문 크기의 차이가 아니다.

복잡도 축에서도 직렬화의 효과는 분명하고, 전송 방식의 효과는 보이지 않는다(`tables/complexity-nodelay.md`). `http-proto` ÷ `http-json`은 +10.0%(complex)~+141.3%(extreme)이고 simple을 빼면 5/5다. `grpc-unary` ÷ `http-proto`는 −8.5%~+5.4%이고 어느 단계에서도 5/5가 아니다. JSON 본문은 Protobuf보다 1.63~2.54배 크다(직접 계산: medium 728 ÷ 446 ≈ 1.63, simple 226 ÷ 89 ≈ 2.54, extreme 421,477 ÷ 219,522 ≈ 1.92).

## 동시 사용자 축 — 10KB, 호스트 직접 실행, 두 설정

동시 사용자 축은 두 설정으로 쟀다. `default`는 손대지 않은 기본값이고, `matched`는 연결과 스레드의 상한을 동시 사용자 수 이상(512)으로 맞춘 설정이다. `default`에서는 apiServer와 dataServer의 Tomcat이 최대 200스레드이고 dataServer의 gRPC 서비스 코드는 `Dispatchers.Default`(코어 수 24)에서 돈다. 그래서 `default`의 동시 500은 경로의 차이가 아니라 스레드 상한을 함께 잰다. `matched`는 두 Tomcat 최대 스레드, apiServer HTTP 연결 풀, dataServer gRPC 서비스 코루틴의 스레드를 모두 512로 두고, dataServer가 HTTP 연결을 끊지 않게 한다. 값은 기동 로그의 `BENCH_CONFIG` 줄로 확인했다(각 표의 측정 조건, `PATHS.md` "연결과 스레드 설정").

결과는 두 설정 모두 처리량의 1위가 모든 동시 사용자 수에서 회차마다 바뀌었다. 예전 결론 "동시 200명 이상이면 gRPC가 9~12% 빠르다"는 처리량에서 재현되지 않았다.

| 동시 사용자 | `default`: proto / gRPC | 1위 | `matched`: proto / gRPC | 1위 |
|---|---|---|---|---|
| 10 | 4,398 / 4,129 | 차이 없음 (proto 3, bytes 2) | 4,488 / 3,609 | 차이 없음 (proto 4, bytes 1) |
| 50 | 8,358 / 8,516 | 차이 없음 (gRPC 4, proto 1) | 7,785 / 7,396 | 차이 없음 (bytes 3, proto 2) |
| 100 | 9,070 / 8,862 | 차이 없음 (bytes 2, proto 2, gRPC 1) | 8,463 / 8,601 | 차이 없음 (gRPC 3, proto 2) |
| 200 | 9,500 / 9,552 | 차이 없음 (gRPC 2, bytes 2, proto 1) | 9,086 / 8,956 | 차이 없음 (proto 4, gRPC 1) |
| 500 | 9,955 / 9,509 | 차이 없음 (proto 3, bytes 2) | 9,727 / 8,651 | 차이 없음 (proto 3, bytes 2) |

표: `tables/concurrency-default-nodelay.md`, `tables/concurrency-matched-nodelay.md`. `http-bytes`는 두 경로와 비슷하고, `http-json`만 모든 조건에서 `http-proto`보다 18~32% 낮다(직접 계산: 1 − 1 ÷ (1 + 표의 직렬화 비율 +22.1%~+46.9%)).

1위 경로는 바뀌었지만 `grpc-unary`가 1위인 회차가 한 번도 없는 조건이 다섯 군데 있다. `default`의 동시 10과 500, `matched`의 동시 10, 50, 500이다. 이 다섯 곳에서 `grpc-unary`는 `http-proto`보다 4.5~19.6% 낮고(`default` 10 −6.1%, 500 −4.5%, `matched` 10 −19.6%, 50 −5.0%, 500 −11.1%), `http-proto`를 앞선 회차는 0~1번이다(각 표의 "경로끼리 견줌"). 다만 다섯 곳 모두 두 경로의 범위가 겹친다(예: `matched` 500의 `grpc-unary` 8,283~9,734, `http-proto` 9,131~9,880). 반대로 `grpc-unary`가 `http-proto`를 5/5로 앞선 조건은 이 축에 없다.

p95 지연은 처리량과 다른 모습을 보인다. 동시 100과 200에서 `grpc-unary`의 p95가 가장 낮은 회차가 `matched`에서 5/5, `default`에서 4/5다(동시 200 `matched` 31.1ms 대 `http-proto` 36.3ms). 반대로 서버가 잰 하위 호출 시간의 평균은 `grpc-unary`가 더 길다(동시 200 `matched` 19.1ms 대 11.4ms, 동시 500 54.1ms 대 22.1ms). k6가 보는 p95는 낮은데 apiServer 안에서 잰 평균은 긴 이유는 확인하지 못했다.

### 잘못된 구성(v1)으로 gRPC만 절반이 됐던 일

`matched`의 처음 구성(커밋 `ee7d3f6`에서 넣었고, 측정은 서버 코드가 같은 `f4aa61f`에서 했다)은 dataServer의 gRPC 서버 실행기와 서비스 코루틴을 같은 512 고정 스레드 풀(`ThreadPoolExecutor(512, 512, LinkedBlockingQueue)`)로 두었다. 이 구성에서는 동시 50 이상에서 `grpc-unary`만 3,171~5,706으로 `http-proto`(8,611~10,398)의 37~55%였다(직접 계산. 동시 50: `grpc-unary` 3,171, `http-proto` 8,611, 서버 하위 호출 13.9ms 대 3.1ms — `tables/record-concurrency-matched-v1-fixed-executor.md`). 같은 조건의 `default` gRPC는 8,516이었으므로, 이것은 방식의 성질이 아니라 구성이 만든 값이다. 이 폴더의 `grpc-unary` 값은 결과로 쓰지 않고 기록으로만 남긴다(`results/2026-11/nodelay/concurrency-matched-v1-fixed-executor/NOTE.md`).

고친 구성(커밋 `f416adc`, 본 측정은 서버 코드가 같은 `30e70f7`에서 했다)은 gRPC 서버 실행기를 grpc-java 기본값으로 되돌리고, 요청을 처리하는 서비스 코루틴만 512 고정 풀에서 돌린다. 고친 뒤 바로 짧게 견준 확인 실행(동시 50·200, 3회 × 15초)에서 `matched`의 `grpc-unary`는 그 뒤에 이어서 잰 `default`보다 동시 50에서 10.9%, 동시 200에서 12.2% 낮았다. 같은 비교에서 `http-proto`도 14.2%, 11.6% 낮았으므로, 이 차이는 gRPC만의 것이 아니고 시간대의 차이와 구분할 수 없다(`tables/check-concurrency-matched-v2.md`). 그 뒤의 본 측정(위 표)에서는 v1의 저하가 사라졌다.

## 복잡도 축 — 호스트 직접 실행, 동시 10

표: `tables/complexity-nodelay.md`. 경로는 셋(`http-json`, `http-proto`, `grpc-unary`)이다. 데이터는 서버가 뜰 때 한 번 만들고 세 경로가 같은 객체에서 응답을 만든다(난수 시드 고정).

| 단계 | `http-json` | `http-proto` | `grpc-unary` | 1위 |
|---|---|---|---|---|
| simple | 3,489 [3,102~4,208] | 4,197 [3,443~4,352] | 3,840 [3,537~4,047] | 차이 없음 (proto 3, json 2) |
| medium | 3,866 [3,735~4,121] | 4,408 [4,277~4,521] | 4,536 [3,972~4,829] | 차이 없음 (gRPC 4, proto 1) |
| complex | 3,711 [3,709~3,809] | 4,083 [3,949~4,201] | 4,303 [3,888~4,363] | 차이 없음 (gRPC 3, proto 2) |
| ultra | 2,093 [1,922~2,155] | 3,061 [2,776~3,124] | 3,149 [2,369~3,218] | 차이 없음 (gRPC 4, proto 1) |
| extreme | 343 [299~345] | 828 [756~845] | 806 [762~889] | 차이 없음 (gRPC 3, proto 2) |

Protobuf 두 경로는 모든 단계에서 1위가 바뀌어 차이가 없다. `http-json`은 medium부터 `http-proto`보다 5/5로 낮다. simple에서는 `http-json`이 두 회차에서 1위였으므로 세 경로 모두 차이 없음이다. 예전 결론 "필드 150개 이상(ultra)이면 HTTP가 12~18% 빠르다"는 재현되지 않았다. 예전 측정의 복잡한 데이터 HTTP 바이트 경로는 응답을 객체로 풀지 않았고(ERRATA 15번), 이번에는 세 경로 모두 객체로 푼다. 이 축의 회차 1·3은 부하 기준을 넘은 채 시작했다(위 "측정 환경").

## 지연 조건 — 컨테이너, 크기 축, 동시 10

### 구성과 지연의 뜻

두 서버 사이에 지연을 걸려고 두 서버를 컨테이너로 띄웠다(`docker-compose.remeasure.yml`). 호스트에서 `tc`를 쓸 권한이 없어서 지연은 dataServer 컨테이너 안에서 건다. 지연은 dataServer 컨테이너의 `eth0`에서 나가는 쪽에만 `tc netem delay Xms`로 건다. 그래서 dataServer → apiServer 방향의 패킷만 X ms 늦고, 왕복 하나에 X ms가 더해진다(왕복 2X가 아니다). k6 → apiServer 구간에는 지연이 없다. 지연 0도 같은 자리에 `netem delay 0ms`를 걸어 큐 구성을 같게 했다.

회차마다 측정 전에 qdisc와 왕복 시간을 확인했고 15번 모두 통과했다. ping 중앙값은 지연 0에서 0.13ms, 1에서 1.17~1.23ms, 10에서 10.2~10.3ms다(각 표의 "왕복 확인"). 세 지연은 회차마다 번갈아 쟀다(회차마다 순서를 돌림, `results/2026-11/delay-run-order-size-default.json`). 몇 시간에 걸친 머신 부하의 변화가 지연의 효과에 섞이지 않게 하려는 것이다. 이미지 id는 15번 모두 같고, 다른 컨테이너는 0개였다.

### 지연의 효과는 `delay0ms`와만 견준다

컨테이너 실행은 호스트 실행과 지연 말고도 다르다. 워밍업이 1,000회(호스트 200회)이고, JDK가 21.0.13(호스트 21.0.5)이고, 두 서버 사이가 루프백이 아니라 Docker 브리지다. 그래서 지연의 효과는 같은 구성의 지연 0(`delay0ms`)과만 견준다. 참고로 `delay0ms`의 처리량은 호스트 실행보다 −12.6%~+0.4%이고, 5/5로 갈린 조건(10KB와 100KB 이상)의 1위는 같다(`tables/size-delay0ms-vs-nodelay.md` — 지연의 효과로 읽지 않는다).

### 결과

| 크기 | 지연 0ms (`tables/size-delay0ms.md`) | 지연 1ms (`tables/size-delay1ms.md`) | 지연 10ms (`tables/size-delay10ms.md`) |
|---|---|---|---|
| 1kb | 차이 없음 | 차이 없음 | `http-proto` 5/5, 네 경로가 2.3% 안(754 대 737~749) |
| 10kb | `grpc-unary` 5/5 (4,323 대 proto 4,126) | `grpc-unary` 5/5 (3,117 대 2,945) | 차이 없음 |
| 50kb | 차이 없음 | `grpc-unary` 5/5 (2,956 대 2,731) | 차이 없음 |
| 100kb | `http-bytes` 5/5 | `grpc-unary` 5/5 (2,671 대 bytes 2,519) | 차이 없음 |
| 200kb | `http-bytes` 5/5 | 차이 없음 (gRPC 3, bytes 2) | `grpc-unary` 5/5 (689 대 bytes 668) |
| 500kb | `http-bytes` 5/5 | `http-bytes` 5/5 | 차이 없음 (gRPC 4, bytes 1, 592 대 591) |
| 1mb | `http-bytes` 5/5 (1,259 / 1,126 / gRPC 661 / json 279) | `http-bytes` 5/5 | `http-bytes` 5/5 (466 / 445 / gRPC 303 / json 220) |

지연 1ms에서는 10KB~100KB에서 `grpc-unary`가 5/5로 앞서고 폭은 `http-proto`보다 5.8~8.2%다. 지연 0에서 1ms로 갈 때 50KB~500KB에서 `http-bytes`와 `http-proto`의 처리량은 21~27% 줄었는데 `grpc-unary`는 0.7~20% 줄었다. 서버 하위 호출 시간도 두 HTTP 경로는 0.97~1.54ms 늘었는데 `grpc-unary`는 0.07~0.70ms 늘었다(`tables/size-delay1ms.md`의 견준 폴더 대비 표). 더해진 왕복(1ms)보다 적게 늘어난 이유는 확인하지 못했다.

지연 10ms에서는 1KB~100KB에서 Protobuf, 바이트, gRPC 세 경로가 2.3% 안에 모이고, 처리량이 지연 0보다 77~83% 낮다. 이것은 측정이 동시 10의 닫힌 부하(가상 사용자 10명이 응답을 받아야 다음 요청을 보낸다)이기 때문이다. 처리량의 상한은 10 ÷ 요청 하나의 시간이다. 1KB `http-proto`의 k6 지연 중앙값 12.82ms로 계산하면 상한은 약 780 req/s이고(직접 계산: 10 ÷ 0.01282), 잰 값은 754다. 이 구간의 처리량은 경로가 아니라 지연에 따라 정해진다. 1KB의 서버 하위 호출 시간은 네 경로 모두 지연 0보다 10.45~10.57ms 늘어 더한 지연과 맞는다.

지연 10ms의 1MB에서는 순위가 지연 0과 같다. 서버 하위 호출 시간은 `http-bytes` +13.25ms, `http-proto` +13.39ms, `grpc-unary` +17.78ms로 gRPC가 더 많이 늘었다. 다만 처리량의 감소율은 `grpc-unary`가 −54.2%로 `http-bytes`(−63.0%)보다 작다. 지연 0의 값이 이미 낮았기 때문이다.

## 예전 결론 가운데 재현되지 않은 것

아래 문장은 README의 2026-01 가이드(지금은 README의 "예전 가이드" 절)에 있던 것이다. 각 문장 뒤에 이번 측정에서 나온 것을 적는다.

- "소용량 API (≤100KB) — gRPC/Unary — 30~67% 더 빠름": 호스트 실행에서 `grpc-unary`가 5/5로 앞선 것은 10KB 하나이고 폭은 `http-proto`보다 7.8%, `http-bytes`보다 11.4%다. 1KB와 50KB는 차이 없음, 100KB는 `grpc-unary`가 `http-bytes`보다 낮다.
- "고동시성 (200+ VU) — gRPC/Unary — 9~12% 더 빠름": 두 설정 모두 처리량의 1위가 회차마다 바뀌었다. `matched`의 동시 500은 오히려 HTTP 쪽이 5/5다. p95는 동시 100~200에서 `grpc-unary`가 낮은 회차가 많다.
- "저동시성 (< 100 VU) — HTTP/Binary — 3~13% 더 빠름": 동시 10·50 모두 차이 없음이다.
- "복잡한 구조 (≤50 필드) — gRPC/Unary — 40~69% 더 빠름": `grpc-unary`와 `http-proto`는 차이 없음이고, `http-proto`는 `http-json`보다 medium에서 14.0%, complex에서 10.0% 높고(5/5), simple에서는 차이 없음이다.
- "극한 복잡도 (≥150 필드) — HTTP/Binary — 12~18% 더 빠름": ultra와 extreme에서도 Protobuf 두 경로는 차이 없음이고 `http-json`이 가장 낮다.
- "대용량 전송 (≥200KB) — HTTP/Binary — 14~60% 더 빠름": `http-bytes`가 5/5 1위인 것은 재현됐다. 다만 범위는 100KB부터이고, 200KB에서 `grpc-unary`보다 34%(직접 계산: 3,229 ÷ 2,417 − 1), 1MB에서 2.07배다. `http-proto`보다는 200KB에서 5.7%, 1MB에서 14.2% 높다(직접 계산: 3,229 ÷ 3,054 − 1, 1,429 ÷ 1,251 − 1).

## 확인하지 못한 것

아래는 관측은 했지만 원인을 확인하지 않은 것이다. 추측을 원인으로 적지 않는다.

1. `matched`의 높은 동시 사용자에서 `grpc-unary`의 서버 하위 호출 시간 평균이 HTTP 경로보다 긴 이유(동시 500에서 54.1ms 대 22.1ms). gRPC는 채널 하나(HTTP/2 연결 하나)에 요청을 겹쳐 보내므로 그 구간에서 기다린다는 설명이 가능하지만 재지 않았다. 같은 조건에서 k6의 p95는 gRPC가 낮거나 비슷하다.
2. 큰 응답(100KB 이상)에서 `grpc-unary`가 느린 이유. 직렬화가 아니라는 것만 알 수 있다. 같은 메시지를 HTTP/1.1로 보내는 `http-proto`는 `http-bytes`와 3~12.5% 차이다. gRPC 전송 구간(HTTP/2 프레이밍, 흐름 제어 창, Netty 버퍼, 마샬러) 가운데 어디인지는 프로파일링하지 않았다.
3. 지연 10ms의 1MB에서 `grpc-unary`의 서버 하위 호출 시간이 HTTP보다 더 많이 늘어난 이유(+17.8ms 대 +13.3ms). HTTP/2 흐름 제어 창 때문인지 재지 않았다.
4. v1 구성의 원인. 원인 조사에서 jstack으로 512개 스레드 중 480~511개가 큐에서 기다리고 19~32개가 큐의 락을 기다리는 것을 보았다. 락 경합이 원인이라는 것은 이 jstack에 근거한 추정이다. 조사 때의 변형별 원자료는 지워져 이 저장소에 없고, 동시 500은 그 조사에서 재지 않았다.
5. 지연 1ms의 중간 크기(10KB~100KB)에서 `grpc-unary`가 앞서는 이유. 지연 0에서 1ms로 갈 때 `grpc-unary`의 서버 하위 호출 시간이 더한 왕복보다 적게 늘었다(위 "지연 조건"). 원인은 확인하지 않았다.

## 이번 측정 밖

스트림(`grpc-stream`)과 TLS는 이번 측정 밖이다. 동시 사용자 축과 복잡도 축은 지연 조건에서 재지 않았다. 측정은 한 머신 안이고 서버마다 인스턴스 하나다.

## 원자료와 다시 뽑는 법

```bash
scripts/remeasure/make-tables.sh          # docs/remeasure/tables/*.md를 다시 만든다(측정은 하지 않는다)
```

| 표 | 원자료 | 무엇 |
|---|---|---|
| `tables/size-nodelay.md` | `results/2026-11/nodelay/size-default` | 크기 축, 호스트 |
| `tables/concurrency-default-nodelay.md` | `results/2026-11/nodelay/concurrency-default` | 동시 사용자 축, `default` |
| `tables/concurrency-matched-nodelay.md` | `results/2026-11/nodelay/concurrency-matched` | 동시 사용자 축, `matched`(고친 구성) |
| `tables/complexity-nodelay.md` | `results/2026-11/nodelay/complexity-default` | 복잡도 축 |
| `tables/size-delay0ms.md`, `size-delay1ms.md`, `size-delay10ms.md` | `results/2026-11/delay{0,1,10}ms/size-default` | 지연 조건(1·10ms는 `delay0ms`와 견줌) |
| `tables/size-delay0ms-vs-nodelay.md` | 위 둘 | 참고: 컨테이너와 호스트의 차이 |
| `tables/record-concurrency-matched-v1-fixed-executor.md` | `results/2026-11/nodelay/concurrency-matched-v1-fixed-executor` | 기록: 잘못된 구성 |
| `tables/check-concurrency-matched-v2.md`, `check-concurrency-default.md` | `results/2026-11/check-*` | 고친 뒤의 확인 실행 |

측정 방법은 `scripts/remeasure/README.md`, 경로가 하는 일은 `docs/remeasure/PATHS.md`에 있다.
