package com.protobench.api.config

import com.protobench.api.client.GrpcDataClient
import com.protobench.api.client.HttpDataClient
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 기동이 끝나면 실제로 적용된 연결·스레드 설정을 한 줄로 찍는다(측정 기록에 남긴다).
 *
 * 컨트롤러는 Tomcat 스레드에서 `runBlocking`으로 dataServer 호출을 기다린다.
 * 그래서 동시에 나가는 요청 수는 Tomcat 최대 스레드 수를 넘지 않는다.
 */
@Component
class BenchConfigLogger(
    private val httpDataClient: HttpDataClient,
    private val grpcDataClient: GrpcDataClient
) {
    @EventListener
    fun onReady(event: ApplicationReadyEvent) {
        val ctx = event.applicationContext
        val profiles = ctx.environment.activeProfiles.joinToString(",").ifEmpty { "default" }
        val tomcatMaxThreads = (ctx as? ServletWebServerApplicationContext)?.let { tomcatMaxThreads(it) }
        // read: 실행 중인 객체에서 읽은 값 · set: 설정한 값, 상수, 규칙으로 계산한 값(객체에서 읽지 못함)
        println(
            "BENCH_CONFIG server=apiServer profile=$profiles " +
                "read: tomcatMaxThreads=${tomcatMaxThreads ?: "unknown"} " +
                "httpPoolMaxConnections=${httpDataClient.poolMaxConnections} " +
                "httpClientAcceptGzip=${httpDataClient.acceptGzip} " +
                "| set: httpPoolPendingAcquireMaxCount=${httpDataClient.poolPendingAcquireMaxCount} " +
                "httpPoolPendingAcquireTimeoutMs=${httpDataClient.poolPendingAcquireTimeoutMs} " +
                "httpPoolMaxIdleTimeMs=${httpDataClient.poolMaxIdleTimeMs} " +
                "httpPoolMaxLifeTimeMs=${httpDataClient.poolMaxLifeTimeMs} " +
                "grpcChannels=${grpcDataClient.channelCount}"
        )
    }

    companion object {
        /** 실행 중인 Tomcat 커넥터 실행기의 최대 스레드 수 */
        fun tomcatMaxThreads(ctx: ServletWebServerApplicationContext): Int? {
            val tomcat = ctx.webServer as? TomcatWebServer ?: return null
            return when (val e = tomcat.tomcat.connector.protocolHandler.executor) {
                is org.apache.tomcat.util.threads.ThreadPoolExecutor -> e.maximumPoolSize
                is java.util.concurrent.ThreadPoolExecutor -> e.maximumPoolSize
                else -> null
            }
        }
    }
}
