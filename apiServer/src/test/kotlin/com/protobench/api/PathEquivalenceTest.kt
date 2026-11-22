package com.protobench.api

import com.google.protobuf.ByteString
import com.protobench.api.benchmark.BenchmarkCollector
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.protobench.api.client.*
import com.protobench.api.client.GrpcDataClient
import com.protobench.api.client.HttpDataClient
import com.protobench.api.service.ApiDataService
import com.protobench.proto.*
import com.sun.net.httpserver.HttpServer
import io.grpc.Server
import io.grpc.ServerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetSocketAddress
import java.net.URI
import java.util.Base64

/**
 * apiServer가 HTTP + Protobuf 응답을 gRPC 응답과 같은 수준(같은 Protobuf 메시지 객체)까지 푸는지 확인한다.
 *
 * 가짜 dataServer가 같은 요청에 같은 메시지를 HTTP(/data/proto, /data/complex/binary)와
 * gRPC(GetData, GetComplexData)로 돌려주고, 두 클라이언트가 같은 객체를 내놓는지 비교한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PathEquivalenceTest {

    private lateinit var httpServer: HttpServer
    private lateinit var grpcServer: Server
    private lateinit var httpClient: HttpDataClient
    private lateinit var grpcClient: GrpcDataClient
    private lateinit var service: ApiDataService

    @BeforeAll
    fun startFakeDataServer() {
        httpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/data/proto") { ex ->
                val q = query(ex.requestURI)
                respond(ex, dataResponse(q.getValue("requestId"), q.getValue("size")).toByteArray())
            }
            createContext("/data/json") { ex ->
                val q = query(ex.requestURI)
                val msg = dataResponse(q.getValue("requestId"), q.getValue("size"))
                val json = """{"requestId":"${msg.requestId}","payload":"${Base64.getEncoder().encodeToString(msg.payload.toByteArray())}",""" +
                    """"timestamp":${msg.timestamp},"payloadSize":${msg.payloadSize}}"""
                respond(ex, json.toByteArray(), "application/json")
            }
            createContext("/data/complex/json") { ex ->
                val q = query(ex.requestURI)
                respond(ex, complexJson(q.getValue("complexity")), "application/json")
            }
            createContext("/data/complex/binary") { ex ->
                val q = query(ex.requestURI)
                respond(ex, complexResponse(q.getValue("requestId"), q.getValue("complexity")).toByteArray())
            }
            start()
        }
        grpcServer = ServerBuilder.forPort(0)
            .addService(object : DataServiceGrpcKt.DataServiceCoroutineImplBase() {
                override suspend fun getData(request: DataRequest) =
                    dataResponse(request.requestId, request.size)

                override suspend fun getComplexData(request: ComplexDataRequest) =
                    complexResponse(request.requestId, request.complexity)
            })
            .build()
            .start()

        httpClient = HttpDataClient("http://localhost:${httpServer.address.port}")
        grpcClient = GrpcDataClient("localhost", grpcServer.port).also { it.init() }
        service = ApiDataService(httpClient, grpcClient, BenchmarkCollector())
    }

    @AfterAll
    fun stopFakeDataServer() {
        grpcClient.shutdown()
        grpcServer.shutdownNow()
        httpServer.stop(0)
    }

    @ParameterizedTest
    @ValueSource(strings = ["1kb", "10kb", "1mb"])
    fun `크기 축 - http-proto와 grpc-unary가 같은 DataResponse 객체를 내놓는다`(size: String) = runBlocking {
        val viaHttp = httpClient.getDataProto("req-1", size)
        val viaGrpc = grpcClient.getData("req-1", size)

        assertEquals(viaGrpc, viaHttp)
        assertEquals(PAYLOAD_SIZES.getValue(size), viaHttp.payloadSize)
        assertEquals(PAYLOAD_SIZES.getValue(size), viaHttp.payload.size())

        // 서비스 계층이 기록·반환하는 크기도 같다
        assertEquals(service.getDataGrpc(size).payloadSize, service.getDataHttpProto(size).payloadSize)
    }

    @ParameterizedTest
    @ValueSource(strings = ["1kb", "10kb", "1mb"])
    fun `크기 축 - http-json이 Base64를 원래 바이트로 되돌린다`(size: String) = runBlocking {
        val viaJson = httpClient.getDataJson("req-3", size)
        val viaGrpc = grpcClient.getData("req-3", size)

        assertArrayEquals(viaGrpc.payload.toByteArray(), viaJson.payload)
        assertEquals(PAYLOAD_SIZES.getValue(size), viaJson.payload.size)
        assertEquals(viaGrpc.payloadSize, viaJson.payloadSize)
        assertEquals(service.getDataGrpc(size).payloadSize, service.getDataHttpJson(size).payloadSize)
    }

    @ParameterizedTest
    @ValueSource(strings = ["simple", "medium", "complex", "ultra", "extreme"])
    fun `복잡도 축 - HTTP Protobuf와 gRPC가 같은 ComplexDataResponse 객체를 내놓는다`(complexity: String) = runBlocking {
        val viaHttp = httpClient.getComplexDataBinary("req-2", complexity).response
        val viaGrpc = grpcClient.getComplexData("req-2", complexity)

        assertEquals(viaGrpc, viaHttp)
        assertEquals(complexity.uppercase(), viaHttp.dataCase.name)
    }

    @ParameterizedTest
    @ValueSource(strings = ["simple", "medium", "complex", "ultra", "extreme"])
    fun `복잡도 축 - http-proto는 받은 바이트 수를, grpc-unary는 값 없음을 기록한다`(complexity: String) = runBlocking {
        val collector = BenchmarkCollector()
        val svc = ApiDataService(httpClient, grpcClient, collector)

        collector.start("HTTP/Binary(Protobuf)", "size-check")
        val proto = svc.getComplexDataHttpBinary(complexity)
        val protoRecorded = collector.end()!!
        // 가짜 서버가 보낸 바이트 수 (requestId는 서비스가 만든 UUID — 길이가 같다)
        val sent = complexResponse(proto.requestId, complexity).serializedSize
        assertEquals(sent, proto.serializedSize)
        assertEquals(sent.toLong(), protoRecorded.dataTransfer.totalBytes)

        collector.start("gRPC/Unary", "size-check")
        val grpc = svc.getComplexDataGrpc(complexity)
        val grpcRecorded = collector.end()!!
        assertEquals(ApiDataService.SIZE_UNKNOWN, grpc.serializedSize)
        assertEquals(-1L, grpcRecorded.dataTransfer.totalBytes)
    }

    @ParameterizedTest
    @ValueSource(strings = ["simple", "medium", "complex", "ultra", "extreme"])
    fun `복잡도 축 - http-json이 기록하는 크기는 실제로 받은 본문의 바이트 수다`(complexity: String) = runBlocking {
        val body = complexJson(complexity)
        val collector = BenchmarkCollector()
        val svc = ApiDataService(httpClient, grpcClient, collector)

        collector.start("HTTP/JSON", "size-check")
        val result = svc.getComplexDataHttpJson(complexity)
        val recorded = collector.end()!!

        // dataServer가 JSON에 담는 serializedSize(고정 추정치)가 아니다
        assertEquals(body.size, result.serializedSize)
        assertEquals(body.size.toLong(), recorded.dataTransfer.totalBytes)
    }

    @ParameterizedTest
    @ValueSource(strings = ["simple", "medium", "complex", "ultra", "extreme"])
    fun `복잡도 축 - http-json이 타입이 있는 객체로 풀고 필드 값이 보낸 것과 같다`(complexity: String) = runBlocking {
        val expectedClass = mapOf(
            "simple" to SimpleDataDto::class, "medium" to MediumDataDto::class, "complex" to ComplexDataDto::class,
            "ultra" to UltraDataDto::class, "extreme" to ExtremeDataDto::class
        ).getValue(complexity)
        val mapper = ObjectMapper().registerKotlinModule()
        val sent = mapper.readTree(complexJson(complexity))

        val response = httpClient.getComplexDataJson("req-4", complexity).response

        assertEquals(expectedClass, response.data::class)
        // 객체를 다시 트리로 바꾸면 보낸 JSON과 같다(모든 필드가 같은 값으로 들어갔다). 숫자는 값으로 비교한다.
        val numericAware = Comparator<JsonNode> { a, b ->
            if (a.isNumber && b.isNumber) a.decimalValue().compareTo(b.decimalValue()) else if (a == b) 0 else 1
        }
        val roundTrip = mapper.valueToTree<JsonNode>(response)
        assertTrue(sent.equals(numericAware, roundTrip), "보낸 JSON:\n$sent\n푼 객체:\n$roundTrip")
    }

    // ---- 가짜 dataServer의 응답 (요청마다 같은 값) ----

    /** 실제 dataServer가 보낸 JSON(배열은 2개, 큰 맵은 3개 항목까지 줄임) */
    private fun complexJson(complexity: String): ByteArray =
        javaClass.getResourceAsStream("/complex-json/$complexity.json")!!.use { it.readBytes() }

    private val PAYLOAD_SIZES = mapOf("1kb" to 1024, "10kb" to 10 * 1024, "1mb" to 1024 * 1024)

    private fun dataResponse(requestId: String, size: String): DataResponse {
        val n = PAYLOAD_SIZES.getValue(size)
        return DataResponse.newBuilder()
            .setRequestId(requestId)
            .setPayload(ByteString.copyFrom(ByteArray(n) { (it % 251).toByte() }))
            .setTimestamp(1_700_000_000_000L)
            .setPayloadSize(n)
            .build()
    }

    private fun complexResponse(requestId: String, complexity: String): ComplexDataResponse {
        val b = ComplexDataResponse.newBuilder().setRequestId(requestId).setTimestamp(1_700_000_000_000L)
        val address = Address.newBuilder().setCity("Seoul").setStreet("1 Main").setZipcode("01234").setCountry("KR")
        when (complexity) {
            "simple" -> b.setSimple(SimpleData.newBuilder().setId("u1").setName("n").setAge(30).setScore(1.5).setIsActive(true))
            "medium" -> b.setMedium(MediumData.newBuilder().setId("u1").addAllTags(listOf("a", "b")).setAddress(address).putMetadata("k", "v"))
            "complex" -> b.setComplex(
                ComplexData.newBuilder().setId("u1").setAddress(address).setBillingAddress(address)
                    .addOrders(Order.newBuilder().setOrderId("o1").setAmount(9.5).addItems(Item.newBuilder().setProductId("p1").setQuantity(2)))
                    .putScores("s", 7).addPermissions("read")
            )
            "ultra" -> b.setUltra(
                UltraData.newBuilder().setId("u1").setPriority(3)
                    .addAddresses(AddressWithContacts.newBuilder().setCity("Seoul").addContacts(Contact.newBuilder().setName("c")))
                    .addCategories(Category.newBuilder().setId("c1").addSubcategories(Subcategory.newBuilder().setId("s1").addItems("i")))
            )
            "extreme" -> b.setExtreme(
                ExtremeData.newBuilder().setId("u1").setTier("GOLD")
                    .addOrganizations(Organization.newBuilder().setId("o").addDepartments(
                        Department.newBuilder().setId("d").addTeams(Team.newBuilder().setId("t").addMembers(Member.newBuilder().setId("m")))
                    ))
            )
            else -> error("unknown complexity $complexity")
        }
        return b.setResponseSize(b.build().serializedSize).build()
    }

    private fun query(uri: URI): Map<String, String> =
        uri.rawQuery.split("&").associate { it.substringBefore("=") to it.substringAfter("=") }

    private fun respond(
        ex: com.sun.net.httpserver.HttpExchange,
        body: ByteArray,
        contentType: String = "application/x-protobuf"
    ) {
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }
}
