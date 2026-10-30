package dev.andsmb.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import dev.andsmb.smb.SmbConfig
import dev.andsmb.smb.SmbLogger
import dev.andsmb.smb.SmbServer
import dev.andsmb.smb.SmbShare
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** SMB サーバーを動かし続けるフォアグラウンドサービス。 */
class SmbService : Service() {

    data class State(val running: Boolean = false, val error: String? = null, val port: Int = 0)

    companion object {
        private const val TAG = "AndSmb"
        private const val CHANNEL = "server"
        private const val ACTION_STOP = "dev.andsmb.app.STOP"

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        private val _log = MutableStateFlow<List<String>>(emptyList())
        val log: StateFlow<List<String>> = _log

        private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun appendLog(line: String) {
            val l = "${timeFmt.format(Date())} $line"
            _log.value = (_log.value + l).takeLast(200)
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, SmbService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SmbService::class.java))
        }

        /** 端末の IPv4 アドレス一覧 (Wi-Fi・テザリングなど) */
        fun ipAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private var server: SmbServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "SMB サーバー", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SmbService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val ip = ipAddresses().firstOrNull() ?: "?"
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SMB サーバー実行中")
            .setContentText("smb://$ip:${s.port}/${s.shareName}")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
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
        val config = SmbConfig(
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
            registerNsd(config.serverName, s.port)
            _state.value = State(running = true, port = s.port)
        } catch (e: Exception) {
            logger.error("起動に失敗しました", e)
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

    /** Bonjour (_smb._tcp) で公開して Mac の Finder サイドバーに出るようにする */
    private fun registerNsd(name: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_smb._tcp."
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = appendLog("Bonjour 公開: ${info.serviceName}")
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = appendLog("Bonjour 公開失敗 ($code)")
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
        nsdListener?.let { l ->
            try { getSystemService(NsdManager::class.java).unregisterService(l) } catch (_: Exception) {}
        }
        nsdListener = null
        server?.stop()
        server = null
        wifiLock?.release()
        wakeLock?.release()
        _state.value = _state.value.copy(running = false)
        super.onDestroy()
    }
}
