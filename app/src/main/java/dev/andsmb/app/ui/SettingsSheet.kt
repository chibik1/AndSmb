package dev.andsmb.app.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.andsmb.app.R
import dev.andsmb.app.Settings

/** 設定シートのアコーディオン。「接続のしかた」は一番下。 */
enum class Section { SHARE, AUTH, NETWORK, HOW }

/** 入力エラーのある欄が入っている区画 (開始ボタンで検証に落ちたときに開く) */
fun sectionFor(field: Settings.Field): Section = when (field) {
    Settings.Field.SHARE, Settings.Field.PATH -> Section.SHARE
    Settings.Field.USER, Settings.Field.PASSWORD -> Section.AUTH
    Settings.Field.PORT -> Section.NETWORK
}

/**
 * 接続の設定を下から出すシート。共有・認証・ネットワーク・接続のしかたの 4 区画で、
 * 開くのは 1 つずつ。サーバーが動いている間は入力欄を触れない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: Settings,
    portText: String,
    editable: Boolean,
    errorFor: (Settings.Field) -> String?,
    onChange: (Settings) -> Unit,
    onPortChange: (String) -> Unit,
    onPickFolder: () -> Unit,
    ip: String?,
    /** Bonjour で公開できた名前。サーバー停止中や公開前は null */
    bonjourName: String?,
    port: Int,
    expanded: Section?,
    onExpand: (Section?) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = c.surfaceBase,
        contentColor = c.ink,
        shape = RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg),
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.borderField.copy(alpha = 0.6f)) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = Space.s4, end = Space.s4, bottom = Space.s6),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_title), style = Type.title, color = c.ink, modifier = Modifier.weight(1f).padding(start = Space.s1))
                val close = stringResource(R.string.settings_close)
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = close, onClick = onDismiss)
                        .semantics { contentDescription = close },
                    contentAlignment = Alignment.Center,
                ) { FIcon(Feather.x, c.ink) }
            }
            if (!editable) Text(stringResource(R.string.settings_locked_note), style = Type.bodySm, color = c.inkMuted, modifier = Modifier.padding(start = Space.s1))

            fun toggle(s: Section) = onExpand(if (expanded == s) null else s)

            Accordion(stringResource(R.string.section_share), settings.shareName, Feather.folder, expanded == Section.SHARE, { toggle(Section.SHARE) }) {
                LabeledField(stringResource(R.string.field_share_name), settings.shareName, { onChange(settings.copy(shareName = it)) }, Feather.share2, editable, error = errorFor(Settings.Field.SHARE))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    LabeledField(
                        stringResource(R.string.field_folder), settings.path, { onChange(settings.copy(path = it)) }, Feather.folder, editable,
                        modifier = Modifier.weight(1f),
                    )
                    Box(Modifier.padding(bottom = 2.dp)) { SecondaryButton(stringResource(R.string.field_choose), onPickFolder, enabled = editable) }
                }
                errorFor(Settings.Field.PATH)?.let { Text(stringResource(R.string.error_prefix, it), style = Type.caption, color = c.danger) }
                ToggleRow(stringResource(R.string.toggle_read_only), Feather.eye, settings.readOnly, { onChange(settings.copy(readOnly = it)) }, editable)
            }
            Accordion(stringResource(R.string.section_auth), settings.user, Feather.lock, expanded == Section.AUTH, { toggle(Section.AUTH) }) {
                LabeledField(stringResource(R.string.field_user), settings.user, { onChange(settings.copy(user = it.trim())) }, Feather.user, editable, error = errorFor(Settings.Field.USER))
                LabeledField(
                    stringResource(R.string.field_password), settings.password, { onChange(settings.copy(password = it)) }, Feather.lock, editable,
                    error = errorFor(Settings.Field.PASSWORD), password = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                )
            }
            Accordion(stringResource(R.string.section_network), portText, Feather.hash, expanded == Section.NETWORK, { toggle(Section.NETWORK) }) {
                LabeledField(
                    stringResource(R.string.field_port), portText, onPortChange, Feather.hash, editable, error = errorFor(Settings.Field.PORT),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                )
                Text(stringResource(R.string.network_note), style = Type.bodySm, color = c.inkMuted)
            }
            Accordion(stringResource(R.string.section_how), stringResource(R.string.how_summary), Feather.wifi, expanded == Section.HOW, { toggle(Section.HOW) }) {
                if (ip == null) {
                    Text(stringResource(R.string.no_network), style = Type.label, color = c.danger)
                    Text(stringResource(R.string.connect_wifi), style = Type.body, color = c.ink)
                } else {
                    // Bonjour 名で接続すると、Finder の「ネットワーク」で公開名と同じ 1 項目にまとまる
                    if (bonjourName != null) {
                        Address(stringResource(R.string.address_mac), "smb://${Uri.encode(bonjourName)}._smb._tcp.local/${settings.shareName}", stringResource(R.string.how_mac_note), onCopy)
                    }
                    Address(
                        stringResource(if (bonjourName != null) R.string.address_ip else R.string.address_mac),
                        "smb://$ip:$port/${settings.shareName}",
                        stringResource(if (bonjourName != null) R.string.how_ip_note else R.string.how_mac_note), onCopy,
                    )
                    Address(
                        stringResource(R.string.address_windows),
                        "net use Z: \\\\$ip\\${settings.shareName} /TCPPORT:$port /USER:${settings.user}",
                        stringResource(R.string.how_windows_note), onCopy,
                    )
                }
            }
        }
    }
}

@Composable
private fun Accordion(
    title: String,
    summary: String,
    icon: ImageVector,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = colors
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    val state = stringResource(if (open) R.string.expanded else R.string.collapsed)
    val shape = RoundedCornerShape(Radius.md)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surfaceCard)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = state }
                .padding(horizontal = Space.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            FIcon(icon, c.ink)
            Text(title, style = Type.label.copy(fontWeight = FontWeight(700)), color = c.ink, modifier = Modifier.weight(1f))
            Text(summary, style = Type.monoSm, color = c.inkMuted, maxLines = 1)
            FIcon(Feather.chevronDown, c.inkMuted, modifier = Modifier.graphicsLayer { rotationZ = rotation })
        }
        AnimatedVisibility(open) {
            Column(
                Modifier.padding(start = Space.s4, end = Space.s4, bottom = Space.s4),
                verticalArrangement = Arrangement.spacedBy(Space.s3),
                content = content,
            )
        }
    }
}

/** コピーできるアドレス行 (48dp 以上)。上にラベル、下に一言説明。 */
@Composable
private fun Address(label: String, value: String, note: String, onCopy: (String) -> Unit) {
    val c = colors
    Column {
        Text(label, style = Type.monoSm, color = c.inkMuted)
        Spacer(Modifier.height(Space.s1))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(c.surfaceTile)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.copy_address)) { onCopy(value) }
                .padding(horizontal = Space.s3, vertical = Space.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            Text(value, style = Type.mono, color = c.ink, modifier = Modifier.weight(1f))
            FIcon(Feather.copy, c.inkMuted)
        }
        Spacer(Modifier.height(Space.s1))
        Text(note, style = Type.bodySm, color = c.inkMuted)
    }
}
