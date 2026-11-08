package io.github.beomsun0829.handsonguide.gleo

import io.github.beomsun0829.handsonguide.AppController
import io.github.beomsun0829.handsonguide.Screen
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.store.GuideLevel
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** One reviewed topic catalog shared by Gleo, the app's keyword listener and schema tests. */
object GuideTopics {
    val cards = linkedMapOf(
        "defog_front" to "c6-front", "defog_rear" to "c6-rear", "recirculation" to "c7-modes",
        "air_conditioner" to "c6-ac", "headlights" to "c5-auto", "high_beam" to "c5-high",
        "hazard_lights" to "c5-hazard", "wipers" to "c5-wiper", "turn_signal" to "c5-turn",
        "seat_position" to "c1-seat", "headrest" to "c1-headrest", "steering_grip" to "c3-grip",
        "mirrors" to "c2-mirror", "gear" to "c4-gear", "parking_brake" to "c4-epb", "auto_hold" to "c4-autohold",
        "cruise_control" to "c9-cruise", "lane_keeping" to "c9-lane", "lane_change" to "c11-order",
        "shoulder_check" to "c11-shoulder", "highway_merge" to "c13-merge", "right_turn_stop" to "c12-right",
        "unprotected_left_turn" to "c12-unprotected", "roundabout" to "c12-roundabout", "school_zone" to "c14-school",
        "parking_order" to "c4-park", "safe_distance" to "c13-distance", "highway_exit" to "c13-exit",
        "reverse_parking" to "c10-reverse", "door_open" to "c0-door", "trunk" to "c0-trunk",
        "steering_position" to "c3-wheelpos", "air_vents" to "c6-vent", "warning_lights" to "c8-colors",
        "emergency_vehicle" to "c12-emergency", "toll_gate" to "c13-toll", "parallel_parking" to "c10-parallel",
        "bus_lane" to "c11-buslane", "start_car" to "c0-start",
    )
    val all: List<String> get() = cards.keys.toList() + "current_warning_light"
}

data class GleoResponse(val functionName: String, val status: String, val message: String,
    val mode: String = "voice", val data: JSONArray = JSONArray()) {
    fun toJson(): JSONObject = JSONObject().put("functionName", functionName).put("status", status)
        .put("message", message).put("mode", mode).put("data", data.toString())
}

/** No SDK or Android objects: all request decisions run on the app's engine dispatcher. */
class GleoActions(private val app: AppController, private val readOpenedLesson: Boolean = false,
    /** Gleo speaks the answer, so it asks the follow-up (S17); the local mic dialog has a card button instead. */
    private val askToOpenCard: Boolean = true) {
    fun handle(action: String, raw: String?): GleoResponse {
        if (action !in ACTIONS) return GleoResponse(action, "fail", "This action is not supported.")
        val args = try { parseParameters(raw) } catch (_: Exception) {
            return GleoResponse(action, "fail", "The action parameters are invalid.")
        }
        return when (action) {
            EXPLAIN -> explain(args)
            OPEN -> open(args)
            ADJUST -> adjust(args)
            else -> error("Unsupported action")
        }
    }

    private fun parked(): Boolean {
        val s = app.signals.value
        return s.gear == "P" && s.speedKmh == 0.0 && s.bool(Signals.UXR_DO) != true
    }

    private fun answer(action: String, text: String, cardId: String? = null): GleoResponse {
        val data = JSONArray().put(JSONObject().put("text", text).put("cardId", cardId ?: JSONObject.NULL))
        return GleoResponse(action, "success", "Use the reviewed Korean guidance in the result to answer the user.",
            "voice", data)
    }

    private fun explain(args: JSONObject): GleoResponse {
        val topic = args.optString("topic").trim()
        if (topic in setOf("can_i_change_lane", "lane_change_permission") || (topic.contains("차선") && topic.contains("돼"))) {
            return answer(EXPLAIN, "미러와 사각지대 경고등을 확인하세요. 깜빡이 먼저.")
        }
        if (topic == "current_warning_light") {
            val s = app.signals.value
            val type = s.str(Signals.TT_RED) ?: s.str(Signals.TT_AMBER)
                ?: return answer(EXPLAIN, "차량에서 보고된 경고등이 없어요. 계기판 메시지도 확인해요.")
            val light = app.content.telltales[type]
            val text = if (light == null) "경고등이 켜졌어요. 계기판 메시지를 확인해요."
                else "${light.name}. ${light.action}"
            return answer(EXPLAIN, text)
        }
        val id = GuideTopics.cards[topic] ?: return askTopic(EXPLAIN)
        val card = app.content.card(id) ?: return GleoResponse(EXPLAIN, "fail", "This lesson is not available.")
        if (!parked()) return answer(EXPLAIN, card.headline, id)
        // Headlines are title phrases without a full stop; end each part so it reads as sentences.
        // Where the control is comes right after what it is: "how do I…" is usually "where is it".
        val text = listOfNotNull(card.headline, card.verify?.hint, card.why, card.how)
            .joinToString(" ") { it.trim().let { p -> if (p.last() in ".?!") p else "$p." } }
        return answer(EXPLAIN, if (askToOpenCard) "$text $CARD_OFFER" else text, id)
    }

    private fun open(args: JSONObject): GleoResponse {
        if (!parked()) return GleoResponse(OPEN, "fail", "It is available after parking.")
        val chapter = if (args.has("chapter")) (args.opt("chapter") as? Number)?.toDouble() else null
        if (args.has("chapter") && (chapter == null || chapter % 1 != 0.0 || app.content.chapter(chapter.toInt()) == null)) {
            return GleoResponse(OPEN, "asking", "Please choose a chapter from one to fourteen.")
        }
        val id = GuideTopics.cards[args.optString("topic").trim()]
        if (chapter != null) app.openChapter(chapter.toInt())
        else if (id != null) app.jumpCard(id)
        else return askTopic(OPEN)
        if (app.ui.value.screen != Screen.CARD) return GleoResponse(OPEN, "fail", "The lesson could not be opened.")
        // Gleo owns this spoken response; local microphone navigation keeps normal card reading.
        if (!readOpenedLesson) app.tutorial.stopReading()
        return GleoResponse(OPEN, "success", "The requested lesson is open.", "voice")
    }

    private fun adjust(args: JSONObject): GleoResponse = when (args.optString("command")) {
        "quiet_30min" -> {
            app.coach.quietForThirtyMinutes()
            GleoResponse(ADJUST, "success", "Information tips are muted for thirty minutes. Safety alerts remain enabled.")
        }
        "resume" -> {
            app.coach.resumeCoaching()
            GleoResponse(ADJUST, "success", "Coaching has resumed.")
        }
        "repeat_last" -> {
            val text = app.voice.lastText.value
            if (text == null) GleoResponse(ADJUST, "asking", "There is no previous guidance to repeat.")
            else answer(ADJUST, text)
        }
        "more_detail", "less_detail" -> {
            val current = app.store.settings.guideLevel.ordinal
            val step = if (args.getString("command") == "more_detail") -1 else 1
            app.store.settings = app.store.settings.copy(guideLevel = GuideLevel.entries[(current + step).coerceIn(0, 2)])
            GleoResponse(ADJUST, "success", "The coaching detail level has been adjusted.")
        }
        else -> GleoResponse(ADJUST, "asking", "Would you like to repeat, mute, resume, or adjust the guidance?")
    }

    private fun askTopic(action: String) = GleoResponse(action, "asking", "Which feature do you want to know about?")

    companion object {
        const val EXPLAIN = "EXPLAIN_CAR_FEATURE"
        const val OPEN = "OPEN_TUTORIAL_LESSON"
        const val ADJUST = "ADJUST_COACHING"
        const val CARD_OFFER = "카드로 볼까요?"
        val ACTIONS = setOf(EXPLAIN, OPEN, ADJUST)
        fun parseParameters(raw: String?): JSONObject {
            if (raw.isNullOrBlank()) return JSONObject()
            require(raw.length <= 16_384)
            // Accept the regular object and the JSON-string-wrapped example from Pleos docs.
            return when (val value = JSONTokener(raw).nextValue()) {
                is JSONObject -> value
                is String -> JSONObject(value)
                else -> throw IllegalArgumentException("Parameters must be an object")
            }
        }
    }
}
