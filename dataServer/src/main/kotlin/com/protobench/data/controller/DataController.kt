package com.protobench.data.controller

import com.protobench.data.service.*
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.Base64

data class DataResponse(
    val requestId: String,
    val payload: String,
    val timestamp: Long,
    val payloadSize: Int
)

/**
 * Phase 5: 복잡한 데이터 JSON 응답
 */
data class ComplexDataJsonResponse<T>(
    val requestId: String,
    val timestamp: Long,
    val serializedSize: Int,
    val complexity: String,
    val data: T
)

const val PROTOBUF_MEDIA_TYPE = "application/x-protobuf"

/**
 * HTTP 데이터 컨트롤러
 *
 * dataServer의 HTTP 엔드포인트를 제공한다.
 * JSON(Base64) 또는 Binary 형식으로 페이로드를 반환한다.
 *
 * Phase 5: 복잡한 데이터 구조 엔드포인트 추가
 * Phase 6: Ultra/Extreme 복잡도 엔드포인트 추가
 */
@RestController
@RequestMapping("/data")
class DataController(
    private val dataService: DataService,
    private val complexDataProtoBuilder: ComplexDataProtoBuilder
) {

    /**
     * JSON 형식으로 페이로드 반환 (Base64 인코딩)
     *
     * @param requestId 요청 ID
     * @param size 페이로드 크기 (1kb, 10kb, 100kb, 1mb)
     * @return DataResponse (Base64 인코딩된 payload 포함)
     */
    @GetMapping("/json")
    fun getDataAsJson(
        @RequestParam(defaultValue = "unknown") requestId: String,
        @RequestParam(defaultValue = "1mb") size: String
    ): ResponseEntity<DataResponse> {
        val payload = dataService.getPayload(size)
        val response = DataResponse(
            requestId = requestId,
            payload = Base64.getEncoder().encodeToString(payload),
            timestamp = System.currentTimeMillis(),
            payloadSize = payload.size
        )
        return ResponseEntity.ok(response)
    }

    /**
     * Binary 형식으로 페이로드 반환 (raw bytes)
     *
     * @param requestId 요청 ID
     * @param size 페이로드 크기 (1kb, 10kb, 100kb, 1mb)
     * @return 바이너리 바이트 배열
     */
    @GetMapping("/binary", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun getDataAsBinary(
        @RequestParam(defaultValue = "unknown") requestId: String,
        @RequestParam(defaultValue = "1mb") size: String
    ): ResponseEntity<ByteArray> {
        val payload = dataService.getPayload(size)
        return ResponseEntity.ok()
            .header("X-Request-Id", requestId)
            .header("X-Payload-Size", payload.size.toString())
            .header("X-Timestamp", System.currentTimeMillis().toString())
            .body(payload)
    }

    /**
     * Protobuf 형식으로 페이로드 반환 (http-proto 경로)
     *
     * gRPC 단건(GetData)과 같은 DataResponse 메시지를 HTTP/1.1로 보낸다.
     *
     * @param requestId 요청 ID
     * @param size 페이로드 크기 (1kb, 10kb, 100kb, 1mb)
     * @return Protobuf로 직렬화한 DataResponse
     */
    @GetMapping("/proto", produces = [PROTOBUF_MEDIA_TYPE])
    fun getDataAsProto(
        @RequestParam(defaultValue = "unknown") requestId: String,
        @RequestParam(defaultValue = "1mb") size: String
    ): ResponseEntity<ByteArray> {
        val response = dataService.buildDataResponseProto(requestId, size)
        return ResponseEntity.ok(response.toByteArray())
    }

    /**
     * 서버 정보 반환
     *
     * @return 서버 ID, 지원 크기 목록 등
     */
    @GetMapping("/info")
    fun getInfo(): Map<String, Any> {
        return mapOf(
            "service" to "dataServer",
            "serverId" to dataService.serverId,
            "supportedSizes" to DataService.PAYLOAD_SIZES.keys,
            "supportedComplexities" to listOf("simple", "medium", "complex", "ultra", "extreme")
        )
    }

    // ============================================
    // Phase 5: 복잡한 데이터 구조 엔드포인트
    // Phase 6: Ultra/Extreme 복잡도 추가
    // ============================================

    /**
     * 복잡한 데이터를 JSON 형식으로 반환
     *
     * @param requestId 요청 ID
     * @param complexity 복잡도 (simple, medium, complex, ultra, extreme)
     * @return ComplexDataJsonResponse (복잡도에 따른 데이터 포함)
     */
    @GetMapping("/complex/json")
    fun getComplexDataAsJson(
        @RequestParam(defaultValue = "unknown") requestId: String,
        @RequestParam(defaultValue = "simple") complexity: String
    ): ResponseEntity<*> {
        val timestamp = System.currentTimeMillis()

        return when (complexity.lowercase()) {
            "simple" -> {
                val data = dataService.generateSimpleData(requestId)
                val response = ComplexDataJsonResponse(
                    requestId = requestId,
                    timestamp = timestamp,
                    serializedSize = estimateJsonSize(data),
                    complexity = "simple",
                    data = data
                )
                ResponseEntity.ok(response)
            }
            "medium" -> {
                val data = dataService.generateMediumData(requestId)
                val response = ComplexDataJsonResponse(
                    requestId = requestId,
                    timestamp = timestamp,
                    serializedSize = estimateJsonSize(data),
                    complexity = "medium",
                    data = data
                )
                ResponseEntity.ok(response)
            }
            "complex" -> {
                val data = dataService.generateComplexData(requestId)
                val response = ComplexDataJsonResponse(
                    requestId = requestId,
                    timestamp = timestamp,
                    serializedSize = estimateJsonSize(data),
                    complexity = "complex",
                    data = data
                )
                ResponseEntity.ok(response)
            }
            // ============================================
            // Phase 6: Ultra/Extreme 복잡도 처리
            // ============================================
            "ultra" -> {
                val data = dataService.generateUltraData(requestId)
                val response = ComplexDataJsonResponse(
                    requestId = requestId,
                    timestamp = timestamp,
                    serializedSize = estimateJsonSize(data),
                    complexity = "ultra",
                    data = data
                )
                ResponseEntity.ok(response)
            }
            "extreme" -> {
                val data = dataService.generateExtremeData(requestId)
                val response = ComplexDataJsonResponse(
                    requestId = requestId,
                    timestamp = timestamp,
                    serializedSize = estimateJsonSize(data),
                    complexity = "extreme",
                    data = data
                )
                ResponseEntity.ok(response)
            }
            else -> {
                ResponseEntity.badRequest().body(
                    mapOf("error" to "Invalid complexity. Use: simple, medium, complex, ultra, extreme")
                )
            }
        }
    }

    /**
     * 복잡한 데이터를 Protobuf Binary 형식으로 반환
     *
     * 메시지는 gRPC 단건(`GetComplexData`)과 같은 [ComplexDataProtoBuilder]로 만든다.
     *
     * @param requestId 요청 ID
     * @param complexity 복잡도 (simple, medium, complex, ultra, extreme)
     * @return Protobuf 바이너리 바이트 배열, 복잡도 값이 잘못되면 400
     */
    @GetMapping("/complex/binary", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun getComplexDataAsBinary(
        @RequestParam(defaultValue = "unknown") requestId: String,
        @RequestParam(defaultValue = "simple") complexity: String
    ): ResponseEntity<ByteArray> {
        val response = complexDataProtoBuilder.build(requestId, complexity)
            ?: return ResponseEntity.badRequest().build()
        val responseBytes = response.toByteArray()

        return ResponseEntity.ok()
            .header("X-Request-Id", requestId)
            .header("X-Complexity", complexity)
            .header("X-Payload-Size", responseBytes.size.toString())
            .header("X-Timestamp", response.timestamp.toString())
            .body(responseBytes)
    }

    /**
     * JSON 크기 추정 (대략적)
     */
    private fun estimateJsonSize(data: Any): Int {
        // 실제로는 Jackson ObjectMapper를 사용해 정확히 계산할 수 있음
        // 여기서는 대략적인 추정치 반환
        return when (data) {
            is SimpleDataDto -> 150
            is MediumDataDto -> 800
            is ComplexDataDto -> 5000
            // Phase 6 추가
            is UltraDataDto -> 15000    // ~15KB
            is ExtremeDataDto -> 50000   // ~50KB
            else -> 0
        }
    }
}