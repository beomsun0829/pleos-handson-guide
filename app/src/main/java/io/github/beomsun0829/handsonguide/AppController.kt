package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.coach.CoachEngine
import io.github.beomsun0829.handsonguide.content.Content
import io.github.beomsun0829.handsonguide.content.Guide
import io.github.beomsun0829.handsonguide.content.Playlist
import io.github.beomsun0829.handsonguide.signal.SignalHub
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.store.Store
import io.github.beomsun0829.handsonguide.tutorial.TutorialEngine
import io.github.beomsun0829.handsonguide.tutorial.VerifyState
import io.github.beomsun0829.handsonguide.voice.Utterance
import io.github.beomsun0829.handsonguide.voice.VoiceQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import io.github.beomsun0829.handsonguide.store.GuideLevel
import io.github.beomsun0829.handsonguide.home.HomeWarning
import io.github.beomsun0829.handsonguide.home.HomeWarnings

enum class Screen { BOOT, ONBOARDING, HOME, CHAPTERS, CARD, CHAPTER_END, DRIVE, REVERSE, SETTINGS, DEV }

data class AppUi(
    val screen: Screen = Screen.BOOT,
    val bootSlow: Boolean = false,
    val homeWarnings: List<HomeWarning> = emptyList(),
)

/**
 * App mode state machine (spec §3.3). Gear and UX restrictions decide between the parked
 * screens and the drive screen; the parked screens stack is a single "current screen" plus
 * Back going home.
 */
class AppController(
    private val scope: CoroutineScope,
    val content: Content,
    val store: Store,
    val hub: SignalHub,
    val voice: VoiceQueue,
    val tutorial: TutorialEngine,
    val coach: CoachEngine,
    val guide: Guide = Guide.EMPTY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _ui = MutableStateFlow(AppUi())
    private val homeWarnings = HomeWarnings()
    val ui: StateFlow<AppUi> = _ui
    val signals: StateFlow<Signals> get() = hub.signals

    private var bootAt = clock()
    private val jobs = ArrayList<Job>()

    fun start() {
        if (jobs.any { it.isActive }) return
        voice.mayStart = coach::mayStart
        jobs += scope.launch {
            hub.signals.collect { s -> onSignals(s) }
        }
        jobs += scope.launch {
            delay(BOOT_SLOW_MS)
            if (_ui.value.screen == Screen.BOOT) update { it.copy(bootSlow = true) }
        }
        jobs += scope.launch {
            tutorial.ui.collect { t ->
                // No auto-advance: the document page keeps every section in place while it is read.
                // Finishing a chapter goes back to the chapter list; no interstitial, no jump to the next chapter.
                if (t.finishedChapter != null && _ui.value.screen == Screen.CARD) { tutorial.closeFinished(); show(Screen.HOME) }
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }; jobs.clear()
        if (tutorial.active) tutorial.pause()
        coach.endDrive()
        bootAt = clock()
        _ui.value = AppUi()
    }

    /** Hidden: practice and voice stop, but the page stays, so the driver returns to where they were (Pleos review A-1). */
    fun onBackground() {
        tutorial.background()
    }

    fun onForeground() {
        tutorial.foreground()
    }

    private fun startCoachDrive() {
        val wasDriving = coach.driving
        coach.startDrive(signals.value)
        if (!wasDriving && !store.firstDriveIntroduced && store.settings.coachingOn && !coach.isQuiet) {
            voice.say(Utterance("주행 중엔 필요한 순간만 짧게 알려드려요. Gleo에게 ‘운전 안내 조용히 해줘’라고 말하면 조용히 할게요.",
                4, "drive-introduction", onStart = { store.firstDriveIntroduced = true }))
        }
    }

    private fun onSignals(s: Signals) {
        val warnings = homeWarnings.update(s)
        if (_ui.value.homeWarnings != warnings) update { it.copy(homeWarnings=warnings) }
        coach.observeDistance(s)
        val screen = _ui.value.screen
        if (screen == Screen.BOOT) {
            val connected = s.bool(Signals.VEHICLE_CONNECTED) == true || s.has(Signals.GEAR)
            if (connected || clock() - bootAt > BOOT_GIVE_UP_MS) leaveBoot(s)
            return
        }
        val gear = s.gear
        val restricted = s.bool(Signals.UXR_DO) == true
        val mustDrive = restricted || gear == "D" || gear == "N" || s.speedKmh > 0
        val reversing = gear == "R"
        val tutorialHoldsGear = tutorial.allowsPractice(s)
        when {
            reversing && !tutorialHoldsGear -> if (screen != Screen.REVERSE) enterDriving(Screen.REVERSE)
            mustDrive && !tutorialHoldsGear -> if (screen != Screen.DRIVE) enterDriving(Screen.DRIVE)
            gear == "P" && !restricted && s.speedKmh == 0.0 && (screen == Screen.DRIVE || screen == Screen.REVERSE) -> leaveDriving()
        }
        // While away from a card page: did the car move, and did a gear card see D with the brake held?
        val left = leftCard
        if (left != null && _ui.value.screen in setOf(Screen.DRIVE, Screen.REVERSE)) {
            if (s.speedKmh > 0) movedSinceLeft = true
            if (s.gear == "D" && s.bool(Signals.BRAKE) == true && content.card(left)?.practice == io.github.beomsun0829.handsonguide.content.Practice.GEAR)
                gearPracticeSawDrive = true
        }
        // Pause before evaluating checks so a moving vehicle cannot complete a parked card.
        if (tutorial.active) tutorial.onSignals(s)
        coach.onSignals(s)
    }

    private fun leaveBoot(s: Signals) {
        val next = when {
            s.gear == "R" -> Screen.REVERSE
            s.bool(Signals.UXR_DO) == true || s.gear in setOf("D", "N") || s.speedKmh > 0 -> Screen.DRIVE
            // The home's "이 차가 처음이라면" card replaces the first-run question.
            else -> { store.onboarded = true; Screen.HOME }
        }
        if (next == Screen.DRIVE || next == Screen.REVERSE) startCoachDrive()
        show(next)
    }

    /** The card page left for the driving screen, while the car has not moved: P brings the driver back to it. */
    private var leftCard: String? = null
    private var movedSinceLeft = false
    private var gearPracticeSawDrive = false

    private fun enterDriving(target: Screen) {
        leftCard = tutorial.ui.value.card?.id?.takeIf { _ui.value.screen == Screen.CARD }
        movedSinceLeft = false; gearPracticeSawDrive = false
        if (tutorial.active) tutorial.pause()
        voice.clear(keepPriorityAtMost = 2)
        startCoachDrive()
        update { it.copy(screen = target) }
    }

    private fun leaveDriving() {
        val back = leftCard?.takeIf { !movedSinceLeft }
        leftCard = null
        if (back != null) {
            // Only shifted (D or N and back to P without moving): no drive to review, return to the page.
            coach.endDrive()
            guide.playlistFor(back)?.let { openPlaylist(it, back) } ?: openCards(listOf(back))
            if (gearPracticeSawDrive) tutorial.confirmFromDrive()
            return
        }
        // No look-back after a drive: nothing is kept, so parking simply returns home.
        coach.endDrive()
        show(Screen.HOME)
    }

    // ---- Parked navigation ----

    fun setWarningCollapsed(warning: HomeWarning, collapsed: Boolean) {
        val s = signals.value
        if (s.gear != "P" || s.num(Signals.SPEED) != 0.0 || s.bool(Signals.UXR_DO) == true) return
        homeWarnings.setCollapsed(warning,collapsed)?.let { warnings -> update { it.copy(homeWarnings=warnings) } }
    }

    private fun canOpenParked(): Boolean {
        val s = signals.value
        return s.bool(Signals.UXR_DO) != true && s.gear !in setOf("D", "R", "N") && s.speedKmh == 0.0
    }

    fun show(screen: Screen) {
        if (screen !in setOf(Screen.BOOT, Screen.DRIVE, Screen.REVERSE) && !canOpenParked()) return
        update { it.copy(screen = screen) }
    }

    fun back() {
        when (_ui.value.screen) {
            Screen.DRIVE, Screen.REVERSE, Screen.BOOT -> Unit
            Screen.CARD -> { tutorial.pause(); show(Screen.HOME) }
            Screen.CHAPTER_END -> { tutorial.closeFinished(); show(Screen.HOME) }
            else -> show(Screen.HOME)
        }
    }

    fun answerOnboarding(experienced: Boolean) {
        if (!canOpenParked()) return
        store.experienced = experienced
        store.onboarded = true
        show(Screen.HOME)
        store.settings = store.settings.copy(guideLevel = if (experienced) GuideLevel.FAMILIAR else GuideLevel.BEGINNER)
        voice.say(Utterance("주행 중엔 필요한 순간에 짧게 알려드려요. 궁금한 건 Gleo에게 ‘김서림 어떻게 해?’처럼 물어보세요.", 3, "onboarding", guidance = false))
    }

    fun openChapter(n: Int) {
        if (!canOpenParked() || content.cardsOf(n).isEmpty()) return
        tutorial.onSignals(signals.value)
        tutorial.startChapter(n)
        show(Screen.CARD)
    }
    fun openPlaylist(p: Playlist, startAt: String? = null) {
        if (!canOpenParked() || p.cards.none { content.card(it) != null }) return
        tutorial.onSignals(signals.value)
        tutorial.startPlaylist(p.title, p.cards, startAt)
        show(Screen.CARD)
    }

    fun openCards(ids: List<String>) {
        if (!canOpenParked() || ids.none { content.card(it) != null }) return
        // One card opens inside its group so 이전/다음 make sense.
        ids.singleOrNull()?.let { jumpCard(it); return }
        tutorial.onSignals(signals.value)
        tutorial.startCards(ids)
        show(Screen.CARD)
    }

    fun resumePaused() {
        if (!canOpenParked()) return
        val id = store.pausedCard
        val card = id?.let { content.card(it) }
        when {
            card == null -> show(Screen.HOME)
            else -> openChapter(card.chapter)
        }
    }

    fun chapterEndNext() {
        if (!canOpenParked()) return
        val finished = tutorial.ui.value.finishedChapter
        tutorial.closeFinished()
        val next = content.chapters.firstOrNull { it.number > (finished ?: Int.MAX_VALUE) }
        if (next != null) openChapter(next.number) else show(Screen.HOME)
    }

    fun continueMore() {
        if (!canOpenParked()) return
        tutorial.continueMore()
        if (tutorial.active) show(Screen.CARD)
    }

    fun jumpCard(id: String) {
        if (!canOpenParked()) return
        // Any card id (also an absorbed one) opens its topic inside its group.
        guide.playlistFor(id)?.let { p ->
            if (tutorial.active && p.cards.any { it == tutorial.ui.value.card?.id } && guide.topic(id) in tutorial.playlistCards.map { it.id }) {
                tutorial.jumpWithin(guide.topic(id)!!); show(Screen.CARD)
            } else openPlaylist(p, guide.topic(id))
            return
        }
        tutorial.jumpTo(id)
        if (tutorial.active) show(Screen.CARD)
    }

    private inline fun update(f: (AppUi) -> AppUi) { _ui.value = f(_ui.value) }

    companion object {
        const val BOOT_SLOW_MS = 10_000L
        const val BOOT_GIVE_UP_MS = 3_000L
    }
}
