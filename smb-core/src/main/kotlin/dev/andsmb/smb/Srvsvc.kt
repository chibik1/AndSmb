package dev.andsmb.smb

/**
 * IPC$ 上の名前付きパイプ \PIPE\srvsvc で動く最小限の DCE/RPC サーバー。
 * Finder や エクスプローラーで「共有の一覧」を表示するための NetrShareEnumAll / NetrShareGetInfo だけを実装する。
 */
internal class SrvsvcPipe(private val shares: List<SmbShare>) {
    private var pending = ByteArray(0)
    private var pendingOff = 0

    /** WRITE / IOCTL(TRANSCEIVE) で届いた PDU を処理し、応答を読み出し待ちに積む */
    fun write(pdu: ByteArray) {
        val resp = handle(pdu) ?: return
        pending = if (pendingOff >= pending.size) resp else pending.copyOfRange(pendingOff, pending.size) + resp
        pendingOff = 0
    }

    fun available(): Int = pending.size - pendingOff

    fun read(max: Int): ByteArray {
        val n = minOf(max, available())
        val out = pending.copyOfRange(pendingOff, pendingOff + n)
        pendingOff += n
        return out
    }

    private fun handle(pdu: ByteArray): ByteArray? {
        if (pdu.size < 16 || pdu[0].toInt() != 5) return null
        val type = pdu.u8(2)
        val callId = pdu.i32(12)
        return when (type) {
            11 -> bindAck(pdu, callId)
            0 -> request(pdu, callId)
            else -> null
        }
    }

    private fun header(b: Buf, type: Int, callId: Int) {
        b.u8(5).u8(0).u8(type).u8(3).bytes(byteArrayOf(0x10, 0, 0, 0)).u16(0).u16(0).u32(callId)
    }

    private fun finish(b: Buf): ByteArray {
        b.set16(8, b.size)
        return b.toByteArray()
    }

    private fun bindAck(pdu: ByteArray, callId: Int): ByteArray {
        val b = Buf()
        header(b, 12, callId)
        b.u16(4280).u16(4280).u32(0x53f0)
        val port = "\\PIPE\\srvsvc\u0000".toByteArray(Charsets.US_ASCII)
        b.u16(port.size).bytes(port).align(4)
        val n = pdu.u8(24)
        b.u8(n).u8(0).u16(0)
        var p = 28
        for (i in 0 until n) {
            if (p + 4 + 20 > pdu.size) break
            val nTransfer = pdu.u8(p + 2)
            val abstract = pdu.copyOfRange(p + 4, p + 24)
            var accepted: ByteArray? = null
            for (t in 0 until nTransfer) {
                val off = p + 24 + t * 20
                if (off + 20 > pdu.size) break
                val syn = pdu.copyOfRange(off, off + 20)
                if (abstract.contentEquals(SRVSVC) && syn.contentEquals(NDR)) accepted = syn
            }
            if (accepted != null) b.u16(0).u16(0).bytes(accepted)
            else b.u16(2).u16(2).zeros(20) // provider rejection: proposed transfer syntaxes not supported
            p += 24 + nTransfer * 20
        }
        return finish(b)
    }

    private fun request(pdu: ByteArray, callId: Int): ByteArray {
        val opnum = pdu.u16(22)
        val stub = pdu.copyOfRange(24, pdu.size)
        val out = when (opnum) {
            15 -> shareEnumAll(stub)
            16 -> shareGetInfo(stub)
            else -> null
        } ?: return fault(callId)
        val b = Buf()
        header(b, 2, callId)
        b.u32(out.size).u16(0).u8(0).u8(0).bytes(out)
        return finish(b)
    }

    private fun fault(callId: Int): ByteArray {
        val b = Buf()
        header(b, 3, callId)
        b.u32(0).u16(0).u8(0).u8(0).u32(0x1c010002).u32(0) // nca_s_op_rng_error
        return finish(b)
    }

    private class Entry(val name: String, val type: Int, val remark: String)

    private fun entries(): List<Entry> =
        shares.map { Entry(it.name, 0, it.comment) } + Entry("IPC$", 0x80000003.toInt(), "IPC Service")

    /** NDR の [unique, string] wchar_t* をスキップ */
    private fun skipUniqueString(s: ByteArray, p0: Int): Int {
        var p = p0
        val ref = s.i32(p); p += 4
        if (ref != 0) p = skipString(s, p)
        return p
    }

    private fun skipString(s: ByteArray, p0: Int): Int {
        val actual = s.i32(p0 + 8)
        var p = p0 + 12 + actual * 2
        p = (p + 3) and 3.inv()
        return p
    }

    private fun readString(s: ByteArray, p: Int): String {
        val actual = s.i32(p + 8)
        return String(s, p + 12, maxOf(0, actual - 1) * 2, UTF16LE)
    }

    private class Ndr {
        val b = Buf()
        private var ref = 0x20000
        fun ptr(): Ndr { b.u32(ref); ref += 4; return this }
        fun str(v: String) {
            val chars = (v + "\u0000").utf16()
            val n = chars.size / 2
            b.u32(n).u32(0).u32(n).bytes(chars).align(4)
        }
    }

    private fun shareEnumAll(s: ByteArray): ByteArray? {
        if (s.size < 8) return null
        val p = skipUniqueString(s, 0)
        if (p + 4 > s.size) return null
        val level = s.i32(p)
        if (level != 0 && level != 1) return null
        val list = entries()
        val n = Ndr()
        n.b.u32(level).u32(level)
        n.ptr()
        n.b.u32(list.size); n.ptr()
        n.b.u32(list.size)
        for (e in list) {
            n.ptr()
            if (level == 1) { n.b.u32(e.type); n.ptr() }
        }
        for (e in list) {
            n.str(e.name)
            if (level == 1) n.str(e.remark)
        }
        n.b.u32(list.size) // TotalEntries
        n.ptr(); n.b.u32(0) // ResumeHandle
        n.b.u32(0) // WERR_OK
        return n.b.toByteArray()
    }

    private fun shareGetInfo(s: ByteArray): ByteArray? {
        var p = skipUniqueString(s, 0)
        if (p + 12 > s.size) return null
        val name = readString(s, p)
        p = skipString(s, p)
        val level = s.i32(p)
        val e = entries().firstOrNull { it.name.equals(name, ignoreCase = true) }
        val n = Ndr()
        if (e == null || (level != 0 && level != 1)) {
            n.b.u32(level).u32(0).u32(if (e == null) 2310 else 124) // NERR_NetNameNotFound / ERROR_INVALID_LEVEL
            return n.b.toByteArray()
        }
        n.b.u32(level); n.ptr()
        n.ptr()
        if (level == 1) { n.b.u32(e.type); n.ptr() }
        n.str(e.name)
        if (level == 1) n.str(e.remark)
        n.b.u32(0)
        return n.b.toByteArray()
    }

    companion object {
        private fun uuid(s: String, ver: Int): ByteArray {
            val h = s.replace("-", "")
            val raw = ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val b = Buf()
            b.u32(raw.copyOfRange(0, 4).reversedArray().i32(0))
            b.u16(raw.copyOfRange(4, 6).reversedArray().u16(0))
            b.u16(raw.copyOfRange(6, 8).reversedArray().u16(0))
            b.bytes(raw, 8, 8)
            b.u32(ver)
            return b.toByteArray()
        }
        private val SRVSVC = uuid("4b324fc8-1670-01d3-1278-5a47bf6ee188", 3)
        private val NDR = uuid("8a885d04-1ceb-11c9-9fe8-08002b104860", 2)
    }
}
