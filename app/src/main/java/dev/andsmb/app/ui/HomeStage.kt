package dev.andsmb.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.andsmb.app.R
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 画面に出す、つながっている端末 1 台ぶん。rxRate / txRate は 1 秒あたりのバイト数 */
class ClientUi(
    val key: String,
    val name: String,
    val address: String,
    val connectedAt: Long,
    val rxRate: Long,
    val txRate: Long,
)

private const val MAX_SLOTS = 8

/** 軌道: 横長 1 本と、左右に傾けた 2 本。rx / ry は地球の半径に対する倍率、tilt は回転角 (ラジアン) */
private class Ring(val rx: Float, val ry: Float, val tilt: Float)

private val Rings = listOf(Ring(1.5f, 0.30f, -0.34f), Ring(1.42f, 0.42f, 1.05f), Ring(1.42f, 0.42f, -1.05f))

/** 端末の置き場所 (軌道の番号, 前面の弧のどこか 0..1)。最初の数台が上下左右に散るよう並べてある */
private val Slots = listOf(
    0 to 0.08f, 0 to 0.92f, 1 to 0.10f, 1 to 0.90f, 2 to 0.10f, 2 to 0.90f, 0 to 0.50f, 1 to 0.50f,
)

/** ラベルの現在位置。画面の更新ごとに目標へ近づく (ジャンプしない) */
private class TagState {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var w = 0f
    var placed by mutableStateOf(false)
    var right: Boolean? = null
}

private class GlobeModel(val dots: FloatArray) {
    var t by mutableLongStateOf(0L)

    // 1 フレームぶんの計算結果 (描画とラベルの両方が読む)
    var w = 0f; var h = 0f
    var cx = 0f; var cy = 0f; var r = 0f
    var rot = 0f
    var server = Offset.Zero
    var nodeKeys = emptyList<String>()
    var nodePts = emptyList<Offset>()
    var alive = false

    val slotOf = HashMap<String, Int>()
    val tags = HashMap<String, TagState>()

    // ポップアップ
    var popX by mutableFloatStateOf(0f)
    var popY by mutableFloatStateOf(0f)
    var popW = 0f; var popH = 0f
    var popFor: String? = null

    var tagH = 0f; var tagStep = 0f; var tagGap = 0f

    private var last = 0L
    private var px = 0f; private var py = 0f; private var pz = 0f
    fun pxOut() = px
    fun pyOut() = py
    fun pzOut() = pz

    /** 単位球の点 (v0, v1, v2) を、現在の回転と傾きで画面に写す。結果は px / py / pz */
    fun project(v0: Float, v1: Float, v2: Float, s: Float = 1f) {
        val cr = cos(rot); val sr = sin(rot)
        val x = v0 * cr + v2 * sr
        val z0 = -v0 * sr + v2 * cr
        val cT = TILT_COS; val sT = TILT_SIN
        val y = v1 * cT - z0 * sT
        pz = v1 * sT + z0 * cT
        px = cx + r * s * x
        py = cy - r * s * y
    }

    fun ringPoint(ring: Ring, th: Float): Offset {
        val x = cos(th) * r * ring.rx
        val y = sin(th) * r * ring.ry
        val cr = cos(ring.tilt); val sr = sin(ring.tilt)
        return Offset(cx + x * cr - y * sr, cy + x * sr + y * cr)
    }

    fun step(
        now: Long, w: Float, h: Float, top: Float, bottom: Float,
        clients: List<ClientUi>, running: Boolean, reduced: Boolean, selected: String?,
    ) {
        val dt = if (last == 0L) 16L else min(now - last, 100L)
        last = now
        this.w = w; this.h = h
        val avail = max(h - top - bottom, 1f)
        cx = w / 2; cy = top + avail / 2
        r = max(60f, min(w * 0.27f, avail / 2 / 1.4f))
        rot = if (reduced) 0f else (sin(now / 7000.0) * 0.42).toFloat()
        alive = running

        project(cos(SERVER_LAT) * sin(SERVER_LON), sin(SERVER_LAT), cos(SERVER_LAT) * cos(SERVER_LON))
        server = Offset(px, py)

        // 端末の置き場所を決める (つながっている間は同じ場所を使う)
        slotOf.keys.retainAll(clients.map { it.key }.toSet())
        tags.keys.retainAll(clients.map { it.key }.toSet())
        val keys = ArrayList<String>(); val pts = ArrayList<Offset>()
        if (running) {
            for (c in clients) {
                val slot = slotOf.getOrPut(c.key) { (0 until MAX_SLOTS).firstOrNull { it !in slotOf.values } ?: (slotOf.size % MAX_SLOTS) }
                val (ri, base) = Slots[slot % MAX_SLOTS]
                val th = PI.toFloat() * base + if (reduced) 0f else (0.28 * sin(now / 3400.0 + slot * 1.3)).toFloat()
                keys += c.key; pts += ringPoint(Rings[ri], th)
            }
        }
        nodeKeys = keys; nodePts = pts

        // ラベル: 地球から外向きに置き、近いものは縦にずらす。位置は目標へなめらかに寄せる
        val k = if (reduced) 1f else (1 - exp(-dt / 110.0)).toFloat()
        for ((i, key) in keys.withIndex()) {
            val ts = tags.getOrPut(key) { TagState() }
            val dx = pts[i].x - cx
            val rt = ts.right
            ts.right = when {
                rt == null -> dx >= 0
                rt == true && dx < -24f -> false
                rt == false && dx > 24f -> true
                else -> rt
            }
        }
        for (rightSide in listOf(false, true)) {
            val side = keys.indices.filter { tags[keys[it]]?.right == rightSide }.sortedBy { pts[it].y }
            var lastY = -9999f
            for (i in side) {
                val ts = tags[keys[i]] ?: continue
                val tw = if (ts.w > 0f) ts.w else tagStep * 3
                val y0 = max(pts[i].y - tagH / 2, lastY + tagStep); lastY = y0
                val x0 = (if (rightSide) pts[i].x + tagGap else pts[i].x - tagGap - tw).coerceIn(2f, max(2f, w - tw - 2f))
                if (!ts.placed) { ts.x = x0; ts.y = y0; ts.placed = true }
                ts.x += (x0 - ts.x) * k; ts.y += (y0 - ts.y) * k
            }
        }

        // ポップアップはラベルのすぐ下 (入らなければ上) に付いてくる
        val sel = selected?.let { tags[it] }
        if (sel != null && popW > 0f) {
            val tx = (sel.x + sel.w / 2 - popW / 2).coerceIn(6f, max(6f, w - popW - 6f))
            var ty = sel.y + tagH + 8f
            if (ty + popH > h - bottom + 20f) ty = sel.y - popH - 10f
            ty = ty.coerceIn(top - 30f, max(top - 30f, h - popH - 6f))
            if (popFor != selected) { popX = tx; popY = ty; popFor = selected }
            popX += (tx - popX) * k; popY += (ty - popY) * k
        } else popFor = null

        t = now
    }

    companion object {
        const val TILT = 0.34f
        val TILT_COS = cos(TILT); val TILT_SIN = sin(TILT)
        val SERVER_LAT = (22 * PI / 180).toFloat(); val SERVER_LON = (14 * PI / 180).toFloat()
    }
}

private fun landDots(): FloatArray {
    val out = ArrayList<Float>()
    var lat = -62
    while (lat <= 72) {
        val step = 6.0 / max(0.35, cos(lat * PI / 180))
        var lon = -180.0
        while (lon < 180) {
            val n = sin(lat * 0.19 + 1.2) * sin(lon * 0.045 + 0.6) + 0.6 * sin(lat * 0.07 - lon * 0.031 + 2) + 0.35 * sin(lon * 0.12 + lat * 0.1)
            if (n > 0.55) {
                val a = lat * PI / 180; val o = lon * PI / 180
                out += (cos(a) * sin(o)).toFloat(); out += sin(a).toFloat(); out += (cos(a) * cos(o)).toFloat()
            }
            lon += step
        }
        lat += 6
    }
    return out.toFloatArray()
}

/** 数字と単位に分けた速さ (例: "1.8" と "MB/s") */
internal fun rateParts(bytesPerSec: Long): Pair<String, String> {
    val kb = bytesPerSec / 1024.0
    return if (kb >= 1024) String.format(Locale.US, "%.1f", kb / 1024) to "MB/s" else kb.roundToInt().toString() to "KB/s"
}

/**
 * ホーム画面の主役。上に稼働状態と太字のアドレス、中央に地球と軌道、
 * 軌道上の端末に名前だけのタグが付いてくる。タグを押すと詳細がポップアップする。
 * 下の帯に接続台数と合計の速さ。
 */
@Composable
fun HomeStage(
    running: Boolean,
    ip: String?,
    port: Int,
    clients: List<ClientUi>,
    onCopyAddress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = colors
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val reduced = remember {
        android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val model = remember { GlobeModel(landDots()) }
    var selected by remember { mutableStateOf<String?>(null) }
    if (selected != null && clients.none { it.key == selected }) selected = null
    if (!running && selected != null) selected = null

    val clientsNow by rememberUpdatedState(clients)
    val runningNow by rememberUpdatedState(running)
    val selectedNow by rememberUpdatedState(selected)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val topPx = with(density) { 112.dp.toPx() }
        val bottomPx = with(density) { 92.dp.toPx() }
        val tagH = with(density) { 28.dp.toPx() }
        val tagStep = with(density) { 30.dp.toPx() }
        val tagGap = with(density) { 14.dp.toPx() }
        model.tagH = tagH; model.tagStep = tagStep; model.tagGap = tagGap

        LaunchedEffect(wPx, hPx, reduced) {
            if (reduced) {
                // 動かさない設定のときは、変化があったときだけ描き直す
                model.step(System.currentTimeMillis(), wPx, hPx, topPx, bottomPx, clientsNow, runningNow, true, selectedNow)
                while (true) {
                    androidx.compose.runtime.withFrameMillis { }
                    model.step(System.currentTimeMillis(), wPx, hPx, topPx, bottomPx, clientsNow, runningNow, true, selectedNow)
                    kotlinx.coroutines.delay(1000)
                }
            } else {
                while (true) {
                    androidx.compose.runtime.withFrameMillis { now ->
                        model.step(now, wPx, hPx, topPx, bottomPx, clientsNow, runningNow, false, selectedNow)
                    }
                }
            }
        }

        // 外側を押したらポップアップを閉じる
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { selected = null } })

        GlobeCanvas(model, running, selected)

        // 上: 稼働状態と、太字のアドレス・ポート
        Column(Modifier.align(Alignment.TopCenter).padding(top = Space.s1), horizontalAlignment = Alignment.CenterHorizontally) {
            StatusPill(running)
            AddressBlock(running, ip, port, onCopyAddress)
        }

        // タグ (名前だけ)
        for (cl in clients) {
            if (!running) continue
            val ts = model.tags.getOrPut(cl.key) { TagState() }
            if (!ts.placed) continue
            ClientTag(
                name = cl.name,
                selected = selected == cl.key,
                onClick = { selected = if (selected == cl.key) null else cl.key },
                modifier = Modifier
                    .offset { IntOffset(ts.x.roundToInt(), (ts.y - with(density) { 10.dp.toPx() }).roundToInt()) }
                    .onSizeChanged { ts.w = it.width.toFloat() },
            )
        }

        // ポップアップ
        val sel = clients.firstOrNull { it.key == selected }
        if (sel != null && running) {
            ClientPopup(
                cl = sel,
                modifier = Modifier
                    .offset { IntOffset(model.popX.roundToInt(), model.popY.roundToInt()) }
                    .onSizeChanged { model.popW = it.width.toFloat(); model.popH = it.height.toFloat() },
            )
        }

        // 下の帯: 接続台数と合計の速さ
        Strip(
            count = if (running) clients.size else 0,
            rx = if (running) clients.sumOf { it.rxRate } else 0L,
            tx = if (running) clients.sumOf { it.txRate } else 0L,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        )
    }
}

@Composable
private fun StatusPill(running: Boolean) {
    val c = colors
    val dot = if (running) c.success else c.inkMuted
    Row(
        Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(c.surfaceCard)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(
            stringResource(if (running) R.string.status_running else R.string.status_stopped),
            style = Type.bodySm.copy(fontWeight = FontWeight(700)), color = c.ink,
        )
    }
}

@Composable
private fun AddressBlock(running: Boolean, ip: String?, port: Int, onCopy: () -> Unit) {
    val c = colors
    val copyHint = stringResource(R.string.copy_hint)
    Column(
        Modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = ip != null, role = Role.Button, onClickLabel = copyHint, onClick = onCopy)
            .padding(horizontal = Space.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.smb_address), style = Type.monoSm.copy(fontSize = 10.sp, letterSpacing = 1.sp), color = c.inkMuted)
        if (ip == null) {
            Text(stringResource(R.string.no_network), style = Type.headline, color = c.danger)
        } else {
            val text: AnnotatedString = buildAnnotatedString {
                append(ip)
                withStyle(SpanStyle(color = c.accent)) { append(":$port") }
            }
            Text(
                text,
                style = Type.display.copy(fontWeight = FontWeight(700)),
                color = if (running) c.ink else c.ink.copy(alpha = 0.55f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ClientTag(name: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = colors
    val desc = stringResource(R.string.client_tag_desc, name)
    // 見た目は高さ 28dp、押せる範囲は上下に 10dp ずつ足して 48dp
    Box(
        modifier
            .semantics(mergeDescendants = true) { contentDescription = desc }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Row(
            Modifier
                .height(28.dp)
                .clip(CircleShape)
                .background(if (selected) c.ink else c.surfaceRaised.copy(alpha = 0.92f))
                .border(1.dp, c.borderField.copy(alpha = 0.5f), CircleShape)
                .padding(start = 8.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (selected) c.onInk else c.accent))
            Text(
                name,
                style = Type.caption.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight(600)),
                color = if (selected) c.onInk else c.ink,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
        }
    }
}

@Composable
private fun ClientPopup(cl: ClientUi, modifier: Modifier) {
    val c = colors
    val minutes = ((System.currentTimeMillis() - cl.connectedAt) / 60_000).toInt()
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .width(212.dp)
            .clip(shape)
            .background(c.surfaceRaised)
            .border(1.dp, c.borderField.copy(alpha = 0.55f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(cl.name, style = Type.headline, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(cl.address, style = Type.monoSm.copy(fontSize = 12.sp), color = c.inkMuted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PopupStat(stringResource(R.string.received), cl.rxRate, Feather.arrowDown, Modifier.weight(1f))
            PopupStat(stringResource(R.string.sent), cl.txRate, Feather.arrowUp, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (minutes < 1) stringResource(R.string.client_since_now) else stringResource(R.string.client_since_min, minutes),
            style = Type.bodySm.copy(fontSize = 12.sp, lineHeight = 16.sp), color = c.inkMuted,
        )
    }
}

@Composable
private fun PopupStat(label: String, rate: Long, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    val c = colors
    val (n, u) = rateParts(rate)
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(c.surfaceTile).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            FIcon(icon, c.inkMuted, 11.dp)
            Text(label, style = Type.monoSm.copy(fontSize = 10.sp, lineHeight = 14.sp), color = c.inkMuted)
        }
        Text(
            "$n $u",
            style = Type.headline.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight(700)),
            color = c.ink, maxLines = 1, softWrap = false, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 下の帯。ラベルは左、数字は右に揃える */
@Composable
private fun Strip(count: Int, rx: Long, tx: Long, modifier: Modifier) {
    val c = colors
    val shape = RoundedCornerShape(20.dp)
    Row(modifier.fillMaxWidth().clip(shape).background(c.surfaceCard.copy(alpha = 0.92f))) {
        StripCell(stringResource(R.string.strip_connected), null, count.toString(), stringResource(R.string.unit_devices), Modifier.weight(0.8f))
        StripDivider()
        val (rn, ru) = rateParts(rx)
        StripCell(stringResource(R.string.received), Feather.arrowDown, rn, ru, Modifier.weight(1f))
        StripDivider()
        val (tn, tu) = rateParts(tx)
        StripCell(stringResource(R.string.sent), Feather.arrowUp, tn, tu, Modifier.weight(1f))
    }
}

@Composable
private fun StripDivider() {
    Box(Modifier.width(1.dp).height(60.dp).background(colors.borderField.copy(alpha = 0.35f)))
}

@Composable
private fun StripCell(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, number: String, unit: String, modifier: Modifier) {
    val c = colors
    Column(modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (icon != null) FIcon(icon, c.inkMuted, 12.dp)
            Text(label, style = Type.monoSm.copy(fontSize = 10.sp, lineHeight = 14.sp), color = c.inkMuted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
            Text(number, style = Type.title.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight(700)), color = c.ink, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(3.dp))
            Text(unit, style = Type.monoSm.copy(fontSize = 10.sp, lineHeight = 14.sp), color = c.inkMuted, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
private fun GlobeCanvas(model: GlobeModel, running: Boolean, selected: String?) {
    val c = colors
    Canvas(Modifier.fillMaxSize()) {
        val now = model.t // 毎フレーム描き直すための読み取り
        val ink = c.ink; val line = c.borderField; val acc = c.accent
        val alive = model.alive
        val cx = model.cx; val cy = model.cy; val r = model.r
        if (r <= 0f) return@Canvas
        val hair = 1.dp.toPx()

        // 軌道の奥側 (地球の後ろを通る半分)
        val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx()))
        fun ringArc(ring: Ring, start: Float, sweep: Float, color: Color) {
            rotate(ring.tilt * 180f / PI.toFloat(), pivot = Offset(cx, cy)) {
                drawArc(
                    color, start, sweep, useCenter = false,
                    topLeft = Offset(cx - r * ring.rx, cy - r * ring.ry), size = Size(2 * r * ring.rx, 2 * r * ring.ry),
                    style = Stroke(hair, pathEffect = dash),
                )
            }
        }
        Rings.forEach { ringArc(it, 180f, 180f, line.copy(alpha = 0.35f)) }

        // 地球の本体
        drawCircle(
            brush = Brush.radialGradient(
                listOf(acc.copy(alpha = if (alive) 0.18f else 0.07f), acc.copy(alpha = 0.02f)),
                center = Offset(cx - r * 0.3f, cy - r * 0.35f), radius = r * 1.3f,
            ),
            radius = r, center = Offset(cx, cy),
        )

        // 緯線・経線 (手前は濃く、奥は薄く)
        val front = Path(); val back = Path()
        fun seg(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) {
            val p = if ((az + bz) / 2 > 0) front else back
            p.moveTo(ax, ay); p.lineTo(bx, by)
        }
        fun sphere(lat: Float, lon: Float, out: FloatArray) {
            val a = lat * PI.toFloat() / 180f; val o = lon * PI.toFloat() / 180f
            model.project(cos(a) * sin(o), sin(a), cos(a) * cos(o))
            out[0] = model.pxOut(); out[1] = model.pyOut(); out[2] = model.pzOut()
        }
        val p0 = FloatArray(3); val p1 = FloatArray(3)
        var lon = -180
        while (lon < 180) {
            var lat = -90; var have = false
            while (lat <= 90) {
                sphere(lat.toFloat(), lon.toFloat(), p1)
                if (have) seg(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2])
                p1.copyInto(p0); have = true; lat += 6
            }
            lon += 30
        }
        var lat2 = -60
        while (lat2 <= 60) {
            var lon2 = -180; var have = false
            while (lon2 <= 180) {
                sphere(lat2.toFloat(), lon2.toFloat(), p1)
                if (have) seg(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2])
                p1.copyInto(p0); have = true; lon2 += 6
            }
            lat2 += 30
        }
        drawPath(back, line.copy(alpha = 0.18f), style = Stroke(hair))
        drawPath(front, ink.copy(alpha = if (alive) 0.5f else 0.3f), style = Stroke(hair))

        // 陸地のような点
        val dotColor = ink.copy(alpha = if (alive) 0.55f else 0.3f)
        val d = model.dots; val ds = 0.9.dp.toPx()
        var i = 0
        while (i < d.size) {
            model.project(d[i], d[i + 1], d[i + 2])
            if (model.pzOut() > 0.04f) drawRect(dotColor, Offset(model.pxOut() - ds, model.pyOut() - ds), Size(ds * 2, ds * 2))
            i += 3
        }
        drawCircle(ink, radius = r, center = Offset(cx, cy), style = Stroke(1.6.dp.toPx()))

        // 軌道の手前側
        Rings.forEach { ringArc(it, 0f, 180f, line.copy(alpha = 0.85f)) }

        // サーバーから各端末への線と、流れるパケット
        val sp = model.server
        if (alive) {
            for ((idx, key) in model.nodeKeys.withIndex()) {
                val pt = model.nodePts[idx]
                val isSel = key == selected
                val dist = hypot(pt.x - sp.x, pt.y - sp.y)
                val qx = (sp.x + pt.x) / 2; val qy = (sp.y + pt.y) / 2 - 12.dp.toPx() - dist * 0.14f
                val link = Path().apply { moveTo(sp.x, sp.y); quadraticTo(qx, qy, pt.x, pt.y) }
                val linkDash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()), -(now / 60f) % (7.dp.toPx()))
                drawPath(link, acc.copy(alpha = if (isSel) 1f else 0.7f), style = Stroke(if (isSel) 2.dp.toPx() else 1.2.dp.toPx(), pathEffect = linkDash))
                for (kk in 0..1) {
                    var u = ((now / 2400.0) + idx * 0.17 + kk * 0.5).toFloat() % 1f
                    if (kk == 1) u = 1f - u
                    val a = (1 - u) * (1 - u); val b = 2 * (1 - u) * u; val cc = u * u
                    drawCircle(if (kk == 1) acc else ink, 2.4.dp.toPx(), Offset(a * sp.x + b * qx + cc * pt.x, a * sp.y + b * qy + cc * pt.y))
                }
            }
        }

        // 点 (サーバーと端末)
        fun marker(p: Offset, color: Color, big: Boolean, phase: Float, sel: Boolean) {
            val pulse = ((now / 1400.0) + phase).toFloat() % 1f
            val base = (if (big) 7 else 5).dp.toPx()
            drawCircle(color.copy(alpha = 0.5f * (1 - pulse)), base + pulse * 12.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
            if (sel) drawCircle(color, 12.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
            drawCircle(c.surfaceCard, base, p)
            drawCircle(color, base, p, style = Stroke(2.dp.toPx()))
            drawCircle(color, (if (big) 3.2f else 2.4f).dp.toPx(), p)
        }
        marker(sp, if (alive) c.success else line, true, 0f, false)
        if (alive) for ((idx, key) in model.nodeKeys.withIndex()) marker(model.nodePts[idx], acc, false, idx * 0.3f, key == selected)
    }
}
