package io.github.beomsun0829.handsonguide.signal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Merges raw values from the vehicle source and the demo overlay, adds derived signals,
 * and publishes one [Signals] snapshot. Emits on change (at most [MAX_HZ]) and on a
 * 250 ms tick so time-based conditions (holds, debounce, minutes counters) advance.
 */
class SignalHub(private val scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {
    private val raw = HashMap<String, Any?>()
    private val receivedAt = HashMap<String, Long>()
    private data class Injected(val value: Any?, val override: Boolean)
    private val overlay = HashMap<String, Injected>()
    private fun derivedKey(key: String) = key in Derivations.OUTPUT_KEYS || key.startsWith("tt_")
    private val _signals = MutableStateFlow(Signals.EMPTY)
    val signals: StateFlow<Signals> = _signals
    private val _realSignals = MutableStateFlow(Signals.EMPTY)
    /** Observational source snapshot; presentation overrides never enter this stream. */
    val realSignals: StateFlow<Signals> = _realSignals

    private var dirty = true
    private var lastEmit = 0L
    private val realDerived = Derivations()
    private val displayedDerived = Derivations()
    private var syntheticDomains = emptySet<Derivations.Domain>()
    private var resetSyntheticHistory = true
    private var shortenedDemoTiming = false
    private var loop: Job? = null

    fun put(key: String, value: Any?) = synchronized(this) {
        receivedAt[key] = clock()
        if (raw[key] != value) { raw[key] = value; dirty = true }
    }

    /** A navigation callback must not publish half of a coordinate/turn update. */
    fun putAll(values: Map<String, Any?>) = synchronized(this) {
        val now = clock()
        values.keys.forEach { receivedAt[it] = now }
        values.forEach { (key, value) -> if (raw[key] != value) { raw[key] = value; dirty = true } }
    }

    /** Real values win unless this injection explicitly requests override (including null). */
    fun overlay(key: String, value: Any?, override: Boolean = false) = synchronized(this) {
        overlay[key] = Injected(value, override)
        dirty = true
    }

    fun clearOverlay() = synchronized(this) {
        overlay.clear(); shortenedDemoTiming = false
        dirty = true; resetSyntheticHistory = true
    }
    fun overlayKeys(): Set<String> = synchronized(this) {
        overlay.filter { (key, value) -> value.override || raw[key] == null && !derivedKey(key) }.keys.toSet()
    }

    fun demoTiming(enabled: Boolean) = synchronized(this) {
        if (shortenedDemoTiming != enabled) {
            shortenedDemoTiming = enabled
            dirty = true
        }
    }

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                emitIfNeeded(force = clock() - lastEmit >= TICK_MS)
                delay(1000L / MAX_HZ)
            }
        }
    }

    fun stop() { loop?.cancel(); loop = null }

    /** Builds a snapshot immediately. Used by tests and by the tick loop. */
    fun emitIfNeeded(force: Boolean = true) = synchronized(this) {
        if (!dirty && !force) return@synchronized
        dirty = false
        val now = clock()
        // This history always observes the real stream, even while a demo hides it.
        val actual = HashMap(raw)
        realDerived.apply(actual, now)
        val reception = receivedAt.toMap()
        _realSignals.value = Signals(actual, now, reception)
        val merged = HashMap(raw)
        val injectedKeys = HashSet<String>()
        overlay.forEach { (key, value) ->
            if (value.override || merged[key] == null && !derivedKey(key)) {
                merged[key] = value.value
                injectedKeys += key
            }
        }
        val currentDomains = Derivations.Domain.entries.filterTo(HashSet()) { domain ->
            domain.inputs.any { it in injectedKeys } || domain == Derivations.Domain.RAIN && shortenedDemoTiming
        }
        // Unaffected domains follow real history. Newly injected domains start from its
        // latest baseline; continuing injected domains keep their own elapsed time.
        Derivations.Domain.entries.forEach { domain ->
            if (domain !in currentDomains || domain !in syntheticDomains || resetSyntheticHistory) {
                displayedDerived.copyHistoryFrom(realDerived, domain)
            }
        }
        displayedDerived.rainHoldMs = if (shortenedDemoTiming) 3000 else Derivations.RAIN_HOLD_MS
        displayedDerived.apply(merged, now)
        val forced = overlay.filterValues { it.override }.mapValues { it.value.value }
        merged.putAll(forced)
        syntheticDomains = currentDomains
        resetSyntheticHistory = false
        lastEmit = now
        _signals.value = Signals(merged, now, reception, isDemo = injectedKeys.isNotEmpty() || shortenedDemoTiming)
    }

    companion object {
        const val MAX_HZ = 10
        const val TICK_MS = 250L
    }
}

/** Stateful derived values (§10.2). Pure Kotlin so it is unit-testable. */
class Derivations {
    enum class Domain(val inputs: Set<String>) {
        MOTION(setOf(Signals.SPEED, Signals.GEAR, Signals.BRAKE)),
        RAIN(setOf(Signals.WIPER_ON, Signals.WIPER_SWITCH)),
        RECIRCULATION(setOf(Signals.RECIRC)),
        NAVIGATION(setOf(Signals.NAV_ACTIVE, Signals.NAV_TURN_DIST, Signals.NAV_TURN_TYPE, Signals.NAV_ROUTE_ID)),
    }

    @Volatile var rainHoldMs = RAIN_HOLD_MS
    private var lastSpeedMs: Double? = null
    private var lastSpeedAt = 0L
    private var accelEma = 0.0
    private var hardBrakeSince = 0L
    private var wiperOnSince = 0L
    private var recircSince = 0L
    private var drivingMs = 0L
    private var parkedSince = 0L
    private var lastAt = 0L
    private var lastTurnDist: Double? = null
    private var lastTurnType: String? = null
    private var lastRouteId: String? = null
    private var turnPassedUntil = 0L

    /** Only temporal state is copied; no injected values enter the real signal stream. */
    fun copyHistoryFrom(other: Derivations, domain: Domain) {
        when (domain) {
            Domain.MOTION -> {
                lastSpeedMs = other.lastSpeedMs; lastSpeedAt = other.lastSpeedAt
                accelEma = other.accelEma; hardBrakeSince = other.hardBrakeSince
                drivingMs = other.drivingMs; parkedSince = other.parkedSince; lastAt = other.lastAt
            }
            Domain.RAIN -> wiperOnSince = other.wiperOnSince
            Domain.RECIRCULATION -> recircSince = other.recircSince
            Domain.NAVIGATION -> {
                lastTurnDist = other.lastTurnDist; lastTurnType = other.lastTurnType
                lastRouteId = other.lastRouteId; turnPassedUntil = other.turnPassedUntil
            }
        }
    }

    fun apply(v: MutableMap<String, Any?>, now: Long) {
        val speed = (v[Signals.SPEED] as? Number)?.toDouble() ?: 0.0
        val gear = v[Signals.GEAR] as? String
        val state = when {
            gear == "P" -> Signals.PARKED
            speed < 0.5 -> Signals.STOPPED
            else -> Signals.MOVING
        }
        v[Signals.DRIVE_STATE] = state

        // Acceleration from speed changes, smoothed (≈1 s window).
        val ms = speed / 3.6
        val prev = lastSpeedMs
        if (prev != null && now > lastSpeedAt) {
            val dt = (now - lastSpeedAt) / 1000.0
            if (dt in 0.05..2.0) {
                val a = (ms - prev) / dt
                val k = (dt / 1.0).coerceIn(0.0, 1.0)
                accelEma += (a - accelEma) * k
            }
        }
        lastSpeedMs = ms; lastSpeedAt = now
        v[Signals.ACCEL] = accelEma

        val braking = v[Signals.BRAKE] == true
        val hard = accelEma <= -3.5 || (braking && accelEma <= -3.0)
        if (hard) { if (hardBrakeSince == 0L) hardBrakeSince = now } else hardBrakeSince = 0L
        v[Signals.HARD_BRAKE] = hard && now - hardBrakeSince >= 300

        val steering = (v[Signals.STEERING] as? Number)?.toDouble()
        v[Signals.TURNING] = steering != null && kotlin.math.abs(steering) > 15

        val night = v[Signals.NIGHT] as? Boolean
        v[Signals.IS_NIGHT] = night

        val wiper = v[Signals.WIPER_ON] == true || (v[Signals.WIPER_SWITCH] as? String).let { it != null && it != "OFF" }
        if (wiper) { if (wiperOnSince == 0L) wiperOnSince = now } else wiperOnSince = 0L
        v[Signals.RAIN_LIKELY] = wiper && now - wiperOnSince >= rainHoldMs

        if (v[Signals.RECIRC] == true) { if (recircSince == 0L) recircSince = now } else recircSince = 0L
        v[Signals.RECIRC_MIN] = if (recircSince == 0L) 0.0 else (now - recircSince) / 60000.0

        val dt = if (lastAt == 0L) 0L else (now - lastAt).coerceIn(0, 2000)
        if (state == Signals.PARKED) {
            if (parkedSince == 0L) parkedSince = now
            if (now - parkedSince > 5 * 60_000) drivingMs = 0
        } else {
            parkedSince = 0L
            drivingMs += dt
        }
        v[Signals.DRIVING_MIN] = drivingMs / 60000.0
        lastAt = now

        // Only a known next turn proves passage. Cancellation, expiry and disconnect do not.
        val dist = (v[Signals.NAV_TURN_DIST] as? Number)?.toDouble()
        val type = v[Signals.NAV_TURN_TYPE] as? String
        val prevDist = lastTurnDist
        val routeId = v[Signals.NAV_ROUTE_ID] as? String
        val routeChanged = routeId != null && lastRouteId != null && routeId != lastRouteId
        val routeEnded = v[Signals.NAV_ACTIVE] == false
        if (routeChanged || routeEnded || dist == null || type == null) turnPassedUntil = 0L
        else if (prevDist != null && prevDist <= 60 && (dist > prevDist + 50 || type != lastTurnType)) {
            turnPassedUntil = now + 3000
        }
        lastTurnDist = dist.takeUnless { routeChanged || routeEnded }
        lastTurnType = type.takeUnless { routeChanged || routeEnded }
        lastRouteId = routeId
        v[Signals.NAV_TURN_PASSED] = now < turnPassedUntil

        val limit = (v[Signals.NAV_SPEED_LIMIT] as? Number)?.toDouble()
        v[Signals.SPEED_OVER] = if (limit != null && limit > 0) speed - limit else null

        val telltales = (v[Signals.TELLTALES] as? List<*>)?.filterIsInstance<Telltale>()
        if (telltales != null) {
            for (t in telltales) v["tt_" + t.type] = t.isOn
            val left = telltales.any { it.type == "TURN_SIGNAL_LEFT" && it.isOn }
            val right = telltales.any { it.type == "TURN_SIGNAL_RIGHT" && it.isOn }
            if (v[Signals.HAZARD] == null) v[Signals.HAZARD] = left && right
            v[Signals.TT_RED] = primaryWarning(telltales, "RED", state)
            v[Signals.TT_AMBER] = primaryWarning(telltales, "YELLOW", state)
        }
    }

    /** Highest-priority warning of one color that a driver should hear about now. */
    private fun primaryWarning(list: List<Telltale>, color: String, state: String): String? =
        WarningLights.active(list,state).firstOrNull { it.color == color }?.type

    companion object {
        val OUTPUT_KEYS = setOf(Signals.DRIVE_STATE, Signals.ACCEL, Signals.HARD_BRAKE, Signals.TURNING,
            Signals.IS_NIGHT, Signals.RAIN_LIKELY, Signals.RECIRC_MIN, Signals.DRIVING_MIN,
            Signals.NAV_TURN_PASSED, Signals.SPEED_OVER, Signals.TT_RED, Signals.TT_AMBER, Signals.HAZARD)
        /** Spec says 30 s of wiping means rain. Demo mode shortens this. */
        const val RAIN_HOLD_MS = 30_000L
    }
}
