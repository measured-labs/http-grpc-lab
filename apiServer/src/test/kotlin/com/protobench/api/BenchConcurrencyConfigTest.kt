package com.protobench.api

import com.protobench.api.client.GrpcDataClient
import com.protobench.api.client.HttpDataClient
import com.protobench.api.config.BenchConfigLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.test.context.ActiveProfiles
import reactor.netty.resources.ConnectionProvider

/**
 * 측정 설정 두 가지(default, matched)가 실제로 Tomcat과 HTTP 연결 풀에 적용되는지 확인한다.
 * (gRPC 채널은 연결을 미리 열지 않으므로 dataServer 없이 뜬다.)
 */
class BenchConcurrencyConfigTest {

    @Nested
    @SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = ["bench.max-concurrency=37"]
    )
    @ActiveProfiles("matched")
    inner class Matched {
        @Autowired lateinit var ctx: ServletWebServerApplicationContext
        @Autowired lateinit var httpDataClient: HttpDataClient
        @Autowired lateinit var grpcDataClient: GrpcDataClient

        @Test
        fun `Tomcat 최대 스레드와 HTTP 연결 풀이 같은 값이고 gRPC 채널은 1개다`() {
            assertEquals(37, BenchConfigLogger.tomcatMaxThreads(ctx))
            assertEquals(37, httpDataClient.poolMaxConnections)
            assertEquals(37, httpDataClient.poolPendingAcquireMaxCount)
            assertEquals(1, grpcDataClient.channelCount)
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    inner class Default {
        @Autowired lateinit var ctx: ServletWebServerApplicationContext
        @Autowired lateinit var httpDataClient: HttpDataClient

        @Test
        fun `기본값을 바꾸지 않는다`() {
            assertEquals(200, BenchConfigLogger.tomcatMaxThreads(ctx))
            // WebClient 기본 커넥터는 HttpClient.create() → reactor-netty 전역 풀("http")을 쓴다.
            // 그 풀은 TcpResources가 ConnectionProvider.create("http", 500)으로 만든다(1.1.15).
            // ConnectionProvider.DEFAULT_POOL_MAX_CONNECTIONS(max(코어 수, 8) × 2)가 아니다.
            assertEquals(500, httpDataClient.poolMaxConnections)
            assertEquals(1000, httpDataClient.poolPendingAcquireMaxCount)
            assertEquals(ConnectionProvider.DEFAULT_POOL_ACQUIRE_TIMEOUT, httpDataClient.poolPendingAcquireTimeoutMs)
        }
    }
}
