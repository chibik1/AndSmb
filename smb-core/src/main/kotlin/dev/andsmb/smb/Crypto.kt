package dev.andsmb.smb

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** NTLM と SMB 署名に必要な暗号処理。MD4 と RC4 は Android/JVM 標準に無いので自前実装。 */
internal object Crypto {

    fun md5(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun hmacMd5(key: ByteArray, vararg parts: ByteArray): ByteArray = hmac("HmacMD5", key, *parts)

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray = hmac("HmacSHA256", key, *parts)

    private fun hmac(alg: String, key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance(alg)
        mac.init(SecretKeySpec(key, alg))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xff)) and 0xff
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = ByteArray(data.size)
        var i = 0; j = 0
        for (k in data.indices) {
            i = (i + 1) and 0xff
            j = (j + s[i]) and 0xff
            val t = s[i]; s[i] = s[j]; s[j] = t
            out[k] = (data[k].toInt() xor s[(s[i] + s[j]) and 0xff]).toByte()
        }
        return out
    }

    fun md4(input: ByteArray): ByteArray {
        val bitLen = input.size.toLong() * 8
        val padLen = ((56 - (input.size + 1) % 64) + 64) % 64
        val msg = ByteArray(input.size + 1 + padLen + 8)
        System.arraycopy(input, 0, msg, 0, input.size)
        msg[input.size] = 0x80.toByte()
        msg.put64(msg.size - 8, bitLen)

        var a = 0x67452301; var b = 0xefcdab89.toInt(); var c = 0x98badcfe.toInt(); var d = 0x10325476
        val x = IntArray(16)
        fun f(x: Int, y: Int, z: Int) = (x and y) or (x.inv() and z)
        fun g(x: Int, y: Int, z: Int) = (x and y) or (x and z) or (y and z)
        fun h(x: Int, y: Int, z: Int) = x xor y xor z
        for (blk in 0 until msg.size / 64) {
            for (i in 0 until 16) x[i] = msg.i32(blk * 64 + i * 4)
            val aa = a; val bb = b; val cc = c; val dd = d
            for (i in 0 until 4) {
                val k = i * 4
                a = Integer.rotateLeft(a + f(b, c, d) + x[k], 3)
                d = Integer.rotateLeft(d + f(a, b, c) + x[k + 1], 7)
                c = Integer.rotateLeft(c + f(d, a, b) + x[k + 2], 11)
                b = Integer.rotateLeft(b + f(c, d, a) + x[k + 3], 19)
            }
            for (i in 0 until 4) {
                a = Integer.rotateLeft(a + g(b, c, d) + x[i] + 0x5a827999, 3)
                d = Integer.rotateLeft(d + g(a, b, c) + x[i + 4] + 0x5a827999, 5)
                c = Integer.rotateLeft(c + g(d, a, b) + x[i + 8] + 0x5a827999, 9)
                b = Integer.rotateLeft(b + g(c, d, a) + x[i + 12] + 0x5a827999, 13)
            }
            for (i in intArrayOf(0, 2, 1, 3)) {
                a = Integer.rotateLeft(a + h(b, c, d) + x[i] + 0x6ed9eba1, 3)
                d = Integer.rotateLeft(d + h(a, b, c) + x[i + 8] + 0x6ed9eba1, 9)
                c = Integer.rotateLeft(c + h(d, a, b) + x[i + 4] + 0x6ed9eba1, 11)
                b = Integer.rotateLeft(b + h(c, d, a) + x[i + 12] + 0x6ed9eba1, 15)
            }
            a += aa; b += bb; c += cc; d += dd
        }
        val out = ByteArray(16)
        out.put32(0, a); out.put32(4, b); out.put32(8, c); out.put32(12, d)
        return out
    }

    /** AES-CMAC (RFC 4493)。CBC-MAC を AES/CBC 一括処理で計算して大きなメッセージでも高速にする。 */
    fun aesCmac(key: ByteArray, msg: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(key, "AES")
        val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keySpec) }
        val l = ecb.doFinal(ByteArray(16))
        val k1 = shiftXor(l)
        val k2 = shiftXor(k1)

        val n = maxOf(1, (msg.size + 15) / 16)
        val complete = msg.isNotEmpty() && msg.size % 16 == 0
        val padded = ByteArray(n * 16)
        System.arraycopy(msg, 0, padded, 0, msg.size)
        val lastOff = (n - 1) * 16
        if (!complete) padded[msg.size] = 0x80.toByte()
        val sub = if (complete) k1 else k2
        for (i in 0 until 16) padded[lastOff + i] = (padded[lastOff + i].toInt() xor sub[i].toInt()).toByte()

        val cbc = Cipher.getInstance("AES/CBC/NoPadding")
        cbc.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(ByteArray(16)))
        val enc = cbc.doFinal(padded)
        return enc.copyOfRange(enc.size - 16, enc.size)
    }

    private fun shiftXor(b: ByteArray): ByteArray {
        val out = ByteArray(16)
        var carry = 0
        for (i in 15 downTo 0) {
            val v = b[i].toInt() and 0xff
            out[i] = ((v shl 1) or carry).toByte()
            carry = v ushr 7
        }
        if ((b[0].toInt() and 0x80) != 0) out[15] = (out[15].toInt() xor 0x87).toByte()
        return out
    }

    /** SP800-108 カウンターモード KDF (HMAC-SHA256)。SMB 3.x の署名鍵導出用。 */
    fun kdf(key: ByteArray, label: ByteArray, context: ByteArray): ByteArray {
        val be = { v: Int -> byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()) }
        return hmacSha256(key, be(1), label, byteArrayOf(0), context, be(128)).copyOf(16)
    }
}
