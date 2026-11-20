package com.protobench.data

import com.google.protobuf.Descriptors.FieldDescriptor.JavaType
import com.google.protobuf.Message
import com.protobench.data.controller.DataController
import com.protobench.data.grpc.GrpcDataService
import com.protobench.data.service.*
import com.protobench.proto.ComplexDataRequest
import com.protobench.proto.ComplexDataResponse
import com.protobench.proto.DataRequest
import com.protobench.proto.DataResponse
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.grpc.Status
import io.grpc.StatusException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Base64

/**
 * 같은 요청에 대해 HTTP 경로와 gRPC 경로가 같은 내용을 돌려주는지 확인한다.
 *
 * 크기 축: http-json, http-bytes, http-proto가 grpc-unary와 같은 페이로드를 보낸다.
 * 복잡도 축: HTTP + Protobuf 응답이 gRPC 응답과 같은 메시지 형태(필드, 반복 개수)를 가진다.
 * (복잡도 축의 값은 요청마다 무작위로 만들어지므로 값이 아니라 형태를 비교한다.)
 */
class PathEquivalenceTest {

    private lateinit var dataService: DataService
    private lateinit var grpcService: GrpcDataService
    private lateinit var mockMvc: MockMvc
    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun setUp() {
        dataService = DataService().also { it.init() }
        val builder = ComplexDataProtoBuilder(dataService)
        grpcService = GrpcDataService(dataService, builder)
        mockMvc = MockMvcBuilders.standaloneSetup(DataController(dataService, builder)).build()
    }

    private fun getBytes(uri: String): ByteArray =
        mockMvc.get(uri).andReturn().response.also { assertEquals(200, it.status, uri) }.contentAsByteArray

    @ParameterizedTest
    @ValueSource(strings = ["1kb", "10kb", "50kb", "100kb", "200kb", "500kb", "1mb"])
    fun `크기 축 - 네 경로가 같은 페이로드를 보낸다`(size: String) {
        val requestId = "req-$size"
        val grpc = runBlocking {
            grpcService.getData(DataRequest.newBuilder().setRequestId(requestId).setSize(size).build())
        }

        val proto = DataResponse.parseFrom(getBytes("/data/proto?requestId=$requestId&size=$size"))
        val bytes = getBytes("/data/binary?requestId=$requestId&size=$size")
        val json = objectMapper.readTree(getBytes("/data/json?requestId=$requestId&size=$size"))
        val jsonPayload = Base64.getDecoder().decode(json["payload"].asText())

        val expectedSize = DataService.PAYLOAD_SIZES[size]!!
        assertEquals(expectedSize, grpc.payloadSize)
        assertEquals(expectedSize, grpc.payload.size())

        // http-proto: gRPC와 같은 메시지(타임스탬프만 호출 시각이라 다르다)
        assertEquals(requestId, proto.requestId)
        assertEquals(grpc.payloadSize, proto.payloadSize)
        assertEquals(grpc.payload, proto.payload)
        assertEquals(grpc.toBuilder().clearTimestamp().build(), proto.toBuilder().clearTimestamp().build())

        // http-proto는 응답 스트림에 바로 직렬화하되 Content-Length를 정해 보낸다
        val protoResponse = mockMvc.get("/data/proto?requestId=$requestId&size=$size").andReturn().response
        assertEquals(proto.serializedSize, protoResponse.contentLength)
        assertEquals("application/x-protobuf", protoResponse.contentType)

        // http-bytes, http-json: 같은 바이트
        assertArrayEquals(grpc.payload.toByteArray(), bytes)
        assertArrayEquals(grpc.payload.toByteArray(), jsonPayload)
        assertEquals(grpc.payloadSize, json["payloadSize"].asInt())
    }

    @ParameterizedTest
    @ValueSource(strings = ["simple", "medium", "complex", "ultra", "extreme"])
    fun `복잡도 축 - HTTP Protobuf 응답이 gRPC 응답과 같은 형태다`(complexity: String) {
        val requestId = "req-$complexity"
        val grpc = runBlocking {
            grpcService.getComplexData(
                ComplexDataRequest.newBuilder().setRequestId(requestId).setComplexity(complexity).build()
            )
        }
        val http = ComplexDataResponse.parseFrom(
            getBytes("/data/complex/binary?requestId=$requestId&complexity=$complexity")
        )

        assertEquals(complexity.uppercase(), http.dataCase.name)
        assertEquals(grpc.dataCase, http.dataCase)
        assertEquals(requestId, http.requestId)
        assertEquals(grpc.requestId, http.requestId)
        assertEquals(shape(grpc), shape(http))
        // response_size 필드는 각 응답 자신의 직렬화 크기(response_size 필드 제외)를 담는다
        assertEquals(http.toBuilder().clearResponseSize().build().serializedSize, http.responseSize)
    }

    @ParameterizedTest
    @ValueSource(strings = ["simple", "medium", "complex", "ultra", "extreme"])
    fun `복잡도 축 - 같은 데이터를 주면 HTTP Protobuf와 gRPC가 같은 메시지를 만든다`(complexity: String) {
        // 생성기가 처음 만든 DTO를 다시 돌려주게 해서 두 경로가 같은 데이터를 받게 한다
        val fixed = FixedDataService().also { it.init() }
        val builder = ComplexDataProtoBuilder(fixed)
        val grpcService = GrpcDataService(fixed, builder)
        val mvc = MockMvcBuilders.standaloneSetup(DataController(fixed, builder)).build()

        val requestId = "req-same-$complexity"
        val grpc = runBlocking {
            grpcService.getComplexData(
                ComplexDataRequest.newBuilder().setRequestId(requestId).setComplexity(complexity).build()
            )
        }
        val http = ComplexDataResponse.parseFrom(
            mvc.get("/data/complex/binary?requestId=$requestId&complexity=$complexity")
                .andReturn().response.also { assertEquals(200, it.status) }.contentAsByteArray
        )

        // 타임스탬프만 호출 시각이라 다르다(varint 길이가 같아 response_size도 같다)
        assertEquals(grpc.toBuilder().clearTimestamp().build(), http.toBuilder().clearTimestamp().build())
    }

    @Test
    fun `복잡도 축 - 잘못된 복잡도 값은 세 경로 모두 거절한다`() {
        assertEquals(400, mockMvc.get("/data/complex/binary?requestId=r&complexity=nope").andReturn().response.status)
        assertEquals(400, mockMvc.get("/data/complex/json?requestId=r&complexity=nope").andReturn().response.status)
        val e = assertThrows(StatusException::class.java) {
            runBlocking {
                grpcService.getComplexData(ComplexDataRequest.newBuilder().setRequestId("r").setComplexity("nope").build())
            }
        }
        assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    }

    /** 복잡도별로 처음 만든 DTO를 계속 돌려주는 DataService */
    private class FixedDataService : DataService() {
        private val cache = mutableMapOf<String, Any>()
        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> once(key: String, make: () -> T): T = cache.getOrPut(key, make) as T
        override fun generateSimpleData(requestId: String) = once("simple") { super.generateSimpleData(requestId) }
        override fun generateMediumData(requestId: String) = once("medium") { super.generateMediumData(requestId) }
        override fun generateComplexData(requestId: String) = once("complex") { super.generateComplexData(requestId) }
        override fun generateUltraData(requestId: String) = once("ultra") { super.generateUltraData(requestId) }
        override fun generateExtremeData(requestId: String) = once("extreme") { super.generateExtremeData(requestId) }
    }

    /** 메시지의 형태: 메시지 필드의 존재 여부와 반복 필드의 개수를 경로별로 모은다. */
    private fun shape(msg: Message, prefix: String = ""): Map<String, Int> {
        val out = sortedMapOf<String, Int>()
        for (f in msg.descriptorForType.fields) {
            val path = prefix + f.name
            if (f.isRepeated) {
                val n = msg.getRepeatedFieldCount(f)
                out[path] = n
                if (f.javaType == JavaType.MESSAGE && !f.isMapField) {
                    for (i in 0 until n) out.putAll(shape(msg.getRepeatedField(f, i) as Message, "$path[$i]."))
                }
            } else if (f.javaType == JavaType.MESSAGE) {
                val has = msg.hasField(f)
                out[path] = if (has) 1 else 0
                if (has) out.putAll(shape(msg.getField(f) as Message, "$path."))
            }
        }
        return out
    }
}
