package io.github.beomsun0829.handsonguide.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Line icons drawn in code so the app ships no image assets. Shapes follow the ISO 2575 style
 * symbols drivers see on their own buttons (defroster fan arc, recirculation loop, …).
 */
/** The telltale's own symbol when drawn here, so the driver can match it to the cluster. */
fun telltaleGlyph(type: String?): String = when (type) {
    "TPMS" -> "tt_TPMS"
    else -> "telltale"
}

@Composable
fun Glyph(name: String, color: Color, size: Dp = 48.dp, description: String? = null, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).semantics { contentDescription = description ?: name }) {
        val s = this.size.minDimension
        val w = s * 0.075f
        val st = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // A situation icon whose direction is only known while driving ("turn_{turnDir}") shows the right turn.
        when (name.replace("{turnDir}", "right")) {
            "roundabout" -> {
                // Ring with the central island and an arrow going round anticlockwise.
                drawCircle(color, s * .12f, Offset(s / 2, s / 2), style = st)
                drawArc(color, 200f, 280f, false, Offset(s * .18f, s * .18f), Size(s * .64f, s * .64f), style = st)
                val a = Math.toRadians(200.0)
                val tip = Offset(s / 2 + (kotlin.math.cos(a) * s * .32f).toFloat(), s / 2 + (kotlin.math.sin(a) * s * .32f).toFloat())
                drawLine(color, tip, tip + Offset(s * .02f, s * .14f), w, StrokeCap.Round)
                drawLine(color, tip, tip + Offset(s * .14f, -s * .02f), w, StrokeCap.Round)
            }
            "uturn" -> {
                // Up the right lane, round the top, back down the left lane.
                val p = Path().apply {
                    moveTo(s * .66f, s * .88f); lineTo(s * .66f, s * .42f)
                    arcTo(androidx.compose.ui.geometry.Rect(s * .3f, s * .24f, s * .66f, s * .6f), 0f, -180f, false)
                    lineTo(s * .3f, s * .72f)
                }
                drawPath(p, color, style = st)
                drawLine(color, Offset(s * .3f, s * .76f), Offset(s * .18f, s * .62f), w, StrokeCap.Round)
                drawLine(color, Offset(s * .3f, s * .76f), Offset(s * .42f, s * .62f), w, StrokeCap.Round)
            }
            "defrost_front" -> {
                // Windshield trapezoid + three wavy arrows up.
                val p = Path().apply {
                    moveTo(s * .15f, s * .72f); lineTo(s * .85f, s * .72f); lineTo(s * .75f, s * .25f); lineTo(s * .25f, s * .25f); close()
                }
                drawPath(p, color, style = st)
                for (i in 0..2) wave(color, s * (.36f + i * .14f), s * .62f, s * .36f, w)
            }
            "defrost_rear" -> {
                drawRoundRect(color, Offset(s * .15f, s * .25f), Size(s * .7f, s * .47f), CornerRadius(s * .06f), style = st)
                for (i in 0..2) wave(color, s * (.36f + i * .14f), s * .64f, s * .34f, w)
            }
            "wiper" -> {
                // Windshield outline, the blade swept across it, and a spray of drops above (the ISO wiper sign).
                val glass = Path().apply {
                    moveTo(s * .1f, s * .82f); lineTo(s * .9f, s * .82f); lineTo(s * .8f, s * .34f); lineTo(s * .2f, s * .34f); close()
                }
                drawPath(glass, color, style = st)
                drawLine(color, Offset(s * .5f, s * .82f), Offset(s * .3f, s * .44f), w, StrokeCap.Round)
                drawArc(color, 215f, 110f, false, Offset(s * .22f, s * .48f), Size(s * .56f, s * .56f), style = Stroke(w * .7f, cap = StrokeCap.Round))
                for (x in listOf(.34f, .5f, .66f)) drawLine(color, Offset(s * x, s * .1f), Offset(s * x, s * .2f), w * .8f, StrokeCap.Round)
            }
            "recirc" -> {
                // Car body with a loop arrow inside.
                val body = Path().apply {
                    moveTo(s * .1f, s * .72f); lineTo(s * .1f, s * .55f); lineTo(s * .28f, s * .32f); lineTo(s * .72f, s * .32f)
                    lineTo(s * .9f, s * .55f); lineTo(s * .9f, s * .72f)
                }
                drawPath(body, color, style = st)
                drawArc(color, 200f, 280f, false, Offset(s * .36f, s * .42f), Size(s * .28f, s * .26f), style = st)
                arrowHead(color, Offset(s * .38f, s * .5f), -1f, w)
            }
            "ac" -> {
                for (a in 0..2) {
                    val ang = Math.toRadians(a * 60.0)
                    val dx = (kotlin.math.cos(ang) * s * .36).toFloat(); val dy = (kotlin.math.sin(ang) * s * .36).toFloat()
                    drawLine(color, Offset(s / 2 - dx, s / 2 - dy), Offset(s / 2 + dx, s / 2 + dy), w, StrokeCap.Round)
                }
            }
            "fan" -> {
                drawCircle(color, s * .07f, Offset(s / 2, s / 2))
                for (a in 0..2) drawArc(color, a * 120f, 70f, true, Offset(s * .18f, s * .18f), Size(s * .64f, s * .64f), style = st)
            }
            "seat", "headrest" -> {
                val p = Path().apply {
                    moveTo(s * .3f, s * .15f); lineTo(s * .38f, s * .62f); lineTo(s * .78f, s * .62f); lineTo(s * .8f, s * .78f); lineTo(s * .36f, s * .78f)
                }
                drawPath(p, color, style = st)
                drawRoundRect(color, Offset(s * .2f, s * .05f), Size(s * .2f, s * .14f), CornerRadius(s * .05f), style = st)
                if (name == "headrest") drawLine(color, Offset(s * .55f, s * .12f), Offset(s * .55f, s * .3f), w, StrokeCap.Round)
            }
            "belt" -> {
                drawCircle(color, s * .1f, Offset(s * .5f, s * .18f), style = st)
                drawLine(color, Offset(s * .3f, s * .35f), Offset(s * .7f, s * .85f), w, StrokeCap.Round)
                drawLine(color, Offset(s * .25f, s * .7f), Offset(s * .75f, s * .7f), w, StrokeCap.Round)
            }
            "mirror" -> {
                drawRoundRect(color, Offset(s * .12f, s * .3f), Size(s * .6f, s * .38f), CornerRadius(s * .12f), style = st)
                drawLine(color, Offset(s * .72f, s * .5f), Offset(s * .9f, s * .55f), w, StrokeCap.Round)
            }
            "wheel" -> {
                drawCircle(color, s * .38f, Offset(s / 2, s / 2), style = st)
                drawCircle(color, s * .1f, Offset(s / 2, s / 2), style = st)
                drawLine(color, Offset(s * .12f, s * .5f), Offset(s * .4f, s * .5f), w)
                drawLine(color, Offset(s * .6f, s * .5f), Offset(s * .88f, s * .5f), w)
                drawLine(color, Offset(s * .5f, s * .6f), Offset(s * .5f, s * .88f), w)
            }
            "pedal", "brake" -> {
                drawRoundRect(color, Offset(s * .22f, s * .2f), Size(s * .24f, s * .6f), CornerRadius(s * .05f), style = st)
                drawRoundRect(color, Offset(s * .56f, s * .3f), Size(s * .18f, s * .5f), CornerRadius(s * .05f), style = st)
            }
            "gear" -> {
                val labels = listOf(.25f to .25f, .5f to .25f, .75f to .25f, .5f to .75f)
                labels.forEach { (x, y) -> drawCircle(color, s * .06f, Offset(s * x, s * y)) }
                drawLine(color, Offset(s * .25f, s * .25f), Offset(s * .75f, s * .25f), w)
                drawLine(color, Offset(s * .5f, s * .25f), Offset(s * .5f, s * .75f), w)
            }
            "park" -> {
                drawCircle(color, s * .4f, Offset(s / 2, s / 2), style = st)
                val p = Path().apply {
                    moveTo(s * .4f, s * .72f); lineTo(s * .4f, s * .28f); lineTo(s * .55f, s * .28f)
                    cubicTo(s * .7f, s * .28f, s * .7f, s * .52f, s * .55f, s * .52f); lineTo(s * .4f, s * .52f)
                }
                drawPath(p, color, style = st)
            }
            "light" -> {
                drawArc(color, 90f, 180f, false, Offset(s * .1f, s * .25f), Size(s * .4f, s * .5f), style = st)
                drawLine(color, Offset(s * .3f, s * .25f), Offset(s * .3f, s * .75f), w)
                for (i in 0..2) drawLine(color, Offset(s * .58f, s * (.32f + i * .18f)), Offset(s * .9f, s * (.32f + i * .18f)), w, StrokeCap.Round)
            }
            "hazard" -> {
                val outer = triangle(s, .1f); val inner = triangle(s, .3f)
                drawPath(outer, color, style = st); drawPath(inner, color, style = st)
            }
            "turn_signal", "turn_left", "turn_right" -> {
                if (name != "turn_right") arrow(color, s, left = true, w = w, single = name == "turn_left")
                if (name != "turn_left") arrow(color, s, left = false, w = w, single = name == "turn_right")
            }
            "tt_TPMS" -> {
                // Tire cross-section open at the top, tread marks underneath, "!" inside.
                drawLine(color, Offset(s * .2f, s * .2f), Offset(s * .2f, s * .55f), w, StrokeCap.Round)
                drawLine(color, Offset(s * .8f, s * .2f), Offset(s * .8f, s * .55f), w, StrokeCap.Round)
                drawArc(color, 0f, 180f, false, Offset(s * .2f, s * .3f), Size(s * .6f, s * .5f), style = st)
                drawLine(color, Offset(s * .2f, s * .2f), Offset(s * .13f, s * .14f), w, StrokeCap.Round)
                drawLine(color, Offset(s * .8f, s * .2f), Offset(s * .87f, s * .14f), w, StrokeCap.Round)
                for (deg in listOf(40.0, 70.0, 90.0, 110.0, 140.0)) {
                    val a = Math.toRadians(deg)
                    val cx = s * .5f; val cy = s * .55f
                    fun at(rx: Float, ry: Float) = Offset(cx + (kotlin.math.cos(a) * rx).toFloat(), cy + (kotlin.math.sin(a) * ry).toFloat())
                    drawLine(color, at(s * .3f, s * .25f), at(s * .38f, s * .33f), w * .8f, StrokeCap.Round)
                }
                drawLine(color, Offset(s / 2, s * .28f), Offset(s / 2, s * .5f), w, StrokeCap.Round)
                drawCircle(color, w * .7f, Offset(s / 2, s * .62f))
            }
            "telltale" -> {
                drawPath(triangle(s, .1f), color, style = st)
                drawLine(color, Offset(s / 2, s * .4f), Offset(s / 2, s * .62f), w, StrokeCap.Round)
                drawCircle(color, w * .7f, Offset(s / 2, s * .74f))
            }
            "cruise" -> {
                drawArc(color, 150f, 240f, false, Offset(s * .12f, s * .12f), Size(s * .76f, s * .76f), style = st)
                drawLine(color, Offset(s / 2, s / 2), Offset(s * .72f, s * .32f), w, StrokeCap.Round)
            }
            "lane", "road" -> {
                drawLine(color, Offset(s * .2f, s * .9f), Offset(s * .35f, s * .1f), w, StrokeCap.Round)
                drawLine(color, Offset(s * .8f, s * .9f), Offset(s * .65f, s * .1f), w, StrokeCap.Round)
                drawLine(color, Offset(s * .5f, s * .75f), Offset(s * .5f, s * .45f), w, StrokeCap.Round)
            }
            "school" -> {
                drawPath(triangle(s, .1f), color, style = st)
                drawCircle(color, s * .05f, Offset(s * .5f, s * .42f))
                drawLine(color, Offset(s * .5f, s * .5f), Offset(s * .48f, s * .67f), w, StrokeCap.Round)
                drawLine(color, Offset(s * .48f, s * .67f), Offset(s * .35f, s * .8f), w, StrokeCap.Round)
                drawLine(color, Offset(s * .48f, s * .67f), Offset(s * .63f, s * .8f), w, StrokeCap.Round)
                drawLine(color, Offset(s * .36f, s * .6f), Offset(s * .63f, s * .56f), w, StrokeCap.Round)
            }
            "rain" -> {
                drawArc(color, 180f, 180f, false, Offset(s * .15f, s * .15f), Size(s * .7f, s * .5f), style = st)
                drawLine(color, Offset(s * .15f, s * .4f), Offset(s * .85f, s * .4f), w)
                drawLine(color, Offset(s * .5f, s * .4f), Offset(s * .5f, s * .78f), w)
                drawArc(color, 0f, 180f, false, Offset(s * .5f, s * .7f), Size(s * .14f, s * .14f), style = st)
            }
            "rest" -> {
                // A cup with steam, the usual rest-area sign: take a break.
                val cup = Path().apply {
                    moveTo(s * .18f, s * .44f); lineTo(s * .66f, s * .44f); lineTo(s * .62f, s * .78f)
                    quadraticBezierTo(s * .61f, s * .86f, s * .53f, s * .86f); lineTo(s * .31f, s * .86f)
                    quadraticBezierTo(s * .23f, s * .86f, s * .22f, s * .78f); close()
                }
                drawPath(cup, color, style = st)
                drawArc(color, -90f, 180f, false, Offset(s * .56f, s * .5f), Size(s * .22f, s * .2f), style = st)
                for (x in listOf(.32f, .46f)) wave(color, s * x, s * .36f, s * .14f, w * .8f)
            }
            "intersection" -> {
                // A plus-shaped crossing seen from above: the road edges around the junction.
                val a = s * .36f; val b = s * .64f
                for ((x0, y0, x1, y1) in listOf(
                    listOf(a, s * .1f, a, a), listOf(a, a, s * .1f, a), listOf(b, s * .1f, b, a), listOf(b, a, s * .9f, a),
                    listOf(a, s * .9f, a, b), listOf(a, b, s * .1f, b), listOf(b, s * .9f, b, b), listOf(b, b, s * .9f, b)))
                    drawLine(color, Offset(x0, y0), Offset(x1, y1), w, StrokeCap.Round)
            }
            "highway" -> {
                // Two lanes and the slip road joining from the right.
                drawLine(color, Offset(s * .22f, s * .1f), Offset(s * .22f, s * .9f), w, StrokeCap.Round)
                drawLine(color, Offset(s * .62f, s * .1f), Offset(s * .62f, s * .9f), w, StrokeCap.Round)
                for (y in listOf(.14f, .42f, .7f)) drawLine(color, Offset(s * .42f, s * y), Offset(s * .42f, s * (y + .14f)), w, StrokeCap.Round)
                drawLine(color, Offset(s * .62f, s * .42f), Offset(s * .86f, s * .9f), w, StrokeCap.Round)
            }
            "speed" -> {
                // Speedometer: an open dial with ticks and the needle well up.
                drawArc(color, 140f, 260f, false, Offset(s * .12f, s * .12f), Size(s * .76f, s * .76f), style = st)
                for (deg in listOf(160.0, 220.0, 270.0, 320.0, 20.0)) {
                    val a = Math.toRadians(deg)
                    drawLine(color, Offset(s / 2 + (kotlin.math.cos(a) * s * .3f).toFloat(), s / 2 + (kotlin.math.sin(a) * s * .3f).toFloat()),
                        Offset(s / 2 + (kotlin.math.cos(a) * s * .36f).toFloat(), s / 2 + (kotlin.math.sin(a) * s * .36f).toFloat()), w * .7f, StrokeCap.Round)
                }
                drawLine(color, Offset(s / 2, s / 2), Offset(s * .7f, s * .3f), w, StrokeCap.Round)
                drawCircle(color, w * .9f, Offset(s / 2, s / 2))
            }
            "check" -> {
                val p = Path().apply { moveTo(s * .2f, s * .52f); lineTo(s * .42f, s * .74f); lineTo(s * .82f, s * .3f) }
                drawPath(p, color, style = Stroke(w * 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            "back" -> {
                val p = Path().apply { moveTo(s * .62f, s * .2f); lineTo(s * .32f, s * .5f); lineTo(s * .62f, s * .8f) }
                drawPath(p, color, style = Stroke(w * 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            "settings" -> {
                // A gear (eight square teeth around a ring with a hole), not rays: rays read as brightness.
                val gear = Path().apply {
                    val teeth = 8
                    for (i in 0 until teeth * 4) {
                        val r = if ((i / 2) % 2 == 0) s * .42f else s * .31f
                        val a = Math.toRadians((i - .5) * 360.0 / (teeth * 4) - 90)
                        val x = s / 2 + (kotlin.math.cos(a) * r).toFloat(); val y = s / 2 + (kotlin.math.sin(a) * r).toFloat()
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(gear, color, style = Stroke(w, join = StrokeJoin.Round))
                drawCircle(color, s * .12f, Offset(s / 2, s / 2), style = st)
            }
            "mic" -> {
                drawRoundRect(color, Offset(s * .38f, s * .12f), Size(s * .24f, s * .46f), CornerRadius(s * .12f), style = st)
                drawArc(color, 0f, 180f, false, Offset(s * .26f, s * .3f), Size(s * .48f, s * .4f), style = st)
                drawLine(color, Offset(s / 2, s * .7f), Offset(s / 2, s * .86f), w)
            }
            "speaker" -> {
                val p = Path().apply { moveTo(s * .15f, s * .4f); lineTo(s * .32f, s * .4f); lineTo(s * .5f, s * .22f); lineTo(s * .5f, s * .78f); lineTo(s * .32f, s * .6f); lineTo(s * .15f, s * .6f); close() }
                drawPath(p, color, style = st)
                drawArc(color, -45f, 90f, false, Offset(s * .45f, s * .3f), Size(s * .3f, s * .4f), style = st)
            }
            else -> {
                drawCircle(color, s * .38f, Offset(s / 2, s / 2), style = st)
                drawLine(color, Offset(s / 2, s * .45f), Offset(s / 2, s * .7f), w, StrokeCap.Round)
                drawCircle(color, w * .7f, Offset(s / 2, s * .32f))
            }
        }
    }
}

private fun DrawScope.wave(color: Color, x: Float, bottom: Float, height: Float, w: Float) {
    val p = Path().apply {
        moveTo(x, bottom)
        val seg = height / 3
        quadraticBezierTo(x - seg * .4f, bottom - seg * .5f, x, bottom - seg)
        quadraticBezierTo(x + seg * .4f, bottom - seg * 1.5f, x, bottom - seg * 2)
        lineTo(x, bottom - height)
    }
    drawPath(p, color, style = Stroke(w * .8f, cap = StrokeCap.Round))
    arrowHead(color, Offset(x, bottom - height), 0f, w * .8f)
}

private fun DrawScope.arrowHead(color: Color, tip: Offset, dir: Float, w: Float) {
    val l = size.minDimension * .07f
    if (dir == 0f) {
        drawLine(color, tip, Offset(tip.x - l, tip.y + l), w, StrokeCap.Round)
        drawLine(color, tip, Offset(tip.x + l, tip.y + l), w, StrokeCap.Round)
    } else {
        drawLine(color, tip, Offset(tip.x - l * dir, tip.y - l), w, StrokeCap.Round)
        drawLine(color, tip, Offset(tip.x + l, tip.y - l * .2f), w, StrokeCap.Round)
    }
}

private fun triangle(s: Float, inset: Float) = Path().apply {
    moveTo(s / 2, s * inset); lineTo(s * (1 - inset), s * (1 - inset * .9f)); lineTo(s * inset, s * (1 - inset * .9f)); close()
}

private fun DrawScope.arrow(color: Color, s: Float, left: Boolean, w: Float, single: Boolean = false) {
    val dir = if (left) -1 else 1
    val p = if (single) Path().apply {
        // One direction: a full-width arrow with a stem, like the car's own turn-signal symbol.
        val tip = s / 2 + dir * s * .38f; val neck = s / 2 + dir * s * .02f; val tail = s / 2 - dir * s * .38f
        moveTo(tip, s * .5f); lineTo(neck, s * .2f); lineTo(neck, s * .38f); lineTo(tail, s * .38f)
        lineTo(tail, s * .62f); lineTo(neck, s * .62f); lineTo(neck, s * .8f); close()
    } else Path().apply {
        val cx = s / 2 + dir * s * .02f
        moveTo(cx, s * .3f); lineTo(cx + dir * s * .4f, s * .5f); lineTo(cx, s * .7f); close()
    }
    drawPath(p, color, style = Stroke(w, join = StrokeJoin.Round))
}

