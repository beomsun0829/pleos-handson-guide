package io.github.beomsun0829.handsonguide.tutorial

import io.github.beomsun0829.handsonguide.content.Card
import io.github.beomsun0829.handsonguide.content.ConditionTracker
import io.github.beomsun0829.handsonguide.content.Content
import io.github.beomsun0829.handsonguide.content.Practice
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.store.CardResult
import io.github.beomsun0829.handsonguide.store.Store
import io.github.beomsun0829.handsonguide.voice.Utterance
import io.github.beomsun0829.handsonguide.voice.VoiceQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class VerifyState { NONE, IDLE, WAITING, HINT, GIVE_UP_OFFER, CONFIRMED, RESTORE, DONE, UNSUPPORTED, SKIPPED, NOT_CONNECTED }
data class SceneNarration(val run: Int, val sentence: Int = -1, val finished: Boolean = false)

data class TutorialUi(
    val card: Card? = null,
    val index: Int = 0,
    val total: Int = 0,
    val stageLabel: String = "",
    val verify: VerifyState = VerifyState.NONE,
    /** Completed steps when the check is a sequence ("P ✓ R ✓ …"). */
    val sequenceDone: Int = 0,
    val alreadyMet: Boolean = false,
    val message: String? = null,
    /** Set when the playlist ends; the UI shows the chapter-end screen. */
    val finishedChapter: Int? = null,
    val finishedSummary: List<String> = emptyList(),
    val basicFinished: Boolean = false,
    val moreCount: Int = 0,
    val needsSpaceConfirmation: Boolean = false,
    val practicePaused: Boolean = false,
    val compact: Boolean = false,
    val allBasicsJustCompleted: Boolean = false,
    val narration: SceneNarration? = null,
    val signalPending: Boolean = false,
    val powerRequired: Boolean = false,
    /** Name of the playlist being played ("출발 전 꼭 알아둘 8가지", a hotspot or road group). */
    val playlistTitle: String = "",
)

/**
 * Plays a list of cards (spec §7.3). The engine never touches vehicle settings: every check
 * waits for the driver's own action and reads the result from [Signals].
 */
class TutorialEngine(
    private val content: Content,
    private val store: Store,
    private val voice: VoiceQueue,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _ui = MutableStateFlow(TutorialUi())
    val ui: StateFlow<TutorialUi> = _ui

    private var playlist: List<Card> = emptyList()
    private var playlistTitle = ""
    val playlistCards: List<Card> get() = playlist
    private var tracker: ConditionTracker? = null
    private var restoreTracker: ConditionTracker? = null
    private var waitingSince = 0L
    private var enteredAt = 0L
    private var speechDone = false
    private var last: Signals = Signals.EMPTY
    private var continuationIndex: Int? = null
    private var awaitingToggleOff = false
    private var spaceConfirmed = false
    private var speechGeneration = 0
    private var reconnectState = VerifyState.IDLE
    private var powerNoticeSpoken = false
    var isEv = false

    val active: Boolean get() = _ui.value.card != null
    val chapterCards: List<Card> get() = _ui.value.card?.chapter?.let { n ->
        content.cardsOf(n).filter { !it.ev || isEv }
    } ?: emptyList()

    /** Move to a card already in the current playlist. */
    fun jumpWithin(id: String) {
        val i = playlist.indexOfFirst { it.id == id }
        if (i >= 0) open(i)
    }

    fun jumpTo(id: String) {
        val card = content.card(id) ?: return
        startChapter(card.chapter, id)
    }

    fun continueMore() {
        val index = continuationIndex ?: return
        continuationIndex = null
        open(index)
    }

    fun setReadAloud(enabled: Boolean) {
        store.settings = store.settings.copy(tutorialReadAloud = enabled)
        voice.clearKey("tutorial")
        if (enabled) replay() else { speechGeneration++; speechDone = true; update { it.copy(narration=null) } }
    }

    fun alreadyAdjusted() {
        val card = _ui.value.card ?: return
        if (card.verify?.allowAlreadyAdjusted != true || _ui.value.compact) return
        store.setCardResult(card.id, CardResult.READ)
        voice.clearKey("tutorial")
        update { it.copy(verify = VerifyState.DONE, message = "이미 맞췄어요") }
    }

    fun confirmSpace() {
        spaceConfirmed = true
        update { it.copy(needsSpaceConfirmation = false) }
        tracker?.reset()
        tracker?.update(last)
        _ui.value.card?.let(::speakCard)
    }

    fun readOnlyPractice() {
        val card = _ui.value.card ?: return
        store.setCardResult(card.id, CardResult.READ)
        voice.clearKey("tutorial")
        update { it.copy(needsSpaceConfirmation = false, verify = VerifyState.DONE) }
    }

    /** Gear cards stay visible at low speed, with all controls removed while moving. */
    fun allowsPractice(s: Signals): Boolean {
        val card = _ui.value.card ?: return false
        if (card.practice == Practice.NONE || s.bool(Signals.UXR_DO) == true || s.speedKmh > 8) return false
        return card.practice !in setOf(Practice.CREEP, Practice.REVERSE) || spaceConfirmed
    }

    /** One chapter: stage 1 then 2 then 3 cards. Starts at the first card not yet done. */
    fun startChapter(chapter: Int, fromCard: String? = null) {
        val list = content.cardsOf(chapter).filter { !it.ev || isEv }
        val start = chapterStart(list, store, fromCard)
        playlistTitle = content.chapter(chapter)?.title.orEmpty()
        play(list, start)
    }

    fun startCards(ids: List<String>) {
        val list = ids.mapNotNull { content.card(it) }
        if (list.isEmpty()) return
        playlistTitle = ""
        play(list, 0)
    }

    /** A named playlist, starting at [startAt] when it is in the list. */
    fun startPlaylist(title: String, ids: List<String>, startAt: String? = null) {
        val list = ids.mapNotNull { content.card(it) }.filter { !it.ev || isEv }
        if (list.isEmpty()) return
        playlistTitle = title
        play(list, list.indexOfFirst { it.id == startAt }.coerceAtLeast(0))
    }

    private fun play(list: List<Card>, start: Int) {
        if (list.isEmpty()) return
        playlist = list
        continuationIndex = null
        open(start)
    }

    fun next() {
        val ui = _ui.value
        val card = ui.card ?: return
        if (ui.verify !in setOf(VerifyState.CONFIRMED, VerifyState.DONE, VerifyState.UNSUPPORTED, VerifyState.NONE, VerifyState.RESTORE)) {
            store.setCardResult(card.id, CardResult.SKIPPED)
        }
        if (playlist.getOrNull(ui.index + 1) != null) open(ui.index + 1) else finish()
    }

    /** "마치기" on the last basic card: the chapter is done for now, the rest stays in its tabs. */
    fun finishChapter() {
        val ui = _ui.value
        val card = ui.card ?: return
        if (ui.verify !in setOf(VerifyState.CONFIRMED, VerifyState.DONE, VerifyState.UNSUPPORTED, VerifyState.NONE, VerifyState.RESTORE)) {
            store.setCardResult(card.id, CardResult.SKIPPED)
        }
        if (playlist.getOrNull(ui.index + 1) != null) continuationIndex = ui.index + 1
        finish(basic = card.stage == 1)
    }

    fun prev() {
        val ui = _ui.value
        if (ui.card != null && ui.index > 0) open(ui.index - 1)
    }

    fun skip() {
        val card = _ui.value.card ?: return
        store.setCardResult(card.id, CardResult.SKIPPED)
        voice.clearKey("tutorial")
        update { it.copy(verify = VerifyState.SKIPPED) }
    }

    fun replay() {
        val card = _ui.value.card ?: return
        voice.clearKey("tutorial")
        speakCard(card, force = true)
    }

    /** Leaving the tutorial (back, or the car started moving). Keeps the place for "이어서 보기". */
    /** The window went to the background: stop listening and speaking, but keep the open group and section. */
    private var inBackground = false
    fun background() {
        if (!active) return
        inBackground = true
        voice.clearKey("tutorial")
        tracker?.interruptTiming()
    }

    /** Back in front: the same section, its practice checking again from where the car is now. */
    fun foreground() {
        if (!inBackground) return
        inBackground = false
        if (active) open(_ui.value.index, speak = false)
    }

    fun pause() {
        inBackground = false
        _ui.value.card?.let { store.pausedCard = it.id }
        voice.clearKey("tutorial")
        tracker = null; restoreTracker = null
        _ui.value = TutorialUi()
    }

    fun closeFinished() { _ui.value = TutorialUi() }

    private fun finish(basic: Boolean = false) {
        val chapterCards = playlist
        val chapter = _ui.value.card?.chapter
        store.pausedCard = continuationIndex?.let { playlist.getOrNull(it)?.id }
        voice.clearKey("tutorial")
        tracker = null; restoreTracker = null
        val summary = chapterCards.filter { !basic || it.stage == 1 }.map { it.headline }.take(6)
        val allBasics = !store.basicsAnnounced && content.cards.filter { it.stage == 1 && (!it.ev || isEv) }
            .all { store.cardResult(it.id).isComplete() }
        if (allBasics) store.basicsAnnounced = true
        _ui.value = TutorialUi(finishedChapter = chapter, finishedSummary = summary,
            basicFinished = basic, moreCount = chapterCards.count { it.stage > 1 }, allBasicsJustCompleted = allBasics)
        val title = content.chapter(chapter ?: 0)?.title ?: ""
        val line = (if (basic) "$title 기본은 다 봤어요." else "$title 다 봤어요.") +
            if (allBasics) " 기본은 다 봤어요. 주행 중엔 제가 옆에서 알려드릴게요." else ""
        if (store.settings.tutorialReadAloud) voice.say(Utterance(line, 3, "tutorial", guidance = false))
    }

    /** The section the learner is looking at in the document page: track its practice, without reading it aloud. */
    fun focus(id: String) {
        val i = playlist.indexOfFirst { it.id == id }
        if (i >= 0 && i != _ui.value.index) open(i, speak = false)
    }

    private fun open(index: Int, speak: Boolean = true) {
        val card = playlist.getOrNull(index) ?: return
        inBackground = false
        voice.clearKey("tutorial")
        speechGeneration++
        store.pausedCard = card.id
        enteredAt = clock()
        speechDone = false
        waitingSince = 0
        restoreTracker = null
        spaceConfirmed = false
        powerNoticeSpoken = false
        val v = card.verify
        tracker = v?.let { ConditionTracker(it.condition) }
        val already = v != null && tracker!!.initiallyMet(last) && v.condition !is io.github.beomsun0829.handsonguide.content.Condition.Sequence
        tracker?.update(last)
        awaitingToggleOff = already
        val state = when {
            v == null -> VerifyState.NONE
            else -> VerifyState.IDLE
        }
        _ui.value = TutorialUi(
            card = card, index = index, total = playlist.size, playlistTitle = playlistTitle,
            stageLabel = stageLabel(card, index),
            verify = state, alreadyMet = already,
            message = if (already) v?.alreadyMet ?: "이미 되어 있네요. 한 번 끄고 다시 해볼까요?" else null,
            needsSpaceConfirmation = card.practice in setOf(Practice.CREEP, Practice.REVERSE),
            powerRequired = card.requiresPower && powerOn(last) == false,
        )
        if (v == null) store.setCardResult(card.id, CardResult.READ)
        if (speak) speakCard(card) else speechDone = true
    }

    private fun stageLabel(card: Card, index: Int): String {
        val sameStage = playlist.filter { it.stage == card.stage && it.chapter == card.chapter }
        val pos = sameStage.indexOf(card) + 1
        val name = when (card.stage) { 1 -> "기본"; 2 -> "더 알아보기"; else -> "상황별" }
        return "$name $pos / ${sameStage.size}"
    }

    /** Cancel this reading without changing the saved preference or completing the card. */
    fun stopReading() {
        speechGeneration++
        voice.clearKey("tutorial")
        speechDone = true
        update { it.copy(narration=null) }
    }

    private fun speakCard(card: Card, force: Boolean = false) {
        val generation = ++speechGeneration
        if ((!store.settings.tutorialReadAloud && !force) || !store.settings.voiceOn || !voice.enabled) {
            speechDone = true
            update { it.copy(narration=null) }
            return
        }
        val lines = card.spoken.toMutableList()
        val ui = _ui.value
        if (ui.needsSpaceConfirmation) lines += "주변이 비어 있을 때만 해보세요. 화면에서 준비됐는지 알려주세요."
        else if(ui.powerRequired) { lines += POWER_REQUIRED_MESSAGE; powerNoticeSpoken=true }
        else card.verify?.let { v -> lines += if (ui.alreadyMet) (ui.message ?: v.instruction) else v.instruction }
        val text = lines.joinToString(" ")
        update { it.copy(narration=SceneNarration(generation)) }
        val accepted = voice.say(Utterance(text, 3, "tutorial", guidance = false,
            onSentence = { index -> if(speechGeneration == generation) update { it.copy(narration=SceneNarration(generation,index)) } },
            onDone = {
                if (speechGeneration == generation) {
                    speechDone = true
                    update { it.copy(narration=it.narration?.copy(finished=true)) }
                }
            }), force = force)
        if (!accepted) { speechDone=true; update { it.copy(narration=null) } }
    }

    /** Called for every signal snapshot while the tutorial is open. */
    fun onSignals(s: Signals) {
        last = s
        if (inBackground) return
        val ui = _ui.value
        val card = ui.card ?: return
        val compact = s.speedKmh > 0 && card.practice != Practice.NONE
        if (ui.compact != compact) update { it.copy(compact = compact) }
        if (ui.needsSpaceConfirmation) return
        val allowBrakeRelease = card.practice == Practice.PARKING && s.gear == "N" &&
            s.bool(Signals.PARKING_BRAKE) == true && s.num(Signals.SPEED)==0.0 && ui.sequenceDone >= 2
        val requiresBrake = card.practice in setOf(Practice.GEAR, Practice.PARKING, Practice.REVERSE)
        val brakeMissing = requiresBrake && s.gear != "P" && s.bool(Signals.BRAKE) == false && !allowBrakeRelease
        if (brakeMissing) {
            if (!ui.practicePaused) {
                tracker?.interruptTiming()
                voice.clearKey("tutorial")
                voice.say(Utterance("브레이크 계속 밟아요.", 2, "practice-brake", guidance = false))
                update { it.copy(practicePaused = true) }
            }
            return
        }
        if (ui.practicePaused) update { it.copy(practicePaused = false) }
        val v = card.verify ?: return
        val now = s.at
        if (s.bool(Signals.VEHICLE_CONNECTED) == false && ui.verify in setOf(
                VerifyState.IDLE, VerifyState.WAITING, VerifyState.HINT, VerifyState.GIVE_UP_OFFER, VerifyState.RESTORE)) {
            reconnectState = if (ui.verify == VerifyState.RESTORE) VerifyState.RESTORE else VerifyState.IDLE
            tracker?.reset()
            restoreTracker?.reset()
            update { it.copy(verify = VerifyState.NOT_CONNECTED, powerRequired=false, signalPending=false) }
            return
        }
        val checking = ui.verify in setOf(VerifyState.IDLE,VerifyState.WAITING,VerifyState.HINT,VerifyState.GIVE_UP_OFFER,VerifyState.RESTORE)
        if(card.requiresPower && checking) {
            val power=powerOn(s)
            if(power==false || ui.powerRequired && power!=true) {
                if(!ui.powerRequired) {
                    tracker?.interruptTiming(); restoreTracker?.interruptTiming()
                    stopReading()
                    update { it.copy(powerRequired=true,signalPending=false) }
                    if(!powerNoticeSpoken && store.settings.tutorialReadAloud && store.settings.voiceOn) {
                        powerNoticeSpoken=true
                        voice.say(Utterance(POWER_REQUIRED_MESSAGE,3,"tutorial",guidance=false))
                    }
                }
                return
            }
            if(ui.powerRequired) {
                enteredAt=now; waitingSince=now
                // Off-state changes and stale values must never count as the user's new action.
                tracker?.reset(); restoreTracker?.reset()
                val already=tracker?.initiallyMet(s)==true
                awaitingToggleOff=already
                tracker?.update(s); restoreTracker?.update(s)
                update { it.copy(powerRequired=false,alreadyMet=already,
                    message=if(ui.verify==VerifyState.RESTORE) it.message else if(already) v.alreadyMet else null,
                    verify=if(ui.verify==VerifyState.RESTORE) VerifyState.RESTORE else VerifyState.IDLE) }
                return
            }
        }
        // A value that never came may still arrive (a scenario, a slow service): check again once it does.
        if (ui.verify == VerifyState.UNSUPPORTED && v.condition.signals().all(s::has)) {
            enteredAt = now; tracker?.reset(); tracker?.update(s)
            update { it.copy(verify = VerifyState.IDLE) }
            return
        }
        if (checking) {
            val needed = if(ui.verify == VerifyState.RESTORE) v.restore?.signals().orEmpty() else v.condition.signals()
            val missing = needed.filterNot(s::has)
            if (missing.isNotEmpty()) {
                val unavailable = s[Signals.UNSUPPORTED_FIELDS] as? Collection<*> ?: emptyList<Any>()
                // Connected but silent about it for a while: this car does not report it (e.g. hazards without telltales).
                val silent = s.bool(Signals.VEHICLE_CONNECTED) == true && now - enteredAt >= SILENT_SIGNAL_MS
                if ((missing.any { it in unavailable } && now-enteredAt>=CAPABILITY_WAIT_MS) || silent) {
                    update { it.copy(verify=VerifyState.UNSUPPORTED,signalPending=false) }
                    store.setCardResult(card.id,CardResult.UNSUPPORTED)
                } else {
                    if(ui.verify == VerifyState.RESTORE) restoreTracker?.reset() else tracker?.reset()
                    if(!ui.signalPending) update { it.copy(signalPending=true) }
                }
                return
            }
            if(ui.signalPending) update { it.copy(signalPending=false) }
        }
        when (ui.verify) {
            VerifyState.IDLE -> {
                if (!speechDone && now - enteredAt < SPEECH_GRACE_MS) {
                    // Still reading the card, but a quick learner can already do it.
                    checkTracker(card, s)
                    return
                }
                waitingSince = now
                update { it.copy(verify = VerifyState.WAITING) }
                checkTracker(card, s)
            }
            VerifyState.NOT_CONNECTED -> if (s.bool(Signals.VEHICLE_CONNECTED) == true) {
                enteredAt = now
                if (reconnectState == VerifyState.RESTORE) restoreTracker?.update(s) else tracker?.update(s)
                update { it.copy(verify = reconnectState) }
            }
            VerifyState.WAITING -> {
                if (checkTracker(card, s)) return
                if (now - waitingSince > HINT_MS && v.hint != null) {
                    update { it.copy(verify = VerifyState.HINT) }
                    voice.say(Utterance(v.hint, 3, "tutorial", guidance = false))
                } else if (now - waitingSince > HINT_MS) {
                    update { it.copy(verify = VerifyState.HINT) }
                }
            }
            VerifyState.HINT -> {
                if (checkTracker(card, s)) return
                if (now - waitingSince > HINT_MS + GIVE_UP_MS) update { it.copy(verify = VerifyState.GIVE_UP_OFFER) }
            }
            VerifyState.GIVE_UP_OFFER -> checkTracker(card, s)
            VerifyState.RESTORE -> {
                if (restoreTracker?.update(s) == true) {
                    update { it.copy(verify = VerifyState.DONE, message = null) }
                    voice.say(Utterance("좋아요.", 3, "tutorial", guidance = false))
                    autoNext()
                }
            }
            else -> Unit
        }
    }

    private fun checkTracker(card: Card, s: Signals): Boolean {
        val t = tracker ?: return false
        if (awaitingToggleOff) {
            if (card.verify!!.condition.signals().any { !s.has(it) }) return false
            if (t.initiallyMet(s)) return false
            awaitingToggleOff = false
            t.reset()
        }
        val met = t.update(s)
        if (t.sequenceProgress != _ui.value.sequenceDone) update { it.copy(sequenceDone = t.sequenceProgress) }
        if (met) confirm(card)
        return met
    }

    /**
     * Completes the open card's practice from steps the car went through while the platform held the
     * driving screen (UX restrictions turn on in D even at a standstill), e.g. the gear card's D then P.
     */
    fun confirmFromDrive() {
        val card = _ui.value.card ?: return
        if (_ui.value.verify in setOf(VerifyState.CONFIRMED, VerifyState.DONE)) return
        confirm(card)
        update { it.copy(sequenceDone = card.verify?.steps?.size ?: 0) }
    }

    private fun confirm(card: Card) {
        val v = card.verify ?: return
        store.setCardResult(card.id, CardResult.CONFIRMED)
        val restore = v.restore
        if (restore != null) {
            restoreTracker = ConditionTracker(restore)
            restoreTracker?.update(last)
            update { it.copy(verify = VerifyState.RESTORE, message = v.restoreSay, sequenceDone = tracker?.sequenceProgress ?: 0) }
            voice.clearKey("tutorial")
            voice.say(Utterance(listOfNotNull(v.success.takeIf { v.restoreSay == null }, v.restoreSay).joinToString(" "), 3, "tutorial", guidance = false))
        } else {
            update { it.copy(verify = VerifyState.CONFIRMED, message = null, sequenceDone = tracker?.sequenceProgress ?: 0) }
            voice.clearKey("tutorial")
            voice.say(Utterance(v.success, 3, "tutorial", guidance = false))
            autoNext()
        }
    }

    /** Auto-advance (setting) is scheduled by the controller, which owns a coroutine scope. */
    private fun autoNext() { confirmedAt = clock() }

    var confirmedAt = 0L
        private set

    private inline fun update(f: (TutorialUi) -> TutorialUi) { _ui.value = f(_ui.value) }

    companion object {
        const val POWER_REQUIRED_MESSAGE = "시동을 켜면 해볼 수 있어요"
        private fun powerOn(s: Signals): Boolean? = when(s.str(Signals.IGNITION)) {
            "OFF", "LOCK", "ACC" -> false
            "ON", "START" -> true
            else -> null
        }
        const val HINT_MS = 20_000L
        const val GIVE_UP_MS = 40_000L
        const val SPEECH_GRACE_MS = 15_000L
        const val CAPABILITY_WAIT_MS = 1_500L
        const val SILENT_SIGNAL_MS = 8_000L
    }
}
