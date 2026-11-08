package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.signal.Telltale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The highway and city demo drives, replayed through the real signal hub and coach without an emulator:
 * every coaching line each drive is meant to show comes up, in order, and nothing else does.
 */
class DriveFlowScenarioTest {
    private class Run(val shown: List<String>, val spoken: List<String>)

    private fun play(file: String): Run = EngineFixture().use { f ->
        val steps = JSONObject(File("src/main/assets/demo/$file").readText()).getJSONArray("steps")
            .let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.sortedBy { it.optDouble("t") }
        f.hub.demoTiming(true)
        data class Ramp(val from: Double, val to: Double, val start: Double, val dur: Double)
        val ramps = HashMap<String, Ramp>()
        val shown = ArrayList<String>()
        var next = 0
        val tick = .2
        var t = 0.0
        val end = steps.maxOf { it.optDouble("t") }
        while (t <= end + .001) {
            while (next < steps.size && steps[next].optDouble("t") <= t + 1e-6) {
                val step = steps[next++]
                if (step.optBoolean("clear")) { ramps.clear(); f.hub.clearOverlay() }
                step.optJSONObject("set")?.let { set -> set.keys().forEach { k -> ramps.remove(k); f.hub.overlay(k, value(k, set.get(k)), true) } }
                step.optJSONObject("ramp")?.let { r ->
                    r.keys().asSequence().filter { it != "dur" }.forEach { k ->
                        val p = r.getJSONArray(k); ramps[k] = Ramp(p.getDouble(0), p.getDouble(1), step.optDouble("t"), r.optDouble("dur", 1.0))
                    }
                }
            }
            for ((k, r) in ramps) f.hub.overlay(k, r.from + (r.to - r.from) * ((t - r.start) / r.dur).coerceIn(0.0, 1.0), true)
            f.emit((tick * 1000).toLong())
            f.voice.pump(); f.speech.complete()
            listOfNotNull(f.coach.ui.value.banner, f.coach.ui.value.card).forEach { a ->
                if (a.situation.id !in shown.takeLast(2)) shown += a.situation.id
            }
            t += tick
        }
        Run(shown, f.speech.spoken.toList())
    }

    private fun value(key: String, v: Any?): Any? = when {
        v == JSONObject.NULL -> null
        key == "telltales" && v is JSONArray -> (0 until v.length()).map { i ->
            v.getJSONObject(i).let { Telltale(it.getString("type"), it.optString("state", "ON"), it.optString("color", "YELLOW")) }
        }
        else -> v
    }

    @Test fun highwayDriveShowsItsCoachingInOrder() {
        val run = play("08-highway.json")
        assertEquals(run.spoken.joinToString("\n"), listOf(
            "BELT_UNBUCKLED_START", "PARKING_BRAKE_ON_DRIVE", "MERGE_PREP", "TOLL_PREP", "CRUISE_ON", "BLIND_SPOT",
            "CRUISE_RELEASED", "FORWARD_COLLISION", "RAIN_START", "HIGHWAY_EXIT_PREP", "DRIVER_ATTENTION",
        ), run.shown)
    }

    @Test fun cityDriveShowsItsCoachingInOrder() {
        val run = play("09-city.json")
        assertEquals(run.spoken.joinToString("\n"), listOf(
            "LIGHT_OFF_NIGHT", "TURN_PREP", "TURN_SIGNAL_MISSING", "UNPROTECTED_LEFT_PREP", "SCHOOL_ZONE", "SPEEDING",
            "ROUNDABOUT_PREP", "UTURN_PREP", "HARD_BRAKE_REPEAT",
        ), run.shown)
    }
}
