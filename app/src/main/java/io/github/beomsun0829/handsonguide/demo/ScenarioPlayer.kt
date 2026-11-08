package io.github.beomsun0829.handsonguide.demo

import io.github.beomsun0829.handsonguide.signal.SignalHub
import io.github.beomsun0829.handsonguide.signal.Telltale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class OverridePolicy(val all: Boolean = false, val keys: Set<String> = emptySet()) {
    fun allows(key: String) = all || key in keys
    companion object {
        fun parse(value: Any?): OverridePolicy = when (value) {
            null, JSONObject.NULL -> OverridePolicy()
            is Boolean -> OverridePolicy(all = value)
            is JSONArray -> OverridePolicy(keys = (0 until value.length()).map { value.getString(it) }.toSet())
            else -> throw IllegalArgumentException("override must be a boolean or array of signal keys")
        }
    }
}

data class Scenario(val name: String, val title: String, val steps: List<JSONObject>,
    val overrides: OverridePolicy = OverridePolicy())

/** Called on the app engine dispatcher. Every ramp belongs to one cancellable playback. */
class ScenarioPlayer(private val scope: CoroutineScope, private val hub: SignalHub,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onStopped: () -> Unit = {}, private val onAction: (String) -> Unit) {
    private var job: Job? = null
    private var generation = 0L
    private val _running = MutableStateFlow<String?>(null)
    val running: StateFlow<String?> = _running
    private val _elapsed = MutableStateFlow(0.0)
    val elapsed: StateFlow<Double> = _elapsed

    fun play(s: Scenario) {
        stop()
        val session = generation
        hub.demoTiming(true)
        _running.value = s.name
        job = scope.launch {
            try {
                coroutineScope {
                    val ramps = HashMap<String, Job>()
                    val started = clock()
                    suspend fun waitFor(milliseconds: Long) {
                        val until = clock() + milliseconds
                        while (clock() < until) {
                            _elapsed.value = (clock() - started) / 1000.0
                            delay(minOf(50L, until - clock()).coerceAtLeast(1))
                        }
                    }
                    // A demo starts from a quiet car: whatever the test vehicle happens to have on (a high
                    // beam left on, a hazard) must not take over a scenario about something else.
                    val first = s.steps.minByOrNull { it.optDouble("t", 0.0) }?.optJSONObject("set")
                    for ((key, quiet) in mapOf<String, Any>("highBeam" to false, "hazard" to false, "wiperOn" to false, "defrostFront" to false)) if (first?.has(key) != true) hub.overlay(key, quiet, s.overrides.allows(key))
                    for (step in s.steps.sortedBy { it.optDouble("t", 0.0) }) {
                        val at = (step.optDouble("t", 0.0) * 1000).toLong()
                        waitFor((at - (clock() - started)).coerceAtLeast(0))
                        ensureActive()
                        val policy = if (step.has("override")) OverridePolicy.parse(step.opt("override")) else s.overrides
                        if (step.optBoolean("clear")) {
                            ramps.values.forEach { it.cancelAndJoin() }; ramps.clear()
                            hub.clearOverlay()
                        }
                        if (step.has("set") || step.has("ramp")) hub.demoTiming(true)
                        step.optJSONObject("set")?.let { set ->
                            for (key in set.keys()) {
                                ramps.remove(key)?.cancelAndJoin()
                                hub.overlay(key, value(key, set.get(key)), policy.allows(key))
                            }
                        }
                        step.optJSONObject("ramp")?.let { ramp ->
                            val duration = (ramp.optDouble("dur", 1.0) * 1000).toLong()
                            for (key in ramp.keys().asSequence().filter { it != "dur" }.toList()) {
                                ramps.remove(key)?.cancelAndJoin()
                                val pair = ramp.getJSONArray(key)
                                val from = pair.getDouble(0); val to = pair.getDouble(1)
                                ramps[key] = launch {
                                    val start = clock()
                                    do {
                                        ensureActive()
                                        val fraction = if (duration == 0L) 1.0 else ((clock() - start).toDouble() / duration).coerceIn(0.0, 1.0)
                                        hub.overlay(key, from + (to - from) * fraction, policy.allows(key))
                                        _elapsed.value = (clock() - started) / 1000.0
                                        if (fraction >= 1.0) break
                                        delay(50)
                                    } while (true)
                                }
                            }
                        }
                        step.optString("say").takeIf { it.isNotBlank() }?.let { onAction("say:$it") }
                        step.optString("action").takeIf { it.isNotBlank() }?.let(onAction)
                        if (step.has("wait")) waitFor((step.getDouble("wait") * 1000).toLong())
                    }
                    ramps.values.forEach { it.join() }
                    _elapsed.value = (clock() - started) / 1000.0
                }
            } finally {
                if (session == generation) {
                    hub.clearOverlay(); hub.demoTiming(false)
                    _running.value = null; job = null
                    onStopped()
                }
            }
        }
    }

    fun stop() {
        ++generation
        job?.cancel(); job = null
        _running.value = null; _elapsed.value = 0.0
        hub.clearOverlay(); hub.demoTiming(false)
        onStopped()
    }

    private fun value(key: String, v: Any?): Any? = when {
        v == null || v == JSONObject.NULL -> null
        key == "telltales" && v is JSONArray -> (0 until v.length()).map { i ->
            val o = v.getJSONObject(i)
            Telltale(o.getString("type"), o.optString("state", "ON"), o.optString("color", "YELLOW"))
        }
        else -> v
    }

    companion object {
        fun parse(json: String): Scenario {
            val o = JSONObject(json)
            val a = o.getJSONArray("steps")
            val steps = (0 until a.length()).map { a.getJSONObject(it) }
            steps.forEach { step ->
                require(step.optDouble("t", 0.0).let { it.isFinite() && it >= 0 }) { "Invalid scenario time" }
                if (step.has("override")) OverridePolicy.parse(step.opt("override"))
                if (step.has("wait")) require(step.getDouble("wait").let { it.isFinite() && it >= 0 }) { "Invalid wait" }
                step.optJSONObject("ramp")?.let { ramp ->
                    require(ramp.optDouble("dur", 1.0).let { it.isFinite() && it >= 0 }) { "Invalid ramp duration" }
                    ramp.keys().asSequence().filter { it != "dur" }.forEach { key ->
                        val pair = ramp.getJSONArray(key)
                        require(pair.length() == 2 && pair.getDouble(0).isFinite() && pair.getDouble(1).isFinite()) { "Invalid ramp endpoints" }
                    }
                }
            }
            return Scenario(o.getString("name"), o.optString("title", o.getString("name")), steps, OverridePolicy.parse(o.opt("override")))
        }
    }
}
