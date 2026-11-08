package io.github.beomsun0829.handsonguide

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Rules every shipped demo scenario follows (docs/beginner-guide-tech.md §17.2.1). */
class DemoScenarioRulesTest {
    private val scenarios = File("src/main/assets/demo").listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        .map { it.name to JSONObject(it.readText()) }

    /** Values the app itself produces for cruise (VehicleSource.cruiseName); anything else is never seen in a car. */
    private val cruiseValues = setOf("ACTIVATED", "ENABLED", "OVERRIDE", "SUSPENDED", "FORCED_OFF", "OFF")

    private fun sets(o: JSONObject) = o.getJSONArray("steps").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        .mapNotNull { it.optJSONObject("set")?.let { s -> it.optDouble("t", 0.0) to s } }

    @Test fun speedOnlyChangesByRamp() {
        for ((name, o) in scenarios) {
            var speed: Double? = null
            for ((t, set) in sets(o).sortedBy { it.first }) {
                if (!set.has("speedKmh")) continue
                val next = set.getDouble("speedKmh")
                // A jump reads as hard braking or acceleration and lands in the drive review.
                speed?.let { assertTrue("$name t=$t: speed $it → $next in one step; use a ramp", kotlin.math.abs(next - it) <= 40 || it == 0.0) }
                speed = next
            }
        }
    }

    @Test fun cruiseValuesAreOnesTheAppUnderstands() {
        for ((name, o) in scenarios) for ((t, set) in sets(o)) if (set.has("cruise")) {
            val v = set.getString("cruise")
            assertTrue("$name t=$t: cruise \"$v\" is not a value the app produces", v in cruiseValues)
        }
    }

    @Test fun ptAutoInjectsEverything() {
        val auto = scenarios.first { it.first == "01b-pt-auto.json" }.second
        assertTrue("pt-auto must drive every signal itself", auto.optBoolean("override"))
    }
}
