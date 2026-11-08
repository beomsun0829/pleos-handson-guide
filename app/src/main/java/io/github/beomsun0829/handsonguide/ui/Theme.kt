package io.github.beomsun0829.handsonguide.ui

import io.github.beomsun0829.handsonguide.R
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Color tokens from spec §5.2. */
@Immutable
data class GuideColors(
    val bg: Color, val surface: Color, val surface2: Color, val text: Color, val textSub: Color,
    val info: Color, val ok: Color, val warn: Color, val alert: Color, val outline: Color,
) {
    fun tone(name: String): Color = when (name) {
        "alert" -> alert
        "warn" -> warn
        "ok" -> ok
        else -> info
    }
}

val DarkColors = GuideColors(
    bg = Color(0xFF0E1116), surface = Color(0xFF171B22), surface2 = Color(0xFF202632),
    text = Color(0xFFF2F4F7), textSub = Color(0xFFA9B1BD),
    info = Color(0xFF4DA3FF), ok = Color(0xFF3DDC97), warn = Color(0xFFFFB547), alert = Color(0xFFFF5C5C),
    outline = Color(0xFF2E3644),
)

val LightColors = GuideColors(
    bg = Color(0xFFF5F7FA), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEEF1F5),
    text = Color(0xFF10141A), textSub = Color(0xFF4B5563),
    // ok and warn darkened to reach 4.5:1 on every light surface, also as text on their own 12% tint (Pleos review C-1).
    info = Color(0xFF0B63CE), ok = Color(0xFF0C784E), warn = Color(0xFF995B00), alert = Color(0xFFC62828),
    outline = Color(0xFFD3D9E1),
)

/** Pretendard (SIL OFL 1.1, assets/licenses/pretendard-OFL.txt): one Korean UI face for every screen and drawing. */
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f, heading: Boolean = false) = TextStyle(
    fontFamily = Pretendard, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp,
    // Korean must wrap between phrases, not inside a word; headings also balance their lines.
    lineBreak = if (heading) LineBreak.Heading else LineBreak(LineBreak.Strategy.HighQuality, LineBreak.Strictness.Strict, LineBreak.WordBreak.Phrase),
    // Center glyphs in their line box and drop the legacy font padding, so text lines up in buttons and rows.
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/**
 * Type scale (spec §5.3, never smaller than caption). Weight carries the hierarchy: Bold only for
 * headings, SemiBold for things you press or scan, Regular for reading. Korean body text needs
 * generous leading; large sizes get slightly tighter tracking.
 */
object GuideType {
    val display = style(64, 76, FontWeight.Bold, -1.2f, heading = true)
    val headline = style(38, 50, FontWeight.Bold, -.6f, heading = true)
    val title = style(28, 38, FontWeight.SemiBold, -.3f, heading = true)
    val body = style(25, 39, FontWeight.Normal, -.1f)
    val button = style(24, 30, FontWeight.SemiBold)
    val label = style(21, 28, FontWeight.SemiBold)
    val caption = style(20, 28, FontWeight.Normal)
}

val LocalGuideColors = staticCompositionLocalOf { DarkColors }

/** Wide = one landscape window ≥ 960 dp (spec §5.1). */
val isWide: Boolean
    @Composable get() = LocalConfiguration.current.screenWidthDp >= 960

@Composable
fun GuideTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) darkColorScheme(background = c.bg, surface = c.surface, primary = c.info, onBackground = c.text, onSurface = c.text)
        else lightColorScheme(background = c.bg, surface = c.surface, primary = c.info, onBackground = c.text, onSurface = c.text)
    CompositionLocalProvider(LocalGuideColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = Typography().let { t ->
            t.copy(bodyLarge = GuideType.body, bodyMedium = GuideType.body, labelLarge = GuideType.button, titleLarge = GuideType.title)
        }) { ProvideTextStyle(GuideType.body, content) }
    }
}
