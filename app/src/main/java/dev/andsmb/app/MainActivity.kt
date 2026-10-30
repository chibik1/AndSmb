package dev.andsmb.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { MainScreen() }
            }
        }
    }
}

private fun hasStorageAccess(ctx: android.content.Context): Boolean =
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

@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    val state by SmbService.state.collectAsState()
    val log by SmbService.log.collectAsState()
    var settings by remember { mutableStateOf(Settings.load(ctx)) }
    var portText by remember { mutableStateOf(settings.port.toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    var storageOk by remember { mutableStateOf(hasStorageAccess(ctx)) }
    var ips by remember { mutableStateOf(SmbService.ipAddresses()) }

    // 設定画面から戻ってきたら権限と IP を再確認する
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                storageOk = hasStorageAccess(ctx)
                ips = SmbService.ipAddresses()
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
            if (path == null) message = "内部ストレージのフォルダを選んでください (SD カードは未対応)"
            else settings = settings.copy(path = path)
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
        val err = s.validate()
        if (err != null) { message = err; return }
        if (!java.io.File(s.path).isDirectory) { message = "フォルダが見つかりません: ${s.path}"; return }
        settings = s
        Settings.save(ctx, s)
        message = null
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        SmbService.start(ctx)
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AndSmb", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        if (!storageOk) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ストレージへのアクセス許可が必要です", fontWeight = FontWeight.Bold)
                    Text("共有するファイルを読み書きするため「すべてのファイルへのアクセス」を許可してください。")
                    Button(onClick = { requestStorage() }) { Text("許可する") }
                }
            }
        }

        StatusCard(state, settings, ips)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.running) {
                Button(
                    onClick = { SmbService.stop(ctx) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("サーバー停止") }
            } else {
                Button(onClick = { startServer() }, enabled = storageOk) { Text("サーバー開始") }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.error?.let { if (!state.running) Text("エラー: $it", color = MaterialTheme.colorScheme.error) }

        Text("設定", style = MaterialTheme.typography.titleMedium)
        val editable = !state.running
        OutlinedTextField(
            value = settings.shareName, onValueChange = { settings = settings.copy(shareName = it) },
            label = { Text("共有名") }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = settings.path, onValueChange = { settings = settings.copy(path = it) },
                label = { Text("共有フォルダ") }, singleLine = true, enabled = editable, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = editable) { Text("選択") }
        }
        OutlinedTextField(
            value = settings.user, onValueChange = { settings = settings.copy(user = it.trim()) },
            label = { Text("ユーザー名") }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = settings.password, onValueChange = { settings = settings.copy(password = it) },
            label = { Text("パスワード") }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = portText, onValueChange = { portText = it.filter(Char::isDigit).take(5) },
            label = { Text("ポート (1024〜65535)") }, singleLine = true, enabled = editable,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("読み取り専用", Modifier.weight(1f))
            Switch(checked = settings.readOnly, onCheckedChange = { settings = settings.copy(readOnly = it) }, enabled = editable)
        }

        if (log.isNotEmpty()) {
            Text("ログ", style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(log.takeLast(40).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun StatusCard(state: SmbService.State, s: Settings, ips: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (state.running) "● 実行中" else "○ 停止中",
                fontWeight = FontWeight.Bold,
                color = if (state.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ips.isEmpty()) {
                Text("ネットワークに接続されていません。Wi-Fi に接続してください。")
            }
            val port = if (state.running) state.port else s.port
            for (ip in ips) {
                SelectionContainer {
                    Column {
                        Text("Mac / iPhone (Finder・ファイル):", style = MaterialTheme.typography.labelMedium)
                        Text("smb://$ip:$port/${s.shareName}", fontFamily = FontFamily.Monospace)
                        Text("Windows 11 24H2 以降 (コマンドプロンプト):", style = MaterialTheme.typography.labelMedium)
                        Text("net use Z: \\\\$ip\\${s.shareName} /TCPPORT:$port /USER:${s.user}", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
