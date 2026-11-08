package io.github.beomsun0829.handsonguide.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * The fog card's scene, built as three stacked layers in one 1000×810 space:
 *  1. the street outside, sharp;
 *  2. the same street blurred and hazed, clipped to the part of the glass still fogged
 *     (the GPU blur also softens the clip edge, so the clearing line looks like evaporating fog);
 *  3. the cabin on top: pillars, dash, wheel, climate panel, droplets, air streams and the finger.
 * A short status line in the corner follows the motion. When the car reports front defrost on,
 * the loop stops and the glass clears for real.
 */

private const val W = 1000f
private const val H = 810f
private const val GLASS_TOP = 54f
private const val GLASS_BOTTOM = 476f
private const val LOOP = 12f

/** One moment of the story. */
private data class Beat(
    val fog: Float,        // 0..1 how much fog there is at all (fades back in at the end of the loop)
    val clear: Float,      // 0..1 how far up the glass has cleared
    val point: Float,      // 0..1 the button is pointed at
    val finger: Float,     // 0..1 finger travel to the button
    val press: Float,      // 0..1 press depth
    val on: Boolean,       // button lit
    val air: Float,        // 0..1 air flowing
    val time: Float,
    val caption: String,
)

private fun ease(x: Float): Float { val v = x.coerceIn(0f, 1f); return v * v * (3 - 2 * v) }
private fun window(t: Float, a: Float, b: Float, fade: Float) = if (t < a || t > b) 0f else min(ease((t - a) / fade), ease((b - t) / fade))

private fun loopBeat(t: Float) = Beat(
    // At the end the fog fades back in evenly, so the next loop starts fogged again.
    fog = if (t < 11f) 1f else ease((t - 11f) / 1f),
    clear = when { t < 4.6f -> 0f; t < 9f -> ease((t - 4.6f) / 4.4f); t < 11f -> 1f; else -> 0f },
    point = window(t, 1.8f, 4.6f, .4f),
    finger = when { t < 2.4f -> 0f; t < 3.4f -> ease((t - 2.4f) / 1f); t < 4.6f -> 1f; t < 5.4f -> 1f - ease((t - 4.6f) / .8f); else -> 0f },
    press = if (t in 3.6f..4.3f) sin((t - 3.6f) / .7f * PI.toFloat()) else 0f,
    on = t in 3.95f..11f,
    air = window(t, 3.95f, 11f, .6f),
    time = t,
    caption = when { t < 1.8f -> "앞이 뿌옇게 흐려졌어요"; t < 4.6f -> "앞유리 김서림 버튼을 눌러요"; t < 9f -> "바람이 유리를 말려요"; else -> "맑아졌어요" },
)

private fun liveBeat(t: Float) = Beat(1f, ease(t / 3.5f), 0f, 0f, 0f, true, 1f, t,
    if (t < 3.5f) "차에서 켜졌어요 · 맑아지는 중" else "맑아졌어요")

/** [done]: the driver already did it in the car, so the scene rests on the cleared glass instead of looping. */
@Composable
fun FogHeroScene(defrostOn: Boolean?, done: Boolean = false, modifier: Modifier = Modifier) {
    val live = defrostOn == true
    var step by remember { mutableIntStateOf(0) }
    var replay by remember { mutableIntStateOf(0) }
    val loop = stepTime(FOG_STEPS, step, replay)
    // The car's own button: clear once and hold.
    val liveT = stepTime(listOf(SceneStep(0f, 3.5f)), 0, if (live) 1 else 0)
    val b = when {
        live -> liveBeat(liveT)
        done -> Beat(1f, 1f, 0f, 0f, 0f, false, 0f, loop, "맑아졌어요")
        else -> loopBeat(loop)
    }
    val text = rememberTextMeasurer()
    val blurOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(Color(0xFF101318))
        .clickable(onClickLabel = "이 장면 다시 보기") { replay++ }
        .semantics { contentDescription = "운전석에서 본 흐린 앞유리. 화면 아래 바의 앞유리 김서림 버튼을 누르면 송풍구 바람이 아래부터 유리를 말려 앞이 맑아져요." }) {
        // 1. Sharp street.
        Canvas(Modifier.fillMaxSize()) { space { street(b.time) } }
        // 2. Fogged street: blurred, hazed, only where the glass is still fogged.
        Canvas(Modifier.fillMaxSize()
            .graphicsLayer {
                alpha = b.fog
                if (blurOk) renderEffect = BlurEffect(28.dp.toPx(), 28.dp.toPx(), TileMode.Clamp)
            }
            .drawWithContent {
                val edge = GLASS_BOTTOM - b.clear * (GLASS_BOTTOM - GLASS_TOP + 60f)
                val (s, _, ty) = fit()
                clipRect(top = 0f, bottom = ty + edge * s) { this@drawWithContent.drawContent() }
            }) {
            space {
                street(b.time)
                drawRect(Color(0xFFDCE3EA).copy(alpha = if (blurOk) .55f else .8f), Offset(0f, 0f), Size(W, GLASS_BOTTOM))
            }
        }
        // 3. Cabin.
        Canvas(Modifier.fillMaxSize()) { space { cabin(b, text) } }
        // Status line follows the motion.
        AnimatedContent(b.caption, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) }, label = "caption",
            modifier = Modifier.align(Alignment.TopStart).padding(20.dp)) { caption ->
            Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xCC0E1116)).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (b.on) Color(0xFFFFA43A) else Color(0xFF8FA3B8)))
                Text("  $caption", style = GuideType.label, color = Color.White)
            }
        }
        if (!live && !done) StepControls(step, FOG_STEPS.size, { step = (step - 1).coerceAtLeast(0) }, { step = (step + 1).coerceAtMost(FOG_STEPS.size - 1) },
            Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

/** Fogged → find and press the button (holds pressed and lit) → air clears the glass (holds clear). */
private val FOG_STEPS = listOf(SceneStep(0f, 1.2f), SceneStep(1.8f, 4.2f), SceneStep(4.6f, 9.4f))

/** Cover-fit of the 1000×810 space into the box, centred. */
private fun DrawScope.fit(): Triple<Float, Float, Float> {
    val s = max(size.width / W, size.height / H)
    return Triple(s, (size.width - W * s) / 2f, (size.height - H * s) / 2f)
}

private inline fun DrawScope.space(block: DrawScope.() -> Unit) {
    val (s, tx, ty) = fit()
    withTransform({ translate(tx, ty); scale(s, s, Offset.Zero) }) { block() }
}

// ---------------- Outside: a wet city street at dusk ----------------

internal fun DrawScope.street(time: Float) {
    val horizon = 300f
    drawRect(Brush.verticalGradient(listOf(Color(0xFF26364B), Color(0xFF51647B), Color(0xFF8A96A3)), 0f, horizon), Offset.Zero, Size(W, horizon))
    // Buildings on both sides, with lit windows.
    val blocks = listOf(0f to 150f, 90f to 110f, 190f to 190f, 290f to 140f, 380f to 220f, 610f to 210f, 700f to 130f, 790f to 180f, 880f to 120f)
    for ((x, h) in blocks) {
        val w = 96f
        drawRect(Brush.verticalGradient(listOf(Color(0xFF1C2633), Color(0xFF2B3746)), horizon - h, horizon), Offset(x, horizon - h), Size(w, h))
        var y = horizon - h + 14f
        var row = 0
        while (y < horizon - 16f) {
            for (k in 0..3) if (((x.toInt() / 10 + row * 3 + k * 7) % 5) < 2)
                drawRect(Color(0xFFF2C77A).copy(alpha = .75f), Offset(x + 10f + k * 21f, y), Size(12f, 9f))
            y += 22f; row++
        }
    }
    drawRect(Brush.verticalGradient(listOf(Color(0xFF2A323C), Color(0xFF181D23)), horizon, GLASS_BOTTOM), Offset(0f, horizon), Size(W, GLASS_BOTTOM - horizon + 40f))
    // Road with wet sheen and lane marks.
    val road = Path().apply { moveTo(470f, horizon); lineTo(540f, horizon); lineTo(1000f, GLASS_BOTTOM + 40f); lineTo(0f, GLASS_BOTTOM + 40f); close() }
    drawPath(road, Brush.verticalGradient(listOf(Color(0xFF3A4350), Color(0xFF242A32)), horizon, GLASS_BOTTOM))
    for (k in 0..5) {
        val a = .08f + k * .17f; val b2 = a + .08f
        fun at(p: Float) = Offset(505f + (p * 330f) * .02f, horizon + p * (GLASS_BOTTOM - horizon + 40f))
        val p0 = at(a); val p1 = at(b2)
        drawLine(Color(0xFFE8ECEF).copy(alpha = .85f), p0, p1, 3f + 9f * a, StrokeCap.Butt)
    }
    // Street lights: warm poles on the right, their reflections on the wet road.
    for (i in 0..3) {
        val d = i / 3f
        val x = 600f + d * 380f; val top = horizon - 70f - d * 110f; val base = horizon + d * 160f
        drawLine(Color(0xFF151A20), Offset(x, base), Offset(x, top), 3f + d * 6f)
        glow(Offset(x - 14f - d * 20f, top), 22f + d * 40f, Color(0xFFFFC97A))
    }
    // Oncoming headlights on the left.
    glow(Offset(420f, horizon + 18f), 26f, Color(0xFFF4F8FF))
    glow(Offset(448f, horizon + 18f), 26f, Color(0xFFF4F8FF))
    // Car ahead with tail lights and their reflections.
    val cx = 520f; val cy = 372f
    drawRoundRect(Color(0xFF151A21), Offset(cx - 70f, cy - 34f), Size(140f, 62f), CornerRadius(16f))
    drawRoundRect(Color(0xFF232B35), Offset(cx - 54f, cy - 58f), Size(108f, 30f), CornerRadius(12f))
    for (dx in listOf(-52f, 52f)) {
        glow(Offset(cx + dx, cy - 14f), 34f, Color(0xFFFF4136))
        drawRoundRect(Color(0xFFFF5A4E), Offset(cx + dx - 14f, cy - 18f), Size(28f, 9f), CornerRadius(4f))
        drawLine(Color(0xFFFF4136).copy(alpha = .22f), Offset(cx + dx, cy + 36f), Offset(cx + dx, cy + 110f), 16f, StrokeCap.Round)
    }
    // Light rain streaks.
    for (i in 0 until 40) {
        val x = (i * 97.3f + time * 40f) % W
        val y = ((i * 53.1f + time * 420f) % (GLASS_BOTTOM - 40f)) + 20f
        drawLine(Color.White.copy(alpha = .12f), Offset(x, y), Offset(x - 4f, y + 18f), 1.5f)
    }
}

internal fun DrawScope.glow(c: Offset, r: Float, color: Color) {
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .9f), color.copy(alpha = .35f), Color.Transparent), c, r * 2.2f), r * 2.2f, c)
    drawCircle(color, r * .28f, c)
}

// ---------------- Cabin ----------------

private val BUTTON = Offset(560f, 676f)

private fun glass() = Path().apply {
    moveTo(118f, GLASS_TOP + 10f); quadraticBezierTo(500f, GLASS_TOP - 30f, 882f, GLASS_TOP + 10f)
    lineTo(992f, GLASS_BOTTOM); lineTo(8f, GLASS_BOTTOM); close()
}

private fun DrawScope.cabin(b: Beat, text: androidx.compose.ui.text.TextMeasurer) {
    // Droplets on the inside of the glass, only where it is still fogged.
    val edge = GLASS_BOTTOM - b.clear * (GLASS_BOTTOM - GLASS_TOP + 60f)
    clipPath(glass()) {
        for (i in 0 until 160) {
            val x = (i * 61.7f) % 984f + 8f
            val y = GLASS_TOP + (i * 37.9f) % (GLASS_BOTTOM - GLASS_TOP)
            val fade = ((edge - y) / 50f).coerceIn(0f, 1f) * b.fog
            if (fade <= 0f) continue
            val r = 2.2f + (i % 5) * .9f
            drawCircle(Color(0xFF0E1218).copy(alpha = .16f * fade), r, Offset(x + .8f, y + 1.2f))
            drawCircle(Color.White.copy(alpha = .55f * fade), r, Offset(x, y))
            drawCircle(Color.White.copy(alpha = .9f * fade), r * .35f, Offset(x - r * .3f, y - r * .3f))
        }
        // Inner reflection of the dash on the glass.
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x22FFFFFF)), GLASS_BOTTOM - 120f, GLASS_BOTTOM), Offset(0f, GLASS_BOTTOM - 120f), Size(W, 120f))
        // The air itself is invisible; show where it works: a soft band of light at the
        // clearing edge, pushing the fog up from the vents.
        if (b.air > 0f && b.clear in .01f..0.99f) {
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFFCFE6FF).copy(alpha = .28f * b.air), Color.Transparent), edge - 10f, edge + 90f),
                Offset(0f, edge - 10f), Size(W, 100f))
        }
    }
    // Roof liner, pillars, mirror.
    drawPath(Path().apply {
        moveTo(0f, 0f); lineTo(W, 0f); lineTo(W, GLASS_BOTTOM); lineTo(992f, GLASS_BOTTOM); lineTo(882f, GLASS_TOP + 10f)
        quadraticBezierTo(500f, GLASS_TOP - 30f, 118f, GLASS_TOP + 10f); lineTo(8f, GLASS_BOTTOM); lineTo(0f, GLASS_BOTTOM); close()
    }, Brush.verticalGradient(listOf(Color(0xFF2A2E35), Color(0xFF1A1D22)), 0f, GLASS_BOTTOM))
    drawPath(Path().apply { moveTo(118f, GLASS_TOP + 10f); lineTo(8f, GLASS_BOTTOM); lineTo(64f, GLASS_BOTTOM); lineTo(156f, GLASS_TOP + 18f); close() },
        Brush.horizontalGradient(listOf(Color(0xFF15181C), Color(0xFF30353D)), 8f, 156f))
    drawPath(Path().apply { moveTo(882f, GLASS_TOP + 10f); lineTo(992f, GLASS_BOTTOM); lineTo(936f, GLASS_BOTTOM); lineTo(844f, GLASS_TOP + 18f); close() },
        Brush.horizontalGradient(listOf(Color(0xFF30353D), Color(0xFF15181C)), 844f, 992f))
    drawRoundRect(Color(0xFF16191E), Offset(462f, 20f), Size(76f, 44f), CornerRadius(12f))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF2B3038), Color(0xFF15181C)), 62f, 118f), Offset(392f, 62f), Size(216f, 56f), CornerRadius(28f))
    drawRoundRect(Color(0xFF4C5866), Offset(404f, 70f), Size(192f, 40f), CornerRadius(20f))

    // Dash: soft top edge catching light, vents at the base of the glass, long display, ambient line.
    val dash = Path().apply {
        moveTo(-50f, 456f); quadraticBezierTo(500f, 420f, 1050f, 456f); lineTo(1050f, H + 50f); lineTo(-50f, H + 50f); close()
    }
    drawPath(dash, Brush.verticalGradient(listOf(Color(0xFF3A3F47), Color(0xFF23272D), Color(0xFF15171B)), 430f, H))
    drawPath(Path().apply { moveTo(-50f, 456f); quadraticBezierTo(500f, 420f, 1050f, 456f) }, Color(0x33FFFFFF), style = Stroke(3f))
    for (i in 0..11) {
        val x = 108f + i * 66f
        val y = 450f - sin(i / 11f * PI.toFloat()) * 16f
        drawRoundRect(if (b.air > 0f) Color(0xFF7FB6E6).copy(alpha = .25f + .45f * b.air) else Color(0xFF0B0D10), Offset(x, y), Size(50f, 7f), CornerRadius(3.5f))
    }
    // Panoramic display with a dim cluster and map.
    drawRoundRect(Color(0xFF07090C), Offset(160f, 492f), Size(680f, 96f), CornerRadius(20f))
    drawRoundRect(Brush.linearGradient(listOf(Color(0x22FFFFFF), Color.Transparent), Offset(160f, 492f), Offset(400f, 588f)), Offset(160f, 492f), Size(680f, 96f), CornerRadius(20f))
    val speed = text.measure("0", TextStyle(fontFamily = Pretendard, color = Color(0xFFE8ECF1), fontSize = 40.sp, fontWeight = FontWeight.SemiBold))
    drawText(speed, topLeft = Offset(214f, 512f))
    val unit = text.measure("km/h", TextStyle(fontFamily = Pretendard, color = Color(0xFF7D8794), fontSize = 14.sp))
    drawText(unit, topLeft = Offset(246f, 536f))
    val gear = text.measure("P  R  N  D", TextStyle(fontFamily = Pretendard, color = Color(0xFF7D8794), fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
    drawText(gear, topLeft = Offset(320f, 530f))
    drawRoundRect(Color(0xFF2F7FE0).copy(alpha = .35f), Offset(316f, 528f), Size(24f, 24f), CornerRadius(6f))
    // Map: a few streets, the route and the car marker.
    clipRect(520f, 504f, 824f, 576f) {
        drawRect(Color(0xFF131C27), Offset(520f, 504f), Size(304f, 72f))
        for (k in 0..5) drawLine(Color(0xFF223042), Offset(520f + k * 60f, 504f), Offset(560f + k * 60f, 576f), 4f)
        drawLine(Color(0xFF223042), Offset(520f, 540f), Offset(824f, 530f), 5f)
        drawLine(Color(0xFF3C86E0), Offset(560f, 576f), Offset(640f, 540f), 6f, StrokeCap.Round)
        drawLine(Color(0xFF3C86E0), Offset(640f, 540f), Offset(800f, 534f), 6f, StrokeCap.Round)
    }
    drawPath(Path().apply { moveTo(560f, 556f); lineTo(570f, 576f); lineTo(560f, 571f); lineTo(550f, 576f); close() }, Color.White)
    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFFE9C38A).copy(alpha = .85f), Color.Transparent), 0f, W), Offset(0f, 606f), Size(W, 3f))
    drawRect(Brush.verticalGradient(listOf(Color(0x33E9C38A), Color.Transparent), 609f, 640f), Offset(0f, 609f), Size(W, 31f))

    // Steering wheel, lower left, leather with a highlight.
    val wc = Offset(270f, 870f)
    drawCircle(Color(0xFF0E1013), 262f, wc, style = Stroke(48f))
    drawArc(Color(0xFF3B4048), 205f, 120f, false, Offset(wc.x - 262f, wc.y - 262f), Size(524f, 524f), style = Stroke(5f, cap = StrokeCap.Round))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF2A2E35), Color(0xFF111316)), wc.y - 110f, wc.y + 40f), Offset(wc.x - 110f, wc.y - 110f), Size(220f, 150f), CornerRadius(54f))
    for (dx in listOf(-30f, -10f, 10f, 30f)) drawCircle(Color(0xFF4A505A), 5f, Offset(wc.x + dx, wc.y - 54f))

    // Climate panel: glossy black glass with five touch keys; front defrost is the first.
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF1D2127), Color(0xFF0B0D10)), 636f, 736f), Offset(470f, 632f), Size(440f, 100f), CornerRadius(26f))
    drawRoundRect(Color(0x26FFFFFF), Offset(470f, 632f), Size(440f, 100f), CornerRadius(26f), style = Stroke(1.5f))
    val keys = listOf("front", "rear", "recirc", "ac", "auto")
    keys.forEachIndexed { i, k ->
        val c = Offset(BUTTON.x + i * 86f, BUTTON.y)
        val lit = k == "front" && b.on
        val sink = if (k == "front") 1f - .08f * b.press else 1f
        withTransform({ scale(sink, sink, c) }) {
            if (lit) drawCircle(Brush.radialGradient(listOf(Color(0x66FFA43A), Color.Transparent), c, 70f), 70f, c)
            drawRoundRect(if (lit) Color(0xFF2B2117) else Color(0xFF15181C), Offset(c.x - 38f, c.y - 32f), Size(76f, 64f), CornerRadius(16f))
            val col = if (lit) Color(0xFFFFB25C) else Color(0xFFAEB6C0)
            when (k) {
                "front" -> frontIcon(c, col)
                "rear" -> {
                    drawRoundRect(col, Offset(c.x - 20f, c.y - 15f), Size(40f, 26f), CornerRadius(5f), style = Stroke(3.5f))
                    for (dx in listOf(-9f, 0f, 9f)) wave(Offset(c.x + dx, c.y + 6f), 14f, col)
                }
                "recirc" -> {
                    drawPath(Path().apply { moveTo(c.x - 22f, c.y + 10f); lineTo(c.x - 14f, c.y - 8f); lineTo(c.x + 14f, c.y - 8f); lineTo(c.x + 22f, c.y + 10f) }, col, style = Stroke(3.5f))
                    drawArc(col, 200f, 280f, false, Offset(c.x - 9f, c.y - 6f), Size(18f, 16f), style = Stroke(3f, cap = StrokeCap.Round))
                }
                else -> {
                    val l = text.measure(if (k == "ac") "A/C" else "AUTO", TextStyle(fontFamily = Pretendard, color = col, fontSize = 20.sp, fontWeight = FontWeight.SemiBold))
                    drawText(l, topLeft = Offset(c.x - l.size.width / 2f, c.y - l.size.height / 2f))
                }
            }
            drawRoundRect(if (lit) Color(0xFFFFA43A) else Color(0xFF353A42), Offset(c.x - 12f, c.y + 22f), Size(24f, 4f), CornerRadius(2f))
        }
    }
    // Pointer: ring and label over the button while it is the thing to find.
    if (b.point > 0f) {
        // In scene space the canvas size is not the scene, so cover generously.
        drawSpotlight(BUTTON, 70f, dim = .3f * b.point, topLeft = Offset(-3000f, -3000f), area = Size(7000f, 7000f))
        val l = text.measure("앞유리 김서림", TextStyle(fontFamily = Pretendard, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold))
        val bw = l.size.width + 32f; val bh = l.size.height + 16f
        val tl = Offset(BUTTON.x - bw / 2f, BUTTON.y - 66f - bh)
        drawRoundRect(Color(0xFF2F7FE0).copy(alpha = b.point), tl, Size(bw, bh), CornerRadius(bh / 2f))
        drawPath(Path().apply { moveTo(BUTTON.x - 10f, tl.y + bh); lineTo(BUTTON.x + 10f, tl.y + bh); lineTo(BUTTON.x, tl.y + bh + 12f); close() }, Color(0xFF2F7FE0).copy(alpha = b.point))
        drawText(l, topLeft = Offset(tl.x + 16f, tl.y + 8f), alpha = b.point)
    }
    // Press ripple.
    if (b.press > 0f) drawCircle(Color.White.copy(alpha = .35f * b.press), 30f + 40f * (1f - b.press), BUTTON, style = Stroke(3f))
    finger(b)
}

private fun DrawScope.frontIcon(c: Offset, col: Color) {
    drawPath(Path().apply {
        moveTo(c.x - 24f, c.y + 12f); quadraticBezierTo(c.x, c.y + 16f, c.x + 24f, c.y + 12f)
        quadraticBezierTo(c.x + 22f, c.y - 6f, c.x + 16f, c.y - 16f); quadraticBezierTo(c.x, c.y - 19f, c.x - 16f, c.y - 16f)
        quadraticBezierTo(c.x - 22f, c.y - 6f, c.x - 24f, c.y + 12f); close()
    }, col, style = Stroke(3.5f))
    for (dx in listOf(-9f, 0f, 9f)) wave(Offset(c.x + dx, c.y + 6f), 16f, col)
}

private fun DrawScope.wave(from: Offset, len: Float, col: Color) {
    drawPath(Path().apply {
        moveTo(from.x, from.y)
        quadraticBezierTo(from.x + 3f, from.y - len * .25f, from.x, from.y - len * .5f)
        quadraticBezierTo(from.x - 3f, from.y - len * .75f, from.x, from.y - len)
    }, col, style = Stroke(2.8f, cap = StrokeCap.Round))
}

/** Right index finger with a little shading, entering from the bottom right. */
private fun DrawScope.finger(b: Beat) {
    if (b.finger <= 0f) return
    val rest = Offset(940f, 960f)
    val tip = Offset(BUTTON.x + 8f, BUTTON.y + 12f + 5f * b.press)
    val at = Offset(rest.x + (tip.x - rest.x) * b.finger, rest.y + (tip.y - rest.y) * b.finger)
    val skin = Brush.horizontalGradient(listOf(Color(0xFFD9A07C), Color(0xFFF0C3A2), Color(0xFFD39674)), -26f, 26f)
    withTransform({ translate(at.x, at.y) }) {
        rotate(-36f, Offset.Zero) {
            drawRoundRect(Color(0x55000000), Offset(-20f, 8f), Size(52f, 200f), CornerRadius(26f))
            drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFFC88C69), Color(0xFFE5B08E)), -70f, 90f), Offset(-70f, 130f), Size(168f, 240f), CornerRadius(70f))
            drawRoundRect(skin, Offset(-24f, 0f), Size(48f, 200f), CornerRadius(24f))
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFF8E2D4), Color(0xFFEBC5AE)), 6f, 38f), Offset(-15f, 6f), Size(30f, 32f), CornerRadius(13f))
            drawLine(Color(0x55A86A4C), Offset(-14f, 86f), Offset(14f, 86f), 2.5f, StrokeCap.Round)
            drawLine(Color(0x55A86A4C), Offset(-12f, 94f), Offset(12f, 94f), 2f, StrokeCap.Round)
            drawRoundRect(skin, Offset(30f, 118f), Size(50f, 130f), CornerRadius(25f))
            drawRoundRect(skin, Offset(-76f, 158f), Size(46f, 120f), CornerRadius(23f))
        }
    }
}
