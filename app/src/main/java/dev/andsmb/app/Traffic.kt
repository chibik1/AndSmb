package dev.andsmb.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 1日分の通信量 (バイト) */
data class DayTraffic(val date: LocalDate, val received: Long, val sent: Long) {
    val total get() = received + sent
}

data class TrafficSnapshot(
    /** サーバーを起動してからの受信・送信 */
    val sessionReceived: Long = 0,
    val sessionSent: Long = 0,
    val connections: Int = 0,
    /** 古い順に直近7日 (最後が今日) */
    val week: List<DayTraffic> = emptyList(),
) {
    val today: DayTraffic? get() = week.lastOrNull()
}

/**
 * 日ごとの通信量を SharedPreferences に貯めて、UI に流す。
 * キーは "rx_20261031" / "tx_20261031"。30日より古い日は消す。
 */
object Traffic {
    private const val PREFS = "traffic"
    private const val KEEP_DAYS = 30L
    private val keyFmt = DateTimeFormatter.BASIC_ISO_DATE

    private val _state = MutableStateFlow(TrafficSnapshot())
    val state: StateFlow<TrafficSnapshot> = _state

    fun load(ctx: Context) {
        _state.value = _state.value.copy(week = readWeek(ctx))
    }

    /** サービスから定期的に呼ぶ。差分を今日の分に足して、表示を更新する。 */
    fun record(ctx: Context, deltaReceived: Long, deltaSent: Long, sessionReceived: Long, sessionSent: Long, connections: Int) {
        if (deltaReceived > 0 || deltaSent > 0) {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val day = LocalDate.now().format(keyFmt)
            val e = p.edit()
                .putLong("rx_$day", p.getLong("rx_$day", 0) + deltaReceived)
                .putLong("tx_$day", p.getLong("tx_$day", 0) + deltaSent)
            val oldest = LocalDate.now().minusDays(KEEP_DAYS).format(keyFmt)
            p.all.keys.filter { it.length == 11 && it.substring(3) < oldest }.forEach { e.remove(it) }
            e.apply()
        }
        _state.value = TrafficSnapshot(sessionReceived, sessionSent, connections, readWeek(ctx))
    }

    fun stopped(ctx: Context) {
        _state.value = _state.value.copy(connections = 0, week = readWeek(ctx))
    }

    private fun readWeek(ctx: Context): List<DayTraffic> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now()
        return (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val k = d.format(keyFmt)
            DayTraffic(d, p.getLong("rx_$k", 0), p.getLong("tx_$k", 0))
        }
    }

    /** 1536 → "1.5 KB" */
    fun format(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (i == 0) "$bytes B" else if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
    }
}
