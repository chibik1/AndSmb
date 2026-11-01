package dev.andsmb.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.andsmb.smb.SmbConfig
import dev.andsmb.smb.SmbLogger
import dev.andsmb.smb.SmbServer
import dev.andsmb.smb.SmbShare
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** SMB サーバーを動かし続けるフォアグラウンドサービス。 */
class SmbService : Service() {

    data class State(
        val running: Boolean = false,
        val error: String? = null,
        val port: Int = 0,
        /** Bonjour で公開できた名前 (同名の機器がいると "PIXEL4A (2)" のように変わる) */
        val bonjourName: String? = null,
    )

    /** 画面に出す、つながっている端末 1 台ぶん。rxRate / txRate は直近 1 秒あたりのバイト数 */
    data class ClientLive(val address: String, val name: String, val connectedAt: Long, val rxRate: Long, val txRate: Long)

    companion object {
        private const val TAG = "AndSmb"
        private const val CHANNEL = "server"
        private const val ACTION_STOP = "dev.andsmb.app.STOP"

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        private val _clients = MutableStateFlow<List<ClientLive>>(emptyList())
        val clients: StateFlow<List<ClientLive>> = _clients

        private val _log = MutableStateFlow<List<String>>(emptyList())
        val log: StateFlow<List<String>> = _log

        private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

        /** ログはファイルにも残し、アプリを閉じても後から原因を追えるようにする */
        private var logFile: File? = null
        private const val LOG_MAX_BYTES = 512 * 1024

        /** ログファイルを決めて、前回までの末尾を画面用に読み込む */
        @Synchronized
        fun initLog(ctx: Context) {
            if (logFile != null) return
            val f = File(ctx.filesDir, "log.txt")
            logFile = f
            val old = try { if (f.exists()) f.readLines().takeLast(200) else emptyList() } catch (e: Exception) { emptyList() }
            _log.value = (old + _log.value).takeLast(200)
        }

        @Synchronized
        fun appendLog(line: String) {
            val l = "${timeFmt.format(Date())} $line"
            _log.value = (_log.value + l).takeLast(200)
            val f = logFile ?: return
            try {
                // 大きくなったら 1 世代だけ残して作り直す
                if (f.length() > LOG_MAX_BYTES) f.renameTo(File(f.parentFile, "log.1.txt"))
                f.appendText(l + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "log write failed", e)
            }
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, SmbService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SmbService::class.java))
        }

        /** サーバーを公開してよいネットワーク: Wi-Fi と有線 LAN (モバイルデータ・VPN は対象外) */
        private fun isLan(caps: NetworkCapabilities?) = caps != null &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        private fun ipv4(lp: LinkProperties?): Inet4Address? =
            lp?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull { !it.isLoopbackAddress }

        /** 接続中の Wi-Fi (または有線 LAN) と、その IPv4 アドレス */
        fun lanAddress(ctx: Context): Pair<Network, Inet4Address>? {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
            @Suppress("DEPRECATION")
            return cm.allNetworks.firstNotNullOfOrNull { n ->
                if (!isLan(cm.getNetworkCapabilities(n))) null else ipv4(cm.getLinkProperties(n))?.let { n to it }
            }
        }

        /** 接続に使うアドレス (Wi-Fi の IPv4)。Wi-Fi につながっていなければ空 */
        fun ipAddresses(ctx: Context): List<String> = listOfNotNull(lanAddress(ctx)?.second?.hostAddress)
    }

    private var server: SmbServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var storageReceiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastReceived = 0L
    private var lastSent = 0L
    private val prevClient = HashMap<String, Pair<Long, Long>>()
    private var prevClientAt = 0L

    /** 1秒ごとに、端末ごとの受信・送信の速さを出す */
    private val clientsTick = object : Runnable {
        override fun run() {
            refreshClients()
            handler.postDelayed(this, 1000)
        }
    }

    private fun refreshClients() {
        val srv = server ?: return
        val now = System.nanoTime()
        val secs = if (prevClientAt == 0L) 1.0 else ((now - prevClientAt) / 1e9).coerceAtLeast(0.2)
        val list = srv.clients().map { c ->
            val prev = prevClient[c.address]
            // 接続が閉じて合計が減ったときは 0 として扱う
            val rx = if (prev == null) 0L else ((c.received - prev.first).coerceAtLeast(0) / secs).toLong()
            val tx = if (prev == null) 0L else ((c.sent - prev.second).coerceAtLeast(0) / secs).toLong()
            ClientLive(c.address, c.name.ifBlank { c.address }, c.connectedAt, rx, tx)
        }
        prevClient.clear()
        srv.clients().forEach { prevClient[it.address] = it.received to it.sent }
        prevClientAt = now
        _clients.value = list
    }

    /** 2秒ごとにサーバーの通信量を読み、日ごとの記録に足す */
    private val trafficTick = object : Runnable {
        override fun run() {
            flushTraffic()
            handler.postDelayed(this, 2000)
        }
    }

    private fun flushTraffic() {
        val srv = server ?: return
        val rx = srv.bytesReceived.get()
        val tx = srv.bytesSent.get()
        Traffic.record(this, rx - lastReceived, tx - lastSent, rx, tx, srv.connectionCount)
        lastReceived = rx
        lastSent = tx
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        initLog(this)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val settings = Settings.load(this)
        startInForeground(settings)
        if (server == null) startServer(settings)
        return START_STICKY
    }

    private fun startInForeground(s: Settings) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SmbService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val ip = ipAddresses(this).firstOrNull() ?: "?"
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("smb://$ip:${s.port}/${s.shareName}")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun startServer(s: Settings) {
        val logger = object : SmbLogger {
            override fun info(msg: String) {
                Log.i(TAG, msg); appendLog(msg)
            }

            override fun error(msg: String, t: Throwable?) {
                Log.e(TAG, msg, t); appendLog("ERROR: $msg${t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""}")
            }
        }
        // Wi-Fi のアドレスだけで待ち受ける (モバイルデータや VPN 側からは届かない)
        val lan = lanAddress(this)
        if (lan == null) {
            appendLog(getString(R.string.log_no_wifi))
            _state.value = State(running = false, error = getString(R.string.err_connect_wifi_first))
            stopSelf()
            return
        }
        val (network, address) = lan
        val config = SmbConfig(
            bindAddress = address.hostAddress,
            port = s.port,
            serverName = (Build.MODEL ?: "ANDROID").filter { it.isLetterOrDigit() }.take(15).ifEmpty { "ANDROID" }.uppercase(),
            shares = listOf(SmbShare(s.shareName, File(s.path), s.readOnly, "Android storage")),
            users = mapOf(s.user to s.password),
        )
        try {
            val srv = SmbServer(config, logger)
            srv.start()
            server = srv
            acquireLocks()
            _state.value = State(running = true, port = s.port)
            registerNsd(config.serverName, s.port)
            watchNetwork(network, address)
            watchStorage(File(s.path))
            lastReceived = 0; lastSent = 0
            handler.post(trafficTick)
            prevClient.clear(); prevClientAt = 0L
            handler.post(clientsTick)
        } catch (e: Exception) {
            logger.error(getString(R.string.log_start_failed), e)
            _state.value = State(running = false, error = e.message ?: e.javaClass.simpleName)
            stopSelf()
        }
    }

    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "AndSmb").apply { setReferenceCounted(false); acquire() }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndSmb:server").apply { setReferenceCounted(false); acquire() }
    }

    private fun stopFor(reason: String) = handler.post {
        if (server == null) return@post
        appendLog(reason)
        _state.value = _state.value.copy(error = reason)
        stopSelf()
    }

    /** 待ち受けている Wi-Fi が切れたりアドレスが変わったりしたら、サーバーを止める */
    private fun watchNetwork(network: Network, address: Inet4Address) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(n: Network) {
                if (n == network) stopFor(getString(R.string.stopped_wifi_lost))
            }

            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                if (n == network && lp.linkAddresses.none { it.address == address }) stopFor(getString(R.string.stopped_ip_changed))
            }
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(req, cb)
        networkCallback = cb
    }

    /** 共有フォルダのある SD カードや USB メモリが外されたら、サーバーを止める */
    private fun watchStorage(root: File) {
        val path = root.absolutePath
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val mount = intent.data?.path ?: return
                if (path == mount || path.startsWith("$mount/")) stopFor(getString(R.string.stopped_storage_removed))
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        storageReceiver = receiver
    }

    /** Bonjour (_smb._tcp) で公開して Mac の Finder サイドバーに出るようにする */
    private fun registerNsd(name: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_smb._tcp."
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                appendLog(getString(R.string.log_bonjour_ok, info.serviceName))
                handler.post { if (server != null) _state.value = _state.value.copy(bonjourName = info.serviceName) }
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = appendLog(getString(R.string.log_bonjour_fail, code))
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        try {
            getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "NSD registration failed", e)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(trafficTick)
        handler.removeCallbacks(clientsTick)
        _clients.value = emptyList()
        flushTraffic()
        Traffic.stopped(this)
        nsdListener?.let { l ->
            try { getSystemService(NsdManager::class.java).unregisterService(l) } catch (_: Exception) {}
        }
        nsdListener = null
        networkCallback?.let { cb ->
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        networkCallback = null
        storageReceiver?.let { r -> try { unregisterReceiver(r) } catch (_: Exception) {} }
        storageReceiver = null
        server?.stop()
        server = null
        wifiLock?.release()
        wakeLock?.release()
        _state.value = _state.value.copy(running = false)
        super.onDestroy()
    }
}
