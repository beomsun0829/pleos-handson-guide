package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.coach.HintLevel
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.store.SituationStat
import org.junit.Assert.*
import org.junit.Test

class CoachingLearningTest {
    @Test fun complyingAfterAPromptDoesNotDoubleCountOrBecomeAnUnpromptedSuccess() = EngineFixture(setOf("TURN_SIGNAL_MISSING")).use { f ->
        f.drive()
        f.emit(400, Signals.SPEED to 25, Signals.NAV_ACTIVE to true, Signals.NAV_TURN_TYPE to "LEFT",
            Signals.NAV_TURN_DIST to 80, Signals.TURN_SIGNAL to "OFF")
        assertEquals(1, f.store.stat("TURN_SIGNAL_MISSING").count)
        f.emit(400, Signals.TURN_SIGNAL to "LEFT"); f.emit(400)
        val stat = f.store.stat("TURN_SIGNAL_MISSING")
        assertEquals(1, stat.count)
        assertEquals(0, stat.okStreak)
        assertTrue(stat.lastMissed)
    }

    @Test fun threeSeparateUnpromptedSuccessesCanReachBriefAfterFiveEncounters() = EngineFixture(setOf("TURN_SIGNAL_MISSING")).use { f ->
        f.store.setStat("TURN_SIGNAL_MISSING", SituationStat(count = 2))
        f.drive()
        repeat(3) { index ->
            f.emit(400, Signals.SPEED to 25, Signals.NAV_ACTIVE to true, Signals.NAV_ROUTE_ID to "route-$index",
                Signals.NAV_TURN_TYPE to "LEFT", Signals.NAV_TURN_DIST to 500, Signals.TURN_SIGNAL to "LEFT")
            f.emit(400, Signals.NAV_TURN_DIST to 80)
        }
        val stat = f.store.stat("TURN_SIGNAL_MISSING")
        assertEquals(5, stat.count)
        assertEquals(3, stat.okStreak)
        assertEquals(HintLevel.BRIEF, f.coach.levelFor(f.content.situation("TURN_SIGNAL_MISSING")!!, stat))
        f.voice.pump()
        assertTrue(f.speech.spoken.isEmpty())
    }

    @Test fun informationPromptsCannotManufactureAStreakOfIndependentSuccesses() = EngineFixture(setOf("ROUNDABOUT_PREP")).use { f ->
        f.store.setStat("ROUNDABOUT_PREP", SituationStat(count = 4, okStreak = 2))
        f.drive()
        f.emit(400, Signals.SPEED to 25, Signals.NAV_ACTIVE to true, Signals.NAV_TURN_TYPE to "ROUNDABOUT", Signals.NAV_TURN_DIST to 250)
        val stat = f.store.stat("ROUNDABOUT_PREP")
        assertEquals(5, stat.count)
        assertEquals(0, stat.okStreak)
        assertEquals(HintLevel.LEARNING, f.coach.levelFor(f.content.situation("ROUNDABOUT_PREP")!!, stat))
    }

    @Test fun automaticVehicleExplanationsShortenByExposureWithoutClaimingDriverSuccess() = EngineFixture().use { f ->
        val banner = f.content.situations.first { it.banner }
        assertEquals(HintLevel.BRIEF, f.coach.levelFor(banner, SituationStat(count = 5, okStreak = 0)))
    }
}
