package dev.andsmb.smb

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * パスワードの総当たり対策。接続元 IP ごとに認証失敗を数える。
 * - 同じ IP からの認証は 1 つずつ順に判定し、失敗したら delayMs 待たせてから応答する (並列接続で数を稼げない)
 * - windowMs 以内に maxFailures 回失敗したら、その IP を lockoutMs のあいだ締め出す
 * - 成功したら数え直す
 */
internal class AuthThrottle(
    private val maxFailures: Int = 5,
    private val windowMs: Long = 15 * 60_000L,
    private val lockoutMs: Long = 5 * 60_000L,
    private val delayMs: Long = 1000L,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private class Entry(var failures: Int = 0, var windowStart: Long = 0, var lockedUntil: Long = 0)

    private val entries = ConcurrentHashMap<InetAddress, Entry>()

    /** 締め出し中なら残り時間 (ミリ秒)、そうでなければ 0 */
    fun lockedFor(addr: InetAddress): Long {
        val e = entries[addr] ?: return 0
        synchronized(e) { return (e.lockedUntil - now()).coerceAtLeast(0) }
    }

    /**
     * 1 回の認証を判定する。締め出し中なら block を呼ばずに null を返す。
     * failed(結果) が true なら失敗として数える (匿名の試行など、数えないものは false を返す)。
     */
    fun <T> attempt(addr: InetAddress, failed: (T) -> Boolean, block: () -> T): T? {
        val e = entries.computeIfAbsent(addr) { Entry() }
        synchronized(e) {
            val t = now()
            if (e.lockedUntil > t) return null
            val r = block()
            if (!failed(r)) {
                entries.remove(addr)
                return r
            }
            if (t - e.windowStart > windowMs) { e.windowStart = t; e.failures = 0 }
            e.failures++
            if (e.failures >= maxFailures) {
                e.lockedUntil = t + lockoutMs
                e.failures = 0
            }
            sleep(delayMs)
            return r
        }
    }
}
