package io.github.beomsun0829.handsonguide.ui

import io.github.beomsun0829.handsonguide.content.Card
import io.github.beomsun0829.handsonguide.signal.Signals
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * One picture per card, all in the same style: where the control is (cockpit), how to sit
 * (seat side view), how to hold the wheel (wheel front view), or what happens on the road (top-down).
 */
@Composable
fun CardArt(card: Card, s: Signals, modifier: Modifier = Modifier, done: Boolean = false, step: Int = 0) =
    // Fresh state per card (pictures, step, motion clock): the same spot may show another card, e.g. tip detail.
    androidx.compose.runtime.key(card.id) { CardArtFor(card, s, modifier, done, step) }

@Composable
private fun CardArtFor(card: Card, s: Signals, modifier: Modifier, done: Boolean, step: Int) {
    val desc = "${card.title} 그림"
    // A photo says nothing to a screen reader by its name; read where the control is instead.
    if (card.id in RENDERED_CARDS) {
        RenderedCardScene(card.id, s, done, listOfNotNull("${card.title} 사진", card.verify?.hint ?: card.how).joinToString(". "), modifier); return
    }
    when (val art = artFor(card)) {
        Art.Fog -> FogHeroScene(s.bool(Signals.DEFROST_FRONT), done, modifier)
        Art.Lane -> LaneHeroScene(done, modifier)
        is Art.Cockpit -> CockpitScene(art.focus, art.label, lit(s), desc, modifier)
        is Art.Seat -> { val time = animationClock(); val text = rememberTextMeasurer()
            SceneBox(modifier, desc) { drawSeat(art, time, text) } }
        is Art.Wheel -> { val time = animationClock(); val text = rememberTextMeasurer()
            SceneBox(modifier, desc) { drawWheelFront(art, time, text) } }
        is Art.Road -> { val time = animationClockState(); val ambient = ambientClockState(); val text = rememberTextMeasurer()
            // Clocks are read while drawing, so each frame redraws without recomposing the card.
            SceneBox(modifier, desc, still = { drawRoadStill3D(art) }) { drawRoadMoving3D(art, time.value, ambient.value, text) } }
    }
}

sealed class Art {
    object Fog : Art()
    object Lane : Art()
    data class Cockpit(val focus: String, val label: String) : Art()
    data class Seat(val focus: String, val label: String) : Art()
    data class Wheel(val move: String, val label: String) : Art()
    data class Road(val base: String, val items: List<RoadItem>, val label: String? = null) : Art()
}

/** Things on a top-down road, in the 1000×720 visible space; cars face up at 0°. */
sealed class RoadItem {
    data class Car(val x: Float, val y: Float, val deg: Float = 0f, val kind: String = "other", val blink: String? = null) : RoadItem()
    data class Path(val points: List<Offset>, val ok: Boolean = true) : RoadItem()
    data class No(val x: Float, val y: Float) : RoadItem()
    data class Zone(val rect: Rect, val ok: Boolean, val text: String? = null) : RoadItem()
    data class Person(val x: Float, val y: Float) : RoadItem()
    data class Sign(val x: Float, val y: Float, val text: String) : RoadItem()
    data class Light(val x: Float, val y: Float, val color: String) : RoadItem()
}

private fun cockpit(focus: String, label: String = COCKPIT_LABELS.getValue(focus)) = Art.Cockpit(focus, label)
private fun p(vararg xy: Float) = xy.toList().chunked(2).map { Offset(it[0], it[1]) }
private fun car(x: Float, y: Float, deg: Float = 0f, kind: String = "other", blink: String? = null) = RoadItem.Car(x, y, deg, kind, blink)
private fun me(x: Float, y: Float, deg: Float = 0f, blink: String? = null) = RoadItem.Car(x, y, deg, "me", blink)
private fun path(vararg xy: Float, ok: Boolean = true) = RoadItem.Path(p(*xy), ok)

private val COCKPIT_LABELS = mapOf(
    "defrost_front" to "앞유리 김서림", "defrost_rear" to "뒷유리 열선", "recirc" to "내기·외기", "ac" to "에어컨",
    "auto" to "자동 공조", "left_stalk" to "라이트·깜빡이 레버", "right_stalk" to "와이퍼 레버", "gear" to "기어",
    "hazard" to "비상등", "epb" to "주차 브레이크", "autohold" to "오토 홀드", "cruise" to "크루즈 버튼",
    "lfa" to "차로 유지 버튼", "cluster" to "계기판 경고등", "room_mirror" to "룸미러", "side_mirror" to "사이드미러",
)

private val BY_CARD: Map<String, Art> = mapOf(
    "c6-front" to Art.Fog,
    // 1 운전 자세
    "c1-sit" to Art.Seat("seat", "엉덩이를 끝까지"), "c1-distance" to Art.Seat("distance", "무릎이 살짝 굽게"),
    "c1-backrest" to Art.Seat("backrest", "등받이는 조금만 뒤로"), "c1-height" to Art.Seat("height", "시야가 넓게"),
    "c1-recline" to Art.Seat("backrest", "너무 눕히지 않기"), "c1-order" to Art.Seat("seat", "시트 → 핸들 → 미러"),
    "c1-save" to Art.Seat("seat", "자세 저장"), "c1-headrest" to Art.Seat("headrest", "머리 중앙 높이"),
    "c1-belt" to Art.Seat("belt", "어깨와 골반"), "c1-wheel" to Art.Seat("wheel", "손목이 핸들 위에"),
    "c7-heat" to Art.Seat("heat", "열선 시트"), "c8-belt" to Art.Seat("belt", "안전벨트"),
    // 2 미러
    "c2-room" to cockpit("room_mirror"), "c2-side" to cockpit("side_mirror"), "c2-convex" to cockpit("side_mirror", "볼록 거울"),
    "c2-blind" to Art.Road("straight3", listOf(me(500f, 420f), car(600f, 560f),
        RoadItem.Zone(Rect(552f, 470f, 650f, 640f), ok = false, "사각지대"))),
    "c2-fold" to cockpit("side_mirror", "미러 접기"), "c9-bsd" to cockpit("side_mirror", "사각지대 경고 표시"),
    // 3 핸들
    "c3-grip" to Art.Wheel("grip", "9시와 3시"), "c3-pushpull" to Art.Wheel("pushpull", "밀고 당기기"),
    "c3-noCross" to Art.Wheel("cross", "손을 교차하지 않기"), "c3-return" to Art.Wheel("return", "미끄러지듯 되돌리기"),
    "c3-feel" to Art.Wheel("feel", "끝까지 돌려보기"),
    // 4 페달과 기어
    "c4-onefoot" to Art.Seat("pedal", "오른발 하나로"), "c4-creep" to Art.Seat("pedal", "브레이크만 살짝 떼기"),
    "c4-smooth" to Art.Seat("pedal", "천천히 밟기"), "c4-regen" to Art.Seat("pedal", "가속 페달 떼기"),
    "c4-gear" to cockpit("gear"), "c4-autohold" to cockpit("autohold"),
    // 5 라이트·와이퍼
    "c5-turn" to cockpit("left_stalk", "깜빡이 레버"), "c5-high" to cockpit("left_stalk", "상향등"),
    "c5-hazard" to cockpit("hazard"), "c5-wiper" to cockpit("right_stalk"),
    // 6·7 김서림, 실내 공기
    "c6-rear" to cockpit("defrost_rear"), "c6-frost" to cockpit("defrost_front"), "c6-why" to cockpit("vent", "송풍구 바람"),
    "c6-inout" to cockpit("right_stalk", "와이퍼로 확인"), "c6-summer" to cockpit("right_stalk", "와이퍼"),
    "c6-outside" to cockpit("recirc", "외기"), "c6-ac" to cockpit("ac"), "c6-autodefog" to cockpit("auto"),
    "c7-modes" to cockpit("recirc"), "c7-auto" to cockpit("auto"), "c7-cleaner" to cockpit("auto", "공기 청정"),
    // 8 경고등
    "c8-colors" to cockpit("cluster", "빨강은 정차, 노랑은 점검"), "c8-tpms" to cockpit("cluster", "타이어 공기압"),
    "c8-fuel" to cockpit("cluster", "연료"),
    // 9 운전 보조
    "c9-rule" to cockpit("cruise", "보조일 뿐"), "c9-cruise" to cockpit("cruise"), "c9-release" to cockpit("cruise", "브레이크로 해제"),
    "c9-lane" to cockpit("lfa"),
    "c9-fca" to Art.Road("straight3", listOf(car(500f, 240f), me(500f, 400f), RoadItem.Zone(Rect(452f, 300f, 548f, 340f), ok = false, "너무 가까움"))),
    "c9-sensor" to Art.Road("parking", listOf(me(500f, 420f, 180f), RoadItem.Zone(Rect(430f, 470f, 570f, 520f), ok = false, "센서 경고음"))),
    // 10 주차와 후진
    "c10-reverse" to Art.Road("parking", listOf(me(620f, 470f, 160f), path(620f, 530f, 560f, 600f, 500f, 630f)), "천천히 후진"),
    "c10-rule" to Art.Road("parking", listOf(me(500f, 420f, 180f), RoadItem.Person(500f, 560f)), "뒤에 사람이 없는지"),
    "c10-tpark" to Art.Road("parking", listOf(me(700f, 380f, 90f), path(660f, 400f, 560f, 430f, 500f, 520f, 500f, 610f)), "T자 주차"),
    "c10-parallel" to Art.Road("curb", listOf(me(540f, 230f), path(540f, 290f, 570f, 380f, 640f, 450f, 650f, 500f)), "나란히 섰다가 후진"),
    "c10-exit" to Art.Road("parking", listOf(me(500f, 600f, blink = "left"), path(500f, 540f, 500f, 400f, 380f, 360f)), "나갈 땐 깜빡이"),
    // 11 차선 바꾸기
    "c11-order" to Art.Lane,
    "c11-shoulder" to Art.Road("straight3", listOf(me(500f, 420f, blink = "right"), car(600f, 560f),
        RoadItem.Zone(Rect(552f, 470f, 650f, 640f), ok = false, "고개로 확인"))),
    "c11-speed" to Art.Road("straight3", listOf(car(600f, 140f), me(500f, 460f, blink = "right"), path(500f, 400f, 530f, 330f, 600f, 280f), car(600f, 640f)), "속도는 그대로"),
    "c11-wait" to Art.Road("straight3", listOf(me(500f, 520f, blink = "right"), path(500f, 360f, 540f, 280f, 600f, 220f)), "켜고 3초 뒤 이동"),
    "c11-gap" to Art.Road("straight3", listOf(car(600f, 140f), car(600f, 640f), me(500f, 420f, blink = "right"),
        RoadItem.Zone(Rect(552f, 230f, 648f, 560f), ok = true, "충분한 틈"))),
    "c11-letin" to Art.Road("straight3", listOf(car(600f, 380f, -20f, blink = "left"), path(590f, 330f, 520f, 260f, 500f, 200f), me(500f, 560f)), "살짝 줄여 양보"),
    "c11-solid" to Art.Road("straight3_solid", listOf(me(500f, 480f), path(500f, 420f, 600f, 260f, ok = false), RoadItem.No(550f, 340f)), "실선은 넘지 않기"),
    "c11-buslane" to Art.Road("buslane", listOf(car(400f, 220f, kind = "bus"), me(500f, 500f), path(500f, 440f, 400f, 300f, ok = false), RoadItem.No(450f, 380f)), "1차로 파란 실선은 버스 전용"),
    "c11-one" to Art.Road("straight3", listOf(me(400f, 540f), path(400f, 480f, 600f, 240f, ok = false), RoadItem.No(500f, 360f)), "한 차로씩"),
    // 12 교차로와 신호
    "c12-right" to Art.Road("cross", listOf(me(560f, 660f, blink = "right"), path(560f, 600f, 566f, 480f, 610f, 436f, 650f, 430f),
        RoadItem.Person(697f, 400f), RoadItem.Zone(Rect(672f, 362f, 722f, 466f), ok = false, "횡단보도 앞 정지"))),
    // The oncoming car has already gone by; only then does my car turn.
    "c12-unprotected" to Art.Road("cross", listOf(me(560f, 660f, blink = "left"), car(460f, 720f, 180f),
        path(560f, 600f, 540f, 420f, 440f, 360f, 240f, 350f)), "맞은편 차가 지나간 뒤에"),
    "c12-yellow" to Art.Road("cross", listOf(me(560f, 680f), RoadItem.Light(700f, 560f, "yellow")), "멈출 수 있으면 정지"),
    "c12-stopline" to Art.Road("cross", listOf(me(560f, 668f), RoadItem.Zone(Rect(510f, 580f, 610f, 600f), ok = true, "정지선 앞")),),
    "c12-roundabout" to Art.Road("roundabout", listOf(car(390f, 300f, -60f), me(540f, 640f, blink = "right"),
        path(540f, 580f, 580f, 470f, 660f, 360f, 800f, 330f)), "도는 차가 먼저"),
    "c12-block" to Art.Road("cross", listOf(car(560f, 250f), car(560f, 120f), car(560f, 380f), me(560f, 680f),
        RoadItem.No(560f, 470f)), "앞이 막히면 진입하지 않기"),
    "c12-emergency" to Art.Road("straight3", listOf(me(500f, 420f, blink = "right"), path(500f, 360f, 560f, 280f, 600f, 180f),
        car(500f, 650f, kind = "emergency", blink = "both")), "오른쪽으로 비켜 서행"),
    "c12-uturn" to Art.Road("uturn", listOf(me(540f, 560f, blink = "left"), path(540f, 500f, 540f, 330f, 500f, 250f, 450f, 300f, 450f, 560f)), "유턴 표시가 있는 곳에서"),
    // 13 고속도로
    "c13-merge" to Art.Road("merge", listOf(car(560f, 200f), me(690f, 520f, -8f, blink = "left"), path(680f, 450f, 650f, 330f, 600f, 260f)), "끝까지 가속하며 합류"),
    "c13-distance" to Art.Road("straight3", listOf(car(500f, 160f), me(500f, 520f), RoadItem.Zone(Rect(452f, 230f, 548f, 450f), ok = true, "2초 간격"))),
    "c13-passing" to Art.Road("straight3", listOf(car(500f, 300f), me(400f, 380f), path(400f, 320f, 410f, 200f, 500f, 100f)), "추월 후 제자리로"),
    "c13-exit" to Art.Road("exit", listOf(me(600f, 560f, blink = "right"), path(600f, 500f, 640f, 380f, 740f, 250f, 800f, 160f)), "미리 오른쪽 차로로"),
    "c13-missed" to Art.Road("exit", listOf(me(600f, 220f), path(600f, 290f, 700f, 420f, ok = false), RoadItem.No(650f, 350f), path(600f, 160f, 600f, 40f)), "놓치면 다음 출구로"),
    "c13-jam" to Art.Road("straight3", listOf(car(500f, 120f), car(500f, 260f), car(400f, 200f), car(600f, 230f), me(500f, 520f, blink = "both")), "정체 끝에서 비상등"),
    "c13-drowsy" to Art.Road("exit", listOf(me(600f, 520f, blink = "right"), path(600f, 460f, 680f, 340f, 790f, 200f), RoadItem.Sign(860f, 120f, "P")), "졸리면 휴게소"),
    "c13-toll" to Art.Road("toll", listOf(me(650f, 620f), path(650f, 560f, 650f, 120f), RoadItem.Sign(760f, 520f, "30")), "하이패스는 파란 차로"),
    "c13-accident" to Art.Road("straight3", listOf(car(690f, 260f, kind = "warn", blink = "both"), RoadItem.Sign(690f, 480f, "▲"), RoadItem.Person(800f, 220f)), "가드레일 밖으로"),
    // 14 도심과 보호구역
    "c14-school" to Art.Road("school", listOf(me(610f, 560f), RoadItem.Person(600f, 300f), RoadItem.Sign(820f, 520f, "30")), "어린이보호구역 30"),
    "c14-senior" to Art.Road("school", listOf(me(610f, 560f), RoadItem.Person(560f, 300f), RoadItem.Sign(820f, 520f, "30")), "노인보호구역"),
    "c14-bus" to Art.Road("school", listOf(car(550f, 260f, kind = "bus", blink = "both"), me(550f, 560f), RoadItem.Zone(Rect(502f, 380f, 598f, 470f), ok = false, "멈춰서 기다리기"))),
    "c14-alley" to Art.Road("alley", listOf(car(380f, 260f), car(380f, 420f), me(520f, 560f), RoadItem.Person(470f, 300f)), "천천히, 언제든 설 수 있게"),
    "c14-buslane" to Art.Road("buslane", listOf(car(400f, 200f, kind = "bus"), me(500f, 480f), path(500f, 420f, 400f, 280f, ok = false), RoadItem.No(450f, 350f)), "버스전용차로"),
)

/** Cards not listed above fall back by their icon, so a new card still gets a fitting picture. */
fun artFor(card: Card): Art = BY_CARD[card.id] ?: when (card.illustration) {
    "seat", "headrest", "belt", "pedal" -> Art.Seat(if (card.illustration == "seat") "seat" else card.illustration!!, card.title)
    "wheel" -> Art.Wheel("grip", card.title)
    "mirror" -> cockpit("side_mirror", card.title)
    "light", "turn_signal" -> cockpit("left_stalk", card.title)
    "wiper" -> cockpit("right_stalk", card.title)
    "recirc" -> cockpit("recirc", card.title)
    "fan", "ac" -> cockpit("auto", card.title)
    "defrost_front" -> cockpit("defrost_front", card.title)
    "defrost_rear" -> cockpit("defrost_rear", card.title)
    "telltale" -> cockpit("cluster", card.title)
    "cruise" -> cockpit("cruise", card.title)
    "lane" -> cockpit("lfa", card.title)
    "gear" -> cockpit("gear", card.title)
    "hazard" -> cockpit("hazard", card.title)
    "park" -> Art.Road("parking", listOf(me(500f, 420f, 180f)), card.title)
    "school" -> Art.Road("school", listOf(me(610f, 560f)), card.title)
    "turn_right" -> Art.Road("cross", listOf(me(560f, 660f)), card.title)
    else -> Art.Road("straight3", listOf(me(500f, 480f)), card.title)
}

private fun lit(s: Signals): Set<String> = buildSet {
    if (s.bool(Signals.DEFROST_FRONT) == true) add("defrost_front")
    if (s.bool(Signals.DEFROST_REAR) == true) add("defrost_rear")
    if (s.bool(Signals.RECIRC) == true) add("recirc")
    if (s.bool(Signals.AC) == true) add("ac")
    if (s.bool(Signals.HVAC_AUTO) == true) add("auto")
    if (s.bool(Signals.HAZARD) == true) add("hazard")
    if (s.bool(Signals.PARKING_BRAKE) == true) add("epb")
    if (s.bool(Signals.HIGH_BEAM) == true) add("high_beam")
    when (s.str(Signals.TURN_SIGNAL)) { "LEFT" -> add("turn_left"); "RIGHT" -> add("turn_right") }
    if (s.str(Signals.TT_RED) != null) add("telltale_red") else if (s.str(Signals.TT_AMBER) != null) add("telltale_amber")
}

// ---------------- Seat, side view (driver faces right) ----------------

private object SeatSpot {
    val rects = mapOf(
        "seat" to Rect(210f, 160f, 600f, 660f), "distance" to Rect(520f, 440f, 880f, 700f),
        "backrest" to Rect(200f, 230f, 350f, 580f), "height" to Rect(280f, 500f, 620f, 680f),
        "headrest" to Rect(210f, 150f, 330f, 260f), "belt" to Rect(260f, 220f, 570f, 570f),
        "wheel" to Rect(560f, 280f, 760f, 540f), "pedal" to Rect(790f, 560f, 960f, 710f),
        "heat" to Rect(290f, 470f, 600f, 600f),
    )
}

private fun DrawScope.drawSeat(art: Art.Seat, time: Float, text: TextMeasurer) {
    // Cabin side view at dusk: side window with the evening sky, dash and glass on the right.
    drawRect(Brush.verticalGradient(listOf(Color(0xFF1C2028), Color(0xFF111418)), 0f, 820f), Offset.Zero, Size(1000f, 820f))
    val window = Path().apply { moveTo(170f, 120f); lineTo(600f, 70f); lineTo(700f, 300f); lineTo(190f, 330f); close() }
    drawPath(window, Brush.verticalGradient(listOf(Color(0xFF2B3D55), Color(0xFF5A6E86), Color(0xFF8592A0)), 70f, 330f))
    for (k in 0..6) drawRect(Color(0xFF1A2431), Offset(200f + k * 70f, 230f - (k % 3) * 30f), Size(56f, 120f))
    drawPath(window, Color(0xFF2A2F37), style = Stroke(14f))
    drawRect(Brush.verticalGradient(listOf(Color(0xFF181B20), Color(0xFF0E1013)), 690f, 820f), Offset(0f, 700f), Size(1000f, 120f))
    drawPath(Path().apply { moveTo(760f, 290f); lineTo(1000f, 290f); lineTo(1000f, 600f); quadraticBezierTo(880f, 560f, 820f, 540f); lineTo(790f, 330f); close() },
        Brush.verticalGradient(listOf(Color(0xFF3A3F47), Color(0xFF1C1F24)), 290f, 600f))
    drawLine(Color(0x33FFFFFF), Offset(762f, 292f), Offset(1000f, 292f), 3f)
    drawLine(Color(0xFF6C8AA6), Offset(770f, 280f), Offset(620f, 40f), 10f, StrokeCap.Round)
    drawLine(Color(0xFF2B2F36), Offset(830f, 440f), Offset(700f, 400f), 26f, StrokeCap.Round)
    // Each focus acts out its adjustment on a gentle loop.
    // One adjustment from the wrong setting to the right one, then hold.
    val wave = FogFrame.ease(time / 1.6f)
    val slide = if (art.focus in setOf("distance", "seat")) -24f + 24f * wave else 0f
    val lift = if (art.focus == "height") -18f * wave else 0f
    val tilt = if (art.focus in setOf("backrest")) 8f * (1f - wave) else 0f
    val headrestDrop = if (art.focus == "headrest") 34f * (1f - wave) else 0f
    val brake = if (art.focus == "pedal") wave else 0f
    val beltDraw = if (art.focus == "belt") (time / 2f).coerceIn(0f, 1f) else 1f
    val leather = Brush.linearGradient(listOf(Color(0xFF4B5260), Color(0xFF2C3139)), Offset(240f, 250f), Offset(600f, 600f))
    val skin = Brush.linearGradient(listOf(Color(0xFFB4BFCC), Color(0xFF7F8B99)), Offset(260f, 180f), Offset(700f, 650f))
    val hip = Offset(330f, 540f)
    withTransform({ translate(slide, lift) }) {
        // Rails and cushion.
        drawLine(Color(0xFF2B2F36), Offset(270f, 668f - lift), Offset(620f, 668f - lift), 14f, StrokeCap.Round)
        drawLine(Color(0xFF2B2F36), Offset(400f, 600f), Offset(420f, 668f - lift), 22f)
        val cushion = Path().apply { moveTo(280f, 540f); lineTo(580f, 520f); quadraticBezierTo(612f, 530f, 600f, 572f); lineTo(300f, 602f); quadraticBezierTo(268f, 590f, 280f, 540f); close() }
        drawPath(cushion, leather)
        drawPath(Path().apply { moveTo(300f, 562f); lineTo(584f, 540f) }, Color(0x33FFFFFF), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
        if (art.focus == "heat") for (k in 0..2) {
            val rise = ((time * .8f + k * .33f) % 1f)
            val x = 380f + k * 70f
            drawPath(Path().apply { moveTo(x, 500f - rise * 60f); quadraticBezierTo(x + 14f, 470f - rise * 60f, x, 440f - rise * 60f); quadraticBezierTo(x - 14f, 410f - rise * 60f, x, 380f - rise * 60f) },
                Cabin.iconOn.copy(alpha = 1f - rise), style = Stroke(6f, cap = StrokeCap.Round))
        }
        // Upper body leans with the backrest.
        withTransform({ rotate(-tilt, hip) }) {
            val back = Path().apply { moveTo(240f, 260f); quadraticBezierTo(282f, 244f, 322f, 250f); lineTo(348f, 560f); lineTo(284f, 582f); close() }
            drawPath(back, leather)
            drawPath(Path().apply { moveTo(262f, 290f); lineTo(304f, 556f) }, Color(0x33FFFFFF), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF4B5260), Color(0xFF2C3139)), 160f, 250f), Offset(225f, 162f + headrestDrop), Size(92f, 84f), CornerRadius(26f))
            drawCircle(skin, 44f, Offset(300f, 210f))
            drawLine(Color(0xFF7F8B99), Offset(304f, 250f), Offset(310f, 280f), 26f, StrokeCap.Round)
            drawLine(skin, Offset(305f, 290f), Offset(330f, 520f), 76f, StrokeCap.Round)
            drawLine(Color(0xFF8E9AA8), Offset(320f, 316f), Offset(480f, 400f), 34f, StrokeCap.Round)
            drawLine(Color(0xFF8E9AA8), Offset(480f, 400f), Offset(660f - slide, 330f - lift), 28f, StrokeCap.Round)
            // Belt: shoulder across the chest to the hip, then the lap part.
            val belt = if (art.focus == "belt") Cabin.focus else Color(0xFF20242A)
            val s1 = (beltDraw * 2f).coerceAtMost(1f); val s2 = (beltDraw * 2f - 1f).coerceIn(0f, 1f)
            drawLine(belt, Offset(270f, 250f), Offset(270f + 110f * s1, 250f + 280f * s1), 14f, StrokeCap.Round)
            if (s2 > 0f) drawLine(belt, Offset(330f, 535f), Offset(330f + 190f * s2, 535f - 20f * s2), 14f, StrokeCap.Round)
        }
        // Legs to the brake; the foot presses it on the pedal card.
        drawLine(skin, Offset(340f, 530f), Offset(570f, 505f), 62f, StrokeCap.Round)
        val foot = Offset(822f - slide + 10f * brake, 636f - lift)
        drawLine(skin, Offset(570f, 505f), foot, 50f, StrokeCap.Round)
        drawRoundRect(Color(0xFF2A2F37), Offset(foot.x - 22f, foot.y - 18f), Size(64f, 30f), CornerRadius(12f))
    }
    if (art.focus == "distance") {
        // Knee still bent with the brake fully pressed.
        drawArc(Cabin.green, 200f, 70f, false, Offset(520f + slide, 455f), Size(100f, 100f), style = Stroke(6f, cap = StrokeCap.Round))
    }
    // Wheel edge-on and the two pedals (they stay put; the brake pedal tilts when pressed).
    drawLine(Color(0xFF0E1013), Offset(650f, 300f), Offset(720f, 520f), 30f, StrokeCap.Round)
    drawLine(Color(0x33FFFFFF), Offset(645f, 304f), Offset(712f, 514f), 3f, StrokeCap.Round)
    withTransform({ rotate(14f * brake, Offset(840f, 560f)) }) {
        drawLine(Color(0xFF6D7480), Offset(840f, 560f), Offset(850f, 650f), 10f, StrokeCap.Round)
        drawRoundRect(if (brake > .5f) Cabin.focus else Color(0xFF6D7480), Offset(828f, 640f), Size(46f, 20f), CornerRadius(6f))
    }
    drawLine(Color(0xFF6D7480), Offset(910f, 580f), Offset(920f, 680f), 10f, StrokeCap.Round)
    drawRoundRect(Color(0xFF6D7480), Offset(900f, 664f), Size(40f, 26f), CornerRadius(6f))
    drawFocusRect(SeatSpot.rects.getValue(art.focus), art.label, 1f, time, text)
}

// ---------------- Steering wheel, front view ----------------

private fun DrawScope.drawWheelFront(art: Art.Wheel, time: Float, text: TextMeasurer) {
    val c = Offset(500f, 380f); val r = 250f
    drawRect(Brush.verticalGradient(listOf(Color(0xFF2B3D55), Color(0xFF6E7F93)), 0f, 170f), Offset.Zero, Size(1000f, 170f))
    drawPath(Path().apply { moveTo(-20f, 170f); quadraticBezierTo(500f, 130f, 1020f, 170f); lineTo(1020f, 820f); lineTo(-20f, 820f); close() },
        Brush.verticalGradient(listOf(Color(0xFF3A3F47), Color(0xFF15171B)), 140f, 820f))
    val turn = when (art.move) {
        "pushpull", "feel" -> FogFrame.ease(time / 1.6f) * 60f
        "return" -> (1f - FogFrame.ease(time / 2f)) * 90f
        else -> 0f
    }
    rotate(turn, c) {
        drawCircle(Color(0xFF0E1013), r, c, style = Stroke(48f))
        drawArc(Color(0x40FFFFFF), 200f, 140f, false, Offset(c.x - r - 14f, c.y - r - 14f), Size((r + 14f) * 2, (r + 14f) * 2), style = Stroke(4f, cap = StrokeCap.Round))
        drawRoundRect(Cabin.wheel, Offset(c.x - 110f, c.y - 70f), Size(220f, 150f), CornerRadius(56f))
        drawLine(Cabin.wheel, Offset(c.x - r, c.y + 10f), Offset(c.x - 100f, c.y), 40f)
        drawLine(Cabin.wheel, Offset(c.x + r, c.y + 10f), Offset(c.x + 100f, c.y), 40f)
        drawLine(Cabin.wheel, Offset(c.x, c.y + 80f), Offset(c.x, c.y + r), 40f)
        drawCircle(Cabin.alert.copy(alpha = .8f), 8f, Offset(c.x, c.y - r))
    }
    // Hands: 9 and 3, except the crossed-hands example.
    fun hand(angleDeg: Float, ok: Boolean = true) {
        val a = Math.toRadians(angleDeg.toDouble())
        val at = Offset(c.x + (cos(a) * r).toFloat(), c.y + (sin(a) * r).toFloat())
        val shade = Brush.horizontalGradient(listOf(Color(0xFFD39674), Color(0xFFF0C3A2), Color(0xFFD39674)), at.x - 42f, at.x + 42f)
        drawOval(Color(0x55000000), Offset(at.x - 38f, at.y - 48f), Size(84f, 112f))
        drawOval(shade, Offset(at.x - 42f, at.y - 56f), Size(84f, 112f), alpha = if (ok) 1f else .7f)
    }
    when (art.move) {
        "cross" -> {
            hand(250f + turn, ok = false); hand(290f + turn, ok = false)
            drawLine(Cabin.alert, Offset(c.x - 60f, c.y - r - 60f), Offset(c.x + 60f, c.y - r + 60f), 12f, StrokeCap.Round)
            drawLine(Cabin.alert, Offset(c.x + 60f, c.y - r - 60f), Offset(c.x - 60f, c.y - r + 60f), 12f, StrokeCap.Round)
        }
        "pushpull" -> { hand(180f + turn * .5f); hand(0f + turn * .5f) }
        else -> { hand(180f + turn); hand(0f + turn) }
    }
    if (art.move in setOf("pushpull", "return", "feel")) {
        drawArc(Cabin.focus, -150f, 120f, false, Offset(c.x - r - 70f, c.y - r - 70f), Size((r + 70f) * 2, (r + 70f) * 2),
            style = Stroke(8f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(30f, 20f), -time * 80f)))
    }
    label(text, art.label, Offset(c.x, 690f))
}

internal fun DrawScope.label(text: TextMeasurer, s: String, center: Offset, color: Color = Cabin.focus) {
    val l = text.measure(s, TextStyle(fontFamily = Pretendard, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold))
    val w = l.size.width + 36f; val h = l.size.height + 18f
    val x = (center.x - w / 2f).coerceIn(10f, 990f - w); val y = (center.y - h / 2f).coerceIn(10f, 710f - h)
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(h / 2f))
    drawText(l, topLeft = Offset(x + 18f, y + 9f))
}

// ---------------- Road ----------------
// Drawn in 3D from behind my car: RoadScene3D.kt. The scenes above stay in top-down world coordinates.

/** Point and heading (degrees, 0 = nose up) at fraction [p] of a polyline. */
internal fun alongPath(points: List<Offset>, p: Float): Pair<Offset, Float> {
    val segs = points.zipWithNext()
    val total = segs.sumOf { (a, b) -> (b - a).getDistance().toDouble() }.toFloat().coerceAtLeast(1f)
    var left = p.coerceIn(0f, 1f) * total
    for ((a, b) in segs) {
        val len = (b - a).getDistance()
        if (left <= len || (a to b) == segs.last()) {
            val f = if (len == 0f) 0f else (left / len).coerceIn(0f, 1f)
            val pos = Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
            val deg = Math.toDegrees(atan2((b.x - a.x).toDouble(), (a.y - b.y).toDouble())).toFloat()
            return pos to deg
        }
        left -= len
    }
    return points.last() to 0f
}
