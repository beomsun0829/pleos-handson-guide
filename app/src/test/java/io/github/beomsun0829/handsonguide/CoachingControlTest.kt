package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.coach.HintLevel
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.store.DriveRecord
import io.github.beomsun0829.handsonguide.store.MemoryStore
import io.github.beomsun0829.handsonguide.store.Miss
import io.github.beomsun0829.handsonguide.store.SituationStat
import org.junit.Assert.*
import org.junit.Test

class CoachingControlTest {
    private fun sample(f: EngineFixture, vararg extra: Pair<String, Any?>): Signals = Signals(mapOf(
        Signals.GEAR to "D", Signals.SPEED to 30, Signals.DRIVE_STATE to Signals.MOVING,
        Signals.BELT to true, Signals.RECIRC_MIN to 25.0, Signals.RECIRC to true,
    ) + extra, f.now)

    @Test fun quietSuppressesInformationTipsButKeepsUrgentSafetyGuidance() = EngineFixture(setOf("RECIRC_LONG", "BELT_UNBUCKLED_START")).use { f ->
        f.drive()
        f.coach.quietForThirtyMinutes()
        f.coach.onSignals(sample(f, Signals.BELT to false))
        assertEquals(0, f.store.stat("RECIRC_LONG").count)
        assertFalse("c7-co2" in f.store.pendingCards)
        assertEquals(1, f.store.stat("BELT_UNBUCKLED_START").count)
        f.voice.pump()
        assertTrue(f.speech.spoken.single().contains("안전벨트"))
    }

    @Test fun quietExpiresWithoutAnAnnouncementAndCanResumeImmediately() = EngineFixture(setOf("RECIRC_LONG")).use { f ->
        f.drive()
        f.coach.quietForThirtyMinutes()
        f.now += 1_800_001
        assertFalse(f.coach.isQuiet)
        assertTrue(f.speech.spoken.isEmpty())
        f.coach.quietForThirtyMinutes()
        f.coach.resumeCoaching()
        assertEquals(0L, f.store.quietUntil)
        assertFalse(f.coach.isQuiet)
    }

    @Test fun quietForDriveEndsAtParkingAndThirtyMinutePreferenceSurvivesReload() = EngineFixture().use { f ->
        f.drive()
        f.coach.quietForDrive()
        assertTrue(f.coach.isQuiet)
        f.coach.endDrive()
        assertFalse(f.coach.isQuiet)
        f.coach.quietForThirtyMinutes()
        assertEquals(f.store.quietUntil, MemoryStore().apply { load(f.store.toJson()) }.quietUntil)
    }

    @Test fun coachingOffKeepsSafetyAndVehicleWarningsButDoesNotRecordMutedTips() = EngineFixture(setOf("LIGHT_OFF_NIGHT", "BELT_UNBUCKLED_START", "RECIRC_LONG")).use { f ->
        f.drive()
        f.store.settings = f.store.settings.copy(coachingOn = false)
        f.coach.onSignals(sample(f, Signals.BELT to false, Signals.IS_NIGHT to true, Signals.LOW_BEAM to false))
        assertEquals(1, f.store.stat("BELT_UNBUCKLED_START").count)
        assertEquals(0, f.store.stat("LIGHT_OFF_NIGHT").count)
        assertEquals(0, f.store.stat("RECIRC_LONG").count)
        assertFalse(MemoryStore().apply { load(f.store.toJson()) }.settings.coachingOn)
    }

    @Test fun callOrGleoDialogueShowsNonurgentCardWithoutQueuingSpeech() = EngineFixture(setOf("LIGHT_OFF_NIGHT")).use { f ->
        f.drive()
        f.coach.onSignals(sample(f, Signals.IS_NIGHT to true, Signals.LOW_BEAM to false, Signals.CALL_ACTIVE to true))
        assertEquals("LIGHT_OFF_NIGHT", f.coach.ui.value.card?.situation?.id)
        assertFalse(f.coach.mayStart(3))
        assertTrue(f.coach.mayStart(2))
        f.voice.pump()
        assertTrue(f.speech.spoken.isEmpty())
    }

    @Test fun recentNavigationSpeechSuppressesOnlyTheDuplicatePreview() = EngineFixture(setOf("TURN_PREP")).use { f ->
        f.drive()
        f.coach.onSignals(sample(f, Signals.NAV_TURN_TYPE to "RIGHT", Signals.NAV_TURN_DIST to 400,
            Signals.NAV_SPOKEN_AT to f.now - 1000))
        assertEquals("TURN_PREP", f.coach.ui.value.card?.situation?.id)
        f.voice.pump()
        assertTrue(f.speech.spoken.isEmpty())
    }

    @Test fun levelBecomesBriefOnlyAfterFiveEncountersAndThreeSuccessfulHandlings() = EngineFixture().use { f ->
        val situation = f.content.situation("TURN_SIGNAL_MISSING")!!
        assertEquals(HintLevel.FIRST, f.coach.levelFor(situation, SituationStat()))
        assertEquals(HintLevel.LEARNING, f.coach.levelFor(situation, SituationStat(count = 4, okStreak = 3)))
        assertEquals(HintLevel.BRIEF, f.coach.levelFor(situation, SituationStat(count = 5, okStreak = 3)))
        assertEquals(HintLevel.LEARNING, f.coach.levelFor(situation, SituationStat(count = 5, okStreak = 3, lastMissed = true)))
    }

    @Test fun missedActionCapturesItsTimePositionAndNavigationDescription() = EngineFixture(setOf("LIGHT_OFF_NIGHT")).use { f ->
        f.drive()
        f.coach.onSignals(sample(f, Signals.IS_NIGHT to true, Signals.LOW_BEAM to false,
            Signals.LATITUDE to 37.5, Signals.LONGITUDE to 127.0, Signals.NAV_TURN_DESC to "강남대로 우회전 전"))
        val miss = f.coach.currentDrive()!!.misses.single()
        assertEquals(f.now, miss.at)
        assertEquals(37.5, miss.lat!!, 0.0)
        val record = f.coach.endDrive()!!
        val restored = MemoryStore().apply { load(f.store.toJson()) }.drives.single().misses.single()
        assertEquals(miss, restored)
        assertTrue(record.tips.contains("LIGHT_OFF_NIGHT"))
    }

    @Test fun oldDriveRecordsWithoutPositionRemainReadableAndHistoryIsBounded() {
        val original = MemoryStore()
        repeat(35) { original.saveDrive(DriveRecord(it.toLong(), it + 100L, misses = listOf(Miss("LIGHT_OFF_NIGHT", 1)))) }
        val restored = MemoryStore().apply { load(original.toJson()) }
        assertEquals(30, restored.drives.size)
        assertNull(restored.drives.first().misses.single().lat)
        assertNull(restored.drives.first().misses.single().description)
    }
}
