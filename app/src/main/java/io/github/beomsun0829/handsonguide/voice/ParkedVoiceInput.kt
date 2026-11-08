package io.github.beomsun0829.handsonguide.voice

import io.github.beomsun0829.handsonguide.AppController
import io.github.beomsun0829.handsonguide.Screen
import io.github.beomsun0829.handsonguide.gleo.GleoActions
import io.github.beomsun0829.handsonguide.signal.Signals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject

data class VoiceInputUi(val listening: Boolean = false, val transcript: String = "",
    val answer: String? = null, val cardId: String? = null)

/** All methods and recognition callbacks run on the same dispatcher as vehicle decisions. */
class ParkedVoiceInput(private val app: AppController, private val scope: CoroutineScope,
    private val recognizer: RecognitionEngine) {
    private val _ui = MutableStateFlow(VoiceInputUi())
    val ui: StateFlow<VoiceInputUi> = _ui
    private var foreground = false
    private var token = 0L
    private var watchdog: Job? = null
    private var expiry: Job? = null
    private var observer: Job? = null
    private val actions = GleoActions(app, readOpenedLesson = true, askToOpenCard = false)

    fun start() {
        if (observer != null) return
        observer = scope.launch {
            combine(app.signals, app.ui, app.voice.state) { _, _, voice -> voice }.collect { voice ->
                if (!canListen() || (_ui.value.listening && voice == VoiceState.SPEAKING)) dismiss()
            }
        }
    }

    fun setForeground(value: Boolean) { foreground = value; if (!value) dismiss() }
    fun stop() { dismiss(); observer?.cancel(); observer = null }

    /** Gleo speaks the answer; only show its reviewed text/link while this UI is parked. */
    fun presentExternalAnswer(text: String, cardId: String?) {
        if (!canListen() || text.isBlank()) return
        dismiss()
        showAnswer(text, cardId, speak = false)
    }

    private fun canListen(): Boolean {
        val s = app.signals.value
        return foreground && s.gear == "P" && s.num(Signals.SPEED) == 0.0 &&
            s.bool(Signals.UXR_DO) != true && s.bool(Signals.CALL_ACTIVE) != true &&
            s.bool(Signals.GLEO_SPEAKING) != true && app.ui.value.screen in setOf(Screen.HOME, Screen.CARD)
    }

    fun listen() {
        if (!canListen()) return
        dismiss()
        app.tutorial.stopReading()
        app.voice.clear(keepPriorityAtMost = 2)
        if (app.voice.state.value == VoiceState.SPEAKING) {
            showAnswer("안전 안내가 끝나면 다시 눌러주세요.", speak = false)
            return
        }
        val session = ++token
        _ui.value = VoiceInputUi(listening = true)
        app.voice.setListening(true)
        watchdog = scope.launch { delay(20_000); if (session == token) fail("잘 듣지 못했어요. 마이크를 눌러 다시 말해주세요.") }
        recognizer.start(onText = { text, final ->
            if (session == token && canListen() && _ui.value.listening) {
                _ui.value = _ui.value.copy(transcript = text.take(500))
                if (final) {
                    endListening()
                    resolve(text.take(500))
                }
            }
        }, onError = { message -> if (session == token) fail(message) })
    }

    private fun endListening() {
        ++token
        watchdog?.cancel(); watchdog = null
        recognizer.stop()
        app.voice.setListening(false)
        _ui.value = _ui.value.copy(listening = false)
    }

    private fun fail(message: String) { endListening(); if (canListen()) showAnswer(message, speak = false) }

    fun dismiss() {
        // Change UI first: StateFlow collectors can run immediately in tests and callbacks.
        _ui.value = VoiceInputUi()
        endListening()
        expiry?.cancel(); expiry = null
        app.voice.clearKey(ANSWER_KEY)
    }

    fun openCard() {
        if (!canListen()) return
        val id = _ui.value.cardId ?: return
        dismiss()
        app.jumpCard(id)
    }

    /** Debug and tests: answer [text] as if it had been heard. */
    fun ask(text: String) { if (canListen()) resolve(text) }

    private fun resolve(text: String) {
        if (!canListen()) return
        val intent = VoiceIntent.parse(text)
        when (intent.kind) {
            VoiceIntent.Kind.HOWTO, VoiceIntent.Kind.TELLTALE, VoiceIntent.Kind.LANE_PERMISSION -> {
                val topic = when (intent.kind) {
                    VoiceIntent.Kind.TELLTALE -> "current_warning_light"
                    VoiceIntent.Kind.LANE_PERMISSION -> "can_i_change_lane"
                    else -> intent.topic!!
                }
                val result = actions.handle(GleoActions.EXPLAIN, JSONObject().put("topic", topic).toString())
                val answer = result.data.optJSONObject(0)
                if (answer == null) showAnswer("차량에서 보고된 경고등이 없어요. 계기판 메시지도 확인해요.")
                else showAnswer(answer.getString("text"), answer.optString("cardId").takeIf { it != "null" && it.isNotBlank() })
            }
            VoiceIntent.Kind.OPEN -> {
                val result = actions.handle(GleoActions.OPEN, JSONObject().put("topic", intent.topic).toString())
                if (result.status != "success") showAnswer("카드를 열 수 없어요. 홈에서 골라주세요.")
            }
            VoiceIntent.Kind.STATE -> showAnswer(stateAnswer(intent.topic!!))
            VoiceIntent.Kind.ROUTE_RULE -> showAnswer("표지판의 아래 조건을 확인하세요.")
            VoiceIntent.Kind.HELP -> showAnswer("지금은 주차 중이에요. 도움이 필요한 기능을 말하거나 홈에서 카드를 골라주세요.")
            VoiceIntent.Kind.QUIET, VoiceIntent.Kind.RESUME, VoiceIntent.Kind.REPEAT -> {
                val command = when (intent.kind) {
                    VoiceIntent.Kind.QUIET -> "quiet_30min"
                    VoiceIntent.Kind.RESUME -> "resume"
                    else -> "repeat_last"
                }
                val result = actions.handle(GleoActions.ADJUST, JSONObject().put("command", command).toString())
                val answer = result.data.optJSONObject(0)?.optString("text")
                if (answer != null) showAnswer(answer)
                else showAnswer(when (intent.kind) {
                    VoiceIntent.Kind.QUIET -> "30분 동안 정보 팁은 조용히 할게요. 안전 안내는 계속해요."
                    VoiceIntent.Kind.RESUME -> "다시 알려드릴게요."
                    else -> "다시 들을 안내가 아직 없어요."
                })
            }
            VoiceIntent.Kind.UNKNOWN -> showAnswer("어떤 기능이 궁금하세요?")
        }
    }

    private fun stateAnswer(topic: String): String {
        val s = app.signals.value
        if (topic == "recirculation") return when (s.bool(Signals.RECIRC)) {
            true -> "지금은 내기 순환이에요."
            false -> "지금은 외기 유입이에요."
            null -> "지금 공기 순환 상태를 확인할 수 없어요. 공조 화면을 확인해요."
        }
        val item = when (topic) {
            "defog_front" -> Signals.DEFROST_FRONT to "앞유리 김서림 제거"
            "defog_rear" -> Signals.DEFROST_REAR to "뒷유리 열선"
            "air_conditioner" -> Signals.AC to "에어컨"
            "headlights" -> Signals.LOW_BEAM to "하향 전조등"
            "high_beam" -> Signals.HIGH_BEAM to "상향등"
            "hazard_lights" -> Signals.HAZARD to "비상등"
            "wipers" -> Signals.WIPER_ON to "와이퍼"
            "parking_brake" -> Signals.PARKING_BRAKE to "주차 브레이크"
            else -> return "이 기능의 현재 상태를 확인할 수 없어요. 차량 화면을 확인해요."
        }
        return when (s.bool(item.first)) {
            true -> "지금 ${item.second} 기능이 켜져 있어요."
            false -> "지금 ${item.second} 기능이 꺼져 있어요."
            null -> "지금 ${item.second} 상태를 확인할 수 없어요. 차량 화면을 확인해요."
        }
    }

    private fun showAnswer(text: String, card: String? = null, speak: Boolean = true) {
        _ui.value = _ui.value.copy(listening = false, answer = text, cardId = card)
        if (speak) app.voice.say(Utterance(text, 3, ANSWER_KEY, guidance = false), force = true)
        expiry?.cancel()
        expiry = scope.launch { delay(12_000); _ui.value = VoiceInputUi() }
    }

    companion object { private const val ANSWER_KEY = "local-answer" }
}
