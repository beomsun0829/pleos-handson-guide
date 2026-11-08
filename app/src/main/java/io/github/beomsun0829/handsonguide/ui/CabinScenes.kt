package io.github.beomsun0829.handsonguide.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
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
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Driver-seat view of the demo car (spec §5.4 viewpoint A), drawn in code in a 1000×800 virtual
 * space. One cockpit serves every control card: [focus] picks the control to point at, [lit]
 * mirrors what the car reports so the drawing follows the real buttons.
 */
@Composable
fun CockpitScene(focus: String?, label: String?, lit: Set<String>, description: String, modifier: Modifier = Modifier) {
    val time = animationClock()
    val text = rememberTextMeasurer()
    // Zoom toward the control so it reads at a glance; the glass still shows at the top.
    SceneBox(modifier, description, focus = focus?.let { Spot.rects[it]?.center }, zoom = 1.5f) {
        drawCockpit(lit, text) { street(time) }
        if (focus != null) drawFocus(focus, label, 1f, time, text)
        // Controls you press get a finger acting it out; mirrors, gauges and vents are only looked at.
        if (focus != null && focus !in setOf("room_mirror", "side_mirror", "cluster", "vent", "wheel")) pressFinger(Spot.center(focus), time)
    }
}

/**
 * The fog story on the same cockpit: fogged glass, the front-defrost button pointed at, a finger
 * pressing it and the glass clearing from the vents up. When the car reports front defrost on,
 * the loop stops and the scene follows the real button instead.
 */
@Composable
fun FogCabinScene(defrostOn: Boolean?, modifier: Modifier = Modifier) {
    val on = defrostOn == true
    var loop by remember { mutableFloatStateOf(0f) }
    var live by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(on) {
        live = 0f
        var last = withFrameNanos { it }
        while (true) withFrameNanos { now ->
            val dt = ((now - last) / 1e9f).coerceAtMost(.1f); last = now
            if (on) live += dt else loop = (loop + dt) % FOG_LOOP
        }
    }
    val f = if (on) FogFrame.live(live) else FogFrame.loop(loop)
    val text = rememberTextMeasurer()
    SceneBox(modifier, "운전석에서 본 앞유리와 공조 버튼. 앞유리가 뿌옇게 흐려지면 화면 아래 바의 앞유리 김서림 버튼을 눌러요. " +
        "앞유리 아래 송풍구에서 바람이 올라와 아래부터 맑아져요.") {
        drawCockpit(if (f.buttonOn) setOf("defrost_front") else emptySet(), text, air = f.air, airTime = f.time) {
            drawOutside(); drawFog(f)
        }
        drawAirflow(f)
        if (f.highlight > 0f) drawFocus("defrost_front", "앞유리 김서림", f.highlight, f.time, text)
        drawFinger(f)
    }
}

/**
 * Shared frame: dark rounded panel showing the 1000×800 space. Without [focus] the width fits and
 * spare height goes below; with it the view is [zoom]ed in around that point, kept inside the space.
 */
@Composable
internal fun SceneBox(modifier: Modifier, description: String, focus: Offset? = null, zoom: Float = 1f,
    /** The parts that never move, drawn once per size into an image instead of every frame. */
    still: (DrawScope.() -> Unit)? = null, draw: DrawScope.() -> Unit) {
    val cache = remember { StillImage() }
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(Cabin.night)) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }) {
            val fit = min(size.width / 1000f, size.height / 720f)
            val s = if (focus == null) fit else fit * zoom
            // When the scene is smaller than the box on an axis it is centred instead of clamped.
            fun place(want: Float, box: Float, scene: Float) = if (scene <= box) (box - scene) / 2f else want.coerceIn(box - scene, 0f)
            val tx = if (focus == null) (size.width - 1000f * s) / 2f else place(size.width / 2f - focus.x * s, size.width, 1000f * s)
            val ty = if (focus == null) 0f else place(size.height * .55f - focus.y * s, size.height, 800f * s)
            if (still != null) drawImage(cache.of(this) { withTransform({ translate(tx, ty); scale(s, s, Offset.Zero) }) { still() } })
            withTransform({
                translate(tx, ty)
                scale(s, s, Offset.Zero)
            }) { draw() }
        }
    }
}

/** An image of a scene's still layer at the canvas's pixel size, redrawn only when that size changes. */
internal class StillImage {
    private var image: ImageBitmap? = null
    fun of(scope: DrawScope, paint: DrawScope.() -> Unit): ImageBitmap {
        val w = scope.size.width.toInt().coerceAtLeast(1); val h = scope.size.height.toInt().coerceAtLeast(1)
        image?.let { if (it.width == w && it.height == h) return it }
        return ImageBitmap(w, h).also { img ->
            CanvasDrawScope().draw(scope, scope.layoutDirection, androidx.compose.ui.graphics.Canvas(img), scope.size) { paint() }
            image = img
        }
    }
}

/** Continuous scene time. Read it as a [State] inside drawing so a frame only redraws and does not recompose. */
@Composable
internal fun ambientClockState(): State<Float> {
    // Continuous time for motion that should never stop or jump: road flow, a blinking turn signal.
    // A step's own clock holds at its end, and anything looping on it froze mid-blink there.
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        // 30 updates a second is smooth enough for these scenes and halves the drawing (the 3D road is GPU-heavy).
        while (true) withFrameNanos { now -> if (now - last >= FRAME_NS) { t.floatValue += ((now - last) / 1e9f).coerceAtMost(.1f); last = now } }
    }
    return t
}

@Composable
internal fun ambientClock(): Float = ambientClockState().value

/** Seconds since shown, stopping at [until]: a card's motion plays once and holds so it can be read. */
@Composable
internal fun animationClockState(until: Float = 3.2f): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (t.floatValue < until) withFrameNanos { now -> if (now - last >= FRAME_NS) { t.floatValue = (t.floatValue + ((now - last) / 1e9f).coerceAtMost(.1f)).coerceAtMost(until); last = now } }
    }
    return t
}

@Composable
internal fun animationClock(until: Float = 3.2f): Float = animationClockState(until).value

/** Scene clocks advance at most every ~33 ms (30 fps). */
internal const val FRAME_NS = 33_000_000L

private const val FOG_LOOP = 10f

/** One moment of the fog story. [clear] is how far up the glass has cleared, 0..1. */
internal data class FogFrame(
    val clear: Float, val fog: Float, val highlight: Float, val finger: Float, val press: Float,
    val buttonOn: Boolean, val air: Float, val time: Float,
) {
    companion object {
        fun loop(t: Float) = FogFrame(
            clear = when { t < 4.4f -> 0f; t < 8f -> ease((t - 4.4f) / 3.6f); t < 9.2f -> 1f; else -> 0f },
            fog = if (t < 9.2f) 1f else ease((t - 9.2f) / .8f),
            highlight = window(t, 1.4f, 4.3f, .35f),
            finger = when { t < 2.8f -> 0f; t < 3.8f -> ease((t - 2.8f) / 1f); t < 4.3f -> 1f; t < 5f -> 1f - ease((t - 4.3f) / .7f); else -> 0f },
            press = if (t in 3.8f..4.3f) sin(((t - 3.8f) / .5f) * PI.toFloat()) else 0f,
            buttonOn = t in 4.05f..9.2f,
            air = window(t, 4.05f, 9.2f, .4f),
            time = t,
        )

        fun live(t: Float) = FogFrame(ease(t / 3f), 1f, 0f, 0f, 0f, true, 1f, t)

        internal fun ease(x: Float): Float { val v = x.coerceIn(0f, 1f); return v * v * (3 - 2 * v) }
        private fun window(t: Float, a: Float, b: Float, fade: Float) =
            if (t < a || t > b) 0f else min(ease((t - a) / fade), ease((b - t) / fade))
    }
}

internal object Cabin {
    val night = Color(0xFF1A1D22)
    val skyTop = Color(0xFF9EC3E6); val skyLow = Color(0xFFDCE8F2)
    val city = Color(0xFFB4C2CF); val road = Color(0xFF6F7680); val lane = Color(0xFFF2F2F2)
    val trim = Color(0xFF23262C); val trimHi = Color(0xFF30343B); val dash = Color(0xFF2B2F36); val dashLow = Color(0xFF1C1F24)
    val screen = Color(0xFF0B0E12); val screenLine = Color(0xFF39424F)
    val ambient = Color(0xFFE9C38A)
    val wheel = Color(0xFF15171B); val wheelHi = Color(0xFF3A3F47)
    val panel = Color(0xFF14171B); val icon = Color(0xFFB9C0CA); val iconOn = Color(0xFFFFA43A)
    val fog = Color(0xFFE9EDF1); val air = Color(0xFFBFE3FF)
    val focus = Color(0xFF4DA3FF); val skin = Color(0xFFE6B48F); val skinShade = Color(0xFFCB966F); val nail = Color(0xFFF3D6C2)
    val alert = Color(0xFFFF5C5C); val green = Color(0xFF3DDC97)
}

/** Where each control sits in the cockpit, in the 1000×800 space. */
internal object Spot {
    val rects = mapOf(
        "defrost_front" to Rect(564f, 629f, 636f, 681f),
        "defrost_rear" to Rect(640f, 629f, 712f, 681f),
        "recirc" to Rect(716f, 629f, 788f, 681f),
        "ac" to Rect(792f, 629f, 864f, 681f),
        "auto" to Rect(868f, 629f, 936f, 681f),
        "left_stalk" to Rect(18f, 596f, 150f, 664f),
        "right_stalk" to Rect(452f, 596f, 572f, 664f),
        "gear" to Rect(498f, 672f, 572f, 728f),
        "hazard" to Rect(846f, 500f, 910f, 556f),
        "epb" to Rect(600f, 722f, 672f, 778f),
        "autohold" to Rect(680f, 722f, 752f, 778f),
        "cruise" to Rect(386f, 728f, 470f, 782f),
        "lfa" to Rect(130f, 728f, 214f, 782f),
        "cluster" to Rect(178f, 484f, 462f, 574f),
        "room_mirror" to Rect(392f, 62f, 608f, 130f),
        "side_mirror" to Rect(0f, 346f, 92f, 462f),
        "wheel" to Rect(70f, 560f, 530f, 800f),
        "vent" to Rect(120f, 424f, 880f, 470f),
    )
    fun center(key: String) = rects.getValue(key).center
}

private val GLASS_BOTTOM = 470f

private fun glassPath() = Path().apply {
    moveTo(120f, 62f); quadraticBezierTo(500f, 28f, 880f, 62f)
    lineTo(988f, GLASS_BOTTOM); lineTo(12f, GLASS_BOTTOM); close()
}

/** Everything but the view through the glass, which [glass] draws inside the windshield. */
private fun DrawScope.drawCockpit(lit: Set<String>, text: TextMeasurer, air: Float = 0f, airTime: Float = 0f, glass: DrawScope.() -> Unit) {
    clipPath(glassPath()) { glass() }
    // Roof liner and A-pillars frame the glass.
    drawPath(Path().apply {
        moveTo(0f, 0f); lineTo(1000f, 0f); lineTo(1000f, GLASS_BOTTOM); lineTo(988f, GLASS_BOTTOM); lineTo(880f, 62f)
        quadraticBezierTo(500f, 28f, 120f, 62f); lineTo(12f, GLASS_BOTTOM); lineTo(0f, GLASS_BOTTOM); close()
    }, Cabin.trim)
    drawPath(Path().apply { moveTo(120f, 62f); lineTo(12f, GLASS_BOTTOM); lineTo(60f, GLASS_BOTTOM); lineTo(150f, 70f); close() }, Cabin.trimHi)
    drawPath(Path().apply { moveTo(880f, 62f); lineTo(988f, GLASS_BOTTOM); lineTo(940f, GLASS_BOTTOM); lineTo(850f, 70f); close() }, Cabin.trimHi)
    // Side mirror seen through the side window, and the room mirror.
    drawRoundRect(Color(0xFF3A3F47), Offset(4f, 360f), Size(80f, 92f), CornerRadius(22f))
    drawRoundRect(Color(0xFF8FA6BD), Offset(14f, 370f), Size(60f, 72f), CornerRadius(16f))
    drawRoundRect(Cabin.trimHi, Offset(455f, 30f), Size(90f, 60f), CornerRadius(14f))
    drawRoundRect(Cabin.dashLow, Offset(400f, 70f), Size(200f, 52f), CornerRadius(26f))
    drawRoundRect(Color(0xFF55606D), Offset(410f, 78f), Size(180f, 36f), CornerRadius(18f))
    drawDash(lit, air, text)
    drawConsole(lit, text)
    drawStalks(lit)
    drawWheel()
}

private fun DrawScope.drawOutside() {
    drawRect(Brush.verticalGradient(listOf(Cabin.skyTop, Cabin.skyLow), 30f, 300f), Offset(0f, 0f), Size(1000f, 300f))
    val tops = floatArrayOf(250f, 214f, 236f, 190f, 228f, 205f, 244f, 222f, 196f, 232f, 210f, 248f)
    tops.forEachIndexed { i, y -> drawRect(Cabin.city, Offset(i * 86f - 10f, y), Size(80f, 300f - y)) }
    drawRect(Color(0xFF8E979F), Offset(0f, 300f), Size(1000f, 180f))
    drawPath(Path().apply { moveTo(470f, 300f); lineTo(530f, 300f); lineTo(900f, GLASS_BOTTOM); lineTo(100f, GLASS_BOTTOM); close() }, Cabin.road)
    for (side in listOf(-1f, 1f)) for (k in 0..3) {
        val a = .18f + k * .24f; val b = a + .12f
        fun at(p: Float) = Offset(500f + side * (8f + p * 150f), 300f + p * 170f)
        drawLine(Cabin.lane, at(a), at(b), 4f + 6f * a, StrokeCap.Round)
    }
    drawRoundRect(Color(0xFF3C4450), Offset(468f, 318f), Size(64f, 40f), CornerRadius(10f))
    drawRoundRect(Color(0xFF59636F), Offset(476f, 306f), Size(48f, 20f), CornerRadius(8f))
    drawRect(Color(0xFFE0483E), Offset(472f, 334f), Size(12f, 6f)); drawRect(Color(0xFFE0483E), Offset(516f, 334f), Size(12f, 6f))
}

private fun DrawScope.drawFog(f: FogFrame) {
    if (f.fog <= 0f) return
    // The vents sit under the glass, so the clear edge rises from the bottom with a soft band.
    val edge = GLASS_BOTTOM - f.clear * (GLASS_BOTTOM + 90f)
    val band = 90f
    drawRect(Brush.verticalGradient(
        0f to Cabin.fog.copy(alpha = .9f * f.fog),
        ((edge - band) / GLASS_BOTTOM).coerceIn(0f, 1f) to Cabin.fog.copy(alpha = .86f * f.fog),
        (edge / GLASS_BOTTOM).coerceIn(0f, 1f) to Cabin.fog.copy(alpha = 0f),
        startY = 0f, endY = GLASS_BOTTOM,
    ), Offset.Zero, Size(1000f, GLASS_BOTTOM))
    for (i in 0 until 70) {
        val x = (i * 137.5f) % 980f + 10f
        val y = 60f + (i * 61.3f) % 400f
        if (y > edge - band / 2) continue
        drawCircle(Color.White.copy(alpha = .55f * f.fog), 3f + (i % 4), Offset(x, y))
    }
}

private fun DrawScope.drawAirflow(f: FogFrame) {
    if (f.air <= 0f) return
    val phase = -f.time * 160f
    for (i in 0..5) {
        val x = 150f + i * 140f
        val lean = (x - 500f) * .18f
        val p = Path().apply { moveTo(x, GLASS_BOTTOM - 4f); quadraticBezierTo(x + lean * .4f, 330f, x + lean, 180f) }
        drawPath(p, Cabin.air.copy(alpha = .75f * f.air),
            style = Stroke(6f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(34f, 30f), phase + i * 17f)))
    }
}

private fun DrawScope.drawDash(lit: Set<String>, air: Float, text: TextMeasurer) {
    drawPath(Path().apply {
        moveTo(-500f, 450f); lineTo(0f, 450f); quadraticBezierTo(500f, 420f, 1000f, 450f); lineTo(1500f, 450f)
        lineTo(1500f, 1600f); lineTo(-500f, 1600f); close()
    }, Brush.verticalGradient(listOf(Cabin.dash, Cabin.dashLow), 420f, 800f))
    for (i in 0..9) {
        val x = 130f + i * 76f
        drawRoundRect(if (air > 0f) Cabin.air.copy(alpha = .25f + .4f * air) else Color(0xFF101215),
            Offset(x, 446f - sin(i / 9f * PI.toFloat()) * 18f), Size(58f, 8f), CornerRadius(4f))
    }
    // Panoramic display: cluster on the left, navigation on the right.
    drawRoundRect(Cabin.screen, Offset(170f, 482f), Size(660f, 92f), CornerRadius(18f))
    drawRoundRect(Cabin.screenLine, Offset(170f, 482f), Size(660f, 92f), CornerRadius(18f), style = Stroke(2f))
    drawCircle(Cabin.screenLine, 30f, Offset(250f, 528f), style = Stroke(5f))
    drawCircle(Cabin.screenLine, 30f, Offset(390f, 528f), style = Stroke(5f))
    // Cluster indicators the cards talk about.
    if ("turn_left" in lit) arrowIcon(Offset(300f, 512f), left = true, Cabin.green)
    if ("turn_right" in lit) arrowIcon(Offset(340f, 512f), left = false, Cabin.green)
    if ("high_beam" in lit) drawText(text.measure("≡D", TextStyle(fontFamily = Pretendard, color = Color(0xFF4D8BFF), fontSize = 22.sp, fontWeight = FontWeight.Bold)), topLeft = Offset(300f, 534f))
    if ("telltale_amber" in lit) drawPath(triangle(Offset(320f, 552f), 14f), Cabin.iconOn)
    if ("telltale_red" in lit) drawPath(triangle(Offset(320f, 552f), 14f), Cabin.alert)
    drawRoundRect(Color(0xFF1B2430), Offset(520f, 496f), Size(290f, 64f), CornerRadius(10f))
    drawLine(Color(0xFF3B6EA8), Offset(560f, 548f), Offset(700f, 510f), 6f, StrokeCap.Round)
    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Cabin.ambient.copy(alpha = .8f), Color.Transparent), 0f, 1000f),
        Offset(0f, 592f), Size(1000f, 4f))
    // Hazard switch on the dash, right of the display.
    val hz = Spot.rects.getValue("hazard")
    drawRoundRect(Cabin.panel, hz.topLeft, hz.size, CornerRadius(12f))
    drawPath(triangle(hz.center, 16f), if ("hazard" in lit) Cabin.alert else Color(0xFFD06A6A), style = Stroke(4f, join = StrokeJoin.Round))
}

private fun DrawScope.drawConsole(lit: Set<String>, text: TextMeasurer) {
    drawRoundRect(Cabin.panel, Offset(556f, 618f), Size(384f, 74f), CornerRadius(20f))
    drawRoundRect(Cabin.screenLine, Offset(556f, 618f), Size(384f, 74f), CornerRadius(20f), style = Stroke(2f))
    for (key in listOf("defrost_front", "defrost_rear", "recirc", "ac", "auto")) drawClimateButton(key, key in lit, text)
    // Console below: electronic parking brake and auto hold.
    drawRoundRect(Color(0xFF181B20), Offset(586f, 712f), Size(184f, 80f), CornerRadius(18f))
    for ((key, word) in listOf("epb" to "P", "autohold" to "A")) {
        val r = Spot.rects.getValue(key)
        drawRoundRect(Color(0xFF22262C), r.topLeft, r.size, CornerRadius(12f))
        val on = key in lit
        drawCircle(if (on) Cabin.iconOn else Cabin.icon, 16f, r.center, style = Stroke(3f))
        val l = text.measure(word, TextStyle(fontFamily = Pretendard, color = if (on) Cabin.iconOn else Cabin.icon, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        drawText(l, topLeft = Offset(r.center.x - l.size.width / 2f, r.center.y - l.size.height / 2f))
    }
}

private fun DrawScope.drawClimateButton(key: String, on: Boolean, text: TextMeasurer) {
    val r = Spot.rects.getValue(key)
    drawRoundRect(if (on) Color(0xFF2A2218) else Color(0xFF1E2227), r.topLeft, r.size, CornerRadius(12f))
    val color = if (on) Cabin.iconOn else Cabin.icon
    val c = r.center
    when (key) {
        "defrost_front" -> defrostFrontIcon(c, color)
        "defrost_rear" -> {
            drawRoundRect(color, Offset(c.x - 20f, c.y - 14f), Size(40f, 26f), CornerRadius(5f), style = Stroke(4f))
            for (dx in listOf(-9f, 0f, 9f)) waveUp(Offset(c.x + dx, c.y + 6f), 14f, color)
        }
        "recirc" -> {
            drawPath(Path().apply { moveTo(c.x - 22f, c.y + 10f); lineTo(c.x - 14f, c.y - 8f); lineTo(c.x + 14f, c.y - 8f); lineTo(c.x + 22f, c.y + 10f) },
                color, style = Stroke(4f, join = StrokeJoin.Round))
            drawArc(color, 200f, 280f, false, Offset(c.x - 9f, c.y - 6f), Size(18f, 16f), style = Stroke(3.5f, cap = StrokeCap.Round))
        }
        else -> {
            val word = if (key == "ac") "A/C" else "AUTO"
            val l = text.measure(word, TextStyle(fontFamily = Pretendard, color = color, fontSize = if (key == "ac") 24.sp else 20.sp, fontWeight = FontWeight.Bold))
            drawText(l, topLeft = Offset(c.x - l.size.width / 2f, c.y - l.size.height / 2f))
        }
    }
    drawRoundRect(if (on) Cabin.iconOn else Color(0xFF3A3F47), Offset(c.x - 12f, r.bottom - 7f), Size(24f, 4f), CornerRadius(2f))
}

/** Light/turn-signal stalk on the left, wiper stalk and column gear selector on the right. */
private fun DrawScope.drawStalks(lit: Set<String>) {
    drawLine(Color(0xFF2B2F36), Offset(190f, 652f), Offset(40f, 626f), 20f, StrokeCap.Round)
    drawCircle(if ("turn_left" in lit || "turn_right" in lit) Cabin.green else Cabin.icon, 9f, Offset(46f, 627f))
    drawLine(Color(0xFF2B2F36), Offset(410f, 652f), Offset(552f, 626f), 20f, StrokeCap.Round)
    drawCircle(Cabin.icon, 9f, Offset(546f, 627f))
    drawLine(Color(0xFF2B2F36), Offset(420f, 704f), Offset(540f, 700f), 24f, StrokeCap.Round)
    drawRoundRect(Color(0xFF3A3F47), Offset(526f, 684f), Size(40f, 34f), CornerRadius(10f))
}

private fun DrawScope.drawWheel() {
    val c = Offset(300f, 790f)
    drawCircle(Cabin.wheel, 228f, c, style = Stroke(40f))
    drawArc(Cabin.wheelHi, 200f, 140f, false, Offset(c.x - 228f, c.y - 228f), Size(456f, 456f), style = Stroke(6f, cap = StrokeCap.Round))
    drawRoundRect(Cabin.wheel, Offset(c.x - 96f, c.y - 92f), Size(192f, 130f), CornerRadius(46f))
    drawLine(Cabin.wheel, Offset(c.x - 210f, c.y - 30f), Offset(c.x - 90f, c.y - 40f), 34f, StrokeCap.Round)
    drawLine(Cabin.wheel, Offset(c.x + 210f, c.y - 30f), Offset(c.x + 90f, c.y - 40f), 34f, StrokeCap.Round)
    for (dx in listOf(-36f, -12f, 12f, 36f)) drawCircle(Cabin.wheelHi, 5f, Offset(c.x + dx, c.y - 30f))
    // Spoke buttons: lane assist on the left, cruise on the right.
    for (x in listOf(156f, 188f, 412f, 444f)) drawCircle(Color(0xFF2E333B), 11f, Offset(x, 755f))
}

private fun DrawScope.drawFocus(key: String, label: String?, alpha: Float, time: Float, text: TextMeasurer) {
    Spot.rects[key]?.let { drawFocusRect(it, label, alpha, time, text) }
}

/** Pulsing outline around [r] and a label bubble pointing at it. */
internal fun DrawScope.drawFocusRect(r: Rect, label: String?, alpha: Float, time: Float, text: TextMeasurer) {
    // Spotlight instead of an outline (an outline on a perspective scene looks crooked).
    if (alpha > 0f) drawSpotlight(r.center, maxOf(r.width, r.height) * .75f + 12f, .3f * alpha, Offset(-3000f, -3000f), Size(7000f, 7000f))
    if (label == null) return
    val l = text.measure(label, TextStyle(fontFamily = Pretendard, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold))
    val bw = l.size.width + 36f; val bh = l.size.height + 18f
    // Above the control, kept inside the picture; below it when there is no room above.
    val above = r.top - 24f - bh > 10f
    val y = if (above) r.top - 24f - bh else r.bottom + 24f
    val x = (r.center.x - bw / 2f).coerceIn(10f, 990f - bw)
    drawRoundRect(Cabin.focus.copy(alpha = alpha), Offset(x, y), Size(bw, bh), CornerRadius(bh / 2f))
    val tipX = r.center.x.coerceIn(x + 20f, x + bw - 20f)
    drawPath(Path().apply {
        if (above) { moveTo(tipX - 12f, y + bh); lineTo(tipX + 12f, y + bh); lineTo(tipX, y + bh + 14f) }
        else { moveTo(tipX - 12f, y); lineTo(tipX + 12f, y); lineTo(tipX, y - 14f) }
        close()
    }, Cabin.focus.copy(alpha = alpha))
    drawText(l, topLeft = Offset(x + 18f, y + 9f), alpha = alpha)
}

internal fun triangle(c: Offset, r: Float) = Path().apply {
    moveTo(c.x, c.y - r); lineTo(c.x + r * .95f, c.y + r * .7f); lineTo(c.x - r * .95f, c.y + r * .7f); close()
}

private fun DrawScope.arrowIcon(c: Offset, left: Boolean, color: Color) {
    val d = if (left) -1f else 1f
    drawPath(Path().apply { moveTo(c.x + d * 14f, c.y); lineTo(c.x - d * 4f, c.y - 12f); lineTo(c.x - d * 4f, c.y + 12f); close() }, color)
}

private fun DrawScope.defrostFrontIcon(c: Offset, color: Color) {
    val st = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(Path().apply {
        moveTo(c.x - 24f, c.y + 12f); quadraticBezierTo(c.x, c.y + 16f, c.x + 24f, c.y + 12f)
        quadraticBezierTo(c.x + 22f, c.y - 6f, c.x + 16f, c.y - 16f); quadraticBezierTo(c.x, c.y - 19f, c.x - 16f, c.y - 16f)
        quadraticBezierTo(c.x - 22f, c.y - 6f, c.x - 24f, c.y + 12f); close()
    }, color, style = st)
    for (dx in listOf(-9f, 0f, 9f)) waveUp(Offset(c.x + dx, c.y + 6f), 16f, color)
}

private fun DrawScope.waveUp(from: Offset, len: Float, color: Color) {
    val p = Path().apply {
        moveTo(from.x, from.y)
        quadraticBezierTo(from.x + 3f, from.y - len * .25f, from.x, from.y - len * .5f)
        quadraticBezierTo(from.x - 3f, from.y - len * .75f, from.x, from.y - len)
    }
    drawPath(p, color, style = Stroke(3f, cap = StrokeCap.Round))
}

/** A right hand's index finger reaching up from the bottom-right edge toward the button. */
private fun DrawScope.drawFinger(f: FogFrame) {
    if (f.finger <= 0f) return
    val button = Spot.center("defrost_front")
    val rest = Offset(980f, 1000f)
    val tip = Offset(button.x + 6f, button.y + 14f + 6f * f.press)
    val at = Offset(rest.x + (tip.x - rest.x) * f.finger, rest.y + (tip.y - rest.y) * f.finger)
    withTransform({ translate(at.x, at.y) }) {
        rotate(-38f, Offset.Zero) {
            drawRoundRect(Cabin.skinShade, Offset(-60f, 120f), Size(150f, 220f), CornerRadius(60f))
            drawRoundRect(Cabin.skin, Offset(-22f, 0f), Size(44f, 190f), CornerRadius(22f))
            drawRoundRect(Cabin.nail, Offset(-14f, 6f), Size(28f, 30f), CornerRadius(12f))
            drawLine(Cabin.skinShade, Offset(-14f, 82f), Offset(14f, 82f), 3f, StrokeCap.Round)
            drawRoundRect(Cabin.skin, Offset(26f, 112f), Size(46f, 120f), CornerRadius(23f))
            drawRoundRect(Cabin.skin, Offset(-66f, 150f), Size(42f, 110f), CornerRadius(21f))
        }
    }
}

/** A finger reaching from the nearer bottom corner, pressing [target] once every four seconds. */
internal fun DrawScope.pressFinger(target: Offset, time: Float) {
    // Reach in, press, rest on the button.
    val t = time.coerceAtMost(2.0f)
    val reach = when { t < .6f -> 0f; t < 1.6f -> FogFrame.ease((t - .6f) / 1f); t < 2.4f -> 1f; t < 3.2f -> 1f - FogFrame.ease((t - 2.4f) / .8f); else -> 0f }
    if (reach <= 0f) return
    val press = if (t in 1.6f..2.2f) sin((t - 1.6f) / .6f * PI.toFloat()) else 0f
    val fromLeft = target.x < 300f
    val rest = if (fromLeft) Offset(-60f, 980f) else Offset(1060f, 980f)
    val tip = Offset(target.x + if (fromLeft) -6f else 6f, target.y + 12f + 5f * press)
    val at = Offset(rest.x + (tip.x - rest.x) * reach, rest.y + (tip.y - rest.y) * reach)
    val skin = Brush.horizontalGradient(listOf(Color(0xFFD9A07C), Color(0xFFF0C3A2), Color(0xFFD39674)), -26f, 26f)
    withTransform({ translate(at.x, at.y) }) {
        rotate(if (fromLeft) 36f else -36f, Offset.Zero) {
            drawRoundRect(Color(0x55000000), Offset(-20f, 8f), Size(52f, 200f), CornerRadius(26f))
            drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFFC88C69), Color(0xFFE5B08E)), -70f, 90f), Offset(-70f, 130f), Size(168f, 240f), CornerRadius(70f))
            drawRoundRect(skin, Offset(-24f, 0f), Size(48f, 200f), CornerRadius(24f))
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFF8E2D4), Color(0xFFEBC5AE)), 6f, 38f), Offset(-15f, 6f), Size(30f, 32f), CornerRadius(13f))
            drawRoundRect(skin, Offset(if (fromLeft) -80f else 30f, 118f), Size(50f, 130f), CornerRadius(25f))
        }
    }
    if (press > 0f) drawCircle(Color.White.copy(alpha = .35f * press), 30f + 40f * (1f - press), target, style = Stroke(3f))
}
