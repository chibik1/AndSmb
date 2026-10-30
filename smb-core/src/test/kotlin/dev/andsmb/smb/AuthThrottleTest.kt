package dev.andsmb.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AuthThrottleTest {
    private var clock = 0L
    private val slept = ArrayList<Long>()
    private val a = InetAddress.getByName("192.168.1.20")
    private val b = InetAddress.getByName("192.168.1.21")
    private val t = AuthThrottle(maxFailures = 5, windowMs = 60_000, lockoutMs = 300_000, delayMs = 1000, now = { clock }, sleep = { slept += it })

    private fun fail(addr: InetAddress = a) = t.attempt(addr, { ok: Boolean -> !ok }) { false }
    private fun succeed(addr: InetAddress = a) = t.attempt(addr, { ok: Boolean -> !ok }) { true }

    @Test fun locksOutAfterRepeatedFailures() {
        repeat(4) { assertEquals(false, fail()) }
        assertEquals(0, t.lockedFor(a))
        assertEquals(false, fail())
        assertEquals(300_000, t.lockedFor(a))
        // 締め出し中は正しいパスワードでも判定しない
        var called = false
        assertNull(t.attempt(a, { ok: Boolean -> !ok }) { called = true; true })
        assertEquals(false, called)
        // 別の IP には影響しない
        assertEquals(true, succeed(b))
        // 時間が経てば解除
        clock += 300_001
        assertEquals(true, succeed())
    }

    @Test fun eachFailureIsDelayed() {
        repeat(3) { fail() }
        assertEquals(listOf(1000L, 1000L, 1000L), slept)
        succeed()
        assertEquals(3, slept.size)
    }

    @Test fun successResetsCount() {
        repeat(4) { fail() }
        succeed()
        repeat(4) { fail() }
        assertEquals(0, t.lockedFor(a))
    }

    @Test fun failuresOutsideWindowAreForgotten() {
        repeat(4) { fail() }
        clock += 60_001
        repeat(4) { fail() }
        assertEquals(0, t.lockedFor(a))
    }

    @Test fun notCountedAttemptsDoNotLockOut() {
        // 匿名ログインの試行 (Finder がまず試す) は数えない
        repeat(20) { t.attempt(a, { _: Boolean -> false }) { false } }
        assertEquals(0, t.lockedFor(a))
    }

    @Test fun parallelAttemptsFromOneAddressCannotExceedLimit() {
        // 実時間で待つ版: 並列に 32 本投げても、締め出しまでに判定されるのは 5 回だけ
        val real = AuthThrottle(maxFailures = 5, lockoutMs = 60_000, delayMs = 20)
        val judged = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(32)
        repeat(32) {
            pool.execute {
                start.await()
                real.attempt(a, { ok: Boolean -> !ok }) { judged.incrementAndGet(); false }
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)
        assertEquals(5, judged.get())
    }
}
