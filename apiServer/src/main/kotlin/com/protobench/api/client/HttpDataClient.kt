package com.protobench.api.client

import com.protobench.proto.ComplexDataResponse
import com.protobench.proto.DataResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

data class HttpDataResponse(
    val requestId: String,
    val payload: String,
    val timestamp: Long,
    val payloadSize: Int
)

// ============================================
// Phase 5: 복잡한 데이터 DTO
// ============================================

/**
 * 복잡한 데이터 JSON 응답 (공통)
 */
data class ComplexDataJsonResponse(
    val requestId: String,
    val timestamp: Long,
    val serializedSize: Int,
    val complexity: String,
    val data: Map<String, Any>
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
    @Value("\${data-server.http.url}") private val baseUrl: String
) {
    private val webClient = WebClient.builder()
        .baseUrl(baseUrl)
        .codecs { it.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) }
        .build()

    /**
     * JSON 형식으로 데이터 요청
     *
     * @param requestId 요청 ID
     * @param size 페이로드 크기 (1kb, 10kb, 100kb, 1mb)
     * @return HttpDataResponse (Base64 인코딩된 payload 포함)
     */
    suspend fun getDataJson(requestId: String, size: String = "1mb"): HttpDataResponse {
        return webClient.get()
            .uri("/data/json?requestId=$requestId&size=$size")
            .retrieve()
            .awaitBody()
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
     * @return ComplexDataJsonResponse
     */
    suspend fun getComplexDataJson(requestId: String, complexity: String = "simple"): ComplexDataJsonResponse {
        return webClient.get()
            .uri("/data/complex/json?requestId=$requestId&complexity=$complexity")
            .retrieve()
            .awaitBody()
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