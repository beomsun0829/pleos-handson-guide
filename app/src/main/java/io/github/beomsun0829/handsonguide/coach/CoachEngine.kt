package io.github.beomsun0829.handsonguide.coach

import io.github.beomsun0829.handsonguide.content.ConditionTracker
import io.github.beomsun0829.handsonguide.content.Content
import io.github.beomsun0829.handsonguide.content.Situation
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.store.DriveRecord
import io.github.beomsun0829.handsonguide.store.GuideLevel
import io.github.beomsun0829.handsonguide.store.Miss
import io.github.beomsun0829.handsonguide.store.SituationStat
import io.github.beomsun0829.handsonguide.store.Store
import io.github.beomsun0829.handsonguide.store.DriveSource
import io.github.beomsun0829.handsonguide.store.LearningLevel
import io.github.beomsun0829.handsonguide.voice.Utterance
import io.github.beomsun0829.handsonguide.voice.VoiceQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

enum class HintLevel { FIRST, LEARNING, BRIEF }

data class ActiveSituation(
    val situation: Situation,
    val level: HintLevel,
    val word: String,
    val sub: String?,
    val icon: String,
    val tone: String,
    val startedAt: Long,
    /** First-time detail was too long to say while busy; show "정차 후 자세히". */
    val detailLater: Boolean,
)

data class CoachUi(
    /** Biggest card on the drive screen (highest priority active situation). */
    val card: ActiveSituation? = null,
    /** "Car did something" banner on top. */
    val banner: ActiveSituation? = null,
    val firstTipNotice: Boolean = false,
    val quiet: Boolean = false,
)

/**
 * Coaching engine (spec §8). Each situation runs IDLE → ACTIVE → COOLDOWN. The hint level
 * comes from how often the driver met the situation and whether they handled it on their own.
 */
class CoachEngine(
    private val content: Content,
    private val store: Store,
    private val voice: VoiceQueue,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Runtime(val s: Situation) {
        val navInputs = s.trigger.signals().filter { it.startsWith("nav") || it == Signals.SPEED_OVER }.toSet()
        val usesRoute = navInputs.any { it in setOf(Signals.NAV_TURN_TYPE, Signals.NAV_TURN_DIST, Signals.NAV_UNPROTECTED, Signals.NAV_ROUTE_ID) }
        var trigger = ConditionTracker(s.trigger, debounceMs = 0)
        var end = s.end?.let { ConditionTracker(it, debounceMs = 300) }
        var ok = s.okWhen?.let { ConditionTracker(it, debounceMs = 0) }
        val suppress = s.suppressWhen?.let { ConditionTracker(it, debounceMs = 0) }
        var active: ActiveSituation? = null
        var cooldownUntil = 0L
        var okUntil = 0L
        val fires = ArrayDeque<Long>()
        fun resetTrigger() { trigger = ConditionTracker(s.trigger, debounceMs = 0) }
    }

    private val runtimes = content.situations.map { Runtime(it) }
    private val _ui = MutableStateFlow(CoachUi())
    val ui: StateFlow<CoachUi> = _ui

    private var drive: DriveRecord? = null
    private var distance: DriveDistance? = null
    private var last: Signals = Signals.EMPTY
    private var lastHardBrakeAt = 0L
    private var firstTipUntil = 0L
    private var quietThisDrive = false
    val isQuiet: Boolean get() = quietThisDrive || clock() < store.quietUntil

    fun quietForThirtyMinutes() {
        store.quietUntil = clock() + 30 * 60_000L
        discardSuppressed()
        publish(clock())
    }

    fun quietForDrive() {
        quietThisDrive = true
        discardSuppressed()
        publish(clock())
    }

    fun resumeCoaching() {
        quietThisDrive = false
        store.quietUntil = 0
        publish(clock())
    }

    private fun allowed(s: Situation): Boolean =
        (store.settings.coachingOn || s.priority <= 2 || s.banner || s.group == "vehicle") &&
            (!isQuiet || s.priority <= 3)

    private fun discardSuppressed() {
        val suppressed = runtimes.filter { !allowed(it.s) }
        suppressed.forEach { it.active = null }
        val keys = suppressed.map { "sit:${it.s.id}" }.toSet()
        voice.discard { it.key in keys }
    }

    var driving = false
        private set

    fun startDrive(initial: Signals? = null) {
        if (driving) return
        driving = true
        drive = DriveRecord(startAt = clock(),source = if (initial?.isDemo == true) DriveSource.DEMO else DriveSource.REAL)
        distance = DriveDistance()
        initial?.let(::observeDistance)
        runtimes.forEach { it.resetTrigger(); it.end?.reset(); it.ok?.reset(); it.suppress?.reset(); it.fires.clear() }
    }

    /** Ends the drive; returns it for review when it contains tips or missed actions. */
    fun endDrive(): DriveRecord? {
        if (!driving) return null
        driving = false
        quietThisDrive = false
        runtimes.forEach { it.active = null; it.fires.clear() }
        lastHardBrakeAt = 0L
        firstTipUntil = 0L
        voice.discard { it.key.startsWith("sit:") && it.priority > 2 }
        publish(clock())
        val d = drive?.let { record ->
            record.copy(endAt = clock(),km = if (record.source == DriveSource.REAL) distance?.km else null)
        } ?: return null
        drive = null
        distance = null
        store.saveDrive(d)
        return d.takeIf { it.tips.isNotEmpty() || it.misses.isNotEmpty() }
    }

    fun currentDrive(): DriveRecord? = drive

    /** Observe before a P transition closes the record, including its final deceleration. */
    fun observeDistance(s: Signals) {
        val d = drive ?: return
        val source = if (s.isDemo) DriveSource.DEMO else DriveSource.REAL
        val restoredParked = d.source == DriveSource.DEMO && source == DriveSource.REAL &&
            s.gear == "P" && s.num(Signals.SPEED) == 0.0
        val combined = if (restoredParked) DriveSource.DEMO else if (d.source == source) source else DriveSource.MIXED
        distance?.observe(s)
        drive = d.copy(source=combined,km=distance?.km.takeIf { combined == DriveSource.REAL })
    }

    /** Stored level is the learned stage; the global familiarity setting is applied later. */
    fun synchronizeLevels() {
        val stats = store.allStats()
        content.situations.forEach { sit ->
            val st = stats[sit.id] ?: return@forEach
            val level = LearningLevel.valueOf(learnedLevelFor(sit,st).name)
            if (st.level != level) store.setStat(sit.id,st.copy(level=level))
        }
    }

    private fun saveStat(sit: Situation, st: SituationStat) {
        store.setStat(sit.id,st.copy(level=LearningLevel.valueOf(learnedLevelFor(sit,st).name)))
    }

    /** Silence rules for the voice queue (spec §9.4). */
    fun mayStart(priority: Int): Boolean {
        if (priority <= 2) return true
        val s = last
        val now = clock()
        if (!driving) return true
        if (s.bool(Signals.CALL_ACTIVE) == true || s.bool(Signals.GLEO_SPEAKING) == true) return false
        if (now - lastHardBrakeAt < 2000) return false
        val turning = s.bool(Signals.TURNING) == true && s.str(Signals.TURN_SIGNAL).let { it != null && it != "OFF" }
        if (turning) return false
        val dist = s.num(Signals.NAV_TURN_DIST)
        if (dist != null && dist <= 30) return false
        return true
    }

    private fun relaxed(s: Signals): Boolean {
        val accel = abs(s.num(Signals.ACCEL) ?: 0.0)
        val dist = s.num(Signals.NAV_TURN_DIST)
        return accel < 1.5 && s.bool(Signals.TURNING) != true && (dist == null || dist > 400)
    }

    fun onSignals(s: Signals) {
        observeDistance(s)
        val previous = last
        last = s
        val now = s.at
        val previousRoute = previous.str(Signals.NAV_ROUTE_ID)
        val currentRoute = s.str(Signals.NAV_ROUTE_ID)
        val changedRoute = previousRoute != null && currentRoute != null && previousRoute != currentRoute
        val previousTurn = previous.str(Signals.NAV_TURN_TYPE)
        val currentTurn = s.str(Signals.NAV_TURN_TYPE)
        val changedTurn = previousTurn != null && currentTurn != null && previousTurn != currentTurn
        val previousDistance = previous.num(Signals.NAV_TURN_DIST)
        val currentDistance = s.num(Signals.NAV_TURN_DIST)
        val nextTurn = previousDistance != null && previousDistance <= 60 && currentDistance != null && currentDistance > previousDistance + 50
        if (s.bool(Signals.HARD_BRAKE) == true) lastHardBrakeAt = now
        discardSuppressed()
        if (s.bool(Signals.CALL_ACTIVE) == true || s.bool(Signals.GLEO_SPEAKING) == true) {
            voice.discard { it.priority > 2 }
        }
        val parkedOnlyEval = !driving
        for (r in runtimes) {
            val sit = r.s
            if (r.suppress?.update(s) == true) {
                r.active = null
                voice.clearKey("sit:${sit.id}")
                r.resetTrigger()
                continue
            }
            val missingNav = r.navInputs.any { !s.has(it) }
            val oldRoute = r.usesRoute && (s.bool(Signals.NAV_ACTIVE) == false || changedRoute || changedTurn || nextTurn)
            if (missingNav || oldRoute) {
                r.active = null
                voice.clearKey("sit:${sit.id}")
                r.resetTrigger(); r.end?.reset(); r.ok?.reset()
                r.cooldownUntil = 0L; r.okUntil = 0L; r.fires.clear()
                if (missingNav || s.bool(Signals.NAV_ACTIVE) == false) continue
            }
            if (!allowed(sit)) { r.resetTrigger(); continue }
            if (parkedOnlyEval && sit.drivingOnly) continue

            // Ended?
            r.active?.let { a ->
                val ended = r.end?.update(s) ?: (now - a.startedAt > DEFAULT_SHOW_MS)
                val tooOld = now - a.startedAt > MAX_SHOW_MS && sit.priority > 1
                if (ended || tooOld) {
                    r.active = null
                    r.cooldownUntil = now + sit.cooldownSec * 1000L
                    r.resetTrigger()
                }
            }
            if (r.active != null) continue

            // Driver handled it before we had to speak.
            r.ok?.let { okT ->
                if (now >= r.okUntil && now >= r.cooldownUntil && okT.update(s)) {
                    val st = store.stat(sit.id)
                    saveStat(sit, st.copy(count = st.count + 1, learningCount = st.learningCount + 1,
                        okStreak = st.okStreak + 1, lastMissed = false, lastAt = now))
                    r.okUntil = now + sit.cooldownSec * 1000L
                    r.cooldownUntil = now + sit.cooldownSec * 1000L
                    okT.reset()
                }
            }

            if (now < r.cooldownUntil) { r.trigger.update(s); continue }
            if (!r.trigger.update(s)) continue

            // Repeat rule: speak only after N fires in the window.
            if (sit.repeatCount > 1) {
                r.fires.addLast(now)
                while (r.fires.isNotEmpty() && now - r.fires.first() > sit.repeatWithinSec * 1000L) r.fires.removeFirst()
                r.resetTrigger()
                if (r.fires.size < sit.repeatCount) continue
                r.fires.clear()
            }
            fire(r, s, now)
        }
        publish(now)
    }

    private fun fire(r: Runtime, s: Signals, now: Long) {
        val sit = r.s
        val st = store.stat(sit.id)
        val level = levelFor(sit, st)
        val vars = vars(s)
        val word = fill(sit.card.word, vars)
        val sub = sit.card.sub?.let { fill(it, vars) }
        var text = when (level) {
            HintLevel.FIRST -> sit.say.first
            HintLevel.LEARNING -> sit.say.learning
            HintLevel.BRIEF -> sit.say.brief
        }?.let { fill(it, vars) }
        var detailLater = false
        if (level == HintLevel.FIRST && driving && VoiceQueue.splitSentences(text ?: "").size >= 2 && !relaxed(s) && sit.priority >= 3) {
            text = fill(sit.say.learning, vars)
            detailLater = true
        }
        val active = ActiveSituation(sit, level, word, sub, fill(sit.card.icon, vars), sit.card.tone, now, detailLater)
        r.active = active
        if (sit.end == null) r.cooldownUntil = now + sit.cooldownSec * 1000L

        val minimal = store.settings.guideLevel == GuideLevel.MINIMAL && sit.priority > 2
        val dialogue = (s.bool(Signals.CALL_ACTIVE) == true || s.bool(Signals.GLEO_SPEAKING) == true) && sit.priority > 2
        val navAt = s.num(Signals.NAV_SPOKEN_AT)?.toLong()
        val navDuplicate = store.settings.navDedupe && sit.priority == 4 && sit.group == "intersection" &&
            navAt != null && now - navAt in 0..5_000
        if (text != null && !minimal && !dialogue && !navDuplicate) voice.say(Utterance(text, sit.priority, "sit:${sit.id}"))

        // Receiving a prompt is not an unprompted success. Only okWhen establishes that.
        saveStat(sit, st.copy(count = st.count + 1, learningCount = st.learningCount + 1,
            okStreak = 0, lastMissed = sit.missed, lastAt = now))
        if (driving) {
            val d = drive ?: DriveRecord(startAt = now)
            drive = d.copy(
                tips = (d.tips + sit.id).distinct(),
                firstTips = if (level == HintLevel.FIRST) (d.firstTips + sit.id).distinct() else d.firstTips,
                misses = if (sit.missed) d.misses + Miss(sit.id, now, s.num(Signals.LATITUDE), s.num(Signals.LONGITUDE),
                    s.str(Signals.NAV_TURN_DESC)) else d.misses,
                // Only when the whole card word is a variable ("{telltale}"): the name alone does not say what it was.
                labels = if (Regex("\\{\\w+\\}").matches(sit.card.word) && word.isNotBlank()) d.labels + (sit.id to word) else d.labels,
            )
        }
        if (level == HintLevel.FIRST) firstTipUntil = now + 10_000
        store.addPending(sit.relatedCards)
    }

    fun levelFor(sit: Situation, st: SituationStat): HintLevel {
        val base = learnedLevelFor(sit,st)
        return if (store.settings.guideLevel != GuideLevel.BEGINNER && base == HintLevel.FIRST) HintLevel.LEARNING else base
    }

    private fun learnedLevelFor(sit: Situation, st: SituationStat): HintLevel = when {
            st.learningCount == 0 -> HintLevel.FIRST
            st.learningCount >= BRIEF_AFTER && (sit.banner || st.okStreak >= OK_STREAK && !st.lastMissed) -> HintLevel.BRIEF
            else -> HintLevel.LEARNING
        }

    private fun publish(now: Long) {
        val actives = runtimes.mapNotNull { it.active }
        val banner = actives.filter { it.situation.banner && now - it.startedAt < BANNER_MS || it.situation.priority == 1 && it.situation.banner }
            .minByOrNull { it.situation.priority }
        val card = actives.filter { !it.situation.banner }.minWithOrNull(compareBy({ it.situation.priority }, { -it.startedAt }))
        val prev = _ui.value.card
        // Minimum display time 3 s unless the new card is more urgent.
        val keep = prev != null && card != null && prev.situation.id != card.situation.id &&
            now - prev.startedAt < MIN_CARD_MS && card.situation.priority >= prev.situation.priority &&
            actives.any { it.situation.id == prev.situation.id }
        _ui.value = CoachUi(card = if (keep) prev else card, banner = banner, firstTipNotice = now < firstTipUntil, quiet = isQuiet)
    }

    private fun vars(s: Signals): Map<String, String> {
        val m = HashMap<String, String>()
        val tt = s.str(Signals.TT_RED) ?: s.str(Signals.TT_AMBER)
        tt?.let { type ->
            val info = content.telltales[type]
            m["telltale"] = info?.name ?: "경고등"
            m["telltaleAction"] = info?.action ?: "계기판 메시지를 확인해요."
        }
        when (s.str(Signals.NAV_TURN_TYPE)) {
            "LEFT" -> { m["turnDir"] = "left"; m["turnName"] = "좌회전"; m["turnSideLane"] = "왼쪽" }
            "RIGHT" -> { m["turnDir"] = "right"; m["turnName"] = "우회전"; m["turnSideLane"] = "오른쪽" }
        }
        s.num(Signals.NAV_TURN_DIST)?.let { m["turnDist"] = (((it / 10).roundToInt()) * 10).toString() }
        m["turnSide"] = when (s.str(Signals.TURN_SIGNAL)) { "LEFT" -> "왼쪽"; "RIGHT" -> "오른쪽"; else -> "옆" }
        s.num(Signals.NAV_SPEED_LIMIT)?.let { m["limit"] = it.roundToInt().toString() }
        m["schoolLimit"] = (s.num(Signals.NAV_SPEED_LIMIT)?.takeIf { it > 0 }?.coerceAtMost(30.0) ?: 30.0).roundToInt().toString()
        return m
    }

    private fun fill(t: String, vars: Map<String, String>): String =
        Regex("\\{(\\w+)\\}").replace(t) { vars[it.groupValues[1]] ?: "" }.replace(Regex("\\s{2,}"), " ").trim()

    companion object {
        const val BRIEF_AFTER = 5
        const val OK_STREAK = 3
        const val MIN_CARD_MS = 3000L
        const val BANNER_MS = 6000L
        const val DEFAULT_SHOW_MS = 6000L
        const val MAX_SHOW_MS = 60_000L
        const val MIN_REVIEW_MS = 2 * 60_000L
    }
}
