package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.coach.CoachEngine
import io.github.beomsun0829.handsonguide.content.Content
import io.github.beomsun0829.handsonguide.signal.SignalHub
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.store.MemoryStore
import io.github.beomsun0829.handsonguide.tutorial.TutorialEngine
import io.github.beomsun0829.handsonguide.voice.SpeechEngine
import io.github.beomsun0829.handsonguide.voice.VoiceQueue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal class FakeSpeech : SpeechEngine {
    override var ready = true
    val spoken = ArrayList<String>()
    var stopped = 0
    private var done: (() -> Unit)? = null
    override fun speak(id: String, text: String, guidance: Boolean, onDone: () -> Unit) { spoken += text; done = onDone }
    fun complete() { val callback = done; done = null; callback?.invoke() }
    override fun stop() { stopped++; complete() }
    override fun setRate(rate: Float) = Unit
}

internal class EngineFixture(ids: Set<String>? = null) : AutoCloseable {
    var now = 10_000L
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private fun asset(name: String) = File("src/main/assets/content/$name.json").readText()
    private val full = Content.parse(asset("cards"), asset("situations"), asset("telltales"))
    val content = if (ids == null) full else Content(full.chapters, full.cards, full.situations.filter { it.id in ids }, full.telltales)
    val store = MemoryStore().apply { onboarded = true; firstDriveIntroduced = true }
    val hub = SignalHub(scope) { now }
    val speech = FakeSpeech()
    val voice = VoiceQueue(scope, speech) { now }
    val tutorial = TutorialEngine(content, store, voice) { now }
    val coach = CoachEngine(content, store, voice) { now }
    val guide = io.github.beomsun0829.handsonguide.content.Guide.parse(asset("guide"))
    val app = AppController(scope, content, store, hub, voice, tutorial, coach, guide) { now }
    init {
        app.start()
        emit(0, Signals.GEAR to "P", Signals.SPEED to 0, Signals.BELT to true, Signals.VEHICLE_CONNECTED to true)
    }
    fun emit(ms: Long = 400, vararg pairs: Pair<String, Any?>) {
        now += ms
        pairs.forEach { hub.put(it.first, it.second) }
        hub.emitIfNeeded()
    }
    fun drive() { emit(400, Signals.GEAR to "D") }
    override fun close() { scope.cancel() }
}
