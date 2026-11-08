package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.voice.SpeechCompletion
import org.junit.Assert.*
import org.junit.Test

class SpeechCompletionTest {
    @Test fun aDuplicateTerminalEventCompletesOnlyOnce() {
        val gate = SpeechCompletion()
        val token = gate.begin()
        var count = 0
        assertTrue(gate.complete(token) { count++ })
        assertFalse(gate.complete(token) { count++ })
        assertEquals(1, count)
    }

    @Test fun stopInvalidatesEvenBeforePlatformStopHasRun() {
        val gate = SpeechCompletion()
        val token = gate.begin()
        gate.invalidate()
        assertFalse(gate.complete(token) { fail("Cancelled speech callback") })
    }

    @Test fun oldCallbacksCannotCompleteAReplacementUtterance() {
        val gate = SpeechCompletion()
        val old = gate.begin()
        val next = gate.begin()
        assertFalse(gate.complete(old) { fail("Replaced speech callback") })
        assertTrue(gate.complete(next) {})
    }
}
