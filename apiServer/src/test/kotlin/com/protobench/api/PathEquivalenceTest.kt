package com.protobench.api

import com.google.protobuf.ByteString
import com.protobench.api.benchmark.BenchmarkCollector
import com.protobench.api.client.GrpcDataClient
import com.protobench.api.client.HttpDataClient
import com.protobench.api.service.ApiDataService
import com.protobench.proto.*
import com.sun.net.httpserver.HttpServer
import io.grpc.Server
import io.grpc.ServerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetSocketAddress
import java.net.URI

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
    @ValueSource(strings = ["simple", "medium", "complex", "ultra", "extreme"])
    fun `복잡도 축 - HTTP Protobuf와 gRPC가 같은 ComplexDataResponse 객체를 내놓는다`(complexity: String) = runBlocking {
        val viaHttp = httpClient.getComplexDataBinary("req-2", complexity)
        val viaGrpc = grpcClient.getComplexData("req-2", complexity)

        assertEquals(viaGrpc, viaHttp)
        assertEquals(complexity.uppercase(), viaHttp.dataCase.name)

        assertEquals(
            service.getComplexDataGrpc(complexity).serializedSize,
            service.getComplexDataHttpBinary(complexity).serializedSize
        )
    }

    // ---- 가짜 dataServer의 응답 (요청마다 같은 값) ----

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

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, body: ByteArray) {
        ex.responseHeaders.add("Content-Type", "application/x-protobuf")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }
}
