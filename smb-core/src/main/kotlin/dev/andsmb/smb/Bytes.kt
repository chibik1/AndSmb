package dev.andsmb.smb

import java.nio.charset.Charset

internal val UTF16LE: Charset = Charsets.UTF_16LE

internal fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xff
internal fun ByteArray.u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
internal fun ByteArray.i32(o: Int): Int = u16(o) or (u16(o + 2) shl 16)
internal fun ByteArray.u32(o: Int): Long = i32(o).toLong() and 0xffffffffL
internal fun ByteArray.u64(o: Int): Long = u32(o) or (i32(o + 4).toLong() shl 32)

internal fun ByteArray.put16(o: Int, v: Int) {
    this[o] = v.toByte(); this[o + 1] = (v ushr 8).toByte()
}

internal fun ByteArray.put32(o: Int, v: Int) {
    put16(o, v); put16(o + 2, v ushr 16)
}

internal fun ByteArray.put64(o: Int, v: Long) {
    put32(o, v.toInt()); put32(o + 4, (v ushr 32).toInt())
}

/** 範囲外を読まない安全な部分配列。壊れたパケットで例外を出さないために使う。 */
internal fun ByteArray.slice(off: Int, len: Int): ByteArray {
    if (off < 0 || len <= 0 || off >= size) return ByteArray(0)
    return copyOfRange(off, minOf(size, off + len))
}

internal fun String.utf16(): ByteArray = toByteArray(UTF16LE)

/** リトルエンディアンの可変長バッファ。 */
internal class Buf(cap: Int = 256) {
    var a = ByteArray(cap)
        private set
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > a.size) a = a.copyOf(maxOf(a.size * 2, size + n))
    }

    fun u8(v: Int) = apply { ensure(1); a[size++] = v.toByte() }
    fun u16(v: Int) = apply { ensure(2); a.put16(size, v); size += 2 }
    fun u32(v: Int) = apply { ensure(4); a.put32(size, v); size += 4 }
    fun u32(v: Long) = u32(v.toInt())
    fun u64(v: Long) = apply { ensure(8); a.put64(size, v); size += 8 }
    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size - off) = apply {
        ensure(len); System.arraycopy(b, off, a, size, len); size += len
    }
    fun zeros(n: Int) = apply { ensure(n); java.util.Arrays.fill(a, size, size + n, 0); size += n }
    fun align(n: Int) = apply { val r = size % n; if (r != 0) zeros(n - r) }

    fun set16(pos: Int, v: Int) = a.put16(pos, v)
    fun set32(pos: Int, v: Int) = a.put32(pos, v)
    fun set64(pos: Int, v: Long) = a.put64(pos, v)

    fun toByteArray(): ByteArray = a.copyOf(size)
}

/** Unix ミリ秒 → Windows FILETIME (100ns 単位, 1601年起点) */
internal fun fileTime(unixMillis: Long): Long =
    if (unixMillis <= 0) 0 else (unixMillis + 11644473600000L) * 10000L

internal fun unixMillis(fileTime: Long): Long = fileTime / 10000L - 11644473600000L
