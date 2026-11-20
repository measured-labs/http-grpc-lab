# 측정 경로 — 무엇을 하는가

> 2026-11-21 다시 재기(task 01)에서 정리했다. 기준 커밋 `1099712`.
> 구조: k6 → apiServer(8080) → dataServer(HTTP 8081 / gRPC 9091). apiServer가 dataServer 호출 구간을 `System.nanoTime()`으로 재서 응답의 `latencyMs`에 담는다.
> 스트림 경로(`/api/data/grpc/stream`, `/api/complex/grpc/stream`)는 이번 측정에서 뺀다. 코드는 남아 있다.

## 경로 이름과 엔드포인트

| 이름 | 크기 축·동시 사용자 축 (apiServer → dataServer) | 복잡도 축 (apiServer → dataServer) |
|------|------|------|
| `http-json` | `/api/data/http/json` → `GET /data/json` | `/api/complex/http/json` → `GET /data/complex/json` |
| `http-bytes` | `/api/data/http/binary` → `GET /data/binary` | 없음 (구조가 있는 데이터에는 해당하지 않는다) |
| `http-proto` | `/api/data/http/proto` → `GET /data/proto` (**새로 더함**) | `/api/complex/http/binary` → `GET /data/complex/binary` |
| `grpc-unary` | `/api/data/grpc` → `DataService/GetData` | `/api/complex/grpc` → `DataService/GetComplexData` |

- 크기 축과 동시 사용자 축은 같은 엔드포인트를 쓴다(크기만 고정해 동시 사용자 수를 바꾼다).
- 비교의 뜻: `http-json` 대 `http-proto` = 직렬화의 효과(둘 다 HTTP/1.1). `http-proto` 대 `grpc-unary` = 전송 방식의 효과(둘 다 같은 Protobuf 메시지).

## 크기 축 · 동시 사용자 축

| 경로 | ① dataServer가 만드는 것 | ② 보내는 형식 | ③ apiServer가 푸는 수준 — 전 | ③ — 후 |
|------|------|------|------|------|
| `http-json` | 서버 시작 때 만든 크기별 무작위 바이트(경로 공통) | JSON `{requestId, payload(Base64 문자열), timestamp, payloadSize}` | Jackson으로 `HttpDataResponse` 객체. `payload`는 Base64 **문자열 그대로**(바이트로 되돌리지 않음) | 같음 (남은 차이 1) |
| `http-bytes` | 같은 바이트 | `application/octet-stream` 원시 바이트 | `ByteArray` (풀 구조가 없다) | 같음 |
| `http-proto` | 같은 바이트로 `DataResponse` 메시지 (gRPC와 **같은 함수** `DataService.buildDataResponseProto`) | `application/x-protobuf` (HTTP/1.1) | — (경로 없음) | `DataResponse` 객체 (`parseFrom`) |
| `grpc-unary` | 같은 바이트로 `DataResponse` 메시지 (전: `GrpcDataService` 안에서 직접 생성 → 후: 같은 내용을 `buildDataResponseProto`로) | gRPC (HTTP/2 + Protobuf) | `DataResponse` 객체 | 같음 |

## 복잡도 축

| 경로 | ① dataServer가 만드는 것 | ② 보내는 형식 | ③ apiServer가 푸는 수준 — 전 | ③ — 후 |
|------|------|------|------|------|
| `http-json` | 요청마다 무작위 DTO(`generate*Data`) | JSON `{requestId, timestamp, serializedSize, complexity, data}` | Jackson으로 `ComplexDataJsonResponse` — `data`는 `Map<String, Any>`(타입 없는 트리) | 같음 (남은 차이 6) |
| `http-proto` | 같은 DTO → `ComplexDataResponse` 메시지 (`DataController`의 빌더) | `application/octet-stream` Protobuf 바이트 | **`ByteArray`로만 받고 풀지 않음** | **`ComplexDataResponse` 객체** (`parseFrom`) — gRPC와 같은 메시지 |
| `grpc-unary` | 같은 DTO → `ComplexDataResponse` 메시지 (`GrpcDataService`의 빌더) | gRPC | `ComplexDataResponse` 객체 | 같음 |

- 기록하는 크기: `http-proto`는 전에는 받은 바이트 수, 후에는 gRPC와 같이 메시지의 `serializedSize`.

## 확인했고 같은 것

- **측정 구간**: 모든 경로가 `ApiDataService`에서 클라이언트 호출 하나(요청 보내기 → 응답 객체 받기)만 잰다. 객체로 푸는 시간(Jackson, `parseFrom`, gRPC 마샬러)이 모두 구간 안에 있다.
- **apiServer가 받는 쪽의 복사**: `http-proto`는 WebClient가 응답을 `byte[]`로 모은 뒤 `parseFrom(byte[])`가 `payload` 필드를 한 번 더 복사한다. gRPC 1.61의 마샬러(`ProtoLiteUtils.MessageMarshaller`)도 스트림을 `byte[]`로 읽은 뒤 `CodedInputStream.newInstance(byte[])`로 파싱하고 aliasing을 켜지 않으므로 같은 복사를 한다(jar의 바이트코드로 확인).
- **크기 상한**: WebClient `maxInMemorySize` 10MB, gRPC `maxInboundMessageSize` 10MB.
- **k6 → apiServer**: 모든 경로가 HTTP/1.1, 컨트롤러는 Tomcat 스레드에서 `runBlocking`, 응답은 같은 작은 JSON.
- **데이터**: 크기 축의 바이트는 프로세스 안에서 경로마다 같다(테스트로 확인). 복잡도 축은 요청마다 무작위지만 형태(필드, 반복 개수)는 경로마다 같다(테스트로 확인).

## 남은 차이 (고치지 않았다 — 어느 쪽으로 맞출지 정해야 한다)

| # | 차이 | 측정에 미칠 영향 | 맞추는 방법 후보 |
|---|------|------|------|
| 1 | `http-json`(크기 축): apiServer가 Base64 문자열을 바이트로 되돌리지 않는다. dataServer는 요청마다 Base64로 인코딩한다(크기 1.33배). | JSON 경로가 "쓸 수 있는 바이트"까지 가지 않아 받는 쪽 비용이 작게 잡힌다. 큰 크기에서 JSON이 실제보다 유리하게 나온다. | ⓐ 클라이언트에서 `Base64.getDecoder().decode(payload)`까지 하고 구간 안에 둔다 ⓑ 그대로 두고 "디코딩 안 함"을 표에 적는다 |
| 2 | dataServer가 보내는 쪽 복사 수가 경로마다 다르다. `http-bytes`: 캐시 배열을 그대로 씀(복사 0). `grpc-unary`: `ByteString.copyFrom`(복사 1) 후 메시지를 바로 Netty 버퍼에 직렬화. `http-proto`: `ByteString.copyFrom`(복사 1) + `toByteArray()`(복사 2) 후 `ByteArrayHttpMessageConverter`가 씀. `http-json`: Base64 인코딩(1.33배 할당). | 큰 크기(500KB, 1MB)에서 `http-proto`가 `grpc-unary`보다 복사 한 번만큼 불리하다 → "전송 방식의 효과"에 직렬화 구현 차이가 섞인다. `http-bytes`가 가장 유리하다. | ⓐ `http-proto`가 `response.writeTo(outputStream)`으로 바로 쓰게 한다(`ProtobufHttpMessageConverter` 등록 또는 `StreamingResponseBody`) ⓑ 크기별 `ByteString`을 시작 때 한 번 만들어 두 Protobuf 경로가 같이 쓴다(복사 1 제거) ⓒ 그대로 두고 적는다 |
| 3 | apiServer → dataServer 연결: WebClient(reactor-netty 1.1.15) 기본 풀은 연결 최대 `max(코어 수, 8) × 2` = 이 머신(24코어)에서 **48개**, HTTP/1.1이라 동시에 48개 요청만 나간다(나머지는 풀에서 대기, 대기 시간 상한 45초). gRPC는 채널 **1개** = HTTP/2 연결 1개에 동시 스트림 수 제한 없음. | 동시 사용자 50 이상(축의 50, 100, 200, 500)에서 HTTP 경로는 풀 대기 시간이 `latencyMs`에 들어가고, gRPC는 연결 하나에 몰린다. 승패가 "전송 방식"보다 "풀 설정"으로 갈릴 수 있다. 풀 크기가 코어 수에 따라 달라 다른 머신과 비교되지 않는다. | ⓐ HTTP 풀 크기를 명시적으로 고정(예: 동시 사용자 최대값 이상)하고 결과에 적는다 ⓑ gRPC 채널 수를 여러 개로 하거나 1개임을 적는다 ⓒ 이것을 전송 방식의 성질로 보고 그대로 재되 설정값을 결과에 적는다 |
| 4 | dataServer의 처리 스레드: HTTP는 Tomcat(최대 200 스레드, 블로킹), gRPC 서버는 `ServerBuilder` 기본 실행기(상한 없는 캐시 스레드 풀). | 동시 요청이 200을 넘으면 차이가 날 수 있으나, 3의 풀 제한(48) 때문에 HTTP 쪽은 지금 그 수에 닿지 않는다. 3을 바꾸면 드러난다. | ⓐ gRPC 서버에 고정 크기 실행기 지정 ⓑ 그대로 두고 적는다 |
| 5 | 복잡도 축 `http-proto`와 `grpc-unary`의 메시지 빌더가 두 군데에 따로 있다(`DataController`, `GrpcDataService`). 지금은 같은 형태를 만든다(테스트로 확인). 보내는 쪽은 2와 같이 `toByteArray()` 한 번이 더 있다. 잘못된 복잡도 값에 HTTP는 400, gRPC는 simple로 대체. | 형태가 같아 지금 영향은 작다(복잡도 축의 메시지는 최대 약 220KB라 복사 비용이 작다). 나중에 한쪽만 고치면 어긋날 수 있다. | ⓐ 빌더를 한 곳으로 모은다 ⓑ 그대로 두고 테스트로 지킨다 |
| 6 | 복잡도 축 `http-json`: apiServer가 `data`를 `Map<String, Any>`로 푼다(Protobuf 경로는 타입이 있는 객체). dataServer가 담는 `serializedSize`는 실제 크기가 아니라 고정 추정치(150/800/5000/15000/50000)라 기록되는 전송량이 틀리다. 실제 JSON 크기(requestId 1자 기준): 185 / 691 / 3,589 / 36,056 / 421,441 바이트. | 지연 비교: 타입 없는 트리는 타입이 있는 객체로 바인딩하는 것보다 대체로 싸다 → JSON이 조금 유리할 수 있다. `BenchmarkCollector`의 `dataTransfer`는 JSON 경로에서 쓸 수 없다. | ⓐ apiServer에 타입이 있는 DTO를 두고 바인딩한다 ⓑ dataServer가 실제 직렬화 크기를 담는다(또는 apiServer가 받은 바이트 수를 잰다) ⓒ 그대로 두고 적는다 |
| 7 | 기록하는 크기의 뜻이 축마다 다르다. 크기 축은 네 경로 모두 원래 페이로드 크기(`payloadSize`, 전송 바이트 아님 — JSON은 실제로 1.33배 넘게 보낸다). 복잡도 축은 Protobuf 경로가 메시지 크기, JSON은 6의 추정치. | 지연 측정에는 영향 없음. 전송량을 결과에 쓰면 틀린다. | 전송량은 k6의 `data_received`나 별도 측정으로 얻는다 |

## 테스트

- `dataServer/src/test/kotlin/com/protobench/data/PathEquivalenceTest.kt`: 같은 요청에 `/data/json`(Base64를 되돌려), `/data/binary`, `/data/proto`가 gRPC `GetData`와 같은 바이트·크기를 돌려준다(7개 크기). `/data/complex/binary`가 gRPC `GetComplexData`와 같은 형태(메시지 필드 존재, 반복 개수)를 가진다(5개 복잡도).
- `apiServer/src/test/kotlin/com/protobench/api/PathEquivalenceTest.kt`: 가짜 dataServer가 같은 메시지를 HTTP와 gRPC로 보낼 때, apiServer의 `http-proto` 클라이언트와 gRPC 클라이언트가 같은(`equals`) 메시지 객체를 내놓는다(크기 축 3개, 복잡도 축 5개).
