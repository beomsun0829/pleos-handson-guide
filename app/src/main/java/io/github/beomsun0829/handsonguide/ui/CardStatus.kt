package io.github.beomsun0829.handsonguide.ui

import io.github.beomsun0829.handsonguide.content.Content
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.signal.Telltale

/**
 * "지금 상태" under a feature card: what the car reports for that control right now, in words.
 * A value the car does not report is left out; a card with nothing to show shows no panel at all.
 */
internal fun cardStatus(cardId: String, s: Signals, content: Content): List<Pair<String, String>> = buildList {
    fun onOff(key: String, label: String, on: String = "켜짐", off: String = "꺼짐") =
        s.bool(key)?.let { add(label to if (it) on else off) }
    when (cardId) {
        "c0-start" -> when (s.str(Signals.IGNITION)) {
            "ON", "START" -> add("시동" to "켜짐")
            "ACC" -> add("시동" to "전원만 켜짐")
            "OFF", "LOCK" -> add("시동" to "꺼짐")
        }
        "c4-gear" -> {
            s.gear?.let { g -> GEARS[g]?.let { add("기어" to it) } }
            onOff(Signals.BRAKE, "브레이크", "밟고 있음", "안 밟음")
        }
        "c4-epb" -> onOff(Signals.PARKING_BRAKE, "주차 브레이크", "활성화", "비활성화")
        "c4-autohold" -> telltales(s)?.let { add("오토홀드" to if (it.any { t -> t.type == "AUTO_HOLD" && t.isOn }) "켜짐" else "꺼짐") }
        "c0-door" -> onOff(Signals.DOOR_DRIVER_OPEN, "운전석 문", "열림", "닫힘")
        "c0-trunk" -> onOff(Signals.TRUNK_OPEN, "트렁크", "열림", "닫힘")
        "c2-mirror" -> onOff(Signals.MIRROR_FOLDED, "사이드미러", "접힘", "펴짐")
        "c5-auto" -> {
            s.str(Signals.LIGHT_SWITCH)?.let { v -> LIGHTS[v]?.let { add("라이트 스위치" to it) } }
            onOff(Signals.LOW_BEAM, "전조등")
        }
        "c5-hazard" -> onOff(Signals.HAZARD, "비상등")
        "c5-wiper" -> s.str(Signals.WIPER_SWITCH)?.let { v -> WIPERS[v]?.let { add("와이퍼" to it) } }
        "c6-vent" -> {
            s.str(Signals.FAN_DIRECTION)?.let { v -> FAN_DIRECTIONS[v]?.let { add("바람 방향" to it) } }
            s.num(Signals.FAN)?.toInt()?.let { add("바람 세기" to if (it <= 0) "꺼짐" else "${it}단") }
        }
        "c6-front" -> {
            onOff(Signals.DEFROST_FRONT, "앞유리 김서림")
            onOff(Signals.DEFROST_REAR, "뒷유리 열선")
            onOff(Signals.AC, "A/C")
            onOff(Signals.RECIRC, "공기", "내기(실내 공기)", "외기(바깥 공기)")
        }
        "c9-cruise" -> s.str(Signals.CRUISE)?.let { v ->
            CRUISE[v]?.let { add("크루즈" to it) }
            if (v == "ACTIVATED") s.num(Signals.CRUISE_TARGET)?.let { add("설정 속도" to "${it.toInt()} km/h") }
        }
        "c8-colors" -> telltales(s)?.let { list ->
            val on = list.filter { it.isOn }.mapNotNull { content.telltales[it.type]?.name }.distinct()
            add("지금 켜진 경고등" to on.ifEmpty { listOf("없음") }.joinToString(", "))
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun telltales(s: Signals): List<Telltale>? = s[Signals.TELLTALES] as? List<Telltale>

private val GEARS = mapOf("P" to "P (주차)", "R" to "R (후진)", "N" to "N (중립)", "D" to "D (주행)")
private val LIGHTS = mapOf("AUTO" to "AUTO", "OFF" to "꺼짐", "ON" to "켜짐", "DRL" to "주간주행등")
private val WIPERS = mapOf("OFF" to "꺼짐", "AUTO" to "AUTO", "INT" to "간헐", "LOW" to "느리게", "HIGH" to "빠르게", "MIST" to "한 번")
private val FAN_DIRECTIONS = mapOf("FACE" to "얼굴", "FLOOR" to "발", "FACE_AND_FLOOR" to "얼굴·발", "DEFROST" to "앞유리",
    "FACE_AND_DEFROST" to "얼굴·앞유리", "DEFROST_AND_FLOOR" to "앞유리·발", "FACE_AND_FLOOR_AND_DEFROST" to "얼굴·발·앞유리")
private val CRUISE = mapOf("OFF" to "꺼짐", "ENABLED" to "대기", "ACTIVATED" to "작동 중", "SUSPENDED" to "잠시 해제",
    "OVERRIDE" to "가속 페달로 잠시 해제", "FORCED_OFF" to "꺼짐")
