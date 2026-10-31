package dev.andsmb.smb

import java.security.SecureRandom

/**
 * NTLMv2 のサーバー側 (MS-NLMP)。NEGOTIATE を受けて CHALLENGE を返し、AUTHENTICATE を検証する。
 * NTLMv1 は安全でないため受け付けない。
 */
internal class NtlmServer(private val serverName: String, private val users: Map<String, String>, private val allowGuest: Boolean) {

    class Result(
        val ok: Boolean,
        val user: String = "",
        /** 名前もパスワードも空の匿名ログイン (Finder が最初に試す)。総当たりの数には入れない */
        val anonymous: Boolean = false,
        val guest: Boolean = false,
        /** 失敗の理由 (ログ用) */
        val reason: String = "",
        val sessionKey: ByteArray? = null,
        val flags: Int = 0,
    )

    private var negotiateMsg = ByteArray(0)
    private var challengeMsg = ByteArray(0)
    private val serverChallenge = ByteArray(8).also { SecureRandom().nextBytes(it) }
    private var flags = 0

    fun challenge(negotiate: ByteArray): ByteArray {
        negotiateMsg = negotiate
        val clientFlags = if (negotiate.size >= 16) negotiate.i32(12) else 0
        flags = UNICODE or REQUEST_TARGET or NTLM or ALWAYS_SIGN or TARGET_TYPE_SERVER or
            EXTENDED_SESSIONSECURITY or TARGET_INFO or VERSION or
            (clientFlags and (SIGN or SEAL or KEY_EXCH or N128 or N56))

        val name = serverName.uppercase().utf16()
        val info = Buf()
        fun av(id: Int, v: ByteArray) { info.u16(id).u16(v.size).bytes(v) }
        av(2, name)                                   // MsvAvNbDomainName
        av(1, name)                                   // MsvAvNbComputerName
        av(4, ByteArray(0))                           // MsvAvDnsDomainName
        av(3, serverName.lowercase().utf16())         // MsvAvDnsComputerName
        av(7, ByteArray(8).also { it.put64(0, fileTime(System.currentTimeMillis())) }) // MsvAvTimestamp
        av(0, ByteArray(0))                           // MsvAvEOL
        val infoBytes = info.toByteArray()

        val b = Buf()
        b.bytes(SIGNATURE).u32(2)
        b.u16(name.size).u16(name.size).u32(56)
        b.u32(flags)
        b.bytes(serverChallenge)
        b.zeros(8)
        b.u16(infoBytes.size).u16(infoBytes.size).u32(56 + name.size)
        b.bytes(byteArrayOf(6, 1, 0xb1.toByte(), 0x1d, 0, 0, 0, 15)) // Version 6.1.7601, NTLMSSP rev 15
        b.bytes(name).bytes(infoBytes)
        challengeMsg = b.toByteArray()
        return challengeMsg
    }

    fun authenticate(msg: ByteArray): Result {
        if (msg.size < 64 || !msg.copyOf(8).contentEquals(SIGNATURE) || msg.i32(8) != 3) return Result(false, reason = "malformed message")
        fun field(o: Int) = msg.slice(msg.i32(o + 4), msg.u16(o))
        val nt = field(20)
        val authFlags = msg.i32(60)
        val unicode = authFlags and UNICODE != 0
        fun str(o: Int) = String(field(o), if (unicode) UTF16LE else Charsets.ISO_8859_1)
        val domain = str(28)
        val user = str(36)
        val encKey = field(52)

        if (user.isEmpty() && nt.isEmpty()) {
            return if (allowGuest) Result(true, guest = true, anonymous = true) else Result(false, anonymous = true)
        }
        val entry = users.entries.firstOrNull { it.key.equals(user, ignoreCase = true) }
        if (entry == null) {
            return if (allowGuest) Result(true, user = user, guest = true) else Result(false, user = user, reason = "unknown user")
        }
        if (nt.size <= 24) return Result(false, user = user, reason = "NTLMv1 not allowed") // NTLMv1 は拒否

        val ntowf = Crypto.hmacMd5(Crypto.md4(entry.value.utf16()), (user.uppercase() + domain).utf16())
        val proof = nt.copyOf(16)
        val blob = nt.copyOfRange(16, nt.size)
        val expected = Crypto.hmacMd5(ntowf, serverChallenge, blob)
        if (!java.security.MessageDigest.isEqual(proof, expected)) return Result(false, user = user, reason = "wrong password")

        val baseKey = Crypto.hmacMd5(ntowf, proof)
        val exported = if (authFlags and KEY_EXCH != 0 && encKey.size == 16) Crypto.rc4(baseKey, encKey) else baseKey

        // MsvAvFlags に MIC 有りの印があれば、メッセージ全体の改ざん検出用 MIC を検証する
        if (hasMic(blob) && msg.size >= 88) {
            val zeroed = msg.copyOf().also { java.util.Arrays.fill(it, 72, 88, 0) }
            val mic = Crypto.hmacMd5(exported, negotiateMsg, challengeMsg, zeroed)
            if (!java.security.MessageDigest.isEqual(mic, msg.copyOfRange(72, 88))) return Result(false, user = user, reason = "MIC mismatch")
        }
        return Result(true, user = entry.key, sessionKey = exported, flags = authFlags)
    }

    private fun hasMic(blob: ByteArray): Boolean {
        var o = 28
        while (o + 4 <= blob.size) {
            val id = blob.u16(o); val len = blob.u16(o + 2)
            if (id == 0) break
            if (id == 6 && len >= 4 && o + 8 <= blob.size) return blob.i32(o + 4) and 2 != 0
            o += 4 + len
        }
        return false
    }

    companion object {
        val SIGNATURE = "NTLMSSP\u0000".toByteArray(Charsets.US_ASCII)
        const val UNICODE = 0x1
        const val REQUEST_TARGET = 0x4
        const val SIGN = 0x10
        const val SEAL = 0x20
        const val NTLM = 0x200
        const val ALWAYS_SIGN = 0x8000
        const val TARGET_TYPE_SERVER = 0x20000
        const val EXTENDED_SESSIONSECURITY = 0x80000
        const val TARGET_INFO = 0x800000
        const val VERSION = 0x2000000
        const val N128 = 0x20000000
        const val KEY_EXCH = 0x40000000
        const val N56 = 0x80000000.toInt()

        /** SPNEGO の mechListMIC 用。サーバー→クライアント方向の NTLM 署名 (シーケンス番号 0)。 */
        fun serverSignature(sessionKey: ByteArray, flags: Int, data: ByteArray): ByteArray {
            val signKey = Crypto.md5(sessionKey, "session key to server-to-client signing key magic constant\u0000".toByteArray())
            val sealKey = Crypto.md5(sessionKey, "session key to server-to-client sealing key magic constant\u0000".toByteArray())
            val seq = ByteArray(4)
            var checksum = Crypto.hmacMd5(signKey, seq, data).copyOf(8)
            if (flags and KEY_EXCH != 0) checksum = Crypto.rc4(sealKey, checksum)
            return Buf().u32(1).bytes(checksum).bytes(seq).toByteArray()
        }
    }
}

/** SPNEGO (RFC 4178) の最小限の DER 処理。NTLMSSP だけを扱う。 */
internal object Spnego {
    private val SPNEGO_OID = byteArrayOf(0x2b, 0x06, 0x01, 0x05, 0x05, 0x02)
    private val NTLMSSP_OID = byteArrayOf(0x2b, 0x06, 0x01, 0x04, 0x01, 0x82.toByte(), 0x37, 0x02, 0x02, 0x0a)

    class Token(val ntlm: ByteArray?, val mechTypes: ByteArray?, val hasMechListMic: Boolean, val wrapped: Boolean)

    private class Tlv(val tag: Int, val start: Int, val off: Int, val len: Int) { val end get() = off + len }

    private fun read(b: ByteArray, p: Int): Tlv? {
        if (p + 2 > b.size) return null
        val tag = b.u8(p)
        var q = p + 1
        var len = b.u8(q++)
        if (len and 0x80 != 0) {
            val n = len and 0x7f
            if (n > 4 || q + n > b.size) return null
            len = 0
            repeat(n) { len = (len shl 8) or b.u8(q++) }
        }
        if (len < 0 || q + len > b.size) return null
        return Tlv(tag, p, q, len)
    }

    private fun children(b: ByteArray, parent: Tlv): List<Tlv> {
        val out = ArrayList<Tlv>()
        var p = parent.off
        while (p < parent.end) {
            val t = read(b, p) ?: break
            out += t; p = t.end
        }
        return out
    }

    fun parse(b: ByteArray): Token {
        if (b.size >= 8 && b.copyOf(8).contentEquals(NtlmServer.SIGNATURE)) return Token(b, null, false, false)
        var t = read(b, 0) ?: return Token(null, null, false, true)
        if (t.tag == 0x60) { // GSS-API ラッパー: OID の次に NegTokenInit
            val kids = children(b, t)
            t = kids.firstOrNull { it.tag == 0xa0 } ?: return Token(null, null, false, true)
        }
        if (t.tag != 0xa0 && t.tag != 0xa1) return Token(null, null, false, true)
        val seq = children(b, t).firstOrNull { it.tag == 0x30 } ?: return Token(null, null, false, true)
        var ntlm: ByteArray? = null
        var mechTypes: ByteArray? = null
        var mic = false
        for (f in children(b, seq)) {
            val inner = children(b, f).firstOrNull() ?: continue
            when {
                t.tag == 0xa0 && f.tag == 0xa0 -> mechTypes = b.copyOfRange(inner.start, inner.end)
                f.tag == 0xa2 && inner.tag == 0x04 -> ntlm = b.copyOfRange(inner.off, inner.end)
                f.tag == 0xa3 -> mic = true
            }
        }
        if (ntlm != null && !(ntlm.size >= 8 && ntlm.copyOf(8).contentEquals(NtlmServer.SIGNATURE))) ntlm = null
        return Token(ntlm, mechTypes, mic, true)
    }

    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    }

    private fun tlv(tag: Int, vararg content: ByteArray): ByteArray {
        val body = content.fold(ByteArray(0)) { acc, x -> acc + x }
        return byteArrayOf(tag.toByte()) + len(body.size) + body
    }

    /** NEGOTIATE 応答に入れる NegTokenInit (NTLMSSP のみ提示) */
    fun initialToken(): ByteArray =
        tlv(0x60, tlv(0x06, SPNEGO_OID), tlv(0xa0, tlv(0x30, tlv(0xa0, tlv(0x30, tlv(0x06, NTLMSSP_OID))))))

    /** negState: 0=accept-completed, 1=accept-incomplete, 2=reject */
    fun response(negState: Int, ntlm: ByteArray?, mic: ByteArray?, includeMech: Boolean): ByteArray {
        val parts = ArrayList<ByteArray>()
        parts += tlv(0xa0, tlv(0x0a, byteArrayOf(negState.toByte())))
        if (includeMech) parts += tlv(0xa1, tlv(0x06, NTLMSSP_OID))
        if (ntlm != null) parts += tlv(0xa2, tlv(0x04, ntlm))
        if (mic != null) parts += tlv(0xa3, tlv(0x04, mic))
        return tlv(0xa1, tlv(0x30, *parts.toTypedArray()))
    }
}
