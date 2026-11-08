package io.github.beomsun0829.handsonguide.content

import io.github.beomsun0829.handsonguide.signal.Signals
import org.json.JSONArray
import org.json.JSONObject

/** Condition DSL from spec §7.2 / §8.1. Parsed once from JSON, evaluated by a [ConditionTracker]. */
sealed class Condition {
    data class Leaf(val signal: String, val op: String, val value: Any?) : Condition()
    data class All(val items: List<Condition>) : Condition()
    data class AnyOf(val items: List<Condition>) : Condition()
    data class Sequence(val steps: List<Condition>) : Condition()
    data class Range(val signal: String, val min: Double?, val max: Double?, val holdMs: Long, val abs: Boolean) : Condition()
    data class Hold(val condition: Condition, val holdMs: Long) : Condition()

    /** Signals this condition reads; used to decide whether a card is supported. */
    fun signals(): Set<String> = when (this) {
        is Leaf -> setOf(signal)
        is All -> items.flatMap { it.signals() }.toSet()
        is AnyOf -> items.flatMap { it.signals() }.toSet()
        is Sequence -> steps.flatMap { it.signals() }.toSet()
        is Range -> setOf(signal)
        is Hold -> condition.signals()
    }

    companion object {
        fun parse(o: JSONObject): Condition = when {
            o.optString("op") == "hold" -> Hold(parse(o.getJSONObject("condition")), o.getLong("holdMs"))
            o.has("all") -> All(o.getJSONArray("all").objects().map(::parse))
            o.has("any") -> AnyOf(o.getJSONArray("any").objects().map(::parse))
            o.has("sequence") -> Sequence(o.getJSONArray("sequence").objects().map(::parse))
            o.optString("op") == "range" -> Range(
                o.getString("signal"),
                if (o.has("min")) o.getDouble("min") else null,
                if (o.has("max")) o.getDouble("max") else null,
                o.optLong("holdMs", 0),
                o.optBoolean("abs", false),
            )
            else -> Leaf(o.getString("signal"), o.optString("op", "equals"), jsonValue(o.opt("value")))
        }

        private fun jsonValue(v: Any?): Any? = when (v) {
            null, JSONObject.NULL -> null
            is JSONArray -> (0 until v.length()).map { jsonValue(v.get(it)) }
            else -> v
        }
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

/**
 * Stateful evaluator. `becomes` and `changed` compare against the value seen when tracking
 * started, sequences advance step by step, and every positive result must hold for
 * [debounceMs] so a flicker from the test console does not count.
 */
class ConditionTracker(private val cond: Condition, private val debounceMs: Long = 300) {
    private val baseline = java.util.IdentityHashMap<Condition, Any?>()
    private val wasMet = java.util.IdentityHashMap<Condition, Boolean>()
    private val seqIndex = java.util.IdentityHashMap<Condition, Int>()
    private val holdSince = java.util.IdentityHashMap<Condition, Long>()
    private val stepSince = java.util.IdentityHashMap<Condition, Long>()
    private var started = false
    private var trueSince: Long? = null
    /** Sequences whose current step was already true when it began: it must go false once before it counts. */
    private val stepArmed = java.util.IdentityHashMap<Condition, Boolean>()

    /** Number of completed steps when the root is a sequence; for "P ✓ R ✓" style progress. */
    val sequenceProgress: Int get() = (cond as? Condition.Sequence)?.let { seqIndex[it] ?: 0 } ?: 0

    /** True when [cond] is already satisfied at start (used for the "already on" toggle flow). */
    fun initiallyMet(s: Signals): Boolean = eval(cond, s, s.at, peek = true)

    fun update(s: Signals): Boolean {
        if (!started) { prime(cond, s); started = true }
        val now = s.at
        val raw = eval(cond, s, now, peek = false)
        if (!raw) { trueSince = null; return false }
        // Each sequence step has already passed debounce; avoid debouncing the final step twice.
        if (cond is Condition.Sequence) return true
        if (trueSince == null) trueSince = now
        return now - trueSince!! >= debounceMs
    }

    fun reset() {
        baseline.clear(); wasMet.clear(); seqIndex.clear(); holdSince.clear(); stepSince.clear(); stepArmed.clear(); started = false; trueSince = null
    }

    /** An interrupted safety hold must restart, while already completed steps remain valid. */
    fun interruptTiming() { holdSince.clear(); stepSince.clear(); trueSince=null }

    private fun prime(c: Condition, s: Signals) {
        when (c) {
            is Condition.Leaf -> { baseline[c] = s[c.signal]; wasMet[c] = leafNow(c, s) }
            is Condition.All -> c.items.forEach { prime(it, s) }
            is Condition.AnyOf -> c.items.forEach { prime(it, s) }
            is Condition.Sequence -> {
                seqIndex[c] = 0; c.steps.forEach { prime(it, s) }
                // "Turn the high beam on" must be done, not already be the case when the card opens.
                stepArmed[c] = c.steps.firstOrNull()?.let { !eval(it, s, s.at, peek = true) } ?: true
            }
            is Condition.Range -> Unit
            is Condition.Hold -> prime(c.condition, s)
        }
    }

    private fun eval(c: Condition, s: Signals, now: Long, peek: Boolean): Boolean = when (c) {
        is Condition.Leaf -> evalLeaf(c, s, peek)
        // Visit every child so edge baselines and hold timers keep observing their signals.
        is Condition.All -> c.items.map { eval(it, s, now, peek) }.all { it }
        is Condition.AnyOf -> c.items.map { eval(it, s, now, peek) }.any { it }
        is Condition.Sequence -> evalSequence(c, s, now, peek)
        is Condition.Range -> evalRange(c, s, now, peek)
        is Condition.Hold -> {
            val met = eval(c.condition, s, now, peek)
            if (peek) met && c.holdMs == 0L
            else if (!met) { holdSince.remove(c); false }
            else now - holdSince.getOrPut(c) { now } >= c.holdMs
        }
    }

    private fun evalLeaf(c: Condition.Leaf, s: Signals, peek: Boolean): Boolean {
        val v = s[c.signal]
        return when (c.op) {
            "becomes" -> {
                if (v == null) return false
                val now = matches(v, c.value)
                if (peek) now else {
                    if (baseline[c] == null) {
                        baseline[c] = v
                        wasMet[c] = now
                        return false
                    }
                    // Must observe a moment where it was NOT the target, then reach the target.
                    if (!now) wasMet[c] = false
                    now && wasMet[c] == false
                }
            }
            "changed" -> if (peek || v == null) false else if (baseline[c] == null) {
                baseline[c] = v
                false
            } else !sameValue(v, baseline[c])
            else -> leafNow(c, s)
        }
    }

    private fun evalSequence(c: Condition.Sequence, s: Signals, now: Long, peek: Boolean): Boolean {
        if (peek) return false
        var i = seqIndex[c] ?: 0
        if (i >= c.steps.size) return true
        val step = c.steps[i]
        if (!eval(step, s, now, peek = false)) {
            stepSince.remove(c)
            stepArmed[c] = true
            return false
        }
        if (stepArmed[c] == false) return false
        val since = stepSince.getOrPut(c) { now }
        if (now - since < debounceMs) return false
        i++
        stepSince.remove(c)
        if (i < c.steps.size) prime(c.steps[i], s)
        stepArmed[c] = true          // later steps follow from what the driver just did
        seqIndex[c] = i
        return i >= c.steps.size
    }

    private fun evalRange(c: Condition.Range, s: Signals, now: Long, peek: Boolean): Boolean {
        val raw = s.num(c.signal) ?: return false.also { if (!peek) holdSince.remove(c) }
        val v = if (c.abs) kotlin.math.abs(raw) else raw
        val inside = (c.min == null || v >= c.min) && (c.max == null || v <= c.max)
        if (!inside) { if (!peek) holdSince.remove(c); return false }
        if (peek) return c.holdMs == 0L
        val since = holdSince.getOrPut(c) { now }
        return now - since >= c.holdMs
    }

    companion object {
        fun leafNow(c: Condition.Leaf, s: Signals): Boolean {
            val v = s[c.signal]
            return when (c.op) {
                "equals", "becomes" -> matches(v, c.value)
                "not" -> v != null && !matches(v, c.value)
                "in" -> v != null && (c.value as? List<*>)?.any { matches(v, it) } == true
                "lte" -> num(v)?.let { n -> num(c.value)?.let { n <= it } } == true
                "lt" -> num(v)?.let { n -> num(c.value)?.let { n < it } } == true
                "gte" -> num(v)?.let { n -> num(c.value)?.let { n >= it } } == true
                "gt" -> num(v)?.let { n -> num(c.value)?.let { n > it } } == true
                "absGte" -> num(v)?.let { n -> num(c.value)?.let { kotlin.math.abs(n) >= it } } == true
                "absLte" -> num(v)?.let { n -> num(c.value)?.let { kotlin.math.abs(n) <= it } } == true
                "present" -> v != null
                "telltaleOn" -> telltales(v).any { it.type == c.value && it.isOn }
                "telltaleOff" -> v != null && telltales(v).none { it.type == c.value && it.isOn }
                "telltaleColorOn" -> telltales(v).any { it.color == c.value && it.isOn }
                else -> false
            }
        }

        private fun telltales(v: Any?): List<io.github.beomsun0829.handsonguide.signal.Telltale> =
            (v as? List<*>)?.filterIsInstance<io.github.beomsun0829.handsonguide.signal.Telltale>() ?: emptyList()

        private fun num(v: Any?): Double? = (v as? Number)?.toDouble()

        fun matches(v: Any?, target: Any?): Boolean {
            if (v == null || target == null) return v == null && target == null
            if (v is Number && target is Number) return v.toDouble() == target.toDouble()
            return v.toString() == target.toString()
        }

        private fun sameValue(a: Any?, b: Any?): Boolean = matches(a, b)
    }
}
