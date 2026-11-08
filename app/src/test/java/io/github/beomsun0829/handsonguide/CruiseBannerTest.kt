package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.signal.Signals
import org.junit.Assert.*
import org.junit.Test

class CruiseBannerTest {
    @Test fun acceleratorOverrideIsNotARelease() = EngineFixture(setOf("CRUISE_RELEASED")).use { f ->
        f.drive()
        f.emit(400, Signals.SPEED to 80, Signals.CRUISE to "ACTIVATED")
        f.emit(400, Signals.CRUISE to "OVERRIDE")
        f.emit(400, Signals.CRUISE to "ACTIVATED")
        assertNull(f.coach.ui.value.banner)
    }

    @Test fun brakeReleaseShowsTheBannerAndSaysToTakeOver() = EngineFixture(setOf("CRUISE_RELEASED")).use { f ->
        f.drive()
        f.emit(400, Signals.SPEED to 80, Signals.CRUISE to "ACTIVATED")
        f.emit(400, Signals.CRUISE to "SUSPENDED")
        assertEquals("크루즈 해제됨", f.coach.ui.value.banner?.word)
        f.voice.pump()
        f.speech.complete(); f.voice.pump()
        assertTrue(f.speech.spoken.toString(), f.speech.spoken.joinToString(" ").contains("직접 속도"))
    }
}
