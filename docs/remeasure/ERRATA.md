# 예전 결과 문서의 불일치 (2026-01 Phase 1~7)

이 문서는 README와 `docs/phase/PHASE*_RESULT.md`(2026-01 측정)에서 수치, 순위, 정의가 서로 맞지 않거나 계산과 맞지 않는 곳을 모은 것이다. 예전 문서는 고치지 않고 그대로 두었다(머리말만 붙였다). 새 결과는 [RESULT.md](RESULT.md)에 있다.

## 이 목록의 출처

다시 재기 전의 조사(하네스 저장소 `docs/plans/2026-11-20/decisions-service-communication/`)는 "README와 결과 문서가 어긋나는 곳 15건(틀린 퍼센트, 같은 조건이 아닌 비교, 문서마다 다른 필드 수 등)"이라고 개수와 종류만 남겼고, 15건의 목록은 그 폴더에 없다. 그래서 이 문서의 항목은 2026-11-22에 이 저장소의 파일을 다시 읽어 찾은 것이다. 원래의 15건과 하나씩 맞춰 보지는 못했다. 세 종류(틀린 퍼센트, 같은 조건이 아닌 비교, 다른 필드 수)는 모두 아래에 있다.

항목마다 파일과 줄 번호는 커밋 `1996b69`의 것이다(이 문서와 함께 붙인 머리말 때문에 `docs/phase/PHASE*_RESULT.md`의 줄 번호는 지금 2줄씩 밀려 있다). 퍼센트는 모두 a ÷ b − 1로 직접 계산했다.

## 수치와 퍼센트

1. **p95 "36% 더 낮음"의 계산.** `README.md:165`와 `docs/phase/PHASE3_RESULT.md:166`은 "500 VU에서 gRPC Latency p95가 HTTP보다 36% 더 낮음"이라고 쓴다. 원값은 `PHASE3_RESULT.md:80-81`의 gRPC/Unary 15.75ms, HTTP/Binary 21.40ms다. 21.40 ÷ 15.75 − 1 = +35.9%이므로 "HTTP가 36% 더 높다"가 맞고, gRPC가 낮은 폭은 1 − 15.75 ÷ 21.40 = 26.4%다.
2. **"극한 복잡도 HTTP/Binary 12~18%"의 기준이 섞임.** `README.md:41`은 HTTP/Binary가 12~18% 빠르다고 쓴다. `PHASE6_RESULT.md:234-235`와 `:243`은 Binary 대 Unary로 +17%, +18%("17~18%")다(2,154 ÷ 1,847 − 1 = +16.6%, 482 ÷ 407 − 1 = +18.4%). 12%는 JSON 대 Unary(2,074 ÷ 1,847 − 1 = +12.3%)에서 온 값이다.
3. **가이드 표 안에서 비교 기준이 줄마다 다름.** `README.md:40`의 "40~69%"는 gRPC/Unary 대 HTTP/JSON이다(`PHASE5_RESULT.md`의 표, `README.md:236-240`: 6,007 ÷ 3,602 − 1 = +66.8%, 5,527 ÷ 3,273 − 1 = +68.9%, 4,415 ÷ 3,154 − 1 = +40.0%). 바로 위 `README.md:36-37`의 "30~67%", "14~60%"와 아래 `:41`은 HTTP/Binary 기준이다. 같은 표에서 기준이 JSON과 Binary를 오간다.
4. **JSON 대 Binary 비교의 부호가 섞임.** `PHASE5_RESULT.md:148-150`은 "Simple +1%, Medium +4%, Complex −6%"로 쓴다. 앞의 둘은 Binary가 JSON보다 높은 폭(3,627 ÷ 3,602 − 1 = +0.7%, 3,393 ÷ 3,273 − 1 = +3.7%)이고, Complex의 −6%는 같은 식의 음수(2,955 ÷ 3,154 − 1 = −6.3%)다. 그런데 표의 문구는 Complex만 "JSON > Binary"로 방향을 바꿔 적었다.

## 순위와 승자 표시

5. **Phase 7 Extreme의 순위.** `README.md:349-350`은 HTTP/JSON 394를 2위, gRPC/Stream 402를 3위로 적었다. 402가 더 크다.
6. **Phase 6의 승자 칸.** `README.md:268-271`은 승자를 "HTTP/JSON (+12%)", "HTTP/JSON (+3%)"로 적고 JSON을 굵게 했다. 같은 줄의 HTTP/Binary가 더 높다(Ultra 2,154 > 2,074, Extreme 482 > 419). `PHASE6_RESULT.md:107-113`은 "최고 성능 HTTP/Binary"와 "JSON vs gRPC" 열을 따로 두었는데, README가 두 열을 한 칸에 합쳤다. `README.md:298-299`의 그림은 "HTTP +12%"라고만 써 JSON인지 Binary인지 밝히지 않는다.

## 정의와 문서 사이의 불일치

7. **복잡도 단계의 필드 수.** Complex는 `README.md:230`에서 50개, `PHASE5_RESULT.md:35`와 `:82`에서 20개, `PHASE5_DESIGN.md:68`에서 "50+개"다. Medium은 `README.md:229`와 `PHASE5_RESULT.md:34`에서 13개, `PHASE5_DESIGN.md:67`에서 15개다. README의 "≤50 필드"(`:40`), "~150 필드"(`:50`) 경계가 어느 정의를 따르는지 정해지지 않는다.
8. **Phase 번호와 주제.** `PHASE1_RESULT.md:244`와 `PHASE2_RESULT.md:143`은 "Phase 4: 복잡한 데이터 구조"라고 쓰지만, 실제 Phase 4는 페이로드 크기 역전 탐색이고 복잡도는 Phase 5다. `PHASE7_RESULT.md:529-532`의 시리즈 요약은 Phase 1을 "gRPC가 단순 데이터에서 우위"(실제 결론은 HTTP/Binary가 약 2배, `README.md:81`), Phase 2를 "대용량에서 HTTP/Binary 우위"(실제는 1KB·10KB에서 gRPC 우위), Phase 4를 "스트리밍 비교"로 적었다.
9. **서버 프레임워크.** `PHASE4_RESULT.md:288`은 서버를 "Ktor (Kotlin)"라고 쓰고, `README.md:23`은 "Spring Boot 3.x"다.
10. **문서 위치.** `README.md:495-496`은 `PHASE*_DESIGN.md`, `PHASE*_RESULT.md`가 프로젝트 루트에 있다고 쓰지만 실제로는 `docs/phase/`에 있다.

## 같은 조건의 값과 반복 횟수

11. **같은 조건(10KB, 동시 10, 30초)의 값이 문서마다 다름.** gRPC/Unary는 `PHASE2_RESULT.md:41`에서 5,748, `PHASE4_RESULT.md:56`(2차)에서 6,268, `PHASE4_RESULT.md:36`(1차)에서 4,165다. HTTP/Binary는 4,027, 3,745, 4,731이다. Unary 대 Binary는 +43%, +67%, −12%로 승자까지 바뀐다. README의 "30~67%"(`README.md:36`)는 이 가운데 Phase 4 2차의 +67%를 쓰고, `README.md:125-126`과 `:197`은 43%와 67%를 같은 조건의 차이라는 말 없이 나란히 둔다.
12. **README의 "저동시성(<100 VU) HTTP/Binary 3~13%"가 동시 10의 결과와 반대.** `README.md:39`의 근거는 Phase 3의 동시 50(+13%)과 100(+3%)이다(`README.md:157-158`). 같은 10KB의 동시 10에서는 Phase 2·4가 gRPC/Unary +43~67%다(`README.md:125-126`, `:197`). 동시 100은 "< 100"에 들지 않는다.
13. **Phase 4의 1차·3차와 Phase 6의 1차가 README에 없음.** `PHASE4_RESULT.md:10`은 3회(1차, 2차, 3차)라고 쓴다. 1차(`PHASE4_RESULT.md:34-40`)는 10KB~500KB 모든 크기에서 HTTP/Binary가 1위였고, README의 Phase 4 표(`README.md:195-201`)와 가이드는 2차 값만 쓴다. 3차는 `PHASE4_RESULT.md:101`의 "2차와 거의 동일" 한 문장뿐이고 값이 없다. Phase 6도 `PHASE6_RESULT.md:11`이 2차 결과이고, 1차에서는 Extreme의 승자가 반대였다(`PHASE6_RESULT.md:151-154`, "gRPC +2%" 대 "JSON +3%"). `PHASE7_RESULT.md:250`의 "3회 테스트 모두에서 일관되게 재현"은 Phase 6의 2차 한 번과 Phase 7의 2회를 센 것이다.

## 결론 문장

14. **기각된 원인을 원인으로 적은 문장.** `README.md:404`(와 `PHASE7_RESULT.md`의 결론)는 "Protobuf 빌더가 CPU 집약적"이라는 가설을 기각했다. 그런데 `README.md:41`의 근거 칸("Protobuf 빌더 오버헤드"), `:289`("원인: Protobuf Builder 객체 생성/해제 비용 누적"), `:578`은 같은 가설을 원인으로 남겨 두었다. `README.md:247`의 "Protobuf over HTTP 빌딩 오버헤드 > JSON 직렬화"도, Ultra·Extreme에서 가장 빠른 쪽이 Protobuf over HTTP(HTTP/Binary)라는 같은 문서의 값(`README.md:270-271`)과 맞지 않는다. 같은 맥락에서 `PHASE7_RESULT.md:196`(`README.md:398`)의 "GC Count와 성능은 반비례"는 자기 표(`PHASE7_RESULT.md:188-193`)에서 JSON이 gRPC/Unary보다 GC가 많은데 처리량도 높다는 값과 맞지 않는다.
15. **같은 일을 하지 않는 경로를 비교한 결론.** 복잡한 데이터의 HTTP/Binary 경로는 응답을 `ByteArray`로만 받고 객체로 풀지 않았다(커밋 `1099712`의 `apiServer/src/main/kotlin/com/protobench/api/client/HttpDataClient.kt:99-104`). gRPC/Unary와 HTTP/JSON은 객체로 풀었다. 그래서 `README.md:236-247`, `:268-271`의 Binary 값과 "Complex에서 HTTP/Binary < HTTP/JSON", "극한 복잡도에서 HTTP/Binary 우위"는 같은 일을 하는 경로끼리의 비교가 아니다. 또 복잡한 데이터의 gRPC/Stream은 단건과 같은 코드였다(같은 커밋의 `apiServer/src/main/kotlin/com/protobench/api/service/ApiDataService.kt:257`의 주석 "Unary와 동일하게 처리한다", `:267`의 `grpcClient.getComplexData`). 그런데 `README.md:239`에서 Medium의 Stream은 3,057, Unary는 5,527이다. 같은 코드의 차이이므로 측정의 변동으로 읽어야 하고, 방식의 차이로 읽으면 안 된다.

## 확인하지 못한 것

- 원래 조사의 15건 목록(위 "이 목록의 출처").
- `PHASE7_RESULT.md`에서 README의 Phase 7 2차 CPU 값(`README.md:337-340`, `:357-360`)이 나온 표. 2차 처리량과 효율에서 거꾸로 계산하면 맞지만(예: 2,248 ÷ 188.4 = 11.9%), 원 표는 찾지 못했다.
- `PHASE2_RESULT.md`, `PHASE3_RESULT.md`, `PHASE4_RESULT.md`의 뒷부분(k6 로그 원문)과 요약 표의 대조. 서술부만 대조했다.
