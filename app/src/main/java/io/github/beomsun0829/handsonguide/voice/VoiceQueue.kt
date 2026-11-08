package io.github.beomsun0829.handsonguide.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Minimal speech engine contract so the queue can be tested without Android TTS. */
interface SpeechEngine {
    val ready: Boolean
    /** Speaks one sentence; [onDone] is called when it finishes or fails. */
    fun speak(id: String, text: String, guidance: Boolean, onDone: () -> Unit)
    fun speak(id: String, text: String, guidance: Boolean, priority: Int, onDone: () -> Unit) =
        speak(id, text, guidance, onDone)
    fun stop()
    fun setRate(rate: Float)
}

data class Utterance(
    val text: String,
    val priority: Int,
    /** Same key replaces a waiting utterance (latest wins). */
    val key: String,
    /** Coaching speech uses navigation-guidance audio; tutorial speech uses assistant audio. */
    val guidance: Boolean = true,
    val createdAt: Long = 0,
    val onStart: (() -> Unit)? = null,
    val onSentence: ((Int) -> Unit)? = null,
    val onDone: (() -> Unit)? = null,
)

enum class VoiceState { IDLE, SPEAKING, LISTENING }

/**
 * Spec §9.3: priority 1 (most urgent) … 5. Higher priority cuts the current utterance at the
 * next sentence boundary (priority 1 cuts immediately). Waiting low-priority speech (4–5) expires
 * after 4 s, identical text is not repeated within 30 s, and [mayStart] lets the coach hold speech
 * during silence windows (turning, intersections) without losing it.
 */
class VoiceQueue(
    private val scope: CoroutineScope,
    private val engine: SpeechEngine,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val waiting = ArrayList<Utterance>()
    private var current: Utterance? = null
    private var sentences: ArrayDeque<String> = ArrayDeque()
    private var sentenceIndex = 0
    private var cutAfterSentence = false
    private val recent = HashMap<String, Long>()
    private var loop: Job? = null
    private var lastEndedAt = 0L

    var enabled = true
    val available: Boolean get() = enabled && engine.ready
    /** Returns false while speech of this priority must wait (silence rules). */
    var mayStart: (priority: Int) -> Boolean = { true }

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state
    private val _lastText = MutableStateFlow<String?>(null)
    val lastText: StateFlow<String?> = _lastText

    /** Local recognition yields immediately to urgent vehicle guidance. */
    fun setListening(listening: Boolean) = synchronized(this) {
        if (current == null) _state.value = if (listening) VoiceState.LISTENING else VoiceState.IDLE
    }

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) { pump(); delay(100) }
        }
    }

    fun say(u: Utterance, force: Boolean = false): Boolean = synchronized(this) {
        if (!enabled || u.text.isBlank()) return false
        val now = clock()
        val stamped = if (u.createdAt == 0L) u.copy(createdAt = now) else u
        if (!force) recent[stamped.text]?.let { if (now - it < DEDUPE_MS) return false }
        waiting.removeAll { it.key == stamped.key }
        waiting.add(stamped)
        waiting.sortWith(compareBy({ it.priority }, { it.createdAt }))
        while (waiting.size > MAX_WAITING) waiting.removeAt(waiting.lastIndex)
        val cur = current
        if (cur != null && stamped.priority < cur.priority) {
            if (stamped.priority == 1) { interruptNow() } else cutAfterSentence = true
        }
        true
    }

    fun stop() { loop?.cancel(); loop = null; clear() }

    fun discard(predicate: (Utterance) -> Boolean) = synchronized(this) {
        waiting.removeAll(predicate)
        if (current?.let(predicate) == true) interruptNow()
    }

    /** Stops everything (user pressed next/back, or app left the screen). */
    fun clear(keepPriorityAtMost: Int = 0) = synchronized(this) {
        waiting.removeAll { it.priority > keepPriorityAtMost }
        if ((current?.priority ?: 0) > keepPriorityAtMost) interruptNow()
    }

    fun repeatLast() { _lastText.value?.let { say(Utterance(it, 3, "repeat"), force = true) } }

    /** Tutorial controls must not cancel an unrelated vehicle warning. */
    fun clearKey(key: String) = synchronized(this) {
        waiting.removeAll { it.key == key }
        if (current?.key == key) interruptNow()
    }

    private fun interruptNow() {
        // Invalidate before stop(): some engines synchronously invoke the old completion
        // callback from stop, which must not start the next sentence of cancelled guidance.
        val interrupted = current
        current = null
        sentences.clear()
        cutAfterSentence = false
        lastEndedAt = clock()
        _state.value = VoiceState.IDLE
        engine.stop()
        interrupted?.onDone?.invoke()
    }

    private fun finishCurrent() {
        val done = current
        current = null
        cutAfterSentence = false
        lastEndedAt = clock()
        _state.value = VoiceState.IDLE
        done?.onDone?.invoke()
    }

    internal fun pump() = synchronized(this) {
        val now = clock()
        waiting.removeAll { it.priority >= 4 && now - it.createdAt > EXPIRE_MS }
        if (current != null || !engine.ready) return
        val next = waiting.firstOrNull() ?: return
        if (_state.value == VoiceState.LISTENING && next.priority > 2) return
        if (!mayStart(next.priority)) return
        if (next.priority > 2 && now - lastEndedAt < GAP_MS) return
        waiting.remove(next)
        current = next
        recent[next.text] = now
        recent.entries.removeAll { now - it.value > DEDUPE_MS }
        sentences = ArrayDeque(splitSentences(next.text))
        sentenceIndex = 0
        _lastText.value = next.text
        _state.value = VoiceState.SPEAKING
        next.onStart?.invoke()
        speakNextSentence(next)
    }

    private fun speakNextSentence(u: Utterance) {
        val s = sentences.removeFirstOrNull()
        if (s == null || current !== u) { if (current === u) finishCurrent(); return }
        u.onSentence?.invoke(sentenceIndex++)
        if (current !== u) return
        engine.speak(u.key, s, u.guidance, u.priority) {
            synchronized(this) {
                if (current !== u) return@synchronized
                if (cutAfterSentence) { sentences.clear(); finishCurrent() } else speakNextSentence(u)
            }
        }
    }

    companion object {
        const val MAX_WAITING = 3
        const val EXPIRE_MS = 4000L
        const val DEDUPE_MS = 30_000L
        /** Quiet gap after an utterance for priorities 3–5 (spec §9.4). */
        const val GAP_MS = 3000L

        fun splitSentences(text: String): List<String> =
            text.split(Regex("(?<=[.!?。])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
