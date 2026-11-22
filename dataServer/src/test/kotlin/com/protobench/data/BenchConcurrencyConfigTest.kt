package com.protobench.data

import com.protobench.data.config.BenchConfigLogger
import com.protobench.data.config.GrpcServerConfig
import com.protobench.data.config.GrpcHandlerDispatcher
import com.protobench.proto.ComplexDataRequest
import com.protobench.proto.DataServiceGrpcKt
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.test.context.ActiveProfiles
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 측정 설정 두 가지(default, matched)가 실제로 Tomcat과 gRPC 서버에 적용되는지 확인한다.
 * gRPC 서버 실행기는 두 설정 모두 기본값이고, matched는 서비스 코루틴의 디스패처만 고정 풀로 바꾼다.
 */
class BenchConcurrencyConfigTest {

    @Nested
    @SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = ["grpc.server.port=0", "bench.max-concurrency=37"]
    )
    @ActiveProfiles("matched")
    inner class Matched {
        @Autowired lateinit var ctx: ServletWebServerApplicationContext
        @Autowired lateinit var grpcHandlerDispatcher: GrpcHandlerDispatcher
        @Autowired lateinit var grpcServerConfig: GrpcServerConfig

        @Test
        fun `Tomcat 최대 스레드와 서비스 코루틴의 고정 풀이 같은 값이다`() {
            assertEquals(37, BenchConfigLogger.tomcatMaxThreads(ctx))
            val executor = grpcHandlerDispatcher.handlerExecutor!!
            assertEquals(37, executor.maximumPoolSize)
            val dispatcher = grpcHandlerDispatcher.coroutineContext[ContinuationInterceptor] as ExecutorCoroutineDispatcher
            assertSame(executor, dispatcher.executor)
            // 그 디스패처에서 도는 코루틴은 고정 풀의 스레드(grpc-bench-*)에 있다
            val threadName = runBlocking { withContext(grpcHandlerDispatcher.coroutineContext) { Thread.currentThread().name } }
            assertTrue(threadName.startsWith("grpc-bench-"), threadName)

            // 실제 호출의 서비스 코루틴이 그 풀을 거친다
            val before = executor.completedTaskCount
            callGetComplexData(grpcServerConfig.port())
            assertTrue(executor.completedTaskCount > before, "gRPC 서비스 코루틴이 고정 풀에서 돌지 않았다")
        }

        @Test
        fun `gRPC 서버 실행기는 정하지 않는다(grpc-java 기본값)`() {
            assertEquals(DEFAULT_EXECUTOR_POOL, serverExecutorPoolClass(grpcServerConfig))
        }

        @LocalServerPort var port: Int = 0

        @Test
        fun `HTTP 연결을 요청 수로 끊지 않는다`() {
            val keepAlive = BenchConfigLogger.tomcatKeepAlive(ctx)!!
            assertEquals(-1, keepAlive.maxKeepAliveRequests)
            assertEquals(-1, keepAlive.keepAliveTimeoutMs)
            // 연결 하나로 150요청을 보내도 서버가 끊지 않는다(기본값이면 100번째에서 끊는다)
            assertEquals(null, requestsUntilServerCloses(port, 150))
        }
    }

    @Nested
    @SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = ["grpc.server.port=0"]
    )
    inner class Default {
        @Autowired lateinit var ctx: ServletWebServerApplicationContext
        @Autowired lateinit var grpcHandlerDispatcher: GrpcHandlerDispatcher
        @Autowired lateinit var grpcServerConfig: GrpcServerConfig

        @Test
        fun `기본값을 바꾸지 않는다`() {
            assertEquals(200, BenchConfigLogger.tomcatMaxThreads(ctx))
            assertNull(grpcHandlerDispatcher.handlerExecutor)
            assertSame(EmptyCoroutineContext, grpcHandlerDispatcher.coroutineContext)
            assertEquals(DEFAULT_EXECUTOR_POOL, serverExecutorPoolClass(grpcServerConfig))
        }

        @LocalServerPort var port: Int = 0

        @Test
        fun `HTTP 연결 유지는 Tomcat 기본값(100요청)이다`() {
            assertEquals(100, BenchConfigLogger.tomcatKeepAlive(ctx)!!.maxKeepAliveRequests)
            assertEquals(100, requestsUntilServerCloses(port, 150))
        }
    }

    companion object {
        /**
         * grpc-java 기본 서버 실행기의 풀 클래스(`ServerImplBuilder.DEFAULT_EXECUTOR_POOL`).
         * `builder.executor(e)`를 부르면 `FixedObjectPool`이 된다.
         */
        const val DEFAULT_EXECUTOR_POOL = "io.grpc.internal.SharedResourcePool"

        /** 실행 중인 gRPC 서버가 쓰는 실행기 풀의 클래스 이름(grpc-java 내부 필드를 읽는다) */
        fun serverExecutorPoolClass(config: GrpcServerConfig): String {
            val server = GrpcServerConfig::class.java.getDeclaredField("server")
                .apply { isAccessible = true }.get(config) as Server
            val pool = server.javaClass.getDeclaredField("executorPool")
                .apply { isAccessible = true }.get(server)
            return pool.javaClass.name
        }

        fun callGetComplexData(port: Int) {
            val channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()
            try {
                val response = runBlocking {
                    DataServiceGrpcKt.DataServiceCoroutineStub(channel).getComplexData(
                        ComplexDataRequest.newBuilder().setRequestId("r").setComplexity("simple").build()
                    )
                }
                assertEquals("r", response.requestId)
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            }
        }

        /**
         * 연결 하나로 요청을 최대 [max]번 보내고, 서버가 `Connection: close`로 끊겠다고 한 요청이 몇 번째인지 돌려준다.
         * 끝까지 끊지 않으면 null.
         */
        fun requestsUntilServerCloses(port: Int, max: Int): Int? = Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            for (i in 1..max) {
                out.write("GET /data/binary?requestId=r$i&size=1kb HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                out.flush()
                val head = readHead(input)
                assertTrue(head.startsWith("HTTP/1.1 200"), head)
                val length = Regex("(?im)^content-length:\\s*(\\d+)").find(head)!!.groupValues[1].toInt()
                input.readNBytes(length)
                if (Regex("(?im)^connection:\\s*close").containsMatchIn(head)) return@use i
            }
            null
        }

        private fun readHead(input: BufferedInputStream): String {
            val buf = ByteArrayOutputStream()
            var last4 = 0
            while (last4 != 0x0D0A0D0A) {
                val b = input.read()
                check(b >= 0) { "연결이 닫혔다" }
                buf.write(b)
                last4 = (last4 shl 8) or b
            }
            return buf.toString(Charsets.ISO_8859_1)
        }
    }
}
