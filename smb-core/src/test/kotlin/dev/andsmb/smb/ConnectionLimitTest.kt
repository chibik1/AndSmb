package dev.andsmb.smb

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files

class ConnectionLimitTest {
    private lateinit var dir: File
    private lateinit var server: SmbServer

    private val quiet = object : SmbLogger {
        override fun info(msg: String) {}
        override fun error(msg: String, t: Throwable?) {}
    }

    @Before fun setUp() {
        dir = Files.createTempDirectory("limit").toFile()
        server = SmbServer(
            SmbConfig(
                port = 0,
                bindAddress = "127.0.0.1",
                shares = listOf(SmbShare("s", dir)),
                users = mapOf("u" to "p"),
                maxConnections = 3,
                authTimeoutMs = 800,
                frameTimeoutMs = 800,
            ),
            quiet,
        )
        server.start()
    }

    @After fun tearDown() {
        server.stop()
        dir.deleteRecursively()
    }

    private fun connect() = Socket("127.0.0.1", server.localPort).apply { soTimeout = 5000 }

    /** サーバー側から切られたら true。5 秒待っても開いたままなら false。 */
    private fun closedByServer(s: Socket): Boolean = try {
        s.getInputStream().read() == -1
    } catch (e: SocketTimeoutException) {
        false
    } catch (e: java.io.IOException) {
        true // RST
    }

    @Test fun connectionsBeyondLimitAreClosed() {
        val held = List(3) { connect() }
        Thread.sleep(200)
        assertEquals(3, server.connectionCount)
        val extra = connect()
        val t0 = System.nanoTime()
        assertTrue(closedByServer(extra))
        // 上限超えは認証タイムアウトを待たずにすぐ切る
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 500)
        held.forEach { it.close() }
    }

    @Test fun idleUnauthenticatedConnectionTimesOut() {
        connect().use { assertTrue(closedByServer(it)) }
    }

    @Test fun slowFrameBodyTimesOut() {
        connect().use { s ->
            // 100 バイトのフレームと言いつつ 1 バイトずつ間を空けて送る
            s.getOutputStream().write(byteArrayOf(0, 0, 0, 100))
            val writer = Thread {
                try {
                    repeat(20) { s.getOutputStream().write(0); Thread.sleep(300) }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            assertTrue(closedByServer(s))
            writer.interrupt()
        }
    }

    @Test fun oversizedUnauthenticatedFrameIsRejected() {
        connect().use { s ->
            // 認証前に 1MB のフレームを宣言したら、本体を待たずに切る
            s.getOutputStream().write(byteArrayOf(0, 0x10, 0, 0))
            val t0 = System.nanoTime()
            assertTrue(closedByServer(s))
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 500)
        }
    }

    @Test fun slotIsFreedAfterTimeout() {
        List(3) { connect() }.forEach { closedByServer(it); it.close() }
        Thread.sleep(200)
        assertEquals(0, server.connectionCount)
    }
}
