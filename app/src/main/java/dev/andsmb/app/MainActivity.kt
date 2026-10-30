package dev.andsmb.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.andsmb.app.ui.AndSmbTheme
import dev.andsmb.app.ui.AppCard
import dev.andsmb.app.ui.FIcon
import dev.andsmb.app.ui.Feather
import dev.andsmb.app.ui.FilterChip
import dev.andsmb.app.ui.InfoChip
import dev.andsmb.app.ui.LabeledField
import dev.andsmb.app.ui.PrimaryButton
import dev.andsmb.app.ui.Radius
import dev.andsmb.app.ui.SecondaryButton
import dev.andsmb.app.ui.SectionTitle
import dev.andsmb.app.ui.Space
import dev.andsmb.app.ui.Stat
import dev.andsmb.app.ui.ToggleRow
import dev.andsmb.app.ui.Type
import dev.andsmb.app.ui.WeekBars
import dev.andsmb.app.ui.colors
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Traffic.load(this)
        setContent {
            AndSmbTheme { MainScreen() }
        }
    }
}

private fun hasStorageAccess(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

/** SAF のツリー URI (内部ストレージのみ) を実パスに変換する */
private fun treeUriToPath(uri: Uri): String? {
    val docId = DocumentsContract.getTreeDocumentId(uri)
    val (volume, rel) = docId.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    if (volume != "primary") return null
    val root = Environment.getExternalStorageDirectory().absolutePath
    return if (rel.isEmpty()) root else "$root/$rel"
}

private fun copyToClipboard(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AndSmb", text))
    // Android 13 以降はシステムがコピーを知らせるので、それより前だけトーストを出す
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "コピーしました", Toast.LENGTH_SHORT).show()
}

@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    val c = colors
    val state by SmbService.state.collectAsState()
    val log by SmbService.log.collectAsState()
    val traffic by Traffic.state.collectAsState()
    var settings by remember { mutableStateOf(Settings.load(ctx)) }
    var portText by remember { mutableStateOf(settings.port.toString()) }
    var fieldError by remember { mutableStateOf<Pair<Settings.Field, String>?>(null) }
    var storageOk by remember { mutableStateOf(hasStorageAccess(ctx)) }
    var ips by remember { mutableStateOf(SmbService.ipAddresses()) }

    // 設定画面から戻ってきたら権限と IP を再確認する
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                storageOk = hasStorageAccess(ctx)
                ips = SmbService.ipAddresses()
                Traffic.load(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(state.running) { ips = SmbService.ipAddresses() }

    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        storageOk = hasStorageAccess(ctx)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val path = treeUriToPath(uri)
            if (path == null) fieldError = Settings.Field.PATH to "内部ストレージのフォルダを選んでください (SD カードは未対応)"
            else { settings = settings.copy(path = path); fieldError = null }
        }
    }

    fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            try { ctx.startActivity(i) } catch (e: Exception) {
                ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun startServer() {
        val s = settings.copy(port = portText.toIntOrNull() ?: -1)
        val err = s.validate() ?: if (!java.io.File(s.path).isDirectory) Settings.Field.PATH to "フォルダが見つかりません" else null
        fieldError = err
        if (err != null) return
        settings = s
        Settings.save(ctx, s)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        SmbService.start(ctx)
    }

    fun errorFor(f: Settings.Field) = fieldError?.takeIf { it.first == f }?.second

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(c.surfaceTop, c.surfaceBase))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = Space.s4, end = Space.s4, top = Space.s8, bottom = Space.s6),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Lockup()
            Spacer(Modifier.height(Space.s1))

            if (!storageOk) PermissionNotice(onAllow = { requestStorage() })

            StatusCard(state, settings, ips, traffic.connections, onCopy = { copyToClipboard(ctx, it) })

            if (state.running) {
                PrimaryButton("サーバー停止", Feather.square, onClick = { SmbService.stop(ctx) }, danger = true)
            } else {
                PrimaryButton("サーバー開始", Feather.play, onClick = { startServer() }, enabled = storageOk)
            }
            state.error?.let { if (!state.running) Text("エラー: $it", style = Type.bodySm, color = c.danger) }

            Spacer(Modifier.height(Space.s6 - Space.s3))
            SectionTitle("通信量", Feather.activity)
            TodayCard(traffic, state.running)
            WeekCard(traffic)

            Spacer(Modifier.height(Space.s6 - Space.s3))
            SectionTitle("設定", Feather.server)
            SettingsFields(
                settings = settings,
                portText = portText,
                editable = !state.running,
                errorFor = ::errorFor,
                onChange = { settings = it; fieldError = null },
                onPortChange = { portText = it.filter(Char::isDigit).take(5); fieldError = null },
                onPickFolder = { folderPicker.launch(null) },
            )

            if (log.isNotEmpty()) {
                Spacer(Modifier.height(Space.s6 - Space.s3))
                SectionTitle("ログ", Feather.terminal)
                AppCard(elevated = false) {
                    SelectionContainer {
                        Text(log.takeLast(40).joinToString("\n"), style = Type.monoSm, color = c.ink)
                    }
                }
            }
        }
    }
}

/** アイコン 40dp + 「AndSmb」。ダークでは黒い角丸に枠を付けて背景に埋もれないようにする。 */
@Composable
private fun Lockup() {
    val c = colors
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(9.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        Box(
            Modifier
                .size(40.dp)
                .clip(shape)
                .background(androidx.compose.ui.graphics.Color(0xFF0B0B0C))
                .then(if (dark) Modifier.border(1.5.dp, c.borderField, shape) else Modifier),
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Text("AndSmb", style = Type.display, color = c.ink)
    }
}

@Composable
private fun PermissionNotice(onAllow: () -> Unit) {
    val c = colors
    AppCard(background = c.dangerSurface, elevated = false) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            FIcon(Feather.alertTriangle, c.danger, 22.dp)
            Text("ストレージへのアクセス許可が必要です", style = Type.headline, color = c.ink)
        }
        Spacer(Modifier.height(Space.s2))
        Text("共有するファイルを読み書きするため「すべてのファイルへのアクセス」を許可してください。", style = Type.body, color = c.ink)
        Spacer(Modifier.height(Space.s3))
        Box(
            Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(50))
                .background(c.ink)
                .clickable(onClick = onAllow)
                .padding(horizontal = Space.s5),
            contentAlignment = Alignment.Center,
        ) { Text("許可する", style = Type.label, color = c.onInk) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusCard(state: SmbService.State, s: Settings, ips: List<String>, connections: Int, onCopy: (String) -> Unit) {
    val c = colors
    AppCard {
        if (state.running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                FIcon(Feather.checkCircle, c.success)
                Text("実行中", style = Type.headline, color = c.success)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                FIcon(Feather.square, c.inkMuted)
                Text("停止中", style = Type.headline, color = c.inkMuted)
            }
        }

        // 状態のチップ: 接続台数・書き込み可否・ポート
        Spacer(Modifier.height(Space.s3))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            if (state.running) InfoChip(if (connections > 0) "接続 $connections 台" else "接続なし", Feather.users)
            InfoChip(if (s.readOnly) "読み取り専用" else "読み書き", if (s.readOnly) Feather.eye else Feather.folder)
            InfoChip("ポート ${if (state.running) state.port else s.port}", Feather.hash)
        }

        if (ips.isEmpty()) {
            Spacer(Modifier.height(Space.s3))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                FIcon(Feather.wifiOff, c.danger)
                Text("ネットワークに接続されていません", style = Type.label, color = c.danger)
            }
            Spacer(Modifier.height(Space.s2))
            Text("Wi-Fi に接続してください。", style = Type.body, color = c.ink)
        }
        val port = if (state.running) state.port else s.port
        for (ip in ips) {
            Address("Mac / iPhone (Finder・ファイル):", "smb://$ip:$port/${s.shareName}", onCopy)
            Address("Windows 11 24H2 以降 (コマンドプロンプト):", "net use Z: \\\\$ip\\${s.shareName} /TCPPORT:$port /USER:${s.user}", onCopy)
        }
    }
}

@Composable
private fun Address(label: String, value: String, onCopy: (String) -> Unit) {
    val c = colors
    Spacer(Modifier.height(Space.s3))
    Text(label, style = Type.bodySm, color = c.inkMuted)
    Spacer(Modifier.height(Space.s1))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(c.surfaceTile)
            .clickable { onCopy(value) }
            .padding(horizontal = Space.s3, vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Text(value, style = Type.mono, color = c.ink, modifier = Modifier.weight(1f))
        FIcon(Feather.copy, c.inkMuted)
    }
}

@Composable
private fun TodayCard(t: TrafficSnapshot, running: Boolean) {
    val c = colors
    val today = t.today
    AppCard {
        Text("今日", style = Type.headline, color = c.ink)
        Spacer(Modifier.height(Space.s3))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
            Stat("受信", Traffic.format(today?.received ?: 0), Feather.arrowDown)
            Stat("送信", Traffic.format(today?.sent ?: 0), Feather.arrowUp)
        }
        if (running) {
            Spacer(Modifier.height(Space.s3))
            HorizontalDivider(thickness = 1.dp, color = c.surfaceBase)
            Spacer(Modifier.height(Space.s3))
            Text(
                "起動してから  受信 ${Traffic.format(t.sessionReceived)} ・ 送信 ${Traffic.format(t.sessionSent)}",
                style = Type.bodySm, color = c.inkMuted,
            )
        }
    }
}

private enum class Metric(val label: String) { TOTAL("合計"), RECEIVED("受信"), SENT("送信") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekCard(t: TrafficSnapshot) {
    val c = colors
    var metric by rememberSaveable { mutableStateOf(Metric.TOTAL) }
    var selected by rememberSaveable { mutableIntStateOf(6) }
    val week = t.week
    if (week.isEmpty()) return
    fun value(d: DayTraffic) = when (metric) {
        Metric.TOTAL -> d.total
        Metric.RECEIVED -> d.received
        Metric.SENT -> d.sent
    }
    val sel = week[selected.coerceIn(0, week.size - 1)]
    val weekSum = week.sumOf { value(it) }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("直近7日", style = Type.headline, color = c.ink, modifier = Modifier.weight(1f))
            Text("計 ${Traffic.format(weekSum)}", style = Type.bodySm, color = c.inkMuted)
        }
        Spacer(Modifier.height(Space.s3))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Metric.entries.forEach { m -> FilterChip(m.label, metric == m) { metric = m } }
        }
        Spacer(Modifier.height(Space.s4))
        // 選択中の日の値
        val dow = sel.date.dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.JAPANESE)
        Text("${sel.date.monthValue}/${sel.date.dayOfMonth} ($dow)", style = Type.bodySm, color = c.inkMuted)
        Text(Traffic.format(value(sel)), style = Type.title, color = c.ink)
        Spacer(Modifier.height(Space.s3))
        WeekBars(
            values = week.map { value(it) },
            labels = week.mapIndexed { i, d -> if (i == week.size - 1) "今日" else d.date.dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.JAPANESE) },
            selected = selected,
            onSelect = { selected = it },
        )
    }
}

@Composable
private fun SettingsFields(
    settings: Settings,
    portText: String,
    editable: Boolean,
    errorFor: (Settings.Field) -> String?,
    onChange: (Settings) -> Unit,
    onPortChange: (String) -> Unit,
    onPickFolder: () -> Unit,
) {
    LabeledField("共有名", settings.shareName, { onChange(settings.copy(shareName = it)) }, Feather.share2, editable, error = errorFor(Settings.Field.SHARE))
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        LabeledField(
            "共有フォルダ", settings.path, { onChange(settings.copy(path = it)) }, Feather.folder, editable,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.padding(bottom = 2.dp)) { SecondaryButton("選択", onPickFolder, enabled = editable) }
    }
    errorFor(Settings.Field.PATH)?.let { Text("エラー: $it", style = Type.caption, color = colors.danger) }
    LabeledField("ユーザー名", settings.user, { onChange(settings.copy(user = it.trim())) }, Feather.user, editable, error = errorFor(Settings.Field.USER))
    LabeledField("パスワード", settings.password, { onChange(settings.copy(password = it)) }, Feather.lock, editable, error = errorFor(Settings.Field.PASSWORD))
    LabeledField(
        "ポート (1024〜65535)", portText, onPortChange, Feather.hash, editable, error = errorFor(Settings.Field.PORT),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    AppCard(padding = 0.dp, elevated = false) {
        Box(Modifier.padding(horizontal = Space.s4)) {
            ToggleRow("読み取り専用", Feather.eye, settings.readOnly, { onChange(settings.copy(readOnly = it)) }, editable)
        }
    }
}
