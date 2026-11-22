package com.protobench.data.grpc

import com.protobench.data.config.GrpcHandlerDispatcher
import com.protobench.data.service.*
import com.protobench.proto.*
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.stereotype.Service

/**
 * gRPC 데이터 서비스
 *
 * DataServiceGrpcKt.DataServiceCoroutineImplBase를 상속받아
 * Unary RPC와 Server Streaming RPC를 구현한다.
 *
 * Phase 5: 복잡한 데이터 구조 RPC 추가
 * Phase 6: Ultra/Extreme 복잡도 RPC 추가
 */
@Service
class GrpcDataService(
    private val dataService: DataService,
    private val complexDataProtoBuilder: ComplexDataProtoBuilder,
    grpcHandlerDispatcher: GrpcHandlerDispatcher = GrpcHandlerDispatcher(null)
) : DataServiceGrpcKt.DataServiceCoroutineImplBase(grpcHandlerDispatcher.coroutineContext) {

    /**
     * Unary RPC: 단일 요청 → 단일 응답
     *
     * 클라이언트가 요청한 크기의 페이로드를 한 번에 전송한다.
     *
     * @param request 요청 (requestId, size 포함)
     * @return DataResponse (전체 페이로드 포함)
     */
    override suspend fun getData(request: DataRequest): DataResponse {
        val size = if (request.size.isNullOrEmpty()) "1mb" else request.size
        return dataService.buildDataResponseProto(request.requestId, size)
    }

    /**
     * Server Streaming RPC: 단일 요청 → 다중 응답(스트림)
     *
     * 클라이언트가 요청한 크기의 페이로드를 64KB 청크로 나누어 스트리밍한다.
     *
     * @param request 요청 (requestId, size 포함)
     * @return Flow<DataChunk> (청크 스트림)
     */
    override fun getDataStream(request: DataRequest): Flow<DataChunk> = flow {
        val size = if (request.size.isNullOrEmpty()) "1mb" else request.size
        val chunks = dataService.getPayloadAsChunks(size)
        val totalChunks = chunks.size

        chunks.forEachIndexed { index, chunk ->
            val dataChunk = DataChunk.newBuilder()
                .setRequestId(request.requestId)
                .setChunk(ByteString.copyFrom(chunk))
                .setChunkIndex(index)
                .setTotalChunks(totalChunks)
                .setIsLast(index == totalChunks - 1)
                .build()

            emit(dataChunk)
        }
    }

    // ============================================
    // Phase 5: 복잡한 데이터 구조 RPC
    // Phase 6: Ultra/Extreme 복잡도 RPC 추가
    // ============================================

    /**
     * 복잡한 데이터 RPC: 복잡도에 따른 데이터 반환
     *
     * 메시지는 HTTP + Protobuf 경로(`/data/complex/binary`)와 같은 [ComplexDataProtoBuilder]로 만든다.
     * 복잡도 값이 비어 있으면 simple(HTTP 경로의 기본값과 같음), 잘못되면 INVALID_ARGUMENT
     * (HTTP 경로는 400).
     *
     * @param request 요청 (requestId, complexity 포함)
     * @return ComplexDataResponse (복잡도에 따른 데이터 포함)
     */
    override suspend fun getComplexData(request: ComplexDataRequest): ComplexDataResponse {
        val complexity = if (request.complexity.isNullOrEmpty()) "simple" else request.complexity
        return complexDataProtoBuilder.build(request.requestId, complexity)
            ?: throw StatusException(
                Status.INVALID_ARGUMENT.withDescription(
                    "Invalid complexity. Use: ${ComplexDataProtoBuilder.COMPLEXITIES.joinToString(", ")}"
                )
            )
    }
}
