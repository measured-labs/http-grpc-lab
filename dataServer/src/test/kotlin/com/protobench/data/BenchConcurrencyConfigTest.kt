package com.protobench.data

import com.protobench.data.config.BenchConfigLogger
import com.protobench.data.config.GrpcServerConfig
import com.protobench.data.config.GrpcServerExecutor
import com.protobench.proto.ComplexDataRequest
import com.protobench.proto.DataServiceGrpcKt
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 측정 설정 두 가지(default, matched)가 실제로 Tomcat과 gRPC 서버에 적용되는지 확인한다.
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
        @Autowired lateinit var grpcServerExecutor: GrpcServerExecutor
        @Autowired lateinit var grpcServerConfig: GrpcServerConfig

        @Test
        fun `Tomcat 최대 스레드와 gRPC 서버 실행기가 같은 값이다`() {
            assertEquals(37, BenchConfigLogger.tomcatMaxThreads(ctx))
            val executor = grpcServerExecutor.executor!!
            assertEquals(37, executor.maximumPoolSize)
            // gRPC 서비스 코루틴도 같은 실행기에서 돈다
            val dispatcher = grpcServerExecutor.coroutineContext[ContinuationInterceptor] as ExecutorCoroutineDispatcher
            assertSame(executor, dispatcher.executor)

            // 실제 호출이 그 실행기를 거친다
            val before = executor.completedTaskCount
            val channel = ManagedChannelBuilder.forAddress("localhost", grpcServerConfig.port()).usePlaintext().build()
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
            assertTrue(executor.completedTaskCount > before, "gRPC 호출이 설정한 실행기에서 돌지 않았다")
        }
    }

    @Nested
    @SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = ["grpc.server.port=0"]
    )
    inner class Default {
        @Autowired lateinit var ctx: ServletWebServerApplicationContext
        @Autowired lateinit var grpcServerExecutor: GrpcServerExecutor

        @Test
        fun `기본값을 바꾸지 않는다`() {
            assertEquals(200, BenchConfigLogger.tomcatMaxThreads(ctx))
            assertNull(grpcServerExecutor.executor)
            assertSame(EmptyCoroutineContext, grpcServerExecutor.coroutineContext)
        }
    }
}
