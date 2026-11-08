package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.voice.Utterance
import org.junit.Assert.*
import org.junit.Test

class DriveHistoryBoundaryTest {
    @Test fun aBrakeFromThePreviousDriveCannotCompleteANewRepeatWarning() = EngineFixture(setOf("HARD_BRAKE_REPEAT")).use { f ->
        f.drive()
        fun brake(value: Boolean) {
            f.now += 400
            f.coach.onSignals(Signals(mapOf(Signals.GEAR to "D", Signals.SPEED to 25,
                Signals.DRIVE_STATE to Signals.MOVING, Signals.HARD_BRAKE to value), f.now))
        }
        brake(false); brake(true)
        assertEquals(0, f.store.stat("HARD_BRAKE_REPEAT").count)
        f.coach.endDrive(); f.coach.startDrive()
        brake(false); brake(true)
        assertEquals(0, f.store.stat("HARD_BRAKE_REPEAT").count)
        brake(false); brake(true)
        assertEquals(1, f.store.stat("HARD_BRAKE_REPEAT").count)
    }

    @Test fun parkingCancelsQueuedRoadInstructionsAndReturnsHome() = EngineFixture(setOf("TURN_PREP")).use { f ->
        f.drive()
        f.emit(400, Signals.SPEED to 25, Signals.NAV_ACTIVE to true, Signals.NAV_TURN_TYPE to "LEFT", Signals.NAV_TURN_DIST to 300)
        assertNotNull(f.coach.ui.value.card)
        f.emit(400, Signals.GEAR to "P", Signals.SPEED to 0)
        f.voice.pump()
        assertTrue(f.speech.spoken.none { it.contains("차로") })
        assertEquals(Screen.HOME, f.app.ui.value.screen)
    }

    @Test fun endingDriveKeepsUrgentSafetySpeech() = EngineFixture().use { f ->
        f.drive()
        f.voice.say(Utterance("안전한 곳에 멈춰요.", 1, "sit:urgent"))
        f.coach.endDrive()
        f.voice.pump()
        assertEquals(listOf("안전한 곳에 멈춰요."), f.speech.spoken)
    }
}
