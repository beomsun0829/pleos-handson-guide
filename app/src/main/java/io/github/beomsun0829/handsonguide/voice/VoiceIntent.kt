package io.github.beomsun0829.handsonguide.voice

data class VoiceIntent(val kind: Kind, val topic: String? = null) {
    enum class Kind { HOWTO, TELLTALE, STATE, ROUTE_RULE, HELP, REPEAT, QUIET, RESUME, LANE_PERMISSION, OPEN, UNKNOWN }
    companion object {
        private val topics = linkedMapOf(
            // Narrow phrases first: "핸들 높이" is not grip, "버스전용차로" is not a lane change.
            "start_car" to listOf("시동"), "trunk" to listOf("트렁크"), "door_open" to listOf("문열", "문손잡이", "도어"),
            "steering_position" to listOf("핸들높이", "핸들위치", "핸들거리", "틸트"), "air_vents" to listOf("송풍구", "바람방향"),
            "emergency_vehicle" to listOf("구급차", "소방차", "긴급차"), "toll_gate" to listOf("톨게이트", "하이패스", "요금소"),
            "parallel_parking" to listOf("평행주차", "일렬주차"), "bus_lane" to listOf("버스전용", "전용차로"),
            "defog_rear" to listOf("뒷유리", "뒤유리"), "defog_front" to listOf("김서림", "성에", "앞유리"),
            "recirculation" to listOf("내기", "외기", "순환"), "air_conditioner" to listOf("에어컨", "냉방"),
            "high_beam" to listOf("상향등", "하이빔"), "headlights" to listOf("전조등", "헤드라이트", "라이트"),
            "hazard_lights" to listOf("비상등"), "wipers" to listOf("와이퍼"), "turn_signal" to listOf("깜빡이", "방향지시등"),
            "headrest" to listOf("머리받침", "헤드레스트"), "seat_position" to listOf("시트", "좌석"),
            "steering_grip" to listOf("핸들", "스티어링"), "mirrors" to listOf("미러", "거울"),
            "parking_order" to listOf("주차순서", "세우는순서"), "parking_brake" to listOf("주차브레이크", "사이드브레이크"),
            "auto_hold" to listOf("오토홀드"), "gear" to listOf("기어", "변속"),
            "cruise_control" to listOf("크루즈"), "lane_keeping" to listOf("차로유지", "차선유지"),
            "shoulder_check" to listOf("숄더체크", "어깨너머", "사각지대"), "lane_change" to listOf("차선", "차로변경"),
            "highway_merge" to listOf("합류"), "right_turn_stop" to listOf("우회전"),
            "unprotected_left_turn" to listOf("비보호", "좌회전"), "roundabout" to listOf("회전교차로"),
            "school_zone" to listOf("어린이보호", "스쿨존"), "safe_distance" to listOf("안전거리", "앞차간격"),
            "highway_exit" to listOf("출구", "진출"), "reverse_parking" to listOf("후진", "후면주차"),
        )

        fun parse(raw: String): VoiceIntent {
            val text = raw.lowercase().replace(Regex("[\\s,.?!]"), "")
            fun has(vararg words: String) = words.any(text::contains)
            val kind = when {
                has("차선", "차로") && has("바꿔도", "바꿔두", "변경해도", "변경해두") -> Kind.LANE_PERMISSION
                has("다시알려", "조용히하지마", "조용히모드꺼", "안내재개", "안내다시켜", "운전안내켜") -> Kind.RESUME
                has("다시말", "다시들", "반복") -> Kind.REPEAT
                has("조용히", "그만말", "안내그만") -> Kind.QUIET
                has("도와줘", "도와주세요", "어떡해") -> Kind.HELP
                has("경고등") -> Kind.TELLTALE
                has("유턴", "불법", "여기돌아") -> Kind.ROUTE_RULE
                else -> null
            }
            if (kind != null) return VoiceIntent(kind)
            val topic = topics.entries.firstOrNull { (_, words) -> words.any(text::contains) }?.key
                ?: return VoiceIntent(Kind.UNKNOWN)
            return VoiceIntent(when {
                has("카드", "설명") && has("열어", "보여") -> Kind.OPEN
                has("지금", "켜져", "상태", "켜있", "꺼져", "꺼있") -> Kind.STATE
                else -> Kind.HOWTO
            }, topic)
        }
    }
}
