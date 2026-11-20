package com.protobench.api

import com.protobench.api.client.HttpDataClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * WebClient가 설정한 연결 풀을 실제로 쓰는지 확인한다.
 *
 * 가짜 서버가 맺어진 TCP 연결 수를 센다. 응답은 요청이 [REQUESTS]개 모이거나 잠시 기다린 뒤에 보내서,
 * 동시에 보낸 요청이 연결을 각자 잡으려 하게 만든다.
 */
class HttpConnectionPoolTest {

    companion object {
        const val POOL = 2
        const val REQUESTS = POOL + 1
    }

    private lateinit var server: ServerSocket
    private val accepted = AtomicInteger()
    private val arrived = CountDownLatch(REQUESTS)
    private val sockets = mutableListOf<Socket>()

    @BeforeEach
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                accepted.incrementAndGet()
                synchronized(sockets) { sockets.add(socket) }
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    @AfterEach
    fun stop() {
        server.close()
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
    }

    /** keep-alive로 요청을 계속 받는다. 요청마다 [arrived]를 내리고, 모두 모이거나 1초 뒤에 응답한다. */
    private fun serve(socket: Socket) = runCatching {
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        while (true) {
            if (!readHead(input)) return@runCatching
            arrived.countDown()
            arrived.await(1, TimeUnit.SECONDS)
            val body = "ok".toByteArray()
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                    "Content-Length: ${body.size}\r\n\r\n").toByteArray()
            )
            out.write(body)
            out.flush()
        }
    }

    /** 요청 머리(빈 줄까지)를 읽는다. 연결이 닫히면 false. */
    private fun readHead(input: BufferedInputStream): Boolean {
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return false
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return true
        }
    }

    private fun sendConcurrently(client: HttpDataClient) = runBlocking {
        (1..REQUESTS).map { i -> async { client.getDataBinary("r$i", "1kb") } }.awaitAll()
    }

    @Test
    fun `matched - 풀 크기가 N이면 N+1개를 동시에 보내도 연결은 N개만 맺는다`() {
        val client = HttpDataClient("http://127.0.0.1:${server.localPort}", maxConcurrency = POOL)
        try {
            val results = sendConcurrently(client)
            assertEquals(REQUESTS, results.size)
            assertTrue(results.all { it.contentEquals("ok".toByteArray()) })
            assertEquals(POOL, accepted.get(), "맺어진 연결 수")
            assertEquals(POOL, client.poolMaxConnections)
        } finally {
            client.close()
        }
    }

    @Test
    fun `default - 전역 풀(500)이라 N+1개를 동시에 보내면 연결도 N+1개다`() {
        val client = HttpDataClient("http://127.0.0.1:${server.localPort}")
        try {
            sendConcurrently(client)
            assertEquals(REQUESTS, accepted.get(), "맺어진 연결 수")
        } finally {
            client.close()
        }
    }
}
