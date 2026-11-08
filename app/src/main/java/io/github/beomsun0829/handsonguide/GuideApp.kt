package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.coach.CoachEngine
import io.github.beomsun0829.handsonguide.content.Content
import io.github.beomsun0829.handsonguide.content.Guide
import io.github.beomsun0829.handsonguide.demo.Scenario
import io.github.beomsun0829.handsonguide.demo.ScenarioPlayer
import io.github.beomsun0829.handsonguide.signal.SignalHub
import io.github.beomsun0829.handsonguide.signal.VehicleSource
import io.github.beomsun0829.handsonguide.signal.NavigationSource
import io.github.beomsun0829.handsonguide.store.FileStore
import io.github.beomsun0829.handsonguide.tutorial.TutorialEngine
import io.github.beomsun0829.handsonguide.voice.PleosSpeech
import io.github.beomsun0829.handsonguide.voice.PleosRecognition
import io.github.beomsun0829.handsonguide.voice.ParkedVoiceInput
import io.github.beomsun0829.handsonguide.voice.VoiceQueue
import io.github.beomsun0829.handsonguide.voice.Utterance
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

/** Process-wide singletons. The guide keeps coaching while the screen shows another app. */
open class GuideApp : Application() {
    lateinit var controller: AppController
        private set
    lateinit var scenarios: List<Scenario>
        private set
    lateinit var player: ScenarioPlayer
        private set
    lateinit var speech: PleosSpeech
        private set
    lateinit var voiceInput: ParkedVoiceInput
        private set
    private lateinit var store: FileStore
    private lateinit var storageReady: Deferred<Unit>
    val storageState get() = store.state
    private var sourceJob: Job? = null
    private var navigationJob: Job? = null
    private var persistJob: Job? = null
    @Volatile var runtimeStarted = false
        private set

    @OptIn(ExperimentalCoroutinesApi::class)
    private val engine = Dispatchers.Default.limitedParallelism(1)
    val scope = CoroutineScope(SupervisorJob() + engine)

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        fun asset(path: String) = assets.open(path).bufferedReader().use { it.readText() }
        val content = Content.parse(asset("content/cards.json"), asset("content/situations.json"), asset("content/telltales.json"))
        store = FileStore.create(this)
        val hub = SignalHub(scope)
        speech = PleosSpeech(this, scope)
        val voice = VoiceQueue(scope, speech)
        val tutorial = TutorialEngine(content, store, voice)
        val coach = CoachEngine(content, store, voice)
        val guide = Guide.parse(asset("content/guide.json"))
        controller = AppController(scope, content, store, hub, voice, tutorial, coach, guide)
        voiceInput = ParkedVoiceInput(controller, scope, PleosRecognition(this, scope))
        scenarios = assets.list("demo").orEmpty().filter { it.endsWith(".json") }.sorted().map { ScenarioPlayer.parse(asset("demo/$it")) }
        player = ScenarioPlayer(scope, hub, onStopped = { controller.voice.clearKey("demo-narration") }) { action -> handleDemoAction(action) }

        voice.enabled = store.settings.voiceOn
        speech.setRate(store.settings.speechRate)
        storageReady = scope.async {
            while (true) {
                try { store.initialize(); coach.synchronizeLevels(); break }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    android.util.Log.w("GuideStore","Waiting to load saved guide state",e)
                    delay(1500)
                }
            }
        }
        // Settings also change through record reset and future storage migrations.
        // Keep audio synchronized even when no settings-screen callback ran.
        scope.launch {
            store.version.map { store.settings.voiceOn to store.settings.speechRate }
                .distinctUntilChanged().collect { (enabled, rate) ->
                    voice.enabled = enabled
                    speech.setRate(rate)
                }
        }
        scope.launch {
            awaitReady()
            while (true) {
                delay(5000)
                if (storageState.value == io.github.beomsun0829.handsonguide.store.StoreState.WRITE_ERROR) store.persist()
            }
        }
    }

    /** GuideService owns live work; Application only constructs the shared model. */
    @OptIn(FlowPreview::class)
    fun startRuntime() { scope.launch {
        awaitReady()
        if (runtimeStarted) return@launch
        runtimeStarted = true
        controller.hub.start()
        controller.voice.start()
        controller.start()
        voiceInput.start()
        sourceJob = scope.launch(Dispatchers.IO) {
            val source = VehicleSource(this@GuideApp, controller.hub, scope) {
                val s = controller.signals.value
                controller.ui.value.screen in RELAXED_SCREENS && s.gear == "P" && s.num(io.github.beomsun0829.handsonguide.signal.Signals.SPEED) == 0.0
            }
            try { source.start(); awaitCancellation() } finally { source.stop() }
        }
        navigationJob = scope.launch(Dispatchers.IO) {
            val source = NavigationSource(this@GuideApp, controller.hub)
            try { source.start(); awaitCancellation() } finally { source.stop() }
        }
        persistJob = scope.launch { store.version.debounce(500).collect { store.persist() } }
    } }

    fun stopRuntime() { scope.launch {
        awaitReady()
        voiceInput.stop()
        controller.stop()
        player.stop()
        controller.voice.stop()
        controller.hub.stop()
        sourceJob?.cancel(); sourceJob = null
        navigationJob?.cancel(); navigationJob = null
        persistJob?.cancel(); persistJob = null
        store.persist()
        runtimeStarted = false
    } }

    suspend fun awaitReady() { storageReady.await() }
    suspend fun persistNow() { awaitReady(); store.persist() }

    private fun handleDemoAction(action: String) {
        val (verb, arg) = action.split(":", limit = 2).let { it[0] to it.getOrNull(1) }
        when (verb) {
            "say" -> arg?.let { controller.voice.say(Utterance(it, 5, "demo-narration", guidance = controller.signals.value.gear != "P"), force = true) }
            "openCard" -> arg?.let { controller.openCards(listOf(it)) }
            "openChapter" -> arg?.toIntOrNull()?.let { controller.openChapter(it) }
            "home" -> controller.show(Screen.HOME)
            "next" -> controller.tutorial.next()
        }
    }
}

/** Parked on these, the car is polled at a calmer pace (VehicleSource). */
private val RELAXED_SCREENS = setOf(Screen.HOME, Screen.CHAPTERS, Screen.SETTINGS)
