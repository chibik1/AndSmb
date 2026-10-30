package dev.andsmb.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.andsmb.app.R

// デザインシステム「AndSmb」(tokens.json v2) の値をそのまま写したもの。

@Immutable
data class AppColors(
    val surfaceTop: Color,
    val surfaceBase: Color,
    val surfaceCard: Color,
    val surfaceTile: Color,
    val surfaceRaised: Color,
    val ink: Color,
    val onInk: Color,
    val inkMuted: Color,
    val borderField: Color,
    val accent: Color,
    val accentDeep: Color,
    val success: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerSurface: Color,
) {
    val focusRing get() = accent
}

private val Light = AppColors(
    surfaceTop = Color(0xFFD3DEE1),
    surfaceBase = Color(0xFFB9CBD1),
    surfaceCard = Color(0xFFE0E9EC),
    surfaceTile = Color(0xFFE8EEF0),
    surfaceRaised = Color(0xFFF5F8F9),
    ink = Color(0xFF0B0B0C),
    onInk = Color(0xFFFFFFFF),
    inkMuted = Color(0xFF3F4F57),
    borderField = Color(0xFF5F7279),
    accent = Color(0xFF0A6D93),
    accentDeep = Color(0xFF2A8BAD),
    success = Color(0xFF1F6F5C),
    danger = Color(0xFFB3261E),
    onDanger = Color(0xFFFFFFFF),
    dangerSurface = Color(0xFFF3D9D6),
)

private val Dark = AppColors(
    surfaceTop = Color(0xFF263640),
    surfaceBase = Color(0xFF141E24),
    surfaceCard = Color(0xFF2B3C45),
    surfaceTile = Color(0xFF33444D),
    surfaceRaised = Color(0xFF364952),
    ink = Color(0xFFF2F6F7),
    onInk = Color(0xFF0B0B0C),
    inkMuted = Color(0xFFB4C3CA),
    borderField = Color(0xFF8EA1A9),
    accent = Color(0xFF5CD3F5),
    accentDeep = Color(0xFF2A8BAD),
    success = Color(0xFF6FD6B4),
    danger = Color(0xFFFF8A80),
    onDanger = Color(0xFF3A0B08),
    dangerSurface = Color(0xFF4A2A2A),
)

/** 可変フォントから太さごとのインスタンスを作る。日本語は端末の Noto Sans CJK にフォールバックする。 */
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private val Sans = FontFamily(
    variable(R.font.plus_jakarta_sans, 400),
    variable(R.font.plus_jakarta_sans, 500),
    variable(R.font.plus_jakarta_sans, 600),
    variable(R.font.plus_jakarta_sans, 700),
)
private val Mono = FontFamily(variable(R.font.jetbrains_mono, 400))

object Type {
    val display = TextStyle(fontFamily = Sans, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight(700), letterSpacing = (-0.02).em)
    val title = TextStyle(fontFamily = Sans, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight(600), letterSpacing = (-0.01).em)
    val headline = TextStyle(fontFamily = Sans, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight(600))
    val body = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight(400))
    val bodySm = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight(400))
    val label = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight(500))
    val caption = TextStyle(fontFamily = Sans, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight(400))
    val mono = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight(400))
    val monoSm = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight(400))
}

object Space {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp
}

object Radius {
    val sm = 12.dp
    val md = 20.dp
    val lg = 28.dp
}

val LocalColors = staticCompositionLocalOf { Light }
val LocalDark = staticCompositionLocalOf { false }

/** 現在のテーマの色 */
val colors: AppColors @Composable get() = LocalColors.current

/** 現在ダークテーマで描いているか */
val isDark: Boolean @Composable get() = LocalDark.current

/** テーマの選び方。SYSTEM は端末の設定に従う。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun ThemeMode.resolveDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun AndSmbTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalColors provides if (dark) Dark else Light, LocalDark provides dark, content = content)
}
