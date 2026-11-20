# 측정 경로 — 무엇을 하는가

> 2026-11-21 다시 재기(task 01)에서 정리했다. 기준 커밋 `1099712`. 남은 차이 일곱 가지 중 여섯을 고쳤다(task 01b, 기준 `649674e`). 리뷰에서 채택한 다섯 가지를 고쳤다(task 01c, 기준 `45f32a9`).
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
| `http-json` | 서버 시작 때 만든 크기별 무작위 바이트(경로 공통). 요청마다 Base64로 인코딩 | JSON `{requestId, payload(Base64 문자열), timestamp, payloadSize}` | Jackson으로 `HttpDataResponse` 객체. `payload`는 Base64 문자열 그대로 | **`HttpDataResponse` 객체 + Base64를 바이트로 되돌림**(`HttpDataClient.getDataJson`, 측정 구간 안) |
| `http-bytes` | 같은 바이트 | `application/octet-stream` 원시 바이트 | `ByteArray` (풀 구조가 없다) | 같음 |
| `http-proto` | 같은 바이트로 `DataResponse` 메시지 (gRPC와 같은 함수 `DataService.buildDataResponseProto`) | `application/x-protobuf` (HTTP/1.1) | — (경로 없음) | `DataResponse` 객체 (`parseFrom`) |
| `grpc-unary` | 같은 바이트로 `DataResponse` 메시지 (`buildDataResponseProto`) | gRPC (HTTP/2 + Protobuf) | `DataResponse` 객체 | 같음 |

## 복잡도 축

| 경로 | ① dataServer가 만드는 것 | ② 보내는 형식 | ③ apiServer가 푸는 수준 — 전 | ③ — 후 |
|------|------|------|------|------|
| `http-json` | **서버 시작 때 복잡도마다 한 번 만든 DTO**(`DataService.getComplexData`, 세 경로 공통). 요청마다 Jackson으로 직렬화 | JSON `{requestId, timestamp, serializedSize, complexity, data}` | Jackson으로 `data`를 `Map<String, Any>`(타입 없는 트리) | **dataServer와 같은 모양의 DTO**(`apiServer/.../client/ComplexDataDtos.kt`)로 바인딩. 본문을 `byte[]`로 모은 뒤 `ObjectMapper.readValue` (http-proto와 같은 순서) |
| `http-proto` | 같은 DTO 객체 → 요청마다 `ComplexDataResponse` 메시지로 만들어 직렬화 (**`ComplexDataProtoBuilder`**) | `application/octet-stream` Protobuf 바이트 | `ByteArray`로만 받고 풀지 않음 | `ComplexDataResponse` 객체 (`parseFrom`) — gRPC와 같은 메시지 |
| `grpc-unary` | 같은 DTO → `ComplexDataResponse` 메시지 (**`ComplexDataProtoBuilder`** — 같은 빌더) | gRPC | `ComplexDataResponse` 객체 | 같음 |

- 데이터: 전에는 요청마다 무작위 값으로 새로 만들어 경로마다(요청마다) 내용과 크기가 달랐다. 지금은 시작 때 한 번 만들고 세 경로가 같은 객체에서 응답을 만든다. 요청마다 달라지는 것은 응답의 `requestId`와 `timestamp`뿐이다(데이터의 `id`는 `simple-fixed` 등으로 고정, `createdAt`·`updatedAt`도 시작 때 값). 미리 직렬화해 두지는 않는다 — 직렬화가 측정 대상이다. Protobuf 두 경로는 DTO를 메시지로 바꾸는 일도 요청마다 한다(JSON 경로에서 Jackson이 DTO를 훑는 것에 해당).
- 메시지 빌더: 전에는 `DataController`와 `GrpcDataService`에 따로 있었다. 지금은 `dataServer/.../service/ComplexDataProtoBuilder.kt` 하나를 두 경로가 같이 쓴다(만드는 내용은 그대로).
- 잘못된 복잡도 값: 세 경로 모두 거절한다 — dataServer는 HTTP 400(JSON, Protobuf) / gRPC `INVALID_ARGUMENT`(전에는 gRPC만 simple로 대신 응답). apiServer는 세 경로 모두 500을 돌려준다. 값이 비어 있으면 gRPC는 simple(HTTP 경로의 파라미터 기본값과 같음).
- apiServer의 JSON 바인딩은 모르는 필드를 실패로 둔다(`failOnUnknownProperties(true)`). dataServer DTO와 모양이 어긋나면 값을 조용히 버리지 않고 실패한다. dataServer의 DTO를 바꾸면 apiServer의 DTO도 같이 바꿔야 한다.

## 기록하는 크기

| 축 | 경로 | 기록하는 값 (`BenchmarkCollector`의 응답 바이트, 응답의 `payloadSize`/`serializedSize`) |
|----|------|------|
| 크기·동시 사용자 | 네 경로 | 원래 페이로드 크기(`payloadSize`). 전송 바이트가 아니다(남긴 차이 E). 메시지의 `payloadSize` 필드나 배열 길이를 읽으므로 메시지를 다시 훑지 않는다(복잡도 축 같은 문제가 없다) |
| 복잡도 | `http-json` | **apiServer가 받은 본문의 바이트 수**(전: dataServer가 담은 고정 추정치 150/800/5000/15000/50000). dataServer가 JSON에 담는 `serializedSize`는 여전히 추정치이고 apiServer는 쓰지 않는다 |
| 복잡도 | `http-proto` | **apiServer가 받은 본문의 바이트 수**(= 보낸 Protobuf 바이트 수). 전: 측정이 끝난 뒤 `serializedSize`로 메시지 트리를 한 번 더 훑어 계산 |
| 복잡도 | `grpc-unary` | **-1 (값 없음, `ApiDataService.SIZE_UNKNOWN`)**. gRPC 스텁은 받은 바이트 수를 내주지 않고, `serializedSize`를 부르면 메시지를 한 번 더 훑게 된다. 전송량은 k6가 잰 값을 쓴다. `BenchmarkCollector`는 기록에 -1이 섞이면 전송량 합계·평균도 -1로 둔다 |

## 보내는 쪽 복사 (dataServer)

페이로드 바이트가 전송 계층의 버퍼(Tomcat 응답 스트림, Netty 버퍼)에 닿을 때까지의 복사를 센다. 그 뒤(소켓 버퍼, 커널)는 전송 구현마다 다르고 세지 않았다.

| 경로 | 전 | 후 |
|------|------|------|
| `grpc-unary` | ① `ByteString.copyFrom(캐시 배열)` ② 메시지를 Netty 버퍼에 바로 직렬화(grpc-java `ProtoInputStream.drainTo` → `writeTo`) = **2** | 같음 = **2** |
| `http-proto` | ① `ByteString.copyFrom` ② `toByteArray()`로 중간 `byte[]` ③ `ByteArrayHttpMessageConverter`가 응답 스트림에 씀 = **3** | ① `ByteString.copyFrom` ② **`message.writeTo(응답 스트림)`**(`DataController.writeProto`) = **2** |
| `http-bytes` | 캐시 배열을 응답 스트림에 씀 = 1 | 같음 (형식의 성질 — 감쌀 메시지가 없다) |
| `http-json` | Base64 인코딩(1.33배 `byte[]` → `String`) 후 Jackson이 문자열을 응답 스트림에 씀 | 같음 (형식의 성질 — JSON에 바이트를 담으려면 텍스트로 바꿔야 한다) |

- 고른 방법: `ProtobufHttpMessageConverter`를 등록하는 대신 컨트롤러가 `HttpServletResponse`에 직접 쓴다. 변환기를 등록하면 다른 엔드포인트의 변환기 선택에도 영향이 갈 수 있어서, 두 Protobuf 엔드포인트(`/data/proto`, `/data/complex/binary`)에만 닿게 했다. `Content-Length`는 `serializedSize`로 먼저 정한다(전처럼 chunked가 아님).
- 크기별 `ByteString`을 시작 때 만들어 두는 방법(복사 ①을 두 경로에서 같이 빼기)은 쓰지 않았다. 두 경로의 횟수는 이미 같고, 바꾸면 gRPC 경로의 일도 달라진다.

## 연결과 스레드 설정 — `default`와 `matched`

두 서버 모두 `SPRING_PROFILES_ACTIVE=matched`이면 `matched`, 없으면 `default`다. `matched`의 값은 `BENCH_MAX_CONCURRENCY`(없으면 512) 하나다(`application-matched.yml`의 `bench.max-concurrency`).

| 설정 | `default` (손대지 않음 — 실제 값) | `matched` |
|------|------|------|
| apiServer HTTP 클라이언트 연결 풀 최대 연결 | **500** (WebClient 기본 커넥터 → `HttpClient.create()` → reactor-netty 전역 풀, `ConnectionProvider.create("http", 500)`) | N |
| 〃 대기 상한 (`pendingAcquireMaxCount`) | 1,000 (지정하지 않으면 최대 연결의 2배) | N |
| 〃 대기 시간 | 45초 | 45초 (그대로) |
| apiServer Tomcat 최대 스레드 | 200 | N |
| dataServer Tomcat 최대 스레드 | 200 | N |
| dataServer gRPC 서버 실행기 | grpc-java 기본(상한 없는 캐시 스레드 풀) | N개 고정 스레드 풀 |
| dataServer gRPC 서비스 코루틴이 도는 곳 | `Dispatchers.Default` (코어 수 = 24) | 위 실행기 (같은 N개) |
| apiServer gRPC 채널 | 1개 | 1개 (HTTP/2 연결 하나에 요청을 겹쳐 보낸다 — 전송 방식의 성질) |
| dataServer Tomcat 연결 하나의 최대 요청 수 (`max-keep-alive-requests`) | **100** (Tomcat 기본 — 100번째 응답에 `Connection: close`) | **-1 (끊지 않음)** |
| dataServer Tomcat 쉬는 연결을 끊는 시간 (`keep-alive-timeout`) | 60초 (Tomcat 기본) | **-1 (끊지 않음)** — gRPC 서버(grpc-java 기본 `maxConnectionIdle` 없음)와 같게 |
| apiServer HTTP 풀 유휴 시간·연결 수명 | -1 (reactor-netty 기본 — 닫지 않음) | -1 (같음 — 클라이언트가 일찍 닫는 설정이 없어 따로 맞추지 않았다) |
| apiServer HTTP 클라이언트 `compress(true)` (`Accept-Encoding: gzip`) | 켜짐 (Spring 기본 커넥터 `ReactorClientHttpConnector()`가 켠다) | **켜짐** (전: 꺼짐 — 직접 만든 `HttpClient`라 빠져 있었다). dataServer는 응답을 압축하지 않아 본문은 같다 |

- 기동이 끝나면 두 서버가 실제로 적용된 값을 한 줄로 찍는다(측정 기록에 남긴다). `read:` 뒤는 실행 중인 객체에서 읽은 값, `| set:` 뒤는 설정한 값·상수·규칙으로 계산한 값(객체에서 읽을 수 없는 것)이다. 2026-11-21 이 머신(24코어)에서 찍힌 줄(task 01c, `7881304`):

```
BENCH_CONFIG server=dataServer profile=default read: tomcatMaxThreads=200 tomcatMaxKeepAliveRequests=100 tomcatKeepAliveTimeoutMs=60000 | set: grpcServerExecutor=grpc-default(cached,unbounded) grpcHandlerDispatcher=Dispatchers.Default(24)
BENCH_CONFIG server=apiServer profile=default read: tomcatMaxThreads=200 httpPoolMaxConnections=500 httpClientAcceptGzip=true | set: httpPoolPendingAcquireMaxCount=1000 httpPoolPendingAcquireTimeoutMs=45000 httpPoolMaxIdleTimeMs=-1 httpPoolMaxLifeTimeMs=-1 grpcChannels=1
BENCH_CONFIG server=dataServer profile=matched read: tomcatMaxThreads=512 tomcatMaxKeepAliveRequests=-1 tomcatKeepAliveTimeoutMs=-1 | set: grpcServerExecutor=fixed(512) grpcHandlerDispatcher=grpcServerExecutor
BENCH_CONFIG server=apiServer profile=matched read: tomcatMaxThreads=512 httpPoolMaxConnections=512 httpClientAcceptGzip=true | set: httpPoolPendingAcquireMaxCount=512 httpPoolPendingAcquireTimeoutMs=45000 httpPoolMaxIdleTimeMs=-1 httpPoolMaxLifeTimeMs=-1 grpcChannels=1
```

- 어디서 읽나: Tomcat 스레드 수·연결 유지 값은 실행 중인 Tomcat 커넥터(실행기, `AbstractHttp11Protocol`)에서 읽는다. HTTP 풀 최대 연결 수와 `compress`는 **WebClient 커넥터에 넘긴 `HttpClient` 객체의 설정**(`configuration().connectionProvider()`, `isAcceptGzip`)에서 읽는다. 이를 위해 두 설정 모두 `HttpClient`를 직접 만들어 `ReactorClientHttpConnector(httpClient)`로 넘긴다. `default`는 Spring 기본 커넥터가 만드는 것과 같은 `HttpClient.create().compress(true)`다(spring-web 6.1.3 바이트코드로 확인 — 동작은 전과 같다). 대기 상한은 `matched`에서는 설정한 값, `default`에서는 reactor-netty의 규칙(최대 연결의 2배)으로 계산한 값이다. 대기 시간·유휴 시간·수명은 reactor-netty 상수, gRPC 실행기는 직접 만든 풀의 값이다(grpc-java는 실행기를 밖으로 내주지 않는다).
- 객체에서 읽은 값도 그 객체를 실제로 쓰는지는 보여 주지 않는다. 그래서 풀은 동작으로 따로 확인한다(`HttpConnectionPoolTest`: 풀 2에 동시 3요청 → 연결 2개, `default`면 3개).
- reactor-netty의 최대 연결 수는 원격 주소마다 적용된다. 여기서는 원격이 dataServer 하나라 풀 전체의 상한과 같다.
- **apiServer 컨트롤러는 Tomcat 스레드에서 `runBlocking`으로 dataServer 호출을 기다린다(그대로 둔다).** 그래서 apiServer가 dataServer로 동시에 보내는 요청 수는 네 경로 모두 apiServer Tomcat 최대 스레드 수(`default` 200, `matched` N)를 넘지 않는다. `default`에서 HTTP 풀(500)은 이 상한에 닿지 않는다. 동시 사용자가 그 수를 넘으면 넘는 요청은 apiServer의 Tomcat 큐에서 기다리고, 그 시간은 `latencyMs`(dataServer 호출 구간)에는 들어가지 않고 k6가 재는 시간에만 들어간다.
- 바로잡음: 앞선 판(task 01)은 기본 HTTP 풀을 `max(코어 수, 8) × 2` = 48개로 적었다. 그것은 `ConnectionProvider.builder()`의 기본값이고, WebClient 기본 커넥터가 쓰는 전역 풀은 500개다(reactor-netty 1.1.15 `TcpResources`의 바이트코드와 기동 로그로 확인).

## 확인했고 같은 것

- **측정 구간**: 모든 경로가 `ApiDataService`에서 클라이언트 호출 하나(요청 보내기 → 쓸 수 있는 객체 받기)만 잰다. 객체로 푸는 시간(Jackson, Base64 되돌리기, `parseFrom`, gRPC 마샬러)이 모두 구간 안에 있다.
- **apiServer가 받는 쪽의 복사**: `http-proto`와 복잡도 축 `http-json`은 WebClient가 응답을 `byte[]`로 모은 뒤 푼다(`parseFrom(byte[])`, `ObjectMapper.readValue(byte[])`). gRPC 1.61의 마샬러(`ProtoLiteUtils.MessageMarshaller`)도 스트림을 `byte[]`로 읽은 뒤 `CodedInputStream.newInstance(byte[])`로 파싱하고 aliasing을 켜지 않으므로 같은 복사를 한다(jar의 바이트코드로 확인). 크기 축 `http-json`은 WebClient의 Jackson 디코더가 모은 버퍼에서 바로 푼다(전과 같음).
- **크기 상한**: WebClient `maxInMemorySize` 10MB, gRPC `maxInboundMessageSize` 10MB.
- **k6 → apiServer**: 모든 경로가 HTTP/1.1, 컨트롤러는 Tomcat 스레드에서 `runBlocking`, 응답은 같은 작은 JSON.
- **데이터**: 크기 축의 바이트는 프로세스 안에서 경로마다 같다(테스트로 확인). 복잡도 축은 시작 때 한 번 만든 같은 객체라 세 경로의 내용이 필드 값까지 같다(테스트로 확인, 실제 서버에서 두 설정 모두 다섯 단계를 세 경로로 불러 확인). 단 값은 프로세스를 띄울 때마다 새로 뽑으므로 재시작하면 크기가 조금 달라진다(남긴 차이 J).

## 고친 것 (task 01b)

| # | 전의 차이 | 고친 방법 | 커밋 |
|---|------|------|------|
| 1 | `http-json`(크기 축): Base64 문자열을 바이트로 되돌리지 않음 | `HttpDataClient.getDataJson`이 측정 구간 안에서 `Base64.getDecoder().decode` | `3f54cb2` |
| 2 | `http-proto`가 `grpc-unary`보다 보내는 쪽 복사가 한 번 많음(`toByteArray()`) | 응답 스트림에 바로 직렬화 — 두 경로 모두 2회(위 "보내는 쪽 복사") | `a1f32d8` |
| 3·4 | HTTP 풀·Tomcat 스레드·gRPC 실행기 설정이 경로마다 다르고 머신마다 달라짐 | `default`/`matched` 두 설정을 고를 수 있게 하고 기동 로그에 실제 값을 찍음(위 "연결과 스레드 설정") | `ee7d3f6` |
| 5 | 복잡도 축 메시지 빌더가 두 군데, 잘못된 복잡도 값 처리가 다름 | `ComplexDataProtoBuilder` 하나로 모음, 두 경로 모두 거절 | `c43b9cd` |
| 6 | 복잡도 축 `http-json`이 `Map`으로 풂, 기록하는 크기가 고정 추정치 | 타입이 있는 DTO로 바인딩(`ab656a8`), 받은 본문의 바이트 수를 기록(`44dce75`) | `ab656a8`, `44dce75` |

## 고친 것 (task 01c — 리뷰에서 채택)

| # | 전의 차이 | 고친 방법 | 커밋 |
|---|------|------|------|
| R1 | 복잡도 축 데이터를 요청마다 무작위로 새로 만들어 경로마다 내용과 크기가 다름 | 시작 때 복잡도마다 한 번 만들고 세 경로가 같은 객체에서 응답을 만듦. 직렬화는 요청마다 | `4653853` |
| R2 | `matched` 풀 테스트가 입력한 값을 되읽기만 함 | 가짜 서버에서 맺어진 연결 수를 셈(풀 2, 동시 3요청 → 2개). 기동 로그를 read/set으로 나눔 | `3e2d145` |
| R3 | 복잡도 축 Protobuf 두 경로만 측정 뒤에 `serializedSize`로 메시지를 한 번 더 훑음 | `http-proto`는 받은 바이트 수, `grpc-unary`는 -1 | `523dbc4` |
| R4 | dataServer HTTP 연결이 100요청마다(그리고 60초 쉬면) 끊기고 gRPC는 유지 | `matched`에서만 `max-keep-alive-requests: -1`, `keep-alive-timeout: -1ms`. `default`는 그대로(남긴 차이 I) | `aac6928` |
| Q2 | `matched`의 HTTP 클라이언트만 `compress(true)`가 꺼져 있음 | `matched`도 켬 | `7881304` |

## 남긴 차이 (고치지 않았다 — 이유와 측정에 미치는 영향)

| # | 차이 | 왜 남겼나 | 측정에 미칠 영향 |
|---|------|------|------|
| A | `http-json`은 요청마다 Base64로 인코딩하고(1.33배) 받는 쪽은 되돌린다. `http-bytes`는 감쌀 것 없이 캐시 배열을 그대로 쓴다 | 형식의 성질이다. JSON에 바이트를 담으려면 텍스트로 바꿔야 하고, 원시 바이트에는 메시지가 없다. 이 차이가 재려는 것 자체다 | 큰 크기에서 `http-json`이 느리고 `http-bytes`가 가장 빠르게 나오는 것은 형식의 비용이다. "전송 방식의 효과"는 `http-proto` 대 `grpc-unary`로만 읽는다 |
| B | `default`에서 dataServer의 일을 하는 스레드가 HTTP는 Tomcat 최대 200, gRPC는 `Dispatchers.Default`(코어 수, 24)다 | 지시대로 `default`는 기본값을 손대지 않는다. 맞춘 비교는 `matched`로 한다 | `default`의 높은 동시 사용자 수에서 gRPC 서버 쪽 처리는 코어 수만큼만 겹친다(CPU를 쓰는 일이라 코어 수 이상은 이득이 작을 수 있다). 두 설정의 결과가 다르면 이 차이 때문일 수 있다 |
| C | gRPC 클라이언트 채널은 두 설정 모두 1개(HTTP/2 연결 하나에 요청을 겹침), HTTP는 요청마다 연결 하나를 잡는다(HTTP/1.1) | 전송 방식의 성질이다 | `http-proto` 대 `grpc-unary`의 차이에 연결 수의 차이가 들어간다. 이것이 "전송 방식의 효과"의 일부다 |
| D | apiServer 컨트롤러가 Tomcat 스레드에서 `runBlocking`으로 기다린다 | 바꾸면 실험이 달라진다 | 동시에 나가는 요청 수의 상한이 apiServer Tomcat 최대 스레드다(네 경로 같음). 넘는 요청의 대기는 `latencyMs`에 안 들어가고 k6의 시간에만 들어간다 |
| E (전 7) | 기록하는 크기의 뜻이 축마다 다르다. 크기 축은 원래 페이로드 크기(JSON은 실제로 1.33배 넘게 보낸다), 복잡도 축은 보낸 바이트 수 | 크기 축의 값은 축의 이름(조건)이라 경로마다 같게 두는 것이 비교에 맞다 | 지연 측정에는 영향 없음. 전송량은 k6의 `data_received`나 별도 측정으로 얻는다 |
| F | dataServer가 복잡도 축 JSON에 담는 `serializedSize`는 여전히 고정 추정치다 | 실제 크기를 담으려면 dataServer가 한 번 더 직렬화해야 해서(자기 크기를 담는 필드라) 보내는 쪽 일이 늘어난다. apiServer가 받은 바이트 수를 쓰므로 기록에는 영향이 없다 | 없음 (값을 쓰지 않는다) |
| G | 잘못된 복잡도 값에 apiServer가 400이 아니라 500을 돌려준다(세 경로 같음) | 측정과 무관하다 | 없음 |
| H | JSON 응답은 `Content-Length` 없이 chunked로 가고, Protobuf와 바이트 응답은 `Content-Length`를 먼저 정한다 | 형식과 프레임워크의 성질이다. Jackson은 스트림에 쓰면서 길이를 모르고, Protobuf는 `serializedSize`를, 바이트는 배열 길이를 미리 안다 | JSON 쪽에 청크 머리(크기 줄)가 붙고 받는 쪽이 청크를 이어 붙인다. 작은 응답에서는 무시할 만하다. JSON 경로의 비용에 들어간다 |
| I | `default`에서는 dataServer가 HTTP 연결을 100요청마다(그리고 60초 쉬면) 끊고, gRPC는 연결을 유지한다 | `default`는 기본값을 손대지 않는다. `matched`에서는 끊지 않게 맞췄다(R4) | `default`의 HTTP 경로는 100요청마다 새 TCP 연결을 맺는 비용이 들어간다. 두 설정의 HTTP 결과가 다르면 이 차이 때문일 수 있다 |
| J | 복잡도 축 데이터의 값은 프로세스를 띄울 때마다 새로 뽑는다(시드 고정 없음). 재시작하면 크기가 조금 달라진다(예: ultra Protobuf 21,370 → 21,342바이트) | 이번 지시 밖이라 고치지 않았다(task 01c에서 찾음) | 한 프로세스 안에서는 세 경로가 같다. 회차마다 서버를 다시 띄우면 회차 사이의 크기가 1% 안쪽으로 흔들린다. 필요하면 생성기의 난수 시드를 고정한다 |

## 테스트

`./gradlew test` — dataServer 27건, apiServer 30건(task 01c 기준).

- `dataServer/src/test/kotlin/com/protobench/data/PathEquivalenceTest.kt` (23건): 같은 요청에 `/data/json`(Base64를 되돌려), `/data/binary`, `/data/proto`가 gRPC `GetData`와 같은 바이트·크기를 돌려준다(7개 크기, `/data/proto`의 `Content-Length`와 형식 포함). `/data/complex/binary`가 gRPC `GetComplexData`와 같은 형태를 가진다(5개 복잡도). 두 경로의 메시지가 같다(5개). **세 경로(`/data/complex/json`, `/data/complex/binary`, `GetComplexData`)가 필드 값까지 같은 데이터를 보낸다 — JSON의 `data`를 DTO로 풀어 같은 빌더로 만든 메시지가 HTTP + Protobuf 메시지와 같다(5개, task 01c). 시작 때 만든 데이터 대신 요청마다 새로 만들게 바꾸면 이 테스트가 실패하는 것을 확인했다.** 잘못된 복잡도 값을 세 경로가 거절한다.
- `dataServer/src/test/kotlin/com/protobench/data/BenchConcurrencyConfigTest.kt` (4건): `matched`에서 Tomcat 최대 스레드와 gRPC 실행기가 설정값이고 실제 gRPC 호출이 그 실행기를 거친다. `matched`에서 연결 유지 값이 -1이고 연결 하나로 150요청을 보내도 끊지 않는다. `default`에서 기본값(200, grpc 기본 실행기)이 그대로이고 100번째 요청에서 끊는다.
- `apiServer/src/test/kotlin/com/protobench/api/PathEquivalenceTest.kt` (26건): 가짜 dataServer에 대해 `http-proto`와 gRPC 클라이언트가 같은 메시지 객체를 내놓는다(크기 3개, 복잡도 5개). `http-json`이 되돌린 바이트가 원래 바이트와 같다(크기 3개). 복잡도 축 `http-json`이 타입이 있는 DTO로 풀고, 그 객체를 다시 JSON 트리로 바꾸면 보낸 것과 같다(5개). 기록된 크기가 받은 본문의 바이트 수와 같다(`http-json` 5개, **`http-proto` 5개 — 같은 테스트에서 `grpc-unary`는 -1**). 가짜 dataServer의 JSON은 실제 dataServer의 응답에서 배열을 2개, 큰 맵을 3개 항목까지 줄인 것이다(`src/test/resources/complex-json/`).
- `apiServer/src/test/kotlin/com/protobench/api/HttpConnectionPoolTest.kt` (2건, task 01c): 가짜 서버가 맺어진 TCP 연결 수를 센다. `matched`(풀 2)에 동시 3요청 → 연결 2개, `default` → 3개. 두 설정 모두 요청에 `Accept-Encoding: gzip`이 붙는다. `matched`를 기본 커넥터로 바꾸면 연결이 3개가 되어 실패하고, `matched`에서 `compress(true)`를 빼면 실패하는 것을 확인했다.
- `apiServer/src/test/kotlin/com/protobench/api/BenchConcurrencyConfigTest.kt` (2건): `matched`에서 Tomcat 최대 스레드, HTTP 풀 최대 연결·대기 상한이 설정값이고 gRPC 채널이 1개다. `default`에서 기본값(200, 500, 1,000, 45초)이 그대로다.

## 측정 밖에서 찾은 것 (task 01c — 고치지 않았다)

- 예전 k6 스크립트(`scripts/phase5/`, `scripts/phase7/`의 `grpc-test.js` 등)는 응답의 `serializedSize > 0`을 check로 본다. 복잡도 축 `grpc-unary`는 이제 -1이라 그 check가 실패한다(요청 실패가 아니라 check 비율). task 02의 새 스크립트는 이 값을 check하지 않아야 한다.
- 측정에서 뺀 `/api/complex/grpc/stream`은 여전히 측정 뒤에 `serializedSize`를 부른다.
