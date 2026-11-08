package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.coach.HintLevel
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.store.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StoredLearningLevelTest {
    @Test fun legacyAndInconsistentLevelsAreRebuiltFromPreservedLearningHistory() = EngineFixture(setOf("ROUNDABOUT_PREP")).use { f ->
        f.store.load(JSONObject("""{"stats":{"ROUNDABOUT_PREP":{"count":5,"okStreak":3,"lastMissed":false}}}"""))
        f.coach.synchronizeLevels()
        assertEquals(LearningLevel.BRIEF,f.store.stat("ROUNDABOUT_PREP").level)
        f.store.setStat("ROUNDABOUT_PREP",SituationStat(level=LearningLevel.BRIEF))
        f.coach.synchronizeLevels()
        assertEquals(LearningLevel.FIRST,f.store.stat("ROUNDABOUT_PREP").level)
    }

    @Test fun learnedLevelSurvivesRestartWithoutBakingInTheGlobalFamiliaritySetting() = EngineFixture(setOf("ROUNDABOUT_PREP")).use { f ->
        f.store.setStat("ROUNDABOUT_PREP",SituationStat())
        f.store.settings = Settings(guideLevel=GuideLevel.FAMILIAR)
        f.coach.synchronizeLevels()
        assertEquals(LearningLevel.FIRST,f.store.stat("ROUNDABOUT_PREP").level)
        val sit = f.content.situation("ROUNDABOUT_PREP")!!
        assertEquals(HintLevel.LEARNING,f.coach.levelFor(sit,f.store.stat(sit.id)))
        val restored = MemoryStore().apply { load(f.store.toJson()) }
        assertEquals(LearningLevel.FIRST,restored.stat(sit.id).level)
        f.store.settings = Settings(guideLevel=GuideLevel.BEGINNER)
        assertEquals(HintLevel.FIRST,f.coach.levelFor(sit,f.store.stat(sit.id)))
    }

    @Test fun successfulHandlingStoresTheNextLearnedLevelAndLaterMissLowersIt() = EngineFixture(setOf("TURN_SIGNAL_MISSING")).use { f ->
        f.store.setStat("TURN_SIGNAL_MISSING",SituationStat(count=2))
        f.drive()
        repeat(3) { index ->
            f.emit(400,Signals.SPEED to 25,Signals.NAV_ACTIVE to true,Signals.NAV_ROUTE_ID to "route-$index",
                Signals.NAV_TURN_TYPE to "LEFT",Signals.NAV_TURN_DIST to 500,Signals.TURN_SIGNAL to "LEFT")
            f.emit(400,Signals.NAV_TURN_DIST to 80)
        }
        val stat = f.store.stat("TURN_SIGNAL_MISSING")
        assertEquals(5,stat.count); assertEquals(3,stat.okStreak)
        assertEquals(LearningLevel.BRIEF,stat.level)
        assertEquals(LearningLevel.BRIEF,MemoryStore().apply { load(f.store.toJson()) }.stat("TURN_SIGNAL_MISSING").level)
        f.emit(400,Signals.NAV_ROUTE_ID to "missed-route",Signals.NAV_TURN_DIST to 500,Signals.TURN_SIGNAL to "OFF")
        f.emit(400,Signals.NAV_TURN_DIST to 80)
        val missed = f.store.stat("TURN_SIGNAL_MISSING")
        assertEquals(6,missed.count); assertEquals(0,missed.okStreak); assertTrue(missed.lastMissed)
        assertEquals(LearningLevel.LEARNING,missed.level)
    }
}
