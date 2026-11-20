package com.protobench.api.client

import com.protobench.proto.ComplexDataResponse
import com.protobench.proto.DataResponse
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import reactor.netty.http.HttpResources
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.util.Base64

data class HttpDataResponse(
    val requestId: String,
    val payload: String,
    val timestamp: Long,
    val payloadSize: Int
)

/**
 * http-json 경로의 응답을 바이트까지 되돌린 것
 *
 * 다른 경로(http-bytes, http-proto, grpc-unary)가 바이트까지 푸는 것과 같은 수준으로 맞추려고
 * Base64 문자열을 바이트 배열로 되돌려 담는다.
 */
class DecodedDataResponse(
    val requestId: String,
    val payload: ByteArray,
    val timestamp: Long,
    val payloadSize: Int
)

/**
 * 복잡도 축 http-json의 응답과 실제로 받은 바이트 수
 *
 * dataServer가 JSON에 담는 serializedSize는 고정 추정치라 쓰지 않고, 받은 본문의 바이트 수를 기록한다.
 */
class ComplexJsonResult(
    val response: ComplexDataJsonResponse<*>,
    val receivedBytes: Int
)

/**
 * dataServer HTTP 클라이언트
 *
 * WebClient를 사용하여 dataServer의 HTTP 엔드포인트를 호출한다.
 *
 * Phase 5: 복잡한 데이터 구조 요청 메서드 추가
 */
@Component
class HttpDataClient(
    @Value("\${data-server.http.url}") private val baseUrl: String,
    @Value("\${bench.max-concurrency:#{null}}") maxConcurrency: Int? = null
) {
    /**
     * 연결 풀
     *
     * - `default`(maxConcurrency 없음): 손대지 않는다. WebClient 기본 커넥터가 쓰는 reactor-netty 전역 풀
     *   (`HttpResources`: 최대 연결 500, 대기 상한 그 두 배, 대기 시간 45초 — reactor-netty 1.1.15).
     * - `matched`: 최대 연결 수와 대기 상한을 모두 maxConcurrency로 둔다(대기 시간은 기본값).
     *   reactor-netty의 최대 연결 수는 원격 주소(여기서는 dataServer 하나)마다 적용된다.
     */
    private val connectionProvider: ConnectionProvider? = maxConcurrency?.let { n ->
        ConnectionProvider.builder("bench-http")
            .maxConnections(n)
            .pendingAcquireMaxCount(n)
            .build()
    }

    /** 실제로 쓰는 풀의 설정 (기동 로그용) */
    val poolMaxConnections: Int = (connectionProvider ?: HttpResources.get()).maxConnections()
    val poolPendingAcquireMaxCount: Int = maxConcurrency ?: (2 * poolMaxConnections)
    val poolPendingAcquireTimeoutMs: Long = ConnectionProvider.DEFAULT_POOL_ACQUIRE_TIMEOUT

    private val webClient = WebClient.builder()
        .baseUrl(baseUrl)
        .codecs { it.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) }
        .apply { b -> connectionProvider?.let { b.clientConnector(ReactorClientHttpConnector(HttpClient.create(it))) } }
        .build()

    @PreDestroy
    fun close() {
        connectionProvider?.dispose()
    }

    // WebClient 기본 JSON 디코더와 같은 설정에서, 모르는 필드는 실패로 둔다
    // (dataServer DTO와 모양이 어긋나면 값을 조용히 버리지 않고 드러나게).
    private val objectMapper: ObjectMapper = Jackson2ObjectMapperBuilder.json()
        .failOnUnknownProperties(true)
        .build()

    /** 복잡도별 `data`의 타입 (`ComplexDataJsonResponse<T>`) */
    private val complexJsonTypes: Map<String, JavaType> = mapOf(
        "simple" to SimpleDataDto::class.java,
        "medium" to MediumDataDto::class.java,
        "complex" to ComplexDataDto::class.java,
        "ultra" to UltraDataDto::class.java,
        "extreme" to ExtremeDataDto::class.java
    ).mapValues { (_, dataClass) ->
        objectMapper.typeFactory.constructParametricType(ComplexDataJsonResponse::class.java, dataClass)
    }

    /**
     * JSON 형식으로 데이터 요청
     *
     * @param requestId 요청 ID
     * @param size 페이로드 크기 (1kb, 10kb, 100kb, 1mb)
     * JSON을 `HttpDataResponse`로 푼 뒤 Base64 문자열을 바이트로 되돌린다.
     * 호출하는 쪽의 측정 구간(`nanoTime`) 안에서 실행된다.
     *
     * @return DecodedDataResponse (바이트로 되돌린 payload 포함)
     */
    suspend fun getDataJson(requestId: String, size: String = "1mb"): DecodedDataResponse {
        val response: HttpDataResponse = webClient.get()
            .uri("/data/json?requestId=$requestId&size=$size")
            .retrieve()
            .awaitBody()
        return DecodedDataResponse(
            requestId = response.requestId,
            payload = Base64.getDecoder().decode(response.payload),
            timestamp = response.timestamp,
            payloadSize = response.payloadSize
        )
    }

    /**
     * Binary 형식으로 데이터 요청
     *
     * @param requestId 요청 ID
     * @param size 페이로드 크기 (1kb, 10kb, 100kb, 1mb)
     * @return 바이너리 바이트 배열
     */
    suspend fun getDataBinary(requestId: String, size: String = "1mb"): ByteArray {
        return webClient.get()
            .uri("/data/binary?requestId=$requestId&size=$size")
            .retrieve()
            .awaitBody()
    }

    /**
     * Protobuf 형식으로 데이터 요청 (http-proto 경로)
     *
     * dataServer가 gRPC 단건과 같은 DataResponse 메시지를 HTTP/1.1로 보내고,
     * 여기서 그것을 DataResponse 객체로 파싱한다.
     *
     * @param requestId 요청 ID
     * @param size 페이로드 크기 (1kb, 10kb, 100kb, 1mb)
     * @return DataResponse (gRPC 경로와 같은 Protobuf 메시지)
     */
    suspend fun getDataProto(requestId: String, size: String = "1mb"): DataResponse {
        val bytes: ByteArray = webClient.get()
            .uri("/data/proto?requestId=$requestId&size=$size")
            .retrieve()
            .awaitBody()
        return DataResponse.parseFrom(bytes)
    }

    // ============================================
    // Phase 5: 복잡한 데이터 구조 요청
    // ============================================

    /**
     * 복잡한 데이터를 JSON 형식으로 요청
     *
     * @param requestId 요청 ID
     * @param complexity 복잡도 (simple, medium, complex)
     * @return ComplexJsonResult (응답 객체와 받은 본문의 바이트 수)
     */
    suspend fun getComplexDataJson(requestId: String, complexity: String = "simple"): ComplexJsonResult {
        // http-proto 경로와 같이 본문을 byte[]로 모은 뒤 타입이 있는 객체로 푼다(받은 바이트 수를 알 수 있다).
        // objectMapper는 WebClient 기본 JSON 디코더와 같은 설정(Jackson2ObjectMapperBuilder)이다.
        val bytes: ByteArray = webClient.get()
            .uri("/data/complex/json?requestId=$requestId&complexity=$complexity")
            .retrieve()
            .awaitBody()
        val type = complexJsonTypes[complexity.lowercase()]
            ?: throw IllegalArgumentException("Invalid complexity: $complexity")
        return ComplexJsonResult(
            response = objectMapper.readValue<ComplexDataJsonResponse<*>>(bytes, type),
            receivedBytes = bytes.size
        )
    }

    /**
     * 복잡한 데이터를 Protobuf Binary 형식으로 요청
     *
     * gRPC 경로와 같은 수준까지 풀기 위해 응답 바이트를 ComplexDataResponse 객체로 파싱한다.
     *
     * @param requestId 요청 ID
     * @param complexity 복잡도 (simple, medium, complex)
     * @return ComplexDataResponse (gRPC 경로와 같은 Protobuf 메시지)
     */
    suspend fun getComplexDataBinary(requestId: String, complexity: String = "simple"): ComplexDataResponse {
        val bytes: ByteArray = webClient.get()
            .uri("/data/complex/binary?requestId=$requestId&complexity=$complexity")
            .retrieve()
            .awaitBody()
        return ComplexDataResponse.parseFrom(bytes)
    }
}