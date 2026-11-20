package com.protobench.api.service

import com.protobench.api.benchmark.BenchmarkCollector
import com.protobench.api.client.GrpcDataClient
import com.protobench.api.client.HttpDataClient
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.util.UUID

data class ApiResponse(
    val requestId: String,
    val protocol: String,
    val payloadSize: Int,
    val latencyMs: Double
)

/**
 * Phase 5: 복잡한 데이터 API 응답
 */
data class ComplexApiResponse(
    val requestId: String,
    val protocol: String,
    val complexity: String,
    val serializedSize: Int,
    val latencyMs: Double
)

/**
 * API 데이터 서비스
 *
 * HTTP/gRPC 클라이언트를 통해 dataServer에 요청하고,
 * 벤치마크 메트릭을 수집한다.
 *
 * Phase 5: 복잡한 데이터 구조 서비스 메서드 추가
 */
@Service
class ApiDataService(
    private val httpClient: HttpDataClient,
    private val grpcClient: GrpcDataClient,
    private val benchmarkCollector: BenchmarkCollector
) {

    companion object {
        /** 크기를 재지 않았음을 뜻하는 값 (복잡도 축 grpc-unary) */
        const val SIZE_UNKNOWN = -1
    }

    /**
     * HTTP JSON 방식으로 데이터 요청
     *
     * @param size 페이로드 크기 (1kb, 10kb, 100kb, 1mb)
     * @return ApiResponse
     */
    suspend fun getDataHttpJson(size: String = "1mb"): ApiResponse {
        val requestId = UUID.randomUUID().toString()
        val startNanos = System.nanoTime()

        return try {
            val response = httpClient.getDataJson(requestId, size)
            val latencyNanos = System.nanoTime() - startNanos

            benchmarkCollector.record(latencyNanos, response.payloadSize.toLong(), true)

            ApiResponse(
                requestId = requestId,
                protocol = "HTTP/JSON",
                payloadSize = response.payloadSize,
                latencyMs = latencyNanos / 1_000_000.0
            )
        } catch (e: Exception) {
            val latencyNanos = System.nanoTime() - startNanos
            benchmarkCollector.record(latencyNanos, 0, false)
            throw e
        }
    }

    /**
     * HTTP Binary 방식으로 데이터 요청
     *
     * @param size 페이로드 크기 (1kb, 10kb, 100kb, 1mb)
     * @return ApiResponse
     */
    suspend fun getDataHttpBinary(size: String = "1mb"): ApiResponse {
        val requestId = UUID.randomUUID().toString()
        val startNanos = System.nanoTime()

        return try {
            val response = httpClient.getDataBinary(requestId, size)
            val latencyNanos = System.nanoTime() - startNanos

            benchmarkCollector.record(latencyNanos, response.size.toLong(), true)

            ApiResponse(
                requestId = requestId,
                protocol = "HTTP/Binary",
                payloadSize = response.size,
                latencyMs = latencyNanos / 1_000_000.0
            )
        } catch (e: Exception) {
            val latencyNanos = System.nanoTime() - startNanos
            benchmarkCollector.record(latencyNanos, 0, false)
            throw e
        }
    }

    /**
     * HTTP/1.1 + Protobuf 방식으로 데이터 요청 (http-proto 경로)
     *
     * @param size 페이로드 크기 (1kb, 10kb, 100kb, 1mb)
     * @return ApiResponse
     */
    suspend fun getDataHttpProto(size: String = "1mb"): ApiResponse {
        val requestId = UUID.randomUUID().toString()
        val startNanos = System.nanoTime()

        return try {
            val response = httpClient.getDataProto(requestId, size)
            val latencyNanos = System.nanoTime() - startNanos

            benchmarkCollector.record(latencyNanos, response.payloadSize.toLong(), true)

            ApiResponse(
                requestId = requestId,
                protocol = "HTTP/Protobuf",
                payloadSize = response.payloadSize,
                latencyMs = latencyNanos / 1_000_000.0
            )
        } catch (e: Exception) {
            val latencyNanos = System.nanoTime() - startNanos
            benchmarkCollector.record(latencyNanos, 0, false)
            throw e
        }
    }

    /**
     * gRPC Unary 방식으로 데이터 요청
     *
     * @param size 페이로드 크기 (1kb, 10kb, 100kb, 1mb)
     * @return ApiResponse
     */
    suspend fun getDataGrpc(size: String = "1mb"): ApiResponse {
        val requestId = UUID.randomUUID().toString()
        val startNanos = System.nanoTime()

        return try {
            val response = grpcClient.getData(requestId, size)
            val latencyNanos = System.nanoTime() - startNanos

            benchmarkCollector.record(latencyNanos, response.payloadSize.toLong(), true)

            ApiResponse(
                requestId = requestId,
                protocol = "gRPC/Unary",
                payloadSize = response.payloadSize,
                latencyMs = latencyNanos / 1_000_000.0
            )
        } catch (e: Exception) {
            val latencyNanos = System.nanoTime() - startNanos
            benchmarkCollector.record(latencyNanos, 0, false)
            throw e
        }
    }

    /**
     * gRPC Streaming 방식으로 데이터 요청
     *
     * @param size 페이로드 크기 (1kb, 10kb, 100kb, 1mb)
     * @return ApiResponse
     */
    suspend fun getDataGrpcStream(size: String = "1mb"): ApiResponse {
        val requestId = UUID.randomUUID().toString()
        val startNanos = System.nanoTime()

        return try {
            val chunks = grpcClient.getDataStream(requestId, size).toList()
            val latencyNanos = System.nanoTime() - startNanos
            val totalSize = chunks.sumOf { it.chunk.size() }

            benchmarkCollector.record(latencyNanos, totalSize.toLong(), true)

            ApiResponse(
                requestId = requestId,
                protocol = "gRPC/Stream",
                payloadSize = totalSize,
                latencyMs = latencyNanos / 1_000_000.0
            )
        } catch (e: Exception) {
            val latencyNanos = System.nanoTime() - startNanos
            benchmarkCollector.record(latencyNanos, 0, false)
            throw e
        }
    }

    // ============================================
    // Phase 5: 복잡한 데이터 구조 서비스
    // ============================================

    /**
     * HTTP JSON 방식으로 복잡한 데이터 요청
     *
     * @param complexity 복잡도 (simple, medium, complex)
     * @return ComplexApiResponse
     */
    suspend fun getComplexDataHttpJson(complexity: String = "simple"): ComplexApiResponse {
        val requestId = UUID.randomUUID().toString()
        val startNanos = System.nanoTime()

        return try {
            val result = httpClient.getComplexDataJson(requestId, complexity)
            val latencyNanos = System.nanoTime() - startNanos

            // dataServer가 담는 serializedSize는 추정치라 받은 본문의 바이트 수를 기록한다
            benchmarkCollector.record(latencyNanos, result.receivedBytes.toLong(), true)

            ComplexApiResponse(
                requestId = requestId,
                protocol = "HTTP/JSON",
                complexity = complexity,
                serializedSize = result.receivedBytes,
                latencyMs = latencyNanos / 1_000_000.0
            )
        } catch (e: Exception) {
            val latencyNanos = System.nanoTime() - startNanos
            benchmarkCollector.record(latencyNanos, 0, false)
            throw e
        }
    }

    /**
     * HTTP Binary (Protobuf) 방식으로 복잡한 데이터 요청
     *
     * @param complexity 복잡도 (simple, medium, complex)
     * @return ComplexApiResponse
     */
    suspend fun getComplexDataHttpBinary(complexity: String = "simple"): ComplexApiResponse {
        val requestId = UUID.randomUUID().toString()
        val startNanos = System.nanoTime()

        return try {
            val result = httpClient.getComplexDataBinary(requestId, complexity)
            val latencyNanos = System.nanoTime() - startNanos

            // 받은 본문의 바이트 수(= 보낸 Protobuf 바이트 수). serializedSize로 메시지를 다시 훑지 않는다
            benchmarkCollector.record(latencyNanos, result.receivedBytes.toLong(), true)

            ComplexApiResponse(
                requestId = requestId,
                protocol = "HTTP/Binary(Protobuf)",
                complexity = complexity,
                serializedSize = result.receivedBytes,
                latencyMs = latencyNanos / 1_000_000.0
            )
        } catch (e: Exception) {
            val latencyNanos = System.nanoTime() - startNanos
            benchmarkCollector.record(latencyNanos, 0, false)
            throw e
        }
    }

    /**
     * gRPC Unary 방식으로 복잡한 데이터 요청
     *
     * @param complexity 복잡도 (simple, medium, complex)
     * @return ComplexApiResponse
     */
    suspend fun getComplexDataGrpc(complexity: String = "simple"): ComplexApiResponse {
        val requestId = UUID.randomUUID().toString()
        val startNanos = System.nanoTime()

        return try {
            grpcClient.getComplexData(requestId, complexity)
            val latencyNanos = System.nanoTime() - startNanos

            // gRPC 스텁은 받은 바이트 수를 내주지 않는다. serializedSize로 메시지를 다시 훑지 않고
            // 값이 없음(SIZE_UNKNOWN)을 기록한다. 전송량은 k6가 잰 값을 쓴다.
            benchmarkCollector.record(latencyNanos, SIZE_UNKNOWN.toLong(), true)

            ComplexApiResponse(
                requestId = requestId,
                protocol = "gRPC/Unary",
                complexity = complexity,
                serializedSize = SIZE_UNKNOWN,
                latencyMs = latencyNanos / 1_000_000.0
            )
        } catch (e: Exception) {
            val latencyNanos = System.nanoTime() - startNanos
            benchmarkCollector.record(latencyNanos, 0, false)
            throw e
        }
    }

    /**
     * gRPC Stream 방식으로 복잡한 데이터 요청
     *
     * 복잡한 데이터는 청크로 나누지 않고 Unary와 동일하게 처리한다.
     *
     * @param complexity 복잡도 (simple, medium, complex)
     * @return ComplexApiResponse
     */
    suspend fun getComplexDataGrpcStream(complexity: String = "simple"): ComplexApiResponse {
        val requestId = UUID.randomUUID().toString()
        val startNanos = System.nanoTime()

        return try {
            val response = grpcClient.getComplexData(requestId, complexity)
            val latencyNanos = System.nanoTime() - startNanos

            benchmarkCollector.record(latencyNanos, response.serializedSize.toLong(), true)

            ComplexApiResponse(
                requestId = requestId,
                protocol = "gRPC/Stream",
                complexity = complexity,
                serializedSize = response.serializedSize,
                latencyMs = latencyNanos / 1_000_000.0
            )
        } catch (e: Exception) {
            val latencyNanos = System.nanoTime() - startNanos
            benchmarkCollector.record(latencyNanos, 0, false)
            throw e
        }
    }
}