package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.content.Condition
import io.github.beomsun0829.handsonguide.content.ConditionTracker
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.signal.Telltale
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConditionTrackerTest {
    private fun sample(time: Long, value: Any?) = Signals(mapOf("x" to value), time)

    @Test fun numericAndMembershipOperatorsMatchOnlyKnownValues() {
        val cases = listOf(
            Triple("equals", 2, true), Triple("not", 2, false), Triple("in", listOf(1, 2), true),
            Triple("lte", 2, true), Triple("lt", 2, false), Triple("gte", 2, true), Triple("gt", 2, false),
            Triple("absGte", 2, true), Triple("absLte", 2, true), Triple("present", null, true),
        )
        cases.forEach { (op, target, expected) ->
            val condition = Condition.Leaf("x", op, target)
            assertEquals(op, expected, ConditionTracker.leafNow(condition, sample(0, 2.0)))
            assertFalse("$op with no signal", ConditionTracker.leafNow(condition, sample(0, null)))
        }
    }

    @Test fun telltaleOperatorsRespectOnBlinkOffAndColor() {
        val signals = sample(0, listOf(Telltale("TPMS", "BLINK", "YELLOW"), Telltale("ENGINE", "OFF", "RED")))
        assertTrue(ConditionTracker.leafNow(Condition.Leaf("x", "telltaleOn", "TPMS"), signals))
        assertFalse(ConditionTracker.leafNow(Condition.Leaf("x", "telltaleOn", "ENGINE"), signals))
        assertTrue(ConditionTracker.leafNow(Condition.Leaf("x", "telltaleOff", "ENGINE"), signals))
        assertTrue(ConditionTracker.leafNow(Condition.Leaf("x", "telltaleColorOn", "YELLOW"), signals))
    }

    @Test fun debounceWorksWhenTheClockStartsAtZero() {
        val tracker = ConditionTracker(Condition.Leaf("x", "equals", true))
        assertFalse(tracker.update(sample(0, true)))
        assertTrue(tracker.update(sample(300, true)))
    }

    @Test fun becomesDoesNotTreatTheFirstReceivedValueAsATransition() {
        val tracker = ConditionTracker(Condition.Leaf("x", "becomes", true))
        tracker.update(sample(0, null))
        assertFalse(tracker.update(sample(100, true)))
        assertFalse(tracker.update(sample(500, true)))
        tracker.update(sample(600, false))
        assertFalse(tracker.update(sample(700, true)))
        assertTrue(tracker.update(sample(1000, true)))
    }

    @Test fun changedWaitsForAKnownBaselineAndADebouncedChange() {
        val tracker = ConditionTracker(Condition.Leaf("x", "changed", null))
        tracker.update(sample(0, null))
        assertFalse(tracker.update(sample(100, 1)))
        assertFalse(tracker.update(sample(500, 1)))
        assertFalse(tracker.update(sample(600, 2)))
        assertTrue(tracker.update(sample(900, 2)))
    }

    @Test fun aSequenceStepAlreadyTrueAtTheStartCountsOnlyAfterItIsDoneAgain() {
        // "High beam on, then off": a high beam left on before the card opened is not the driver's action.
        val tracker = ConditionTracker(Condition.Sequence(listOf(
            Condition.Leaf("x", "equals", true), Condition.Leaf("x", "equals", false))))
        tracker.update(sample(0, true))
        assertFalse(tracker.update(sample(1000, true)))
        assertEquals(0, tracker.sequenceProgress)
        tracker.update(sample(1100, false))
        tracker.update(sample(1200, true)); tracker.update(sample(1600, true))
        assertEquals(1, tracker.sequenceProgress)
        tracker.update(sample(1700, false)); assertTrue(tracker.update(sample(2100, false)))
    }

    @Test fun eachSequenceStepMustBeStableAndCannotSkipInOneSample() {
        val tracker = ConditionTracker(Condition.Sequence(listOf(
            Condition.Leaf("x", "equals", "R"), Condition.Leaf("x", "equals", "P"))))
        tracker.update(sample(0, "R"))
        tracker.update(sample(100, "P"))
        assertEquals(0, tracker.sequenceProgress)
        tracker.update(sample(200, "R"))
        assertFalse(tracker.update(sample(500, "R")))
        assertEquals(1, tracker.sequenceProgress)
        tracker.update(sample(600, "P"))
        assertTrue(tracker.update(sample(900, "P")))
        assertEquals(2, tracker.sequenceProgress)
    }

    @Test fun rangeHoldResetsWhenValueLeavesRangeOrBecomesUnknown() {
        val tracker = ConditionTracker(Condition.Range("x", 1.0, 5.0, 1000, false), debounceMs = 0)
        tracker.update(sample(0, 2))
        assertFalse(tracker.update(sample(900, 2)))
        tracker.update(sample(950, null))
        assertFalse(tracker.update(sample(1000, 2)))
        assertTrue(tracker.update(sample(2000, 2)))
        assertFalse(tracker.update(sample(2100, 9)))
    }

    @Test fun compositeHoldRequiresAllConditionsForTheWholeDuration() {
        val condition = Condition.parse(JSONObject("""{"op":"hold","holdMs":1000,"condition":{"all":[
            {"signal":"x","op":"equals","value":true},{"signal":"y","op":"equals","value":true}]}}"""))
        val tracker = ConditionTracker(condition, debounceMs = 0)
        fun s(at: Long, y: Boolean) = Signals(mapOf("x" to true, "y" to y), at)
        tracker.update(s(0, true))
        tracker.update(s(900, false))
        assertFalse(tracker.update(s(1000, true)))
        assertFalse(tracker.update(s(1900, true)))
        assertTrue(tracker.update(s(2000, true)))
    }

    @Test fun anyKeepsObservingAllStatefulBranches() {
        val tracker = ConditionTracker(Condition.AnyOf(listOf(
            Condition.Leaf("x", "equals", true), Condition.Leaf("y", "becomes", true))))
        tracker.update(Signals(mapOf("x" to true, "y" to true), 0))
        tracker.update(Signals(mapOf("x" to true, "y" to false), 100))
        tracker.update(Signals(mapOf("x" to false, "y" to false), 200))
        tracker.update(Signals(mapOf("x" to false, "y" to true), 300))
        assertTrue(tracker.update(Signals(mapOf("x" to false, "y" to true), 600)))
    }
}
