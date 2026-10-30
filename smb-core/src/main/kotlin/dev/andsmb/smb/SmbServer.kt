package dev.andsmb.smb

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** SMB2/3 サーバー本体。1 接続 = 1 スレッドで、各接続内のリクエストは順番に処理する。 */
class SmbServer(val config: SmbConfig, internal val log: SmbLogger) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val connections = Collections.synchronizedSet(HashSet<SmbConnection>())

    internal val serverGuid: ByteArray = UUID.randomUUID().let {
        Buf().u64(it.leastSignificantBits).u64(it.mostSignificantBits).toByteArray()
    }
    internal val startTime = System.currentTimeMillis()
    private val fileIds = ConcurrentHashMap<String, Long>()
    private val nextFileId = AtomicLong(1)

    /** パスごとに安定した FileId (FileInternalInformation) を割り当てる */
    internal fun fileId(path: String): Long = fileIds.computeIfAbsent(path) { nextFileId.getAndIncrement() }

    /** 認証失敗の数え上げ (接続をまたいで IP ごと) */
    internal val authThrottle = AuthThrottle()

    /** true にするとコマンドごとの結果をログに出す */
    var debug = System.getenv("SMB_DEBUG") != null

    /** 起動してからの受信・送信バイト数 (SMB のフレーム単位で数える) */
    val bytesReceived = AtomicLong()
    val bytesSent = AtomicLong()

    val isRunning: Boolean get() = serverSocket?.isClosed == false
    val connectionCount: Int get() = connections.size

    /** 待ち受け中のポート (config.port に 0 を渡したときの実際の番号を知るため) */
    val localPort: Int get() = serverSocket?.localPort ?: -1

    @Throws(IOException::class)
    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        val addr = config.bindAddress?.let { InetAddress.getByName(it) }
        ss.bind(InetSocketAddress(addr, config.port), 50)
        serverSocket = ss
        log.info("SMB server listening on port ${config.port}")
        acceptThread = Thread({
            while (!ss.isClosed) {
                val s: Socket = try { ss.accept() } catch (e: IOException) { break }
                // LAN の外からの接続は受け付けない
                if (!config.clientFilter(s.inetAddress)) {
                    log.info("non-local client, rejected: ${s.inetAddress.hostAddress}")
                    try { s.close() } catch (_: IOException) {}
                    continue
                }
                // 上限を超えた接続はスレッドを作らずにすぐ切る
                if (connections.size >= config.maxConnections) {
                    log.info("too many connections, rejected: ${s.inetAddress.hostAddress}")
                    try { s.close() } catch (_: IOException) {}
                    continue
                }
                s.tcpNoDelay = true
                s.keepAlive = true
                val c = SmbConnection(this, s)
                connections += c
                Thread({
                    try { c.run() } finally { connections -= c }
                }, "smb-${s.inetAddress.hostAddress}").apply { isDaemon = true }.start()
            }
        }, "smb-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        synchronized(connections) { connections.toList() }.forEach { it.close() }
        log.info("SMB server stopped")
    }
}

internal class Session(val id: Long, val ntlm: NtlmServer) {
    var valid = false
    var user = ""
    var guest = false
    var signingKey: ByteArray? = null
    var preauth = ByteArray(64)
    var mechTypes: ByteArray? = null
    val trees = HashMap<Int, Tree>()
}

internal class Tree(val id: Int, val share: SmbShare?) {
    val isIpc get() = share == null
}

internal class Open(val id: Long, val session: Session, val tree: Tree, var file: java.io.File, var path: String, val isDir: Boolean, val access: Int) {
    var raf: java.io.RandomAccessFile? = null
    var writable = false
    var deleteOnClose = false
    var pipe: SrvsvcPipe? = null
    var dirEntries: List<Pair<String, java.io.File>>? = null
    var dirPos = 0
}

internal class SmbError(val status: Int) : Exception()

private class Req(val msg: ByteArray) {
    val command = msg.u16(12)
    val flags = msg.i32(16)
    val messageId = msg.u64(24)
    var treeId = msg.i32(36)
    var sessionId = msg.u64(40)
    val signed get() = flags and 0x8 != 0
    val related get() = flags and 0x4 != 0
    val body = 64
}

private class Resp(val status: Int, val body: ByteArray) {
    var sessionId: Long? = null
    var treeId: Int? = null
    var forceSign = false
    var afterBuild: ((ByteArray) -> Unit)? = null
}

/** 認証後に受け付ける最大フレーム長: 告知した最大読み書きサイズ 1MB + ヘッダ・複合要求の余裕 */
private const val MAX_FRAME = (1 shl 20) + 65536

/** 認証前に受け付ける最大フレーム長。NEGOTIATE / SESSION_SETUP はこれで十分足りる */
private const val MAX_FRAME_UNAUTH = 65536

internal class SmbConnection(private val server: SmbServer, private val socket: Socket) {
    private val config = server.config
    private val log = server.log
    private val peer = socket.inetAddress.hostAddress ?: "?"

    private var dialect = 0
    private var clientCaps = 0
    private var clientGuid = ByteArray(16)
    private var clientSecMode = 0
    private var serverCaps = 0
    private val serverSecMode = 1 // SIGNING_ENABLED
    private var connPreauth = ByteArray(64)

    private val sessions = HashMap<Long, Session>()
    private val opens = HashMap<Long, Open>()
    private var nextSessionId = 0x1000L + (Math.random() * 0x10000).toLong()
    private var nextTreeId = 1
    private var nextOpenId = 1L

    private val out = socket.getOutputStream().buffered(1 shl 16)

    fun close() {
        try { socket.close() } catch (_: IOException) {}
    }

    /** 認証済みのセッションがあるか。認証前は受け付けるフレームの大きさと待ち時間を絞る。 */
    private val authenticated get() = sessions.values.any { it.valid }

    fun run() {
        log.info("connected: $peer")
        val input = socket.getInputStream().buffered(1 shl 16)
        val hdr = ByteArray(4)
        var authDeadline = System.nanoTime() + config.authTimeoutMs * 1_000_000L
        try {
            while (true) {
                val auth = authenticated
                // LOGOFF 後に認証し直す場合も、そこから改めて制限時間を数える
                if (auth) authDeadline = System.nanoTime() + config.authTimeoutMs * 1_000_000L
                // 認証後は無通信でも待ち続ける (切断検出は TCP keepalive 任せ)
                if (!readFully(input, hdr, if (auth) 0L else authDeadline)) break
                val len = (hdr.u8(1) shl 16) or (hdr.u8(2) shl 8) or hdr.u8(3)
                val maxLen = if (auth) MAX_FRAME else MAX_FRAME_UNAUTH
                if (len > maxLen) { log.info("frame too large ($len bytes), closing: $peer"); break }
                // 長さを受け取ったら、本体は一定時間内に届き終えなければならない
                val frameDeadline = System.nanoTime() + config.frameTimeoutMs * 1_000_000L
                val deadline = if (auth) frameDeadline else minOf(frameDeadline, authDeadline)
                if (hdr[0].toInt() != 0) { if (!skipFully(input, len, deadline)) break; continue } // NetBIOS keepalive 等
                if (len < 4) break
                val data = ByteArray(len)
                if (!readFully(input, data, deadline)) break
                server.bytesReceived.addAndGet(4L + len)
                handleFrame(data)
            }
        } catch (e: SocketTimeoutException) {
            log.info("timed out: $peer")
        } catch (e: SocketException) {
            // 切断
        } catch (e: Exception) {
            log.error("connection error ($peer)", e)
        } finally {
            opens.values.forEach { closeOpen(it) }
            opens.clear()
            close()
            log.info("disconnected: $peer")
        }
    }

    /**
     * b を埋めるまで読む。deadline (System.nanoTime 基準、0 なら無期限) を過ぎたら SocketTimeoutException。
     * 少しずつ送り続ける相手でも合計時間で打ち切れるよう、読むたびに残り時間をタイムアウトにする。
     */
    private fun readFully(input: java.io.InputStream, b: ByteArray, deadline: Long): Boolean {
        var n = 0
        while (n < b.size) {
            setReadTimeout(deadline)
            val r = input.read(b, n, b.size - n)
            if (r < 0) return false
            n += r
        }
        return true
    }

    private fun skipFully(input: java.io.InputStream, len: Int, deadline: Long): Boolean {
        val buf = ByteArray(minOf(len, 4096))
        var left = len
        while (left > 0) {
            setReadTimeout(deadline)
            val r = input.read(buf, 0, minOf(left, buf.size))
            if (r < 0) return false
            left -= r
        }
        return true
    }

    private fun setReadTimeout(deadline: Long) {
        if (deadline == 0L) { socket.soTimeout = 0; return }
        val ms = (deadline - System.nanoTime()) / 1_000_000
        if (ms <= 0) throw SocketTimeoutException()
        socket.soTimeout = ms.toInt()
    }

    private fun send(vararg msgs: ByteArray) {
        val total = msgs.sumOf { it.size }
        out.write(byteArrayOf(0, (total ushr 16).toByte(), (total ushr 8).toByte(), total.toByte()))
        msgs.forEach { out.write(it) }
        out.flush()
        server.bytesSent.addAndGet(4L + total)
    }

    private fun handleFrame(data: ByteArray) {
        if (data.size >= 4 && data.u8(0) == 0xFF && data[1] == 'S'.code.toByte()) {
            smb1Negotiate(data); return
        }
        if (data.size < 64 || data.u8(0) != 0xFE || data[1] != 'S'.code.toByte()) {
            close(); return
        }
        val responses = ArrayList<ByteArray>()
        val sessionsToSign = ArrayList<Session?>()
        var off = 0
        var lastSession = 0L
        var lastTree = 0
        var lastFileId = -1L
        var lastCreateStatus = 0
        while (true) {
            val next = data.i32(off + 20)
            val end = if (next == 0 || off + next > data.size) data.size else off + next
            val req = Req(data.copyOfRange(off, end))
            if (req.related) {
                req.sessionId = lastSession
                req.treeId = lastTree
            }
            val session = sessions[req.sessionId]
            var resp: Resp
            // 関連付けられた複合リクエストでは、先行 CREATE の失敗だけを後続に伝播させる
            if (req.related && lastCreateStatus != 0) {
                resp = error(lastCreateStatus)
            } else if (req.signed && session?.signingKey != null && !verify(req.msg, session)) {
                log.error("signature mismatch from $peer (cmd ${req.command})")
                resp = error(Status.ACCESS_DENIED)
            } else {
                resp = try {
                    dispatch(req, if (req.related) lastFileId else -1L)
                } catch (e: SmbError) {
                    error(e.status)
                } catch (e: Exception) {
                    log.error("error in command ${req.command}", e)
                    error(Status.INTERNAL_ERROR)
                }
            }
            if (req.command == Cmd.CANCEL) { if (next == 0) break else { off += next; continue } }
            if (server.debug) log.info("$peer cmd=${req.command} mid=${req.messageId} -> ${"%08x".format(resp.status)} (${resp.body.size}b)")
            val msg = build(req, resp)
            val sid = resp.sessionId ?: req.sessionId
            val signSession = sessions[sid]?.takeIf { it.signingKey != null && (req.signed || resp.forceSign) }
            responses += msg
            sessionsToSign += signSession
            resp.afterBuild?.invoke(msg)
            lastSession = sid
            lastTree = resp.treeId ?: req.treeId
            if (req.command == Cmd.CREATE) lastCreateStatus = resp.status
            if (req.command == Cmd.CREATE && resp.status == 0) lastFileId = resp.body.u64(64 + 8)
            if (next == 0 || off + next >= data.size) break
            off += next
        }
        if (responses.isEmpty()) return
        val related = ArrayList<ByteArray>(responses.size)
        for (i in responses.indices) {
            var m = responses[i]
            if (i < responses.size - 1) {
                m = m.copyOf((m.size + 7) and 7.inv())
                m.put32(20, m.size)
            }
            sessionsToSign[i]?.let { sign(m, it) }
            related += m
        }
        send(*related.toTypedArray())
    }

    private fun build(req: Req, resp: Resp): ByteArray {
        var body = resp.body
        val structSize = if (body.size >= 2) body.u16(0) else 0
        if (body.size < structSize) body = body.copyOf(structSize)
        val m = ByteArray(64 + body.size)
        m[0] = 0xFE.toByte(); m[1] = 'S'.code.toByte(); m[2] = 'M'.code.toByte(); m[3] = 'B'.code.toByte()
        m.put16(4, 64)
        m.put16(6, req.msg.u16(6))
        m.put32(8, resp.status)
        m.put16(12, req.command)
        val credits = req.msg.u16(14).coerceIn(1, 2048)
        m.put16(14, credits)
        m.put32(16, 0x1 or (req.flags and 0x4))
        m.put64(24, req.messageId)
        m.put32(32, req.msg.i32(32))
        m.put32(36, resp.treeId ?: req.treeId)
        m.put64(40, resp.sessionId ?: req.sessionId)
        System.arraycopy(body, 0, m, 64, body.size)
        return m
    }

    private fun error(status: Int) = Resp(status, ByteArray(9).also { it.put16(0, 9) })

    // ---------------------------------------------------------------- 署名

    private fun signature(msg: ByteArray, s: Session): ByteArray {
        val key = s.signingKey!!
        return if (dialect >= 0x300) Crypto.aesCmac(key, msg) else Crypto.hmacSha256(key, msg).copyOf(16)
    }

    private fun sign(msg: ByteArray, s: Session) {
        msg.put32(16, msg.i32(16) or 0x8)
        java.util.Arrays.fill(msg, 48, 64, 0)
        System.arraycopy(signature(msg, s), 0, msg, 48, 16)
    }

    private fun verify(msg: ByteArray, s: Session): Boolean {
        val copy = msg.copyOf()
        java.util.Arrays.fill(copy, 48, 64, 0)
        return java.security.MessageDigest.isEqual(signature(copy, s).copyOf(16), msg.copyOfRange(48, 64))
    }

    // ---------------------------------------------------------------- ディスパッチ

    private fun dispatch(r: Req, relatedFileId: Long): Resp {
        when (r.command) {
            Cmd.NEGOTIATE -> return negotiate(r)
            Cmd.SESSION_SETUP -> return sessionSetup(r)
            Cmd.ECHO -> return Resp(0, Buf().u16(4).u16(0).toByteArray())
            Cmd.CANCEL -> return error(0)
        }
        if (dialect == 0) throw SmbError(Status.INVALID_PARAMETER)
        val session = sessions[r.sessionId]?.takeIf { it.valid } ?: throw SmbError(Status.USER_SESSION_DELETED)
        when (r.command) {
            Cmd.LOGOFF -> {
                session.trees.keys.toList().forEach { disconnectTree(session, it) }
                val resp = Resp(0, Buf().u16(4).u16(0).toByteArray())
                // ログオフ応答は署名してから削除する
                resp.afterBuild = { sessions.remove(session.id) }
                return resp
            }
            Cmd.TREE_CONNECT -> return treeConnect(r, session)
        }
        val tree = session.trees[r.treeId] ?: throw SmbError(Status.NETWORK_NAME_DELETED)
        val fileIdOff = when (r.command) {
            Cmd.CLOSE, Cmd.FLUSH -> 8
            Cmd.READ, Cmd.WRITE -> 16
            Cmd.LOCK -> 8
            Cmd.IOCTL -> 8
            Cmd.QUERY_DIRECTORY -> 8
            Cmd.CHANGE_NOTIFY -> 8
            Cmd.QUERY_INFO -> 24
            Cmd.SET_INFO -> 16
            else -> -1
        }
        val open: Open? = if (fileIdOff >= 0) {
            var vid = r.msg.u64(r.body + fileIdOff + 8)
            if (vid == -1L) vid = relatedFileId
            opens[vid]?.takeIf { it.session === session }
        } else null

        return when (r.command) {
            Cmd.TREE_DISCONNECT -> { disconnectTree(session, tree.id); Resp(0, Buf().u16(4).u16(0).toByteArray()) }
            Cmd.CREATE -> create(r, session, tree)
            Cmd.IOCTL -> ioctl(r, open)
            else -> {
                val o = open ?: throw SmbError(Status.FILE_CLOSED)
                when (r.command) {
                    Cmd.CLOSE -> close(r, o)
                    Cmd.FLUSH -> { o.raf?.channel?.force(false); Resp(0, Buf().u16(4).u16(0).toByteArray()) }
                    Cmd.READ -> read(r, o)
                    Cmd.WRITE -> write(r, o)
                    Cmd.LOCK -> Resp(0, Buf().u16(4).u16(0).toByteArray())
                    Cmd.QUERY_DIRECTORY -> queryDirectory(r, o)
                    Cmd.CHANGE_NOTIFY -> error(Status.NOT_SUPPORTED)
                    Cmd.QUERY_INFO -> queryInfo(r, o)
                    Cmd.SET_INFO -> setInfo(r, o)
                    else -> error(Status.NOT_SUPPORTED)
                }
            }
        }
    }

    // ---------------------------------------------------------------- NEGOTIATE

    private fun smb1Negotiate(data: ByteArray) {
        if (data.size < 35 || data.u8(4) != 0x72) { close(); return }
        val dialects = String(data, 35, data.size - 35, Charsets.ISO_8859_1).split('\u0002', '\u0000').filter { it.isNotEmpty() }
        val d = when {
            "SMB 2.???" in dialects -> 0x02FF
            "SMB 2.002" in dialects -> 0x0202
            else -> { close(); return }
        }
        val fake = ByteArray(64).also { it.put16(14, 1) }
        val body = negotiateBody(d, emptyList())
        val m = build(Req(fake), Resp(0, body))
        if (d == 0x0202) setDialect(d)
        send(m)
    }

    private fun negotiate(r: Req): Resp {
        if (dialect != 0) { close(); throw SmbError(Status.INVALID_PARAMETER) }
        val b = r.body
        val count = r.msg.u16(b + 2)
        clientSecMode = r.msg.u16(b + 4)
        clientCaps = r.msg.i32(b + 8)
        clientGuid = r.msg.copyOfRange(b + 12, b + 28)
        val offered = (0 until count).map { r.msg.u16(b + 36 + it * 2) }
        val chosen = listOf(0x0311, 0x0302, 0x0300, 0x0210, 0x0202).firstOrNull { it in offered }
            ?: throw SmbError(Status.NOT_SUPPORTED)
        setDialect(chosen)
        val contexts = ArrayList<ByteArray>()
        if (chosen == 0x0311) {
            val salt = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            contexts += Buf().u16(1).u16(38).u32(0).u16(1).u16(32).u16(1).bytes(salt).toByteArray()
            connPreauth = Crypto.sha512(ByteArray(64), r.msg)
        }
        val resp = Resp(0, negotiateBody(chosen, contexts))
        if (chosen == 0x0311) resp.afterBuild = { connPreauth = Crypto.sha512(connPreauth, it) }
        log.info("$peer negotiated dialect ${"%04x".format(chosen)}")
        return resp
    }

    private fun setDialect(d: Int) {
        dialect = d
        serverCaps = if (d >= 0x0210) 0x4 else 0 // LARGE_MTU
    }

    private fun negotiateBody(d: Int, contexts: List<ByteArray>): ByteArray {
        val token = Spnego.initialToken()
        val max = if (d == 0x0202) 65536 else 1 shl 20
        val b = Buf()
        b.u16(65).u16(serverSecMode).u16(d).u16(contexts.size)
        b.bytes(server.serverGuid)
        b.u32(if (d == 0x02FF) 0 else serverCaps).u32(max).u32(max).u32(max)
        b.u64(fileTime(System.currentTimeMillis())).u64(fileTime(server.startTime))
        b.u16(128).u16(token.size)
        val ctxOffPos = b.size
        b.u32(0)
        b.bytes(token)
        if (contexts.isNotEmpty()) {
            b.align(8)
            b.set32(ctxOffPos, 64 + b.size)
            contexts.forEachIndexed { i, c -> if (i > 0) b.align(8); b.bytes(c) }
        }
        return b.toByteArray()
    }

    // ---------------------------------------------------------------- SESSION_SETUP

    private fun sessionSetup(r: Req): Resp {
        if (dialect == 0 || dialect == 0x02FF) throw SmbError(Status.INVALID_PARAMETER)
        val b = r.body
        val token = r.msg.slice(r.msg.u16(b + 12), r.msg.u16(b + 14))
        val session: Session
        if (r.sessionId == 0L) {
            session = Session(nextSessionId++, NtlmServer(config.serverName, config.users, config.allowGuest))
            session.preauth = connPreauth.copyOf()
            sessions[session.id] = session
        } else {
            session = sessions[r.sessionId] ?: throw SmbError(Status.USER_SESSION_DELETED)
        }
        if (dialect == 0x0311) session.preauth = Crypto.sha512(session.preauth, r.msg)

        val tok = Spnego.parse(token)
        if (tok.mechTypes != null) session.mechTypes = tok.mechTypes
        val ntlm = tok.ntlm
        val msgType = if (ntlm != null && ntlm.size >= 12) ntlm.i32(8) else 0

        fun body(flags: Int, t: ByteArray) =
            Buf().u16(9).u16(flags).u16(if (t.isEmpty()) 0 else 72).u16(t.size).bytes(t).toByteArray()

        fun moreProcessing(t: ByteArray): Resp {
            val resp = Resp(Status.MORE_PROCESSING_REQUIRED, body(0, t))
            resp.sessionId = session.id
            if (dialect == 0x0311) resp.afterBuild = { session.preauth = Crypto.sha512(session.preauth, it) }
            return resp
        }

        when (msgType) {
            0 -> {
                if (!tok.wrapped) { sessions.remove(session.id); throw SmbError(Status.LOGON_FAILURE) }
                return moreProcessing(Spnego.response(1, null, null, true))
            }
            1 -> {
                val ch = session.ntlm.challenge(ntlm!!)
                return moreProcessing(if (tok.wrapped) Spnego.response(1, ch, null, true) else ch)
            }
            3 -> {
                val res = server.authThrottle.attempt(socket.inetAddress, { r: NtlmServer.Result -> !r.ok && !r.anonymous }) {
                    session.ntlm.authenticate(ntlm!!)
                }
                if (res == null) {
                    sessions.remove(session.id)
                    val sec = (server.authThrottle.lockedFor(socket.inetAddress) + 999) / 1000
                    log.info("$peer locked out after repeated logon failures (${sec}s left)")
                    return error(Status.ACCOUNT_LOCKED_OUT).also { it.sessionId = session.id }
                }
                if (!res.ok) {
                    sessions.remove(session.id)
                    log.info("$peer logon failure")
                    return error(Status.LOGON_FAILURE).also { it.sessionId = session.id }
                }
                session.user = res.user
                session.guest = res.guest
                if (!res.guest && res.sessionKey != null) {
                    val key = res.sessionKey.copyOf(16)
                    session.signingKey = when {
                        dialect >= 0x0311 -> Crypto.kdf(key, "SMBSigningKey\u0000".toByteArray(), session.preauth)
                        dialect >= 0x0300 -> Crypto.kdf(key, "SMB2AESCMAC\u0000".toByteArray(), "SmbSign\u0000".toByteArray())
                        else -> key
                    }
                }
                val mic = if (tok.wrapped && tok.hasMechListMic && session.mechTypes != null && res.sessionKey != null)
                    NtlmServer.serverSignature(res.sessionKey, res.flags, session.mechTypes!!) else null
                val t = if (tok.wrapped) Spnego.response(0, null, mic, false) else ByteArray(0)
                session.valid = true
                log.info("$peer logged on as ${if (res.guest) "guest" else res.user}")
                val resp = Resp(0, body(if (res.guest) 1 else 0, t))
                resp.sessionId = session.id
                resp.forceSign = session.signingKey != null
                return resp
            }
            else -> { sessions.remove(session.id); throw SmbError(Status.LOGON_FAILURE) }
        }
    }

    // ---------------------------------------------------------------- TREE

    private fun treeConnect(r: Req, session: Session): Resp {
        val b = r.body
        val path = String(r.msg.slice(r.msg.u16(b + 4), r.msg.u16(b + 6)), UTF16LE)
        val name = path.substringAfterLast('\\')
        if (server.debug) log.info("tree connect: $path flags=${r.msg.u16(b + 2)}")
        val tree: Tree
        val resp = Buf().u16(16)
        if (name.equals("IPC$", ignoreCase = true)) {
            tree = Tree(nextTreeId++, null)
            resp.u8(2).u8(0).u32(0).u32(0).u32(ACCESS_FULL)
        } else {
            val share = config.shares.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw SmbError(Status.BAD_NETWORK_NAME)
            tree = Tree(nextTreeId++, share)
            resp.u8(1).u8(0).u32(0).u32(0).u32(if (share.readOnly) ACCESS_READ else ACCESS_FULL)
        }
        session.trees[tree.id] = tree
        return Resp(0, resp.toByteArray()).also { it.treeId = tree.id }
    }

    private fun disconnectTree(session: Session, treeId: Int) {
        val tree = session.trees.remove(treeId) ?: return
        opens.values.filter { it.tree === tree }.forEach { closeOpen(it); opens.remove(it.id) }
    }

    // ---------------------------------------------------------------- CREATE / CLOSE

    private fun create(r: Req, session: Session, tree: Tree): Resp {
        val b = r.body
        val m = r.msg
        val desired = m.i32(b + 24)
        val disposition = m.i32(b + 36)
        val options = m.i32(b + 40)
        var name = String(m.slice(m.u16(b + 44), m.u16(b + 46)), UTF16LE)
        val ctxOff = m.i32(b + 48)
        val ctxLen = m.i32(b + 52)
        val ctxNames = parseCreateContexts(m, ctxOff, ctxLen)

        if (server.debug) log.info("create: '$name' disp=$disposition opts=${"%x".format(options)} access=${"%x".format(desired)}")
        val share = tree.share
        if (share == null) {
            val pipeName = name.trim('\\').substringAfterLast('\\')
            if (!pipeName.equals("srvsvc", ignoreCase = true)) throw SmbError(Status.OBJECT_NAME_NOT_FOUND)
            val o = Open(nextOpenId++, session, tree, java.io.File(pipeName), pipeName, false, ACCESS_FULL)
            o.pipe = SrvsvcPipe(config.shares)
            opens[o.id] = o
            val st = Stat(false, 0, 0, 0, 0, Attr.NORMAL, 0)
            return createResponse(o, st, 1, emptyList())
        }

        // ストリーム名 (file:stream:$DATA)。既定ストリーム以外は未対応。
        val colon = name.indexOf(':')
        if (colon >= 0) {
            val stream = name.substring(colon + 1)
            if (stream != "" && !stream.equals(":\$DATA", true) && !stream.equals("\$DATA", true)) {
                throw SmbError(Status.OBJECT_NAME_NOT_FOUND)
            }
            name = name.substring(0, colon)
        }
        val (file, rel) = resolve(share, name)
        val readOnly = share.readOnly
        val wantsDir = options and 0x1 != 0
        val wantsFile = options and 0x40 != 0
        // 読み取り専用共有では新規作成・上書きを拒否 (書き込み権付きで開くこと自体は許し、実際の書き込みで拒否する)
        if (readOnly && disposition != 1 && disposition != 3) throw SmbError(Status.ACCESS_DENIED)
        val parent = file.parentFile
        var exists = file.exists()
        if (!exists && rel.isNotEmpty() && parent != null && !parent.isDirectory) throw SmbError(Status.OBJECT_PATH_NOT_FOUND)
        if (exists && file.isDirectory && wantsFile) throw SmbError(Status.FILE_IS_A_DIRECTORY)
        if (exists && !file.isDirectory && wantsDir) throw SmbError(Status.NOT_A_DIRECTORY)

        var action = 1 // OPENED
        when (disposition) {
            1 -> if (!exists) throw SmbError(Status.OBJECT_NAME_NOT_FOUND)
            2 -> if (exists) throw SmbError(Status.OBJECT_NAME_COLLISION)
            4 -> if (!exists) throw SmbError(Status.OBJECT_NAME_NOT_FOUND)
            0, 3, 5 -> {}
            else -> throw SmbError(Status.INVALID_PARAMETER)
        }
        if (!exists) {
            if (readOnly) throw SmbError(Status.ACCESS_DENIED)
            val ok = if (wantsDir) file.mkdir() else try { file.createNewFile() } catch (e: IOException) { false }
            if (!ok) throw SmbError(Status.ACCESS_DENIED)
            exists = true
            action = 2 // CREATED
        } else if (disposition == 0 || disposition == 4 || disposition == 5) {
            if (readOnly) throw SmbError(Status.ACCESS_DENIED)
            if (file.isDirectory) throw SmbError(Status.INVALID_PARAMETER)
            try { java.io.RandomAccessFile(file, "rw").use { it.setLength(0) } } catch (e: IOException) { throw SmbError(Status.ACCESS_DENIED) }
            action = if (disposition == 0) 0 else 3
        }

        val isDir = file.isDirectory
        val access = if (readOnly) ACCESS_READ else ACCESS_FULL
        val o = Open(nextOpenId++, session, tree, file, rel, isDir, access)
        if (!isDir && desired and WRITE_ACCESS_MASK != 0 && !readOnly) o.writable = true
        if (options and 0x1000 != 0 && !readOnly) o.deleteOnClose = true
        val st = statOf(o) ?: throw SmbError(Status.OBJECT_NAME_NOT_FOUND)
        opens[o.id] = o
        return createResponse(o, st, action, ctxNames)
    }

    private fun parseCreateContexts(m: ByteArray, off: Int, len: Int): List<String> {
        val names = ArrayList<String>()
        if (off <= 0 || len <= 0) return names
        var p = off
        while (p + 16 <= m.size && p < off + len) {
            val next = m.i32(p)
            val nameOff = m.u16(p + 4)
            val nameLen = m.u16(p + 6)
            names += String(m.slice(p + nameOff, nameLen), Charsets.ISO_8859_1)
            if (next <= 0) break
            p += next
        }
        return names
    }

    private fun createResponse(o: Open, st: Stat, action: Int, ctxNames: List<String>): Resp {
        val ctx = Buf()
        val wanted = ArrayList<Pair<String, ByteArray>>()
        if ("MxAc" in ctxNames) wanted += "MxAc" to Buf().u32(0).u32(o.access).toByteArray()
        if ("QFid" in ctxNames) wanted += "QFid" to Buf().u64(st.id).u64(0).u64(server.serverGuid.u64(0)).u64(0).toByteArray()
        wanted.forEachIndexed { i, (n, d) ->
            val start = ctx.size
            ctx.u32(0).u16(16).u16(4).u16(0).u16(24).u32(d.size)
            ctx.bytes(n.toByteArray(Charsets.US_ASCII)).zeros(4).bytes(d)
            if (i < wanted.size - 1) { ctx.align(8); ctx.set32(start, ctx.size - start) }
        }
        val c = ctx.toByteArray()
        val b = Buf(160)
        b.u16(89).u8(0).u8(0).u32(action)
        b.u64(fileTime(st.created)).u64(fileTime(st.accessed)).u64(fileTime(st.modified)).u64(fileTime(st.modified))
        b.u64(st.allocation).u64(st.size).u32(st.attributes).u32(0)
        b.u64(o.id).u64(o.id)
        b.u32(if (c.isEmpty()) 0 else 64 + 88).u32(c.size)
        b.bytes(c)
        return Resp(0, b.toByteArray())
    }

    private fun close(r: Req, o: Open): Resp {
        val postQuery = r.msg.u16(r.body + 2) and 1 != 0
        val st = if (postQuery) statOf(o) else null
        closeOpen(o)
        opens.remove(o.id)
        val b = Buf().u16(60).u16(if (st != null) 1 else 0).u32(0)
        if (st != null) {
            b.u64(fileTime(st.created)).u64(fileTime(st.accessed)).u64(fileTime(st.modified)).u64(fileTime(st.modified))
            b.u64(st.allocation).u64(st.size).u32(st.attributes)
        } else b.zeros(52)
        return Resp(0, b.toByteArray())
    }

    private fun closeOpen(o: Open) {
        try { o.raf?.close() } catch (_: IOException) {}
        o.raf = null
        if (o.deleteOnClose) {
            o.file.delete()
            log.info("deleted ${o.path}")
        }
    }

    // ---------------------------------------------------------------- READ / WRITE

    private fun raf(o: Open, write: Boolean): java.io.RandomAccessFile {
        if (o.isDir) throw SmbError(Status.FILE_IS_A_DIRECTORY)
        if (write && (o.tree.share?.readOnly != false)) throw SmbError(Status.ACCESS_DENIED)
        val cur = o.raf
        if (cur != null && (!write || o.writable)) return cur
        try { cur?.close() } catch (_: IOException) {}
        val wantRw = write || o.writable
        val f = try {
            java.io.RandomAccessFile(o.file, if (wantRw) "rw" else "r")
        } catch (e: IOException) {
            throw SmbError(if (o.file.exists()) Status.ACCESS_DENIED else Status.OBJECT_NAME_NOT_FOUND)
        }
        if (wantRw) o.writable = true
        o.raf = f
        return f
    }

    private fun read(r: Req, o: Open): Resp {
        val b = r.body
        val len = r.msg.i32(b + 4).coerceIn(0, 1 shl 23)
        val offset = r.msg.u64(b + 8)
        val data: ByteArray
        val pipe = o.pipe
        if (pipe != null) {
            data = pipe.read(len)
            if (data.isEmpty()) throw SmbError(0xC00000D9.toInt()) // STATUS_PIPE_EMPTY
        } else {
            val f = raf(o, false)
            val size = f.length()
            if (offset >= size && len > 0) throw SmbError(Status.END_OF_FILE)
            val n = minOf(len.toLong(), size - offset).toInt()
            data = ByteArray(n)
            val bb = java.nio.ByteBuffer.wrap(data)
            var pos = offset
            while (bb.hasRemaining()) {
                val k = f.channel.read(bb, pos)
                if (k < 0) break
                pos += k
            }
            if (bb.hasRemaining()) return readResp(data.copyOf(bb.position()))
        }
        return readResp(data)
    }

    private fun readResp(data: ByteArray): Resp {
        val b = Buf(16 + data.size)
        b.u16(17).u8(0x50).u8(0).u32(data.size).u32(0).u32(0).bytes(data)
        return Resp(0, b.toByteArray())
    }

    private fun write(r: Req, o: Open): Resp {
        val b = r.body
        val dataOff = r.msg.u16(b + 2)
        val len = r.msg.i32(b + 4)
        val offset = r.msg.u64(b + 8)
        if (dataOff + len > r.msg.size || len < 0) throw SmbError(Status.INVALID_PARAMETER)
        val pipe = o.pipe
        if (pipe != null) {
            pipe.write(r.msg.copyOfRange(dataOff, dataOff + len))
        } else {
            val f = raf(o, true)
            val bb = java.nio.ByteBuffer.wrap(r.msg, dataOff, len)
            var pos = offset
            try {
                while (bb.hasRemaining()) pos += f.channel.write(bb, pos)
            } catch (e: IOException) {
                throw SmbError(Status.DISK_FULL)
            }
        }
        return Resp(0, Buf().u16(17).u16(0).u32(len).u32(0).u16(0).u16(0).toByteArray())
    }

    // ---------------------------------------------------------------- QUERY_DIRECTORY

    private fun queryDirectory(r: Req, o: Open): Resp {
        if (!o.isDir) throw SmbError(Status.INVALID_PARAMETER)
        val b = r.body
        val cls = r.msg.u8(b + 2)
        val flags = r.msg.u8(b + 3)
        val pattern = String(r.msg.slice(r.msg.u16(b + 24), r.msg.u16(b + 26)), UTF16LE)
        val maxOut = r.msg.i32(b + 28).coerceAtMost(1 shl 20)

        val firstCall = o.dirEntries == null || flags and (0x1 or 0x10) != 0
        if (firstCall) {
            val match = FileInfo.matcher(pattern)
            val list = ArrayList<Pair<String, java.io.File>>()
            if (match(".")) list += "." to o.file
            if (match("..")) list += ".." to (if (o.path.isEmpty()) o.file else (o.file.parentFile ?: o.file))
            val children = SharePath.listChildren(o.tree.share!!.root, o.file)
            children.filter { match(it.name) }.forEach { list += it.name to it }
            o.dirEntries = list
            o.dirPos = 0
            if (list.isEmpty()) throw SmbError(Status.NO_SUCH_FILE)
        }
        val entries = o.dirEntries!!
        if (o.dirPos >= entries.size) throw SmbError(Status.NO_MORE_FILES)

        val out = Buf(minOf(maxOut, 65536))
        var lastStart = -1
        val ro = o.tree.share?.readOnly == true
        while (o.dirPos < entries.size) {
            val (n, f) = entries[o.dirPos]
            val childPath = when (n) { ".", ".." -> o.path; else -> if (o.path.isEmpty()) n else o.path + "\\" + n }
            val st = FileInfo.stat(f, server.fileId(childPath), ro)
            if (st == null) { o.dirPos++; continue }
            val e = FileInfo.dirEntry(cls, n, st) ?: throw SmbError(Status.INVALID_INFO_CLASS)
            val start = (out.size + 7) and 7.inv()
            if (start + e.size > maxOut) {
                if (lastStart < 0) throw SmbError(Status.BUFFER_OVERFLOW)
                break
            }
            out.align(8)
            if (lastStart >= 0) out.set32(lastStart, start - lastStart)
            out.bytes(e)
            lastStart = start
            o.dirPos++
            if (flags and 0x2 != 0) break
        }
        val data = out.toByteArray()
        return Resp(0, Buf().u16(9).u16(72).u32(data.size).bytes(data).toByteArray())
    }

    // ---------------------------------------------------------------- QUERY_INFO / SET_INFO

    private fun statOf(o: Open): Stat? {
        if (o.pipe != null) return Stat(false, 0, 0, 0, 0, Attr.NORMAL, 0)
        return FileInfo.stat(o.file, server.fileId(o.path), o.tree.share?.readOnly == true)
    }

    private fun queryInfo(r: Req, o: Open): Resp {
        val b = r.body
        val type = r.msg.u8(b + 2)
        val cls = r.msg.u8(b + 3)
        val maxOut = r.msg.i32(b + 4)
        val additional = r.msg.i32(b + 16)
        val share = o.tree.share
        val data: ByteArray = when (type) {
            1 -> {
                val st = statOf(o) ?: throw SmbError(Status.OBJECT_NAME_NOT_FOUND)
                FileInfo.fileInfo(cls, st, o.path, o.access, o.deleteOnClose) ?: throw SmbError(Status.INVALID_INFO_CLASS)
            }
            2 -> {
                val root = share?.root ?: throw SmbError(Status.INVALID_PARAMETER)
                FileInfo.fsInfo(cls, root, share.name, share.readOnly) ?: throw SmbError(Status.INVALID_INFO_CLASS)
            }
            3 -> FileInfo.securityDescriptor(additional)
            else -> throw SmbError(Status.NOT_SUPPORTED)
        }
        var status = 0
        var out = data
        if (data.size > maxOut) {
            if (type == 3) {
                return Resp(Status.BUFFER_TOO_SMALL, Buf().u16(9).u8(0).u8(0).u32(4).u32(data.size).toByteArray())
            }
            // 可変長 (名前を含む) クラスは切り詰めて BUFFER_OVERFLOW、固定長は長さ不一致
            if (type == 1 && cls in intArrayOf(9, 18, 22, 48) || type == 2 && cls in intArrayOf(1, 5)) {
                status = Status.BUFFER_OVERFLOW
                out = data.copyOf(maxOut)
            } else throw SmbError(Status.INFO_LENGTH_MISMATCH)
        }
        return Resp(status, Buf().u16(9).u16(72).u32(out.size).bytes(out).toByteArray())
    }

    private fun setInfo(r: Req, o: Open): Resp {
        val b = r.body
        val m = r.msg
        val type = m.u8(b + 2)
        val cls = m.u8(b + 3)
        val len = m.i32(b + 4)
        val off = m.u16(b + 8)
        val d = m.slice(off, len)
        val ok = Resp(0, Buf().u16(2).toByteArray())
        if (type == 3) return ok // ACL の変更は無視
        if (type != 1) throw SmbError(Status.NOT_SUPPORTED)
        val share = o.tree.share ?: throw SmbError(Status.NOT_SUPPORTED)
        if (share.readOnly) throw SmbError(Status.ACCESS_DENIED)
        when (cls) {
            4 -> { // FileBasicInformation
                if (d.size < 36) throw SmbError(Status.INFO_LENGTH_MISMATCH)
                val mtime = d.u64(16)
                if (mtime > 0) o.file.setLastModified(unixMillis(mtime))
            }
            10 -> rename(o, share, d)
            13 -> setDelete(o, d.isNotEmpty() && d[0].toInt() != 0)
            64 -> setDelete(o, d.size >= 4 && d.i32(0) and 1 != 0)
            19 -> {} // AllocationInformation
            20 -> { // EndOfFileInformation
                if (d.size < 8) throw SmbError(Status.INFO_LENGTH_MISMATCH)
                try { raf(o, true).setLength(d.u64(0)) } catch (e: IOException) { throw SmbError(Status.DISK_FULL) }
            }
            39 -> {} // ValidDataLength
            else -> throw SmbError(Status.INVALID_INFO_CLASS)
        }
        return ok
    }

    private fun setDelete(o: Open, delete: Boolean) {
        if (delete && o.isDir && (o.file.list()?.isNotEmpty() == true)) throw SmbError(Status.DIRECTORY_NOT_EMPTY)
        if (delete && o.path.isEmpty()) throw SmbError(Status.ACCESS_DENIED)
        o.deleteOnClose = delete
    }

    private fun rename(o: Open, share: SmbShare, d: ByteArray) {
        if (d.size < 20) throw SmbError(Status.INFO_LENGTH_MISMATCH)
        val replace = d[0].toInt() != 0
        val nameLen = d.i32(16)
        val newName = String(d.slice(20, nameLen), UTF16LE)
        val (target, rel) = resolve(share, newName)
        if (o.path.isEmpty() || rel.isEmpty()) throw SmbError(Status.ACCESS_DENIED)
        if (target.exists() && !target.path.equals(o.file.path, ignoreCase = true)) {
            if (!replace || target.isDirectory) throw SmbError(Status.OBJECT_NAME_COLLISION)
        }
        if (target.parentFile?.isDirectory != true) throw SmbError(Status.OBJECT_PATH_NOT_FOUND)
        try { o.raf?.close() } catch (_: IOException) {}
        o.raf = null
        try {
            if (replace) java.nio.file.Files.move(o.file.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            else java.nio.file.Files.move(o.file.toPath(), target.toPath())
        } catch (e: Exception) {
            throw SmbError(Status.ACCESS_DENIED)
        }
        log.info("renamed ${o.path} -> $rel")
        // 同じパス配下を開いているハンドルの参照先も追従させる
        val oldPath = o.path
        for (other in opens.values) {
            if (other.tree.share !== share) continue
            if (other === o || other.path.equals(oldPath, true)) {
                other.file = target; other.path = rel
            } else if (other.path.startsWith(oldPath + "\\", true)) {
                val suffix = other.path.substring(oldPath.length + 1)
                other.path = rel + "\\" + suffix
                other.file = java.io.File(target, suffix.replace('\\', '/'))
            }
        }
    }

    // ---------------------------------------------------------------- IOCTL

    private fun ioctl(r: Req, o: Open?): Resp {
        val b = r.body
        val m = r.msg
        val code = m.i32(b + 4)
        val inOff = m.i32(b + 24)
        val inLen = m.i32(b + 28)
        val maxOut = m.i32(b + 44)
        val input = m.slice(inOff, inLen)
        var status = 0
        val output: ByteArray = when (code) {
            0x00140204 -> { // FSCTL_VALIDATE_NEGOTIATE_INFO
                Buf().u32(serverCaps).bytes(server.serverGuid).u16(serverSecMode).u16(dialect).toByteArray()
            }
            0x0011C017 -> { // FSCTL_PIPE_TRANSCEIVE
                val pipe = o?.pipe ?: throw SmbError(Status.INVALID_DEVICE_REQUEST)
                pipe.write(input)
                val res = pipe.read(maxOut)
                if (pipe.available() > 0) status = Status.BUFFER_OVERFLOW
                res
            }
            0x00060194, 0x000601B0 -> throw SmbError(Status.FS_DRIVER_REQUIRED) // DFS referral
            0x000900A8 -> throw SmbError(Status.NOT_A_REPARSE_POINT) // FSCTL_GET_REPARSE_POINT
            else -> throw SmbError(Status.NOT_SUPPORTED)
        }
        val fileId = m.copyOfRange(b + 8, b + 24)
        val resp = Buf(64 + output.size)
        resp.u16(49).u16(0).u32(code).bytes(fileId)
        resp.u32(112).u32(0).u32(112).u32(output.size).u32(0).u32(0)
        resp.bytes(output)
        return Resp(status, resp.toByteArray())
    }

    // ---------------------------------------------------------------- パス

    private fun resolve(share: SmbShare, name: String): Pair<java.io.File, String> = SharePath.resolve(share.root, name)
}
