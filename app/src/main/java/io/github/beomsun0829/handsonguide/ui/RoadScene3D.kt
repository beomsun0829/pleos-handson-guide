package io.github.beomsun0829.handsonguide.ui

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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The road scenes seen from behind and above my car. The scenes are written in the top-down world
 * (1000×800, cars nose up at 0°, y grows toward the viewer); this camera projects that world, with
 * height z, onto the 1000×720 card.
 */
internal class RoadCamera(private val x: Float, private val y: Float, private val h: Float, pitchDeg: Float = 20f, private val focal: Float = 800f) {
    private val c = cos(Math.toRadians(pitchDeg.toDouble())).toFloat()
    private val s = sin(Math.toRadians(pitchDeg.toDouble())).toFloat()
    /** Screen row of the horizon. */
    val horizon = CENTER_Y - focal * tan(Math.toRadians(pitchDeg.toDouble())).toFloat()

    fun depth(wy: Float, z: Float = 0f) = (-(wy - y) * c - (z - h) * s).coerceAtLeast(1f)
    fun at(wx: Float, wy: Float, z: Float = 0f): Offset {
        val d = depth(wy, z)
        return Offset(500f + focal * (wx - x) / d, CENTER_Y - focal * (-(wy - y) * s + (z - h) * c) / d)
    }
    /** Screen pixels per world unit at that spot. */
    fun scale(wy: Float, z: Float = 0f) = focal / depth(wy, z)

    companion object {
        private const val CENTER_Y = 396f

        private val cameras = java.util.WeakHashMap<Art.Road, RoadCamera>()

        /** Far enough back that everything in the scene, down to its lowest car, is on the card. */
        fun forScene(art: Art.Road): RoadCamera = synchronized(cameras) { cameras.getOrPut(art) { place(art) } }

        private fun place(art: Art.Road): RoadCamera {
            val me = art.items.filterIsInstance<RoadItem.Car>().firstOrNull { it.kind == "me" }
            val lowest = art.items.maxOfOrNull {
                when (it) {
                    is RoadItem.Car -> it.y + 70f
                    is RoadItem.Path -> it.points.maxOf { p -> p.y }
                    else -> 0f
                }
            } ?: 600f
            // Parked cars belong to the layout, not the items: keep the nearest one in view too.
            val parked = when (art.base) { "parking" -> 650f; "curb" -> 680f; else -> 0f }
            val top = art.base in setOf("parking", "curb", "toll")    // spaces and booths read better from higher up
            return RoadCamera(me?.x ?: 500f, maxOf(lowest, parked, 560f) + if (top) 470f else 390f, if (top) 520f else 330f, if (top) 30f else 20f)
        }
    }
}

internal val SKY_TOP = Color(0xFF141A24); internal val SKY = Color(0xFF263246)
internal val GROUND3 = Color(0xFF1A2027); internal val ASPHALT3 = Color(0xFF343B44); private val PAINT3 = Color(0xFFE6E8EB)
private val YELLOW3 = Color(0xFFF2C14E); private val BLUE_LANE = Color(0xFF3D7BE0)
internal const val FAR = -2600f; internal const val NEAR = 1300f

internal fun DrawScope.quad(cam: RoadCamera, pts: List<Triple<Float, Float, Float>>, color: Color) {
    val path = Path()
    pts.forEachIndexed { i, (x, y, z) -> val p = cam.at(x, y, z); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
    path.close()
    drawPath(path, color)
}

internal fun DrawScope.ground(cam: RoadCamera, x: Float, y: Float, w: Float, h: Float, color: Color, z: Float = 0f) =
    quad(cam, listOf(Triple(x, y, z), Triple(x + w, y, z), Triple(x + w, y + h, z), Triple(x, y + h, z)), color)

/** A painted line on the road, [width] world units wide. */
internal fun DrawScope.paint(cam: RoadCamera, x0: Float, y0: Float, x1: Float, y1: Float, width: Float = 6f, color: Color = PAINT3) {
    val a = atan2(y1 - y0, x1 - x0); val nx = -sin(a) * width / 2f; val ny = cos(a) * width / 2f
    quad(cam, listOf(Triple(x0 + nx, y0 + ny, 0f), Triple(x1 + nx, y1 + ny, 0f), Triple(x1 - nx, y1 - ny, 0f), Triple(x0 - nx, y0 - ny, 0f)), color)
}

internal fun DrawScope.dashed(cam: RoadCamera, x: Float, from: Float = FAR, to: Float = NEAR, color: Color = PAINT3, scroll: Float = 0f) {
    var y = from + scroll % 90f
    while (y < to) {
        // Near the horizon a dash is under a pixel long and only costs time: the haze covers that band anyway.
        if (cam.scale(y) * 50f >= 1.5f) paint(cam, x, y, x, minOf(y + 50f, to), 6f, color)
        y += 90f
    }
}

private fun DrawScope.disc(cam: RoadCamera, cx: Float, cy: Float, r: Float, color: Color, ring: Float = 0f) {
    val path = Path()
    for (i in 0..48) {
        val a = i / 48f * 2f * Math.PI.toFloat()
        val p = cam.at(cx + r * cos(a), cy + r * sin(a))
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    if (ring > 0f) drawPath(path, color, style = Stroke(ring * cam.scale(cy), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f))))
    else drawPath(path, color)
}

private fun DrawScope.drawBase3D(cam: RoadCamera, base: String) {
    ground(cam, -4000f, FAR, 9000f, NEAR - FAR, GROUND3)
    fun straight(x0: Float, x1: Float) { ground(cam, x0, FAR, x1 - x0, NEAR - FAR, ASPHALT3); paint(cam, x0 + 10f, FAR, x0 + 10f, NEAR); paint(cam, x1 - 10f, FAR, x1 - 10f, NEAR) }
    when (base) {
        "straight3", "straight3_solid", "buslane" -> {
            straight(340f, 660f)
            // The bus lane is the first lane (leftmost), as on highways and central bus lanes.
            if (base == "buslane") ground(cam, 340f, FAR, 110f, NEAR - FAR, BLUE_LANE.copy(alpha = .35f))
            if (base == "buslane") paint(cam, 450f, FAR, 450f, NEAR, 8f, BLUE_LANE) else dashed(cam, 450f)
            if (base == "straight3_solid") paint(cam, 550f, FAR, 550f, NEAR) else dashed(cam, 550f)
        }
        "uturn" -> {
            // Two-way road: the yellow centre line breaks into a white dashed stretch where the U-turn sign allows it.
            straight(340f, 660f)
            paint(cam, 500f, FAR, 500f, 200f, 6f, YELLOW3); paint(cam, 500f, 360f, 500f, NEAR, 6f, YELLOW3)
            dashed(cam, 500f, from = 200f, to = 360f); dashed(cam, 420f); dashed(cam, 580f)
        }
        "merge" -> {
            // The main road's right edge is solid only past the merge: along the acceleration lane it is a dashed lane line.
            ground(cam, 390f, FAR, 240f, NEAR - FAR, ASPHALT3); paint(cam, 400f, FAR, 400f, NEAR); paint(cam, 620f, FAR, 620f, 160f)
            quad(cam, listOf(Triple(630f, 160f, 0f), Triple(760f, 720f, 0f), Triple(760f, NEAR, 0f), Triple(630f, NEAR, 0f)), ASPHALT3)
            dashed(cam, 510f); dashed(cam, 620f, from = 160f); paint(cam, 630f, 160f, 760f, 720f); paint(cam, 760f, 720f, 760f, NEAR)
        }
        "exit" -> {
            straight(440f, 660f)
            quad(cam, listOf(Triple(660f, 520f, 0f), Triple(1200f, -500f, 0f), Triple(1290f, -450f, 0f), Triple(700f, 620f, 0f)), ASPHALT3)
            dashed(cam, 550f); paint(cam, 650f, FAR, 650f, 500f)
        }
        "cross" -> {
            ground(cam, 400f, FAR, 220f, NEAR - FAR, ASPHALT3); ground(cam, -3000f, 260f, 7000f, 200f, ASPHALT3)
            paint(cam, 510f, 560f, 510f, NEAR, 6f, YELLOW3); paint(cam, 510f, FAR, 510f, 160f, 6f, YELLOW3)
            paint(cam, -3000f, 360f, 300f, 360f, 6f, YELLOW3); paint(cam, 720f, 360f, 4000f, 360f, 6f, YELLOW3)
            // Crosswalks sit a little back from the junction, and the stop line before the crosswalk.
            for (i in 0..6) {
                ground(cam, 412f + i * 30f, 520f, 16f, 34f, PAINT3); ground(cam, 412f + i * 30f, 166f, 16f, 34f, PAINT3)
                ground(cam, 680f, 272f + i * 26f, 34f, 14f, PAINT3); ground(cam, 306f, 272f + i * 26f, 34f, 14f, PAINT3)
            }
            paint(cam, 512f, 590f, 612f, 590f, 8f)
        }
        "roundabout" -> {
            ground(cam, 440f, 450f, 160f, NEAR - 450f, ASPHALT3); ground(cam, 600f, 260f, 3000f, 140f, ASPHALT3)
            ground(cam, -2600f, 260f, 3000f, 140f, ASPHALT3); ground(cam, 440f, FAR, 160f, 100f - FAR, ASPHALT3)
            disc(cam, 520f, 330f, 230f, ASPHALT3); disc(cam, 520f, 330f, 100f, Color(0xFF2E4A33))
            disc(cam, 520f, 330f, 165f, PAINT3, ring = 4f)
        }
        "parking" -> {
            ground(cam, -2000f, FAR, 5000f, NEAR - FAR, ASPHALT3)
            for (i in 0..6) paint(cam, 150f + i * 120f, 480f, 150f + i * 120f, 700f, 5f)
            for (i in 0..6) paint(cam, 150f + i * 120f, 0f, 150f + i * 120f, 220f, 5f)
        }
        "school" -> {
            ground(cam, 240f, FAR, 80f, NEAR - FAR, Color(0xFF5B6168)); ground(cam, 720f, FAR, 80f, NEAR - FAR, Color(0xFF5B6168))
            ground(cam, 320f, FAR, 400f, NEAR - FAR, ASPHALT3)
            ground(cam, 320f, 120f, 400f, 440f, Color(0xFF9C3B33).copy(alpha = .75f))
            paint(cam, 520f, FAR, 520f, NEAR, 6f, YELLOW3)
            for (i in 0..8) ground(cam, 330f + i * 44f, 330f, 26f, 60f, PAINT3)
        }
        "alley" -> {
            ground(cam, 320f, FAR, 320f, NEAR - FAR, Color(0xFF4A4F57))
            ground(cam, 260f, FAR, 60f, NEAR - FAR, Color(0xFF2E3238)); ground(cam, 640f, FAR, 60f, NEAR - FAR, Color(0xFF2E3238))
        }
        "toll" -> {
            straight(300f, 700f)
            ground(cam, 600f, FAR, 100f, NEAR - FAR, BLUE_LANE.copy(alpha = .4f))
            for (x in listOf(500f, 600f)) dashed(cam, x, from = 380f)
        }
        "curb" -> {
            ground(cam, 300f, FAR, 400f, NEAR - FAR, ASPHALT3); ground(cam, 700f, FAR, 90f, NEAR - FAR, Color(0xFF5B6168))
            paint(cam, 310f, FAR, 310f, NEAR); paint(cam, 322f, FAR, 322f, NEAR, 5f, YELLOW3)
            ground(cam, 612f, 270f, 78f, 270f, Cabin.green.copy(alpha = .22f))
        }
    }
}

/** Light poles along both verges, as on the lane-change scene. */
internal fun DrawScope.streetLights(cam: RoadCamera, xs: List<Float>, scroll: Float = 0f) {
    for (x in xs) for (i in 0..10) {
        val y = FAR + 200f + i * 400f + scroll % 400f
        if (cam.depth(y) < 420f) continue    // a pole right beside the camera would only be a blur
        val base = cam.at(x, y); val top = cam.at(x, y, 170f); val k = cam.scale(y, 170f)
        drawLine(Color(0xFF465060), base, top, (5f * k).coerceAtLeast(1f))
        if (34f * k >= 4f) drawCircle(Brush.radialGradient(listOf(Color(0x66FFC97A), Color.Transparent), top, 34f * k), 34f * k, top)
        drawCircle(Color(0xFFFFDEA0), 7f * k, top)
    }
}

/** A box on the ground, [w] across and [l] along the car, from height z0 to z1; faces toward the camera only. */
private class Box(val cx: Float, val cy: Float, val deg: Float, val w: Float, val l: Float, val z0: Float, val z1: Float,
    /** The top face narrower/shorter than the bottom by these factors: sloped glass. */
    val topW: Float = 1f, val topL: Float = 1f)

private fun Box.corner(dx: Float, dy: Float, z: Float): Triple<Float, Float, Float> {
    val a = Math.toRadians(deg.toDouble()); val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
    return Triple(cx + dx * ca - dy * sa, cy + dx * sa + dy * ca, z)
}

/** Face index: 0 front, 1 right, 2 rear, 3 left, 4 top. Calls [extra] after each drawn side face (for lamps on it). */
private fun DrawScope.box(cam: RoadCamera, b: Box, color: Color, extra: (Int) -> Unit = {}) {
    val hw = b.w / 2f; val hl = b.l / 2f
    val bot = listOf(b.corner(-hw, -hl, b.z0), b.corner(hw, -hl, b.z0), b.corner(hw, hl, b.z0), b.corner(-hw, hl, b.z0))
    val tw = hw * b.topW; val tl = hl * b.topL
    val top = listOf(b.corner(-tw, -tl, b.z1), b.corner(tw, -tl, b.z1), b.corner(tw, tl, b.z1), b.corner(-tw, tl, b.z1))
    val faces = listOf(listOf(bot[0], bot[1], top[1], top[0]) to .62f, listOf(bot[1], bot[2], top[2], top[1]) to .76f,
        listOf(bot[2], bot[3], top[3], top[2]) to .86f, listOf(bot[3], bot[0], top[0], top[3]) to .76f, top to 1f)
    faces.forEachIndexed { i, (f, light) ->
        val q = f.map { (x, y, z) -> cam.at(x, y, z) }
        val area = (0 until 4).sumOf { k -> (q[k].x * q[(k + 1) % 4].y - q[(k + 1) % 4].x * q[k].y).toDouble() }
        if (area > 0) {
            quad(cam, f, Color(color.red * light, color.green * light, color.blue * light, color.alpha))
            if (i < 4) extra(i)
        }
    }
}

/** A flat patch on face [face] of box [b]: [u0..u1] across the face (−1..1), [z0..z1] high. */
private fun DrawScope.onFace(cam: RoadCamera, b: Box, face: Int, u0: Float, u1: Float, z0: Float, z1: Float, color: Color) {
    val hw = b.w / 2f + .5f; val hl = b.l / 2f + .5f
    fun p(u: Float, z: Float) = when (face) {
        0 -> b.corner(-u * hw, -hl, z)    // front: seen from ahead, its left is the car's right
        2 -> b.corner(u * hw, hl, z)
        1 -> b.corner(hw, u * hl, z)
        else -> b.corner(-hw, -u * hl, z)
    }
    quad(cam, listOf(p(u0, z0), p(u1, z0), p(u1, z1), p(u0, z1)), color)
}

internal fun DrawScope.car3D(cam: RoadCamera, c: RoadItem.Car, time: Float) {
    val on = (time * 1.6f) % 1f < .55f
    val sides = when (c.blink) { "left" -> listOf(-1f); "right" -> listOf(1f); null -> emptyList(); else -> listOf(-1f, 1f) }
    val bus = c.kind == "bus"
    val w = if (bus) 74f else 56f; val l = if (bus) 250f else 112f
    val color = when (c.kind) { "me" -> Color(0xFF2F7FE0); "warn" -> Color(0xFFB0453E); "emergency" -> Color(0xFFE9ECEF); "bus" -> Color(0xFFE2AE3E)
        "parked" -> Color(0xFF5E6873); "light" -> Color(0xFFB9C2CC); else -> Color(0xFF8A939E) }
    // Soft contact shadow: one flat patch (wheels are hidden under the body from this camera).
    run {
        val a = Math.toRadians(c.deg.toDouble()); val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        val hw = w / 2f + 5f; val hl = l / 2f + 6f
        fun p(dx: Float, dy: Float) = Triple(c.x + dx * ca - dy * sa, c.y + 6f + dx * sa + dy * ca, 0f)
        quad(cam, listOf(p(-hw, -hl), p(hw, -hl), p(hw, hl), p(-hw, hl)), Color(0x55000000))
    }
    val body = Box(c.x, c.y, c.deg, w, l, 9f, if (bus) 88f else 31f)
    val blinkers = on && sides.isNotEmpty() && c.kind != "emergency"
    box(cam, body, color) { face ->
        when (face) {
            2 -> {    // rear: lamps, plate, and the blinkers
                onFace(cam, body, 2, -.92f, -.56f, 20f, 27f, Color(0xFFE0483E)); onFace(cam, body, 2, .56f, .92f, 20f, 27f, Color(0xFFE0483E))
                onFace(cam, body, 2, -.2f, .2f, 11f, 18f, Color(0xFFDADDE1))
                if (bus) onFace(cam, body, 2, -.8f, .8f, 50f, 80f, Color(0xFF16202B))
                if (blinkers) for (d in sides) onFace(cam, body, 2, if (d < 0) -.98f else .7f, if (d < 0) -.7f else .98f, 27f, 31f, Color(0xFFFFB25C))
            }
            0 -> { onFace(cam, body, 0, -.92f, -.56f, 20f, 27f, Color(0xFFF2F6FA)); onFace(cam, body, 0, .56f, .92f, 20f, 27f, Color(0xFFF2F6FA))
                if (bus) onFace(cam, body, 0, -.85f, .85f, 46f, 82f, Color(0xFF16202B)) }
            else -> if (bus) onFace(cam, body, face, -.9f, .7f, 52f, 76f, Color(0xFF16202B))
        }
    }
    if (!bus) {
        val glass = Box(c.x, c.y, c.deg, w - 10f, 56f, 31f, 47f)
        // Glass sits a little behind the middle of the car: rotate that offset with the car.
        val a = Math.toRadians(c.deg.toDouble())
        val gx = c.x - 6f * sin(a).toFloat(); val gy = c.y + 6f * cos(a).toFloat()
        box(cam, Box(gx, gy, c.deg, glass.w, glass.l + 14f, glass.z0, glass.z1, topW = .86f, topL = .62f), Color(0xFF2A3A4F))
        box(cam, Box(gx, gy, c.deg, (glass.w) * .9f, (glass.l + 14f) * .64f, 47f, 50f), color)
        if (c.kind == "emergency") {
            box(cam, Box(gx - 9f * cos(a).toFloat(), gy - 9f * sin(a).toFloat(), c.deg, 18f, 12f, 50f, 57f), if (on) Color(0xFFE5484D) else Color(0xFF3D7BE0))
            box(cam, Box(gx + 9f * cos(a).toFloat(), gy + 9f * sin(a).toFloat(), c.deg, 18f, 12f, 50f, 57f), if (on) Color(0xFF3D7BE0) else Color(0xFFE5484D))
            val top = cam.at(gx, gy, 58f)
            drawCircle(Brush.radialGradient(listOf((if (on) Color(0xFFE5484D) else Color(0xFF3D7BE0)).copy(alpha = .5f), Color.Transparent), top, 90f * cam.scale(gy, 58f)), 90f * cam.scale(gy, 58f), top)
        }
    }
    if (blinkers) for (d in sides) {
        val p = cam.at(c.x + d * (w / 2f) * cos(Math.toRadians(c.deg.toDouble())).toFloat(), c.y + l / 2f, 29f)
        val r = 34f * cam.scale(c.y + l / 2f, 29f)
        drawCircle(Brush.radialGradient(listOf(Color(0xAAFFA43A), Color.Transparent), p, r), r, p)
    }
}

/** Person, sign, traffic light, "no" mark: upright cut-outs standing at their spot. */
private fun DrawScope.standee(cam: RoadCamera, item: RoadItem, text: TextMeasurer) {
    when (item) {
        is RoadItem.Person -> {
            val k = cam.scale(item.y, 40f); val feet = cam.at(item.x, item.y); val head = cam.at(item.x, item.y, 64f)
            drawOval(Color(0x55000000), Offset(feet.x - 16f * k, feet.y - 5f * k), Size(32f * k, 10f * k))
            drawRoundRect(YELLOW3, Offset(feet.x - 11f * k, head.y + 12f * k), Size(22f * k, feet.y - head.y - 12f * k), CornerRadius(9f * k))
            drawCircle(YELLOW3, 10f * k, head)
        }
        is RoadItem.Sign -> {
            val k = cam.scale(item.y, 80f); val foot = cam.at(item.x, item.y); val c = cam.at(item.x, item.y, 95f)
            drawLine(Color(0xFF9AA3AD), foot, c, 4f * k)
            drawCircle(Color.White, 32f * k, c); drawCircle(Cabin.alert, 32f * k, c, style = Stroke(7f * k))
            val l = text.measure(item.text, TextStyle(fontFamily = Pretendard, color = Color(0xFF15181C), fontSize = 28.sp, fontWeight = FontWeight.Bold))
            val f = (k * .9f).coerceAtMost(1.4f)
            withTransform({ scale(f, f, c) }) { drawText(l, topLeft = Offset(c.x - l.size.width / 2f, c.y - l.size.height / 2f)) }
        }
        is RoadItem.Light -> {
            val k = cam.scale(item.y, 150f); val foot = cam.at(item.x, item.y); val top = cam.at(item.x, item.y, 170f)
            drawLine(Color(0xFF59636F), foot, top, 5f * k)
            drawRoundRect(Color(0xFF111316), Offset(top.x - 50f * k, top.y - 18f * k), Size(100f * k, 36f * k), CornerRadius(10f * k))
            listOf("red", "yellow", "green").forEachIndexed { i, name ->
                val col = when (name) { "red" -> Cabin.alert; "yellow" -> YELLOW3; else -> Cabin.green }
                drawCircle(if (name == item.color) col else col.copy(alpha = .18f), 12f * k, Offset(top.x + (i - 1) * 30f * k, top.y))
            }
        }
        is RoadItem.No -> {
            val k = cam.scale(item.y, 50f); val p = cam.at(item.x, item.y, 50f)
            drawCircle(Cabin.alert, 30f * k, p)
            drawLine(Color.White, Offset(p.x - 12f * k, p.y - 12f * k), Offset(p.x + 12f * k, p.y + 12f * k), 6f * k, StrokeCap.Round)
            drawLine(Color.White, Offset(p.x + 12f * k, p.y - 12f * k), Offset(p.x - 12f * k, p.y + 12f * k), 6f * k, StrokeCap.Round)
        }
        else -> Unit
    }
}

/** The arrow my car follows, painted on the road. */
private fun DrawScope.groundArrow(cam: RoadCamera, points: List<Offset>, color: Color, time: Float) {
    if (points.size < 2) return
    val smooth = buildList {
        add(points[0])
        for (i in 1 until points.size - 1) {
            val a = points[i - 1]; val b = points[i]; val c = points[i + 1]
            for (t in 1..6) { val u = t / 7f
                val p0 = Offset(a.x + (b.x - a.x) * (.5f + u * .5f), a.y + (b.y - a.y) * (.5f + u * .5f))
                val p1 = Offset(b.x + (c.x - b.x) * (u * .5f), b.y + (c.y - b.y) * (u * .5f))
                add(Offset(p0.x + (p1.x - p0.x) * u, p0.y + (p1.y - p0.y) * u)) }
        }
        add(points.last())
    }
    val path = Path()
    smooth.forEachIndexed { i, p -> val s = cam.at(p.x, p.y, 1f); if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y) }
    val k = cam.scale(smooth.map { it.y }.average().toFloat())
    drawPath(path, color, style = Stroke((12f * k).coerceIn(4f, 18f), cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(26f, 16f), -time * 60f)))
    val end = smooth.last(); val before = smooth[smooth.size - 2]
    val a = atan2(end.y - before.y, end.x - before.x)
    fun at(da: Float, r: Float) = Offset(end.x - r * cos(a + da), end.y - r * sin(a + da))
    quad(cam, listOf(Triple(end.x + 14f * cos(a), end.y + 14f * sin(a), 1f), Triple(at(.6f, 34f).x, at(.6f, 34f).y, 1f),
        Triple(at(-.6f, 34f).x, at(-.6f, 34f).y, 1f)), color)
}

/** Sky down past the horizon: the far ground edge sits a little below it. */
internal fun DrawScope.sky(cam: RoadCamera) =
    drawRect(Brush.verticalGradient(listOf(SKY_TOP, SKY), 0f, cam.horizon), Offset(-500f, -100f), Size(2000f, cam.horizon + 400f))

/** Distance haze where the road meets the sky. */
internal fun DrawScope.haze(cam: RoadCamera) =
    drawRect(Brush.verticalGradient(listOf(SKY, SKY.copy(alpha = 0f)), cam.horizon - 2f, cam.horizon + 150f), Offset(-500f, cam.horizon - 2f), Size(2000f, 152f))

internal fun DrawScope.drawRoad3D(art: Art.Road, time: Float, ambient: Float, text: TextMeasurer) {
    drawRoadStill3D(art)
    drawRoadMoving3D(art, time, ambient, text)
}

/** What never moves: sky, road, painted zones, haze, street lights. Drawn once into a cached image (SceneBox). */
internal fun DrawScope.drawRoadStill3D(art: Art.Road) {
    val cam = RoadCamera.forScene(art)
    sky(cam)
    drawBase3D(cam, art.base)
    for (item in art.items) if (item is RoadItem.Zone) {
        val color = if (item.ok) Cabin.green else Cabin.alert
        ground(cam, item.rect.left, item.rect.top, item.rect.width, item.rect.height, color.copy(alpha = .28f))
        paint(cam, item.rect.left, item.rect.top, item.rect.right, item.rect.top, 4f, color); paint(cam, item.rect.left, item.rect.bottom, item.rect.right, item.rect.bottom, 4f, color)
        paint(cam, item.rect.left, item.rect.top, item.rect.left, item.rect.bottom, 4f, color); paint(cam, item.rect.right, item.rect.top, item.rect.right, item.rect.bottom, 4f, color)
    }
    haze(cam)
    streetLights(cam, if (art.base in setOf("cross", "roundabout", "parking")) listOf(150f, 850f) else listOf(230f, 770f))
}

/** Every frame: the arrows' flow, cars (mine drives, blinkers blink), people and signs, and the labels over them. */
internal fun DrawScope.drawRoadMoving3D(art: Art.Road, time: Float, ambient: Float, text: TextMeasurer) {
    val cam = RoadCamera.forScene(art)
    art.items.filterIsInstance<RoadItem.Path>().forEach { groundArrow(cam, it.points, if (it.ok) Cabin.focus else Cabin.alert, ambient) }
    // My car drives the first arrow: 3 s along it, then holds. A wrong move (red) stops partway.
    val firstPath = art.items.filterIsInstance<RoadItem.Path>().firstOrNull()
    val me = art.items.filterIsInstance<RoadItem.Car>().firstOrNull { it.kind == "me" }
    // An arrow that starts toward the viewer is a reverse: the car backs along it, nose still forward.
    val reversing = me != null && firstPath != null && firstPath.points.first().y > me.y
    val route = firstPath?.let { pth ->
        val p = (time / 3f).coerceAtMost(1f).let { if (pth.ok) it else it * .45f }
        alongPath(listOfNotNull(me?.let { Offset(it.x, it.y) }) + pth.points, p).let { (at, deg) -> at to if (reversing) deg + 180f else deg }
    }
    // Far things first, so nearer ones cover them.
    val things = art.items.filter { it !is RoadItem.Path && it !is RoadItem.Zone }.map { item ->
        if (item is RoadItem.Car && item.kind == "me" && route != null) item.copy(x = route.first.x, y = route.first.y, deg = route.second) else item
    } + when (art.base) {
        "toll" -> listOf(400f, 500f, 600f).map { RoadItem.Car(it, 315f, 0f, "booth") } + RoadItem.Car(500f, 316f, 0f, "canopy")
        "parking" -> listOf(330f, 690f).map { RoadItem.Car(it, 580f, 0f, "parked") }
        "curb" -> listOf(170f, 610f).map { RoadItem.Car(650f, it, 0f, "parked") }
        else -> emptyList()
    }
    fun yOf(it: RoadItem) = when (it) { is RoadItem.Car -> it.y; is RoadItem.Person -> it.y; is RoadItem.Sign -> it.y; is RoadItem.Light -> it.y; is RoadItem.No -> it.y; else -> 0f }
    for (item in things.sortedBy(::yOf)) when {
        item is RoadItem.Car && item.kind == "booth" -> {
            box(cam, Box(item.x, item.y, 0f, 22f, 110f, 0f, 20f), Color(0xFF9AA3AD))
            box(cam, Box(item.x, item.y, 0f, 18f, 46f, 20f, 70f), Color(0xFFCBD2D9))
        }
        item is RoadItem.Car && item.kind == "canopy" -> {
            for (x in listOf(300f, 700f)) box(cam, Box(x, item.y, 0f, 12f, 12f, 0f, 112f), Color(0xFF9AA3AD))
            box(cam, Box(item.x, item.y, 0f, 420f, 44f, 112f, 126f), Color(0xFF6B7682))
        }
        item is RoadItem.Car -> car3D(cam, item, ambient)
        else -> standee(cam, item, text)
    }
    for (item in art.items) if (item is RoadItem.Zone) item.text?.let {
        val p = cam.at(item.rect.center.x, item.rect.top)
        label(text, it, Offset(p.x, p.y - 30f), if (item.ok) Color(0xFF2E7D4F) else Color(0xFFB23B3B))
    }
    if (art.base == "toll") label(text, "하이패스", cam.at(650f, 260f, 130f), Color(0xFF2F5FA8))
    if (art.base == "exit" && art.items.none { it is RoadItem.Sign }) label(text, "출구", cam.at(1000f, -150f, 60f), Color(0xFF2E7D4F))
    // Top edge: cars and arrows sit lower down, so the caption never covers them.
    art.label?.let { label(text, it, Offset(500f, 48f)) }
}
