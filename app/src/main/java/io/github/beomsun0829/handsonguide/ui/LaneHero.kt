package io.github.beomsun0829.handsonguide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/*
 * "차선 바꾸는 순서" scene, seen from behind my car on a three-lane road at dusk. The loop: signal, mirror
 * (its view drawn as a cone), shoulder check (the blind spot it misses, with a car in it),
 * wait for that car to pass, then move over at the same speed and switch the signal off.
 */

private const val LW = 1000f
private const val LH = 810f
/** Signal and mirror → shoulder check (and wait) → move: plays through once and holds; a tap replays it. */
private val LANE_PLAY = listOf(SceneStep(0f, 14.2f))
private val LANES = floatArrayOf(340f, 500f, 660f)

private data class LaneBeat(
    val blink: Boolean, val mirror: Float, val spot: Float, val other: Float, val move: Float,
    val fade: Float, val time: Float, val caption: String, val step: Int,
)

private fun le(x: Float): Float { val v = x.coerceIn(0f, 1f); return v * v * (3 - 2 * v) }
private fun lwin(t: Float, a: Float, b: Float, f: Float) = if (t < a || t > b) 0f else minOf(le((t - a) / f), le((b - t) / f))

/** [t]: the step's motion, which plays once and holds; [a]: steady ambient time for road flow and the blink. */
private fun laneLoop(t: Float, a: Float) = LaneBeat(
    blink = t in .6f..12.6f && (a * 1.6f) % 1f < .55f,
    mirror = lwin(t, 2.4f, 5f, .4f),
    spot = lwin(t, 5f, 9f, .4f),
    other = le((t - 7f) / 2.6f),
    move = le((t - 9.8f) / 2.4f),
    fade = if (t > 14.4f) 1f - le((t - 14.4f) / .6f) else le(t / .4f),
    time = a,
    caption = when {
        t < 5f -> "깜빡이를 켜고 미러로 뒤를 봐요"
        t < 9.8f -> "고개를 살짝 돌려 옆 뒤를 확인해요"
        else -> "속도는 그대로, 부드럽게 옮겨요"
    },
    step = when { t < 5f -> 0; t < 9.8f -> 1; else -> 2 },
)

@Composable
fun LaneHeroScene(done: Boolean, modifier: Modifier = Modifier) {
    var replay by remember { mutableIntStateOf(0) }
    val t = stepTimeState(LANE_PLAY, 0, replay); val a = ambientClockState()
    // Drawing reads the clocks each frame; the words around it recompose only when they change.
    val caption by remember { derivedStateOf { laneLoop(t.value, a.value).caption } }
    val beat by remember { derivedStateOf { laneLoop(t.value, a.value).let { it.step to it.blink } } }
    val text = rememberTextMeasurer()
    val still = remember { StillImage() }
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(Color(0xFF101318))
        .clickable(onClickLabel = "이 장면 다시 보기") { replay++ }
        .semantics { contentDescription = "내 차 뒤에서 본 3차로. 깜빡이를 켜고, 사이드미러를 보고, 고개를 돌려 사각지대를 확인한 뒤, 옆 차가 지나가면 속도를 유지하며 옆 차로로 옮겨요." }) {
        Canvas(Modifier.fillMaxSize()) {
            val s = max(size.width / LW, size.height / LH)
            val tx = (size.width - LW * s) / 2f; val ty = (size.height - LH * s) / 2f
            drawImage(still.of(this) { withTransform({ translate(tx, ty); scale(s, s, Offset.Zero) }) { laneRoad() } })
            withTransform({ translate(tx, ty); scale(s, s, Offset.Zero) }) {
                laneScene(laneLoop(t.value, a.value), text)
            }
        }
        // Where we are in signal → mirror → shoulder → move, and under it what to do now. Stacked, so the
        // two never collide however narrow the picture or large the type.
        Column(Modifier.align(Alignment.TopStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xCC0E1116)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                listOf("깜빡이·미러", "고개", "이동").forEachIndexed { i, s ->
                    val on = done || i <= beat.first
                    Text(if (i == 0) s else "  ›  $s", style = GuideType.label, color = if (on) Color.White else Color(0xFF6E7884))
                }
            }
            AnimatedContent(caption, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) }, label = "caption") { caption ->
                Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xCC0E1116)).padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (beat.second) Color(0xFFFFA43A) else Color(0xFF8FA3B8)))
                    Text("  $caption", style = GuideType.label, color = Color.White)
                }
            }
        }
    }
}

/** The scene at step time [t] and ambient time [a], on a 1000×810 canvas (for RoadSceneRenderTest). */
internal fun DrawScope.laneSceneAt(t: Float, a: Float, text: TextMeasurer) { laneRoad(); laneScene(laneLoop(t, a), text) }

/** Lanes of the 3D road (RoadScene3D world): left, mine, right. */
private val LANES3 = floatArrayOf(400f, 500f, 600f)
private val LANE_CAM = RoadCamera(550f, 1080f, 330f)

/** The still part: sky, verges, asphalt and edge lines (drawn once into an image). */
private fun DrawScope.laneRoad() {
    val cam = LANE_CAM
    sky(cam)
    ground(cam, -4000f, FAR, 9000f, NEAR - FAR, GROUND3)
    ground(cam, 340f, FAR, 320f, NEAR - FAR, ASPHALT3)
    paint(cam, 350f, FAR, 350f, NEAR); paint(cam, 650f, FAR, 650f, NEAR)
}

private fun DrawScope.laneScene(b: LaneBeat, text: TextMeasurer) {
    val cam = LANE_CAM
    val scroll = b.time * 260f    // the road flows toward us: we are moving
    dashed(cam, 450f, scroll = scroll); dashed(cam, 550f, scroll = scroll)
    haze(cam)
    val me = Offset(LANES3[1] + (LANES3[2] - LANES3[1]) * b.move, 560f)
    val angle = 10f * kotlin.math.sin(b.move * Math.PI.toFloat())
    // Mirror view: what the right mirror shows behind; it stops short of the blind spot right beside us.
    if (b.mirror > 0f) {
        val cone = listOf(Triple(me.x + 30f, me.y - 20f, 1f), Triple(LANES3[2] + 60f, 1000f, 1f), Triple(LANES3[1] + 70f, 1000f, 1f))
        quad(cam, cone, Color(0xFF4DA3FF).copy(alpha = .30f * b.mirror))
    }
    // Blind spot: beside and just behind, where the mirror does not reach.
    if (b.spot > 0f) {
        ground(cam, LANES3[2] - 46f, 500f, 92f, 200f, Color(0xFFFF5C5C).copy(alpha = .26f * b.spot))
        for ((x0, y0, x1, y1) in listOf(listOf(554f, 500f, 646f, 500f), listOf(554f, 700f, 646f, 700f), listOf(554f, 500f, 554f, 700f), listOf(646f, 500f, 646f, 700f)))
            paint(cam, x0, y0, x1, y1, 4f, Color(0xFFFF5C5C).copy(alpha = .9f * b.spot))
    }
    // Preview of the move while waiting; during the move the car itself shows it.
    if (b.spot > .5f) {
        val path = Path()
        for (i in 0..20) {
            val u = i / 20f; val x = LANES3[1] + (LANES3[2] - LANES3[1]) * le(u); val y = 470f - 170f * u
            val p = cam.at(x, y, 1f); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, Color.White.copy(alpha = .55f), style = Stroke(6f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f))))
    }
    streetLights(cam, listOf(230f, 770f), scroll)
    // Traffic, far first: a car ahead in my lane, one ahead on the left, the one in the blind spot, then me.
    val cars = listOf(
        RoadItem.Car(LANES3[1], 140f), RoadItem.Car(LANES3[0], 330f - b.other * 60f, kind = "parked"),
        RoadItem.Car(LANES3[2], 600f - b.other * 520f, kind = "light"),
        RoadItem.Car(me.x, me.y, angle, "me", if (b.blink) "right" else null),
    )
    for (c in cars.sortedBy { it.y }) car3D(cam, c, b.time)
    // Labels last, so no car covers them.
    val spotAt = cam.at(LANES3[2], 500f, 60f)
    if (b.mirror > 0f) bubble(text, "미러에 보이는 곳 ↓", Offset(spotAt.x, spotAt.y - 40f), Color(0xFF2F7FE0), b.mirror)
    if (b.spot > 0f) bubble(text, "사각지대 · 고개로 확인", Offset(spotAt.x, spotAt.y - 40f), Color(0xFFB23B3B), b.spot)
    if (b.fade < 1f) drawRect(Color(0xFF101318).copy(alpha = 1f - b.fade), Offset.Zero, Size(LW, LH))
}

/** Car from above, nose up: shaded body, glass, mirrors, lamps; blinkers on the right side when signalling. */
/** Car from above, nose up. [sides]: -1 left, 1 right, for the blinkers. */
internal fun DrawScope.topCar(c: Offset, color: Color, blink: Boolean, mine: Boolean = false, sides: List<Float> = listOf(1f)) {
    val w = 84f; val l = 176f
    drawRoundRect(Color(0x66000000), Offset(c.x - w / 2f + 6f, c.y - l / 2f + 10f), Size(w, l), CornerRadius(26f))
    drawRoundRect(Brush.horizontalGradient(listOf(color.copy(alpha = .75f), color, color.copy(alpha = .75f)), c.x - w / 2f, c.x + w / 2f),
        Offset(c.x - w / 2f, c.y - l / 2f), Size(w, l), CornerRadius(26f))
    drawRoundRect(Color(0xFF16202B), Offset(c.x - 32f, c.y - l / 2f + 34f), Size(64f, 34f), CornerRadius(10f))
    drawRoundRect(Brush.verticalGradient(listOf(color.copy(alpha = .9f), color.copy(alpha = .7f)), c.y - 20f, c.y + 40f),
        Offset(c.x - 30f, c.y - 18f), Size(60f, 62f), CornerRadius(10f))
    drawRoundRect(Color(0xFF16202B), Offset(c.x - 28f, c.y + l / 2f - 46f), Size(56f, 22f), CornerRadius(8f))
    for (d in listOf(-1f, 1f)) drawRoundRect(color, Offset(c.x + d * (w / 2f + 6f) - 6f, c.y - 50f), Size(12f, 10f), CornerRadius(4f))
    for (d in listOf(-1f, 1f)) {
        drawRoundRect(Color(0xFFF2F6FA), Offset(c.x + d * 26f - 9f, c.y - l / 2f + 2f), Size(18f, 6f), CornerRadius(3f))
        drawRoundRect(Color(0xFFE0483E), Offset(c.x + d * 26f - 10f, c.y + l / 2f - 8f), Size(20f, 6f), CornerRadius(3f))
    }
    if (blink) for (d in sides) for (y in listOf(c.y - l / 2f + 8f, c.y + l / 2f - 8f)) {
        val p = Offset(c.x + d * (w / 2f - 6f), y)
        drawCircle(Brush.radialGradient(listOf(Color(0xCCFFA43A), Color.Transparent), p, 42f), 42f, p)
        drawCircle(Color(0xFFFFB25C), 8f, p)
    }
    if (mine) drawRoundRect(Color.White.copy(alpha = .35f), Offset(c.x - w / 2f, c.y - l / 2f), Size(w, l), CornerRadius(26f), style = Stroke(3f))
}

private fun DrawScope.bubble(text: TextMeasurer, s: String, center: Offset, color: Color, alpha: Float) {
    val l = text.measure(s, TextStyle(fontFamily = Pretendard, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
    val w = l.size.width + 28f; val h = l.size.height + 14f
    val x = (center.x - w / 2f).coerceIn(10f, LW - 10f - w)
    drawRoundRect(color.copy(alpha = alpha), Offset(x, center.y - h / 2f), Size(w, h), CornerRadius(h / 2f))
    drawText(l, topLeft = Offset(x + 14f, center.y - h / 2f + 7f), alpha = alpha)
}
