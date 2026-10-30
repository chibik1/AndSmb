package dev.andsmb.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.andsmb.app.R

/** フォーカスリング: 2dp 実線、要素から 2dp 外側。レイアウトをずらさないよう外側に描く。 */
fun Modifier.focusRing(focused: Boolean, color: Color, radius: Dp): Modifier = drawWithContent {
    drawContent()
    if (focused) {
        val inset = 3.dp.toPx() // offset 2dp + 線幅の半分 1dp
        drawRoundRect(
            color = color,
            topLeft = Offset(-inset, -inset),
            size = Size(size.width + inset * 2, size.height + inset * 2),
            cornerRadius = CornerRadius(radius.toPx() + inset),
            style = Stroke(2.dp.toPx()),
        )
    }
}

/** 状態・通知・設定・ログを載せるカード */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    background: Color = colors.surfaceCard,
    elevated: Boolean = true,
    padding: Dp = Space.s4,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radius.md)
    Column(
        modifier
            .fillMaxWidth()
            .then(if (elevated) Modifier.shadow(12.dp, shape, ambientColor = ShadowTint, spotColor = ShadowTint) else Modifier)
            .clip(shape)
            .background(background)
            .padding(padding),
        content = content,
    )
}

private val ShadowTint = Color(0x331E323C)

/** セクション見出し (設定・通信量・ログ) */
@Composable
fun SectionTitle(text: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        FIcon(icon, colors.ink, 22.dp)
        Text(text, style = Type.title, color = colors.ink)
    }
}

/** 主操作 (黒) と停止 (赤) の全幅ピルボタン */
@Composable
fun PrimaryButton(text: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false) {
    val c = colors
    val bg = if (danger) c.danger else c.ink
    val fg = if (danger) c.onDanger else c.onInk
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .focusRing(focused, c.focusRing, 28.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, interactionSource = source, indication = androidx.compose.material3.ripple(color = fg), role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FIcon(icon, fg)
        Spacer(Modifier.width(Space.s2))
        Text(text, style = Type.label, color = fg)
    }
}

/** 補助ボタン (透明地 + 枠)。欄の横に置く「選択」など。 */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val c = colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        modifier
            .height(48.dp)
            .focusRing(focused, c.focusRing, 24.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .border(1.5.dp, c.borderField, CircleShape)
            .clickable(enabled = enabled, interactionSource = source, indication = androidx.compose.material3.ripple(color = c.ink), role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s5),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.label, color = c.ink) }
}

/** ラベル付き1行入力欄 (先頭に線画アイコン) */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    icon: ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    password: Boolean = false,
) {
    val c = colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Radius.md)
    var revealed by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = Type.bodySm, color = c.inkMuted)
        Spacer(Modifier.height(Space.s1))
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .focusRing(focused, c.focusRing, Radius.md)
                .alpha(if (enabled) 1f else 0.4f)
                .clip(shape)
                .background(c.surfaceRaised)
                .border(1.5.dp, if (error != null) c.danger else c.borderField, shape)
                .padding(start = Space.s4, end = if (password) Space.s1 else Space.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FIcon(icon, c.inkMuted)
            Spacer(Modifier.width(Space.s2))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = Type.body.copy(color = c.ink),
                cursorBrush = SolidColor(c.ink),
                keyboardOptions = keyboardOptions,
                visualTransformation = if (password && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                interactionSource = source,
                modifier = Modifier.weight(1f),
            )
            if (password) {
                val desc = stringResource(if (revealed) R.string.password_hide else R.string.password_show)
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(enabled = enabled, role = Role.Button, onClickLabel = desc) { revealed = !revealed }
                        .semantics { contentDescription = desc },
                    contentAlignment = Alignment.Center,
                ) { FIcon(if (revealed) Feather.eyeOff else Feather.eye, c.inkMuted) }
            }
        }
        if (error != null) {
            Spacer(Modifier.height(Space.s1))
            Text(stringResource(R.string.error_prefix, error), style = Type.caption, color = c.danger)
        }
    }
}

/** オン/オフ設定の行 (52×32 のスイッチ) */
@Composable
fun ToggleRow(label: String, icon: ImageVector, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean) {
    val c = colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val anim = tween<Dp>(150)
    val thumbSize by animateDpAsState(if (checked) 24.dp else 20.dp, anim, label = "thumb")
    val thumbX by animateDpAsState(if (checked) 24.dp else 6.dp, anim, label = "thumbX")
    val track by animateColorAsState(if (checked) c.ink else Color.Transparent, tween(150), label = "track")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .toggleable(checked, enabled = enabled, role = Role.Switch, interactionSource = source, indication = null, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        FIcon(icon, c.inkMuted)
        Text(label, style = Type.label, color = c.ink, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(52.dp, 32.dp)
                .focusRing(focused, c.focusRing, 16.dp)
                .clip(CircleShape)
                .background(track)
                .then(if (checked) Modifier else Modifier.border(1.5.dp, c.borderField, CircleShape)),
        ) {
            Box(
                Modifier
                    .offset(x = thumbX, y = (32.dp - thumbSize) / 2)
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(if (checked) c.onInk else c.borderField),
            )
        }
    }
}

/** 情報チップ (押せない)。状態の要約を短く並べる。 */
@Composable
fun InfoChip(text: String, icon: ImageVector, tint: Color = colors.inkMuted) {
    val c = colors
    Row(
        Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(c.surfaceTile)
            .padding(horizontal = Space.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FIcon(icon, tint, 16.dp)
        Text(text, style = Type.bodySm.copy(fontWeight = FontWeight(500)), color = c.ink)
    }
}

/** 選択チップ。選択中は ink 塗り、それ以外は枠だけ。 */
@Composable
fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = colors
    val bg by animateColorAsState(if (selected) c.ink else Color.Transparent, tween(150), label = "chip")
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    // 見た目は 32dp、押せる範囲は 48dp (touch-min)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .selectable(selected, interactionSource = source, indication = null, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(32.dp)
                .focusRing(focused, c.focusRing, 16.dp)
                .clip(CircleShape)
                .background(bg)
                .then(if (selected) Modifier else Modifier.border(1.5.dp, c.borderField, CircleShape))
                .padding(horizontal = Space.s3 + 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = Type.bodySm.copy(fontWeight = FontWeight(500)), color = if (selected) c.onInk else c.ink)
        }
    }
}

/** カード内の小さなアイコン台 */
@Composable
fun IconTile(icon: ImageVector, tint: Color = colors.ink) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.surfaceTile),
        contentAlignment = Alignment.Center,
    ) { FIcon(icon, tint) }
}

/**
 * 7日分の棒グラフ。表紙と同じピル型の棒を、薄い台 (chart-track) の上に ink で立てる。
 * 選択中の日は ink、他の日は border-field。棒をタップすると選択が変わる。
 * 台は chart-track (カードより一段濃い)。
 */
@Composable
fun WeekBars(
    values: List<Long>,
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 140.dp,
) {
    val c = colors
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        values.forEachIndexed { i, v ->
            val isSel = i == selected
            Column(
                Modifier
                    .weight(1f)
                    .selectable(isSel, role = Role.Tab, indication = null, interactionSource = null) { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(Modifier.width(28.dp).height(chartHeight)) {
                    val w = size.width
                    val r = CornerRadius(w / 2)
                    drawRoundRect(c.chartTrack, cornerRadius = r)
                    if (v > 0) {
                        // 0 でない日は最低でも丸い頭が見える高さにする
                        val h = (size.height * v / max).coerceAtLeast(w)
                        drawRoundRect(
                            if (isSel) c.ink else c.borderField,
                            topLeft = Offset(0f, size.height - h),
                            size = Size(w, h),
                            cornerRadius = r,
                        )
                    }
                }
                Spacer(Modifier.height(Space.s2))
                Text(
                    labels[i],
                    style = Type.caption.copy(fontWeight = if (isSel) FontWeight(600) else FontWeight(400)),
                    color = if (isSel) c.ink else c.inkMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun RowScope.Stat(label: String, value: String, icon: ImageVector) {
    val c = colors
    Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
            FIcon(icon, c.inkMuted, 16.dp)
            Text(label, style = Type.bodySm, color = c.inkMuted)
        }
        Spacer(Modifier.height(Space.s1))
        Text(value, style = Type.title, color = c.ink)
    }
}

/** 画面上部のタブ。トラック (surfaceTile) の上で、選択中だけ墨色のピルになる。 */
@Composable
fun TabBar(tabs: List<Pair<String, ImageVector>>, selected: Int, onSelect: (Int) -> Unit) {
    val c = colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(c.surfaceTile)
            .padding(Space.s1)
            .selectableGroup(),
    ) {
        tabs.forEachIndexed { i, (label, icon) ->
            val on = i == selected
            val bg by animateColorAsState(if (on) c.ink else Color.Transparent, tween(150), label = "tab")
            val fg = if (on) c.onInk else c.inkMuted
            Row(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .selectable(on, role = Role.Tab, onClick = { onSelect(i) }),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FIcon(icon, fg, 18.dp)
                Spacer(Modifier.width(Space.s2 - 2.dp))
                Text(label, style = Type.bodySm.copy(fontWeight = FontWeight(600)), color = fg)
            }
        }
    }
}

/** テーマ切り替えボタン。押すたびに 自動 → ライト → ダーク と回る。見た目は 40dp、押せる範囲は 48dp。 */
@Composable
fun ThemeButton(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val c = colors
    val (labelRes, icon) = when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_auto to Feather.smartphone
        ThemeMode.LIGHT -> R.string.theme_light to Feather.sun
        ThemeMode.DARK -> R.string.theme_dark to Feather.moon
    }
    val label = stringResource(labelRes)
    val description = stringResource(R.string.theme_content_desc, label)
    val next = ThemeMode.entries[(mode.ordinal + 1) % ThemeMode.entries.size]
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.theme_switch)) { onChange(next) }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(40.dp)
                .focusRing(focused, c.focusRing, 20.dp)
                .clip(CircleShape)
                .border(1.5.dp, c.borderField, CircleShape)
                .padding(start = Space.s3, end = Space.s3 + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s2 - 2.dp),
        ) {
            FIcon(icon, c.ink, 18.dp)
            Text(label, style = Type.bodySm.copy(fontWeight = FontWeight(600)), color = c.ink)
        }
    }
}
