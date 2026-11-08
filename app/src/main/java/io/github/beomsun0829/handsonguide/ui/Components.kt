package io.github.beomsun0829.handsonguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val c: GuideColors @Composable get() = LocalGuideColors.current

/** 88 dp tall, ≥ 240 dp wide (spec §5.4). */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    GuideButton(text, modifier, enabled, filled = true, onClick)

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    GuideButton(text, modifier, enabled, filled = false, onClick)

@Composable
private fun GuideButton(text: String, modifier: Modifier, enabled: Boolean, filled: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bg = if (filled) c.info else Color.Transparent
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .heightIn(min = 88.dp)
            .widthIn(min = 240.dp)
            .alpha(if (enabled) 1f else .4f)
            .clip(shape)
            .background(if (pressed && filled) bg.copy(alpha = .8f) else if (pressed) c.surface2 else bg)
            .then(if (filled) Modifier else Modifier.border(2.dp, c.outline, shape))
            .clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = GuideType.button, color = if (filled) Color.White else c.text, maxLines = 1)
    }
}

@Composable
fun IconTap(glyph: String, description: String, modifier: Modifier = Modifier, tint: Color = c.text, onClick: () -> Unit) {
    Box(
        modifier.size(76.dp).clip(RoundedCornerShape(38.dp)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Glyph(glyph, tint, 40.dp, description) }
}

@Composable
fun Chip(text: String, glyph: String? = null, tone: String = "neutral", modifier: Modifier = Modifier, big: Boolean = false) {
    val color = if (tone == "neutral") c.textSub else c.tone(tone)
    Row(
        modifier
            .height(if (big) 72.dp else 56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (tone == "neutral") c.surface2 else color.copy(alpha = .16f))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (glyph != null) Glyph(glyph, color, if (big) 36.dp else 28.dp)
        Text(text, style = if (big) GuideType.title else GuideType.label, color = if (tone == "neutral") c.text else color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = c.info) {
    Box(modifier.height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.surface2)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, tone: String? = null, padding: Dp = 32.dp, content: @Composable () -> Unit) {
    val border = tone?.let { c.tone(it) }
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(if (border != null) border.copy(alpha = .10f) else c.surface)
            .then(if (border != null) Modifier.border(2.dp, border, RoundedCornerShape(28.dp)) else Modifier)
            .padding(padding),
    ) { content() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    // Quiet group heading: the rows under it carry the weight.
    Text(text, style = GuideType.label, color = c.textSub, modifier = modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
fun Hspace(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun Vspace(h: Dp) = Spacer(Modifier.height(h))
