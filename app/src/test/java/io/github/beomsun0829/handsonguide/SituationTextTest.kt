package io.github.beomsun0829.handsonguide

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A slot in a coaching line ({limit}, {turnName}…) left empty reads as broken Korean while driving
 * ("지금 제한속도는 이에요"). Each slot is allowed only where the trigger guarantees its value, or the
 * engine has a default for it.
 */
class SituationTextTest {
    private val situations = JSONObject(File("src/main/assets/content/situations.json").readText()).getJSONArray("situations")
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    /** Slot → signal that must appear in the trigger. null: CoachEngine.vars always fills it. */
    private val needs = mapOf(
        "telltale" to "tt", "telltaleAction" to "tt",
        "turnDir" to "navNextTurnType", "turnName" to "navNextTurnType", "turnSideLane" to "navNextTurnType",
        "limit" to "speedOverKmh",            // null without a known limit, so the trigger cannot fire without one
        "turnSide" to null, "schoolLimit" to null,
    )

    @Test fun everySlotIsGuaranteedByItsTriggerOrHasADefault() {
        for (s in situations) {
            val text = listOf(s.optJSONObject("say"), s.optJSONObject("card")).joinToString(" ")
            val trigger = s.getJSONObject("trigger").toString()
            for (slot in Regex("\\{(\\w+)\\}").findAll(text).map { it.groupValues[1] }.toSet()) {
                assertTrue("${s.getString("id")}: unknown slot {$slot}", slot in needs)
                needs[slot]?.let { signal -> assertTrue("${s.getString("id")}: {$slot} needs $signal in its trigger", signal in trigger) }
            }
        }
    }
}
