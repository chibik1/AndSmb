package dev.andsmb.smb

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FileIdTest {
    private val server = SmbServer(
        SmbConfig(shares = listOf(SmbShare("s", File("."))), users = emptyMap()),
        object : SmbLogger {
            override fun info(msg: String) {}
            override fun error(msg: String, t: Throwable?) {}
        },
    )

    @Test fun samePathGetsOneIdUnderContention() {
        val threads = 16
        val paths = List(200) { "dir\\file$it" }
        val seen = ConcurrentHashMap<String, MutableSet<Long>>()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                for (p in paths) seen.computeIfAbsent(p) { ConcurrentHashMap.newKeySet() } += server.fileId(p)
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        // どのパスもスレッドを問わず同じ ID 1 つだけ
        assertEquals(paths.size, seen.size)
        seen.forEach { (p, ids) -> assertEquals("ID がぶれた: $p", 1, ids.size) }
        // 別のパスには別の ID
        val ids = seen.values.map { it.single() }
        assertEquals(ids.size, ids.toSet().size)
        // 割り当ては 1 から欠番なしで連続する (使われない番号を消費しない)
        assertEquals((1L..paths.size).toList(), ids.sorted())
    }
}
