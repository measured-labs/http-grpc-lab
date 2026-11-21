package com.protobench.data.config

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * gRPC 서비스 코루틴의 디스패처
 *
 * 측정 설정(`bench.max-concurrency`, 프로파일 `matched`에서 `BENCH_MAX_CONCURRENCY`로 정함)이
 * - 없으면(`default`): 아무것도 바꾸지 않는다. grpc-kotlin 기본 디스패처(`Dispatchers.Default`, 코어 수만큼)를 쓴다.
 * - 있으면(`matched`): 그 수만큼의 고정 스레드 풀에서 서비스 코루틴(요청 처리)을 돌린다.
 *   Tomcat 최대 스레드와 같은 수의 스레드가 요청을 처리한다.
 *
 * gRPC 서버 실행기(전송 콜백이 도는 곳)는 두 설정 모두 grpc-java 기본값(상한 없는 캐시 스레드 풀)이다.
 * 서버 실행기까지 이 고정 풀로 두면 쉬는 스레드 수백 개가 큐의 락 하나를 두고 다퉈 gRPC만 크게 느려졌다
 * (docs/remeasure/PATHS.md, results/2026-11/nodelay/concurrency-matched-v1-fixed-executor/).
 */
@Component
class GrpcHandlerDispatcher(
    @Value("\${bench.max-concurrency:#{null}}") val maxConcurrency: Int?
) {
    /** 서비스 코루틴이 도는 고정 스레드 풀 — `matched`에서만 있다 */
    val handlerExecutor: ThreadPoolExecutor? = maxConcurrency?.let { n ->
        val seq = AtomicInteger()
        ThreadPoolExecutor(n, n, 60, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
            Thread(r, "grpc-bench-${seq.incrementAndGet()}").apply { isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }
    }

    /** gRPC 서비스(코루틴)가 도는 곳 — `matched`면 [handlerExecutor], 아니면 grpc-kotlin 기본값 */
    val coroutineContext: CoroutineContext = handlerExecutor?.asCoroutineDispatcher() ?: EmptyCoroutineContext

    fun describe(): String {
        val handler = handlerExecutor?.let { "fixed(${it.maximumPoolSize})" }
            ?: "Dispatchers.Default(${Runtime.getRuntime().availableProcessors()})"
        return "grpcServerExecutor=grpc-default(cached,unbounded) grpcHandlerDispatcher=$handler"
    }

    @PreDestroy
    fun shutdown() {
        (handlerExecutor as ExecutorService?)?.shutdown()
    }
}

/**
 * 기동이 끝나면 실제로 적용된 연결·스레드 설정을 한 줄로 찍는다(측정 기록에 남긴다).
 */
@Component
class BenchConfigLogger(
    private val grpcHandlerDispatcher: GrpcHandlerDispatcher
) {
    @EventListener
    fun onReady(event: ApplicationReadyEvent) {
        val ctx = event.applicationContext
        val profiles = ctx.environment.activeProfiles.joinToString(",").ifEmpty { "default" }
        val webCtx = ctx as? ServletWebServerApplicationContext
        val tomcatMaxThreads = webCtx?.let { tomcatMaxThreads(it) }
        val keepAlive = webCtx?.let { tomcatKeepAlive(it) }
        // read: 실행 중인 객체에서 읽은 값 · set: 코드에서 정한 값(grpc-java는 실행기를 밖으로 내주지 않는다)
        println(
            "BENCH_CONFIG server=dataServer profile=$profiles " +
                "read: tomcatMaxThreads=${tomcatMaxThreads ?: "unknown"} " +
                "tomcatMaxKeepAliveRequests=${keepAlive?.maxKeepAliveRequests ?: "unknown"} " +
                "tomcatKeepAliveTimeoutMs=${keepAlive?.keepAliveTimeoutMs ?: "unknown"} " +
                "| set: ${grpcHandlerDispatcher.describe()}"
        )
    }

    /** Tomcat HTTP 연결 유지 설정 (-1 = 상한 없음) */
    data class TomcatKeepAlive(val maxKeepAliveRequests: Int, val keepAliveTimeoutMs: Int)

    companion object {
        /** 실행 중인 Tomcat 커넥터의 연결 유지 설정 */
        fun tomcatKeepAlive(ctx: ServletWebServerApplicationContext): TomcatKeepAlive? {
            val tomcat = ctx.webServer as? TomcatWebServer ?: return null
            val protocol = tomcat.tomcat.connector.protocolHandler
                as? org.apache.coyote.http11.AbstractHttp11Protocol<*> ?: return null
            return TomcatKeepAlive(protocol.maxKeepAliveRequests, protocol.keepAliveTimeout)
        }

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
