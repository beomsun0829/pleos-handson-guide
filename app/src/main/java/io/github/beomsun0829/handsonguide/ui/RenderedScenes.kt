package io.github.beomsun0829.handsonguide.ui

import io.github.beomsun0829.handsonguide.signal.Signals
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/*
 * Cards whose pictures are rendered offline (Blender, official photo crops) and shipped as frames
 * under assets/scenes3d/<card>/. The app plays them as a slideshow with crossfades, draws the
 * highlight ring where the 3D target landed, and the caption in the app's own type.
 */

/** A tap ring (one point) or an arrow (a path ending in a head), with an optional short label, in 0..1 image coordinates. */
data class Cue(val pts: List<Offset>, val label: String?, val below: Boolean = false)

/**
 * [live]: shown while the car reports this; true for an on/off signal, or the exact value (e.g. "LEFT", "R").
 * [hidden]: not a step the learner pages through; only shown while live.
 */
data class RenderedFrame(val file: String, val caption: String, val ms: Long, val ring: FloatArray?, val live: Any?, val done: Boolean,
                         val taps: List<Cue> = emptyList(), val arrows: List<Cue> = emptyList(), val hidden: Boolean = false)

private fun cues(o: JSONObject, key: String, single: Boolean): List<Cue> {
    val arr = o.optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val c = arr.getJSONObject(i)
        val pts = if (single) c.getJSONArray("at").let { listOf(Offset(it.getDouble(0).toFloat(), it.getDouble(1).toFloat())) }
        else c.getJSONArray("pts").let { a -> (0 until a.length()).map { k -> a.getJSONArray(k).let { Offset(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) } } }
        Cue(pts, c.optString("label").takeIf { it.isNotEmpty() }, c.optString("side") == "below")
    }
}

/** Which signal means the car is doing this card's action (null: the card has no live state). */
private val LIVE_SIGNAL = mapOf(
    "c6-rear" to Signals.DEFROST_REAR, "c5-hazard" to Signals.HAZARD, "c5-turn" to Signals.TURN_SIGNAL,
    "c4-gear" to Signals.GEAR, "c5-auto" to null, "c2-side" to null, "c4-autohold" to null,
    "c1-sit" to null, "c4-park" to null, "c6-front" to null, "c4-onefoot" to null, "c4-creep" to null, "c4-smooth" to null, "c4-regen" to null,
    "c8-colors" to null, "c8-common" to null, "c8-tpms" to null, "c8-fuel" to null,
    "c9-cruise" to null, "c9-lane" to null, "c9-rule" to null, "c5-high" to null, "c5-wiper" to null,
    "c7-modes" to null, "c6-ac" to null, "c7-heat" to null,
    "c0-start" to null, "c0-door" to null, "c0-trunk" to null, "c1-seat" to null, "c3-wheelpos" to null, "c2-mirror" to null, "c4-epb" to Signals.PARKING_BRAKE,
    "c6-vent" to null,
)

val RENDERED_CARDS: Set<String> = LIVE_SIGNAL.keys

private fun RenderedFrame.matches(s: Signals, signal: String?): Boolean = when {
    signal == null || live == null || live == false -> false
    live == true -> s.bool(signal) == true
    else -> s.str(signal) == live
}

/** Decoded card pictures (about 30 cards of one or two 1200x960 frames). */
private val FRAME_CACHE = object : LinkedHashMap<String, List<Pair<RenderedFrame, ImageBitmap>>>(16, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pair<RenderedFrame, ImageBitmap>>>) = size > 12
}

@Composable
fun RenderedCardScene(cardId: String, s: Signals, done: Boolean, description: String, modifier: Modifier = Modifier) =
    // Fresh state per card (CardArt keys it too): the same spot can show another card, and its pictures must not linger.
    androidx.compose.runtime.key(cardId) { RenderedCardSceneFor(cardId, s, done, description, modifier) }

@Composable
private fun RenderedCardSceneFor(cardId: String, s: Signals, done: Boolean, description: String, modifier: Modifier) {
    val context = LocalContext.current
    // Decoded frames are kept, so scrolling back to a section or reopening a card shows its picture at once.
    val frames by produceState(FRAME_CACHE[cardId], cardId) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            val dir = "scenes3d/$cardId"
            val meta = JSONObject(context.assets.open("$dir/frames.json").bufferedReader().use { it.readText() })
            val arr = meta.getJSONArray("frames")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ring = o.optJSONArray("ring")?.let { r -> FloatArray(4) { r.getDouble(it).toFloat() } }
                val f = RenderedFrame(o.getString("file"), o.getString("caption"), o.optLong("ms", 2000), ring,
                    o.opt("live"), o.optBoolean("done"), cues(o, "taps", single = true), cues(o, "arrows", single = false),
                    o.optBoolean("hidden"))
                f to context.assets.open("$dir/${f.file}.webp").use { BitmapFactory.decodeStream(it).asImageBitmap() }
            }
        }.also { FRAME_CACHE[cardId] = it }
    }
    val list = frames
    // Until the picture is decoded the box holds the page's own surface tone, not a black hole.
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(c.surface2).semantics { contentDescription = description }) {
        if (list == null) return@Box
        val signal = LIVE_SIGNAL[cardId]
        val liveFrames = list.indices.filter { list[it].first.matches(s, signal) }
        val live = liveFrames.isNotEmpty()
        // The learner steps through by hand; nothing advances on a timer. Hidden frames are not steps.
        val steps = remember(list) { list.indices.filter { !list[it].first.hidden } }
        var step by remember(list) { mutableIntStateOf(0) }
        val restAt = steps.indexOfFirst { list[it].first.done }
        LaunchedEffect(done) { if (done && restAt >= 0) step = restAt }
        val index = steps[step]
        // While the car reports the action, show its result (the last matching frame) in place of the step.
        // Two neighbouring live frames with the same caption are a blinking indicator, so they alternate.
        val liveAt = liveFrames.lastOrNull()
        var blink by remember { mutableIntStateOf(0) }
        val blinkPair = liveAt?.let { a -> listOf(a - 1, a + 1).firstOrNull { it in liveFrames && list[it].first.caption == list[a].first.caption } }
        LaunchedEffect(blinkPair) { blink = 0; if (blinkPair != null) while (true) { delay(600); blink = 1 - blink } }
        val shown = when { liveAt == null -> index; blinkPair != null && blink == 1 -> blinkPair; else -> liveAt }
        Crossfade(shown, animationSpec = tween(350), label = "frame") { i ->
            val (frame, bitmap) = list[i]
            Box(Modifier.fillMaxSize()) {
                Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                frame.ring?.let { r -> Spotlight(r, bitmap.width.toFloat() / bitmap.height) }
                if (frame.taps.isNotEmpty() || frame.arrows.isNotEmpty()) Cues(frame, bitmap.width.toFloat() / bitmap.height)
            }
        }
        SceneCaption(list[shown].first.caption, lit = live || done, modifier = Modifier.align(Alignment.TopStart).padding(20.dp))
        if (!live) StepControls(step, steps.size, onPrev = { step = (step - 1).coerceAtLeast(0) },
            onNext = { step = (step + 1).coerceAtMost(steps.size - 1) }, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

/**
 * Spotlight on [r] (x, y, w, h as 0..1 of the image, same centre crop as the image): the scene
 * dims softly everywhere except a round pool of light on the target. No outline to look crooked.
 */
@Composable
private fun Spotlight(r: FloatArray, imageAspect: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val boxAspect = size.width / size.height
        val (sw, sh) = if (boxAspect > imageAspect) size.width to size.width / imageAspect else size.height * imageAspect to size.height
        val ox = (size.width - sw) / 2f; val oy = (size.height - sh) / 2f
        // Light scenes only need a gentle dim to point the eye; a heavy one hides the context.
        val radius = maxOf(maxOf(r[2] * sw, r[3] * sh) * .62f + 4.dp.toPx(), 44.dp.toPx())
        val c = Offset(ox + (r[0] + r[2] / 2f) * sw, oy + (r[1] + r[3] / 2f) * sh)
        drawSpotlight(c, radius, dim = .15f)
        // The same ring as a tap cue, so the spot reads at a glance and not only through the dimming.
        drawCircle(Color(0x99101318), radius, c, style = Stroke(7.dp.toPx()))
        drawCircle(Color.White, radius, c, style = Stroke(3.5f.dp.toPx()))
    }
}

/**
 * What to do, drawn over the picture in the app's own ink: a ring where to press, an arrow for which way to
 * move, each with a short label. The arrow draws itself in once when the frame appears, then stays.
 */
@Composable
private fun Cues(frame: RenderedFrame, imageAspect: Float) {
    val text = rememberTextMeasurer()
    val draw = remember(frame.file) { Animatable(0f) }
    LaunchedEffect(frame.file) { draw.animateTo(1f, tween(650, delayMillis = 250)) }
    Canvas(Modifier.fillMaxSize()) {
        val boxAspect = size.width / size.height
        val (sw, sh) = if (boxAspect > imageAspect) size.width to size.width / imageAspect else size.height * imageAspect to size.height
        val ox = (size.width - sw) / 2f; val oy = (size.height - sh) / 2f
        fun at(p: Offset) = Offset(ox + p.x * sw, oy + p.y * sh)
        val ink = Color.White
        val shade = Color(0x99101318)
        val w = 4.dp.toPx()
        fun label(s: String, anchor: Offset, dir: Offset) {
            val l = text.measure(s, TextStyle(fontFamily = Pretendard, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink))
            val pw = l.size.width + 20.dp.toPx(); val ph = l.size.height + 10.dp.toPx()
            val c = anchor + dir * (ph * .5f + 14.dp.toPx() + maxOf(0f, dir.x * dir.x) * pw * .5f)
            val tl = Offset((c.x - pw / 2f).coerceIn(4f, size.width - pw - 4f), (c.y - ph / 2f).coerceIn(4f, size.height - ph - 4f))
            drawRoundRect(Color(0xE6101318), tl, Size(pw, ph), CornerRadius(ph / 2f))
            drawText(l, topLeft = tl + Offset(10.dp.toPx(), 5.dp.toPx()))
        }
        for (a in frame.arrows) {
            val pts = a.pts.map(::at)
            if (pts.size < 2) continue
            // Draw-in: the path grows from its start to its end.
            val lens = pts.zipWithNext { p, q -> (q - p).getDistance() }
            val total = lens.sum()
            var left = total * draw.value
            val path = Path().apply { moveTo(pts[0].x, pts[0].y) }
            var tip = pts[0]; var dir = Offset(0f, -1f)
            for ((k, len) in lens.withIndex()) {
                if (left <= 0f || len == 0f) break
                val t = (left / len).coerceAtMost(1f)
                val seg = pts[k + 1] - pts[k]
                tip = pts[k] + seg * t; dir = seg / len
                path.lineTo(tip.x, tip.y); left -= len
            }
            val stroke = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(path, shade, style = Stroke(w * 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, ink, style = stroke)
            val h = 9.dp.toPx(); val n = Offset(-dir.y, dir.x)
            val head = Path().apply {
                moveTo((tip + dir * h).x, (tip + dir * h).y)
                lineTo((tip - dir * h * .6f + n * h).x, (tip - dir * h * .6f + n * h).y)
                lineTo((tip - dir * h * .6f - n * h).x, (tip - dir * h * .6f - n * h).y); close()
            }
            drawPath(head, shade, style = Stroke(w, join = StrokeJoin.Round)); drawPath(head, ink)
            if (draw.value >= 1f) a.label?.let { label(it, tip + dir * h, dir) }
        }
        for (t in frame.taps) {
            val c = at(t.pts[0])
            val r = 20.dp.toPx()
            // One soft pulse as the frame appears, then a steady ring.
            val pulse = (1f - draw.value)
            if (pulse > 0f) drawCircle(ink.copy(alpha = .5f * pulse), r * (1f + .8f * draw.value), c, style = Stroke(w * .6f))
            drawCircle(ink.copy(alpha = .16f), r, c)
            drawCircle(shade, r, c, style = Stroke(w * 2f))
            drawCircle(ink, r, c, style = Stroke(w * .9f))
            // Right of the ring by default; below where the photo has its own text to the right.
            t.label?.let { if (t.below) label(it, c + Offset(0f, r), Offset(0f, 1f)) else label(it, c + Offset(r, 0f), Offset(1f, 0f)) }
        }
    }
}

/** Dim everything but a soft circle of [radius] around [centre], and lift the target a little. */
fun DrawScope.drawSpotlight(centre: Offset, radius: Float, dim: Float = .7f, topLeft: Offset = Offset.Zero, area: Size = size) {
    drawRect(Brush.radialGradient(
        0f to Color.Transparent, .62f to Color.Transparent, 1f to Color.Black.copy(alpha = dim),
        center = centre, radius = radius * 1.6f, tileMode = TileMode.Clamp,
    ), topLeft, area)
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .1f * dim), Color.Transparent), centre, radius * .7f), radius * .7f, centre)
}

/** ‹ 2 / 5 › under a scene: the learner moves between steps when ready. */
@Composable
fun StepControls(index: Int, count: Int, onPrev: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    if (count < 2) return
    Row(modifier.clip(RoundedCornerShape(28.dp)).background(Color(0xD90E1116)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        StepArrow("‹", "이전 장면", enabled = index > 0, onPrev)
        Text("${index + 1} / $count", style = GuideType.label, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
        StepArrow("›", "다음 장면", enabled = index < count - 1, onNext)
    }
}

@Composable
private fun StepArrow(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(glyph, style = GuideType.headline, color = if (enabled) Color.White else Color(0x55FFFFFF))
    }
}

/** One step of an animated scene: its motion runs from [from] to [to] once, then holds. */
data class SceneStep(val from: Float, val to: Float)

/** Scene time for the current step; [replay] restarts the step's motion. */
@Composable
fun stepTime(steps: List<SceneStep>, step: Int, replay: Int): Float = stepTimeState(steps, step, replay).value

/** [stepTime] as a [State], for reading inside drawing only. */
@Composable
fun stepTimeState(steps: List<SceneStep>, step: Int, replay: Int): androidx.compose.runtime.State<Float> {
    val t = remember { mutableFloatStateOf(steps[step].from) }
    LaunchedEffect(step, replay) {
        val w = steps[step]
        t.floatValue = w.from
        var last = withFrameNanos { it }
        while (t.floatValue < w.to) withFrameNanos { now -> if (now - last >= FRAME_NS) { t.floatValue = (t.floatValue + ((now - last) / 1e9f).coerceAtMost(.1f)).coerceAtMost(w.to); last = now } }
    }
    return t
}

/** The status line shown in the corner of a scene, following its motion. */
@Composable
fun SceneCaption(text: String, lit: Boolean, modifier: Modifier = Modifier) {
    AnimatedContent(text, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) }, label = "caption", modifier = modifier) { t ->
        Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xCC0E1116)).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (lit) Color(0xFFFFA43A) else Color(0xFF8FA3B8)))
            Text("  $t", style = GuideType.label, color = Color.White)
        }
    }
}
