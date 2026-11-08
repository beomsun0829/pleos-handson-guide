package io.github.beomsun0829.handsonguide.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.rotate
import io.github.beomsun0829.handsonguide.AppController
import io.github.beomsun0829.handsonguide.coach.ActiveSituation
import io.github.beomsun0829.handsonguide.coach.CoachUi
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.voice.VoiceState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The "now" card: one thing to do, or null when there is nothing. The turn arrow and remaining
 * distance stay in the navigation app (spec S09), so the card never redraws them.
 */
data class DriveCardModel(val icon: String, val word: String, val sub: String?, val tone: String)

fun driveCard(coach: CoachUi, s: Signals): DriveCardModel? {
    coach.card?.let { a ->
        val icon = if (a.icon == "telltale") telltaleGlyph(s.str(Signals.TT_RED) ?: s.str(Signals.TT_AMBER)) else a.icon
        return DriveCardModel(icon, a.word, a.sub, a.tone)
    }
    if (s.gear == "R") return DriveCardModel("park", "후진 중", "천천히 · 브레이크로 속도 조절", "info")
    return null
}

/** The "다음" line: the nearest situation ahead that is hard for a beginner. */
data class NextHard(val icon: String, val word: String, val distance: String)

fun nextHard(coach: CoachUi, s: Signals): NextHard? {
    // Skip what the now card is already about, so the two never say the same thing.
    val busy = coach.card?.situation?.trigger?.signals().orEmpty()
    val turn = if (s.bool(Signals.NAV_ACTIVE) == false || Signals.NAV_TURN_DIST in busy) null else when (s.str(Signals.NAV_TURN_TYPE)) {
        "LEFT" -> if (s.bool(Signals.NAV_UNPROTECTED) == true) "turn_left" to "비보호 좌회전" else null
        "ROUNDABOUT" -> "turn_left" to "회전교차로"
        "MERGE", "HIGHWAY_ENTER" -> "lane" to "합류"
        "HIGHWAY_EXIT" -> "lane" to "고속도로 출구"
        "TOLL" -> "road" to "요금소"
        "TUNNEL" -> "road" to "터널"
        else -> null
    }?.let { (icon, word) -> s.num(Signals.NAV_TURN_DIST)?.let { Triple(icon, word, it) } }
    val school = s.num(Signals.NAV_SCHOOL_ZONE_DISTANCE)
        ?.takeIf { Signals.NAV_SCHOOL_ZONE_DISTANCE !in busy }?.let { Triple("school", "어린이보호구역", it) }
    return listOfNotNull(turn, school).minByOrNull { it.third }?.let { (icon, word, m) -> NextHard(icon, word, distLabel(m)) }
}

fun distLabel(m: Double): String = if (m >= 1000) "%.1f km".format(m / 1000) else "${((m / 10).roundToInt() * 10)} m"

// ---------------- S09 Drive ----------------

@Composable
fun DriveScreen(app: AppController, coach: CoachUi, s: Signals, voice: VoiceState) {
    val wide = isWide
    val model = driveCard(coach, s)
    val next = nextHard(coach, s)
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().padding(if (wide) 32.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            // Value changes cross-fade only (no decorative motion while driving, spec §5.5).
            // With nothing to do now, the card area is left empty and the next line takes its place.
            AnimatedContent(model to next, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) }, label = "card",
                modifier = Modifier.weight(1f).fillMaxWidth()) { (m, n) ->
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (m != null) {
                        Box(Modifier.weight(1f)) { SituationCard(m) }
                        if (n != null) NextLine(n, big = false)        // the idle line only fills an empty screen
                    } else Box(Modifier.weight(1f)) { NextLine(n, big = true) }
                }
            }
            ChipsRow(s, voice, wide)
            if (coach.quiet) Text("정보 팁은 조용히 · 안전·경고 안내는 계속해요", style = GuideType.label, color = c.textSub)
        }
        coach.banner?.let { Banner(it) }
    }
}

@Composable
fun SituationCard(m: DriveCardModel) {
    val color = if (m.tone == "neutral") c.textSub else c.tone(m.tone)
    val shape = RoundedCornerShape(32.dp)
    Row(
        Modifier.fillMaxSize().clip(shape).background(if (m.tone == "neutral") c.surface else color.copy(alpha = .12f))
            .then(if (m.tone == "neutral") Modifier else Modifier.border(6.dp, color, shape)).padding(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(m.icon, color, 160.dp)
        Hspace(40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(m.word, style = if (m.word.length <= 10) GuideType.display else GuideType.headline, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            m.sub?.let { Text(it, style = GuideType.title, color = c.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun NextLine(n: NextHard?, big: Boolean) {
    val shape = RoundedCornerShape(if (big) 32.dp else 24.dp)
    Row(
        Modifier.fillMaxWidth().then(if (big) Modifier.fillMaxSize() else Modifier).clip(shape).background(c.surface)
            .padding(horizontal = if (big) 40.dp else 28.dp, vertical = if (big) 40.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (n == null) {
            Glyph("road", c.textSub, if (big) 120.dp else 56.dp)    // watching the road with you
            Hspace(if (big) 32.dp else 20.dp)
            Text("가이드가 함께 보고 있어요", style = if (big) GuideType.headline else GuideType.title, color = c.textSub,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            return@Row
        }
        Glyph(n.icon, c.info, if (big) 120.dp else 56.dp)
        Hspace(if (big) 32.dp else 20.dp)
        Column(Modifier.weight(1f)) {
            Text("다음", style = GuideType.label, color = c.textSub)
            Text(n.word, style = if (big) GuideType.display else GuideType.headline, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(n.distance, style = if (big) GuideType.display else GuideType.headline, color = c.text)
    }
}

@Composable
private fun ChipsRow(s: Signals, voice: VoiceState, wide: Boolean) {
    val chips = buildList<@Composable () -> Unit> {
        s.num(Signals.NAV_SPEED_LIMIT)?.let { add { Chip("제한 ${it.roundToInt()}", "speed", tone = "neutral", big = true) } }
        when (s.str(Signals.TURN_SIGNAL)) {
            "LEFT" -> add { Chip("깜빡이 왼쪽", "turn_left", tone = "ok", big = true) }
            "RIGHT" -> add { Chip("깜빡이 오른쪽", "turn_right", tone = "ok", big = true) }
        }
        val warn = s.str(Signals.TT_RED) ?: s.str(Signals.TT_AMBER)
        if (warn != null) add { Chip("경고등", "telltale", tone = if (s.str(Signals.TT_RED) != null) "alert" else "warn", big = true) }
        else when (s.str(Signals.CRUISE)) {
            // OVERRIDE is the driver pressing the accelerator; cruise comes back on release.
            "ACTIVATED", "OVERRIDE" -> add { Chip("크루즈 작동", "cruise", tone = "info", big = true) }
            else -> Unit                       // off or standby is not news while driving
        }
        add {
            when (voice) {
                VoiceState.SPEAKING -> Chip("말하는 중", "speaker", tone = "info", big = true)
                VoiceState.LISTENING -> Chip("듣는 중", "mic", tone = "info", big = true)
                VoiceState.IDLE -> Chip("가이드", "mic", tone = "neutral", big = true)
            }
        }
    }
    if (wide || chips.size <= 2) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { chips.forEach { it() } }
    else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        chips.chunked(2).forEach { pair -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { pair.forEach { it() } } }
    }
}

@Composable
fun Banner(a: ActiveSituation) {
    val color = c.tone(a.tone)
    Row(
        Modifier.fillMaxWidth().padding(24.dp).height(if (isWide) 140.dp else 120.dp)
            .clip(RoundedCornerShape(28.dp)).background(c.surface).border(4.dp, color, RoundedCornerShape(28.dp)).padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(a.icon, color, 80.dp)
        Hspace(28.dp)
        Text(a.word, style = GuideType.headline, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        a.sub?.let { Chip(it, tone = a.tone, big = true) }
    }
}

// ---------------- S12 Reverse gauge ----------------

@Composable
fun ReverseScreen(s: Signals) {
    val deg = s.num(Signals.STEERING) ?: 0.0
    val turns = abs(deg) / 360.0
    val side = when { deg > 15 -> "오른쪽"; deg < -15 -> "왼쪽"; else -> null }
    val label = when {
        side == null -> "핸들 가운데"
        turns < .375 -> "$side 조금"
        turns < .75 -> "$side 반 바퀴"
        turns < 1.25 -> "$side 한 바퀴"
        else -> "$side 끝까지"
    }
    Column(Modifier.fillMaxSize().background(c.bg).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = GuideType.display, color = c.text)
        // What the wheel does to the car while backing: the rear swings the way the wheel is turned.
        Text(side?.let { "차 뒤쪽이 ${it}으로 가요" } ?: "똑바로 뒤로 가요", style = GuideType.title, color = c.info)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SteeringGauge(deg) }
        Text("천천히 · 브레이크로 속도 조절", style = GuideType.title, color = c.textSub)
    }
}

/**
 * The steering wheel seen from the seat, turned by [deg]: the rim, the hub, the spoke down and a mark at the
 * top of the rim, so half a turn reads as the mark at six o'clock. Around it, how far it has turned (up to 1.5 turns).
 */
@Composable
fun SteeringGauge(deg: Double, target: ClosedFloatingPointRange<Double>? = null) {
    val ok = c.ok; val info = c.info; val track = c.textSub.copy(alpha = .22f); val rim = c.text
    Canvas(Modifier.size(460.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val outer = size.minDimension / 2 * .96f
        val band = outer * .07f
        // Turn amount: a full circle is 540 degrees of wheel (lock to lock each way).
        drawCircle(track, outer - band / 2, center, style = Stroke(band))
        target?.let { t ->
            drawArc(ok, (-90 + t.start / 540.0 * 360).toFloat(), ((t.endInclusive - t.start) / 540.0 * 360).toFloat(), false,
                Offset(center.x - outer + band / 2, center.y - outer + band / 2), Size((outer - band / 2) * 2, (outer - band / 2) * 2), style = Stroke(band))
        }
        val sweep = (deg.coerceIn(-540.0, 540.0) / 540.0 * 360).toFloat()
        drawArc(info, -90f, sweep, false, Offset(center.x - outer + band / 2, center.y - outer + band / 2),
            Size((outer - band / 2) * 2, (outer - band / 2) * 2), style = Stroke(band, cap = StrokeCap.Round))
        // The wheel itself.
        val r = outer * .74f
        val w = r * .13f
        rotate(deg.toFloat(), center) {
            drawCircle(rim, r, center, style = Stroke(w))
            drawRoundRect(rim, Offset(center.x - r, center.y - w * .55f), Size(r * 2, w * 1.1f), CornerRadius(w))
            drawLine(rim, center, Offset(center.x, center.y + r), w * .9f, StrokeCap.Round)
            drawCircle(rim, r * .26f, center)
            drawLine(info, Offset(center.x, center.y - r - w * .7f), Offset(center.x, center.y - r + w * .7f), w * .55f, StrokeCap.Round)
        }
    }
}
