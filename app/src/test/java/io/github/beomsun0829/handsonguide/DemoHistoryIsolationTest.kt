package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.signal.SignalHub
import io.github.beomsun0829.handsonguide.signal.Signals
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DemoHistoryIsolationTest {
    @Test fun stoppingAnAbruptSyntheticBrakeDoesNotInventARealBrake() = runTest {
        var now = 1000L
        val hub = SignalHub(this) { now }
        hub.putAll(mapOf(Signals.GEAR to "D", Signals.SPEED to 30, Signals.BRAKE to false))
        hub.emitIfNeeded()
        hub.overlay(Signals.SPEED, 80, override = true); hub.emitIfNeeded()
        now += 1000; hub.overlay(Signals.SPEED, 0, override = true); hub.emitIfNeeded()
        now += 400; hub.emitIfNeeded()
        assertEquals(true, hub.signals.value.bool(Signals.HARD_BRAKE))
        hub.clearOverlay(); hub.emitIfNeeded(force = false)
        assertEquals(30.0, hub.signals.value.num(Signals.SPEED)!!, .01)
        assertEquals(0.0, hub.signals.value.num(Signals.ACCEL)!!, .01)
        assertEquals(false, hub.signals.value.bool(Signals.HARD_BRAKE))
    }

    @Test fun fakeDrivingDoesNotAddRealDrivingMinutes() = runTest {
        var now = 1000L
        val hub = SignalHub(this) { now }
        hub.putAll(mapOf(Signals.GEAR to "P", Signals.SPEED to 0))
        hub.emitIfNeeded()
        hub.overlay(Signals.GEAR, "D", override = true)
        hub.overlay(Signals.SPEED, 30, override = true)
        hub.emitIfNeeded()
        repeat(10) { now += 1000; hub.emitIfNeeded() }
        assertTrue(hub.signals.value.num(Signals.DRIVING_MIN)!! > .1)
        hub.clearOverlay(); hub.emitIfNeeded()
        assertEquals(0.0, hub.signals.value.num(Signals.DRIVING_MIN)!!, .001)
    }

    @Test fun realDrivingTimeKeepsAdvancingWhileDemoShowsParked() = runTest {
        var now = 1000L
        val hub = SignalHub(this) { now }
        hub.putAll(mapOf(Signals.GEAR to "D", Signals.SPEED to 30))
        hub.emitIfNeeded()
        repeat(10) { now += 1000; hub.emitIfNeeded() }
        hub.overlay(Signals.GEAR, "P", override = true)
        hub.overlay(Signals.SPEED, 0, override = true)
        repeat(10) { now += 1000; hub.emitIfNeeded() }
        hub.clearOverlay(); hub.emitIfNeeded()
        assertEquals(20.0 / 60, hub.signals.value.num(Signals.DRIVING_MIN)!!, .001)
    }

    @Test fun stoppingSyntheticRecirculationRestoresTheRealTimerThatNeverStopped() = runTest {
        var now = 1000L
        val hub = SignalHub(this) { now }
        hub.put(Signals.RECIRC, true); hub.emitIfNeeded()
        now += 20_000; hub.emitIfNeeded()
        hub.overlay(Signals.RECIRC, false, override = true); hub.emitIfNeeded()
        now += 60_000; hub.emitIfNeeded()
        assertEquals(0.0, hub.signals.value.num(Signals.RECIRC_MIN)!!, .001)
        hub.clearOverlay(); hub.emitIfNeeded()
        assertEquals(80.0 / 60, hub.signals.value.num(Signals.RECIRC_MIN)!!, .001)
    }

    @Test fun fakeRainCannotShortenTheNextRealRainDetectionWindow() = runTest {
        var now = 1000L
        val hub = SignalHub(this) { now }
        hub.put(Signals.WIPER_ON, false); hub.emitIfNeeded()
        hub.demoTiming(true)
        hub.overlay(Signals.WIPER_ON, true, override = true); hub.emitIfNeeded()
        now += 4000; hub.emitIfNeeded()
        assertEquals(true, hub.signals.value.bool(Signals.RAIN_LIKELY))
        hub.clearOverlay(); hub.emitIfNeeded()
        assertEquals(false, hub.signals.value.bool(Signals.RAIN_LIKELY))
        hub.put(Signals.WIPER_ON, true); hub.emitIfNeeded()
        now += 29_000; hub.emitIfNeeded()
        assertEquals(false, hub.signals.value.bool(Signals.RAIN_LIKELY))
        now += 1100; hub.emitIfNeeded()
        assertEquals(true, hub.signals.value.bool(Signals.RAIN_LIKELY))
    }

    @Test fun actualWipingDoesNotLoseItsElapsedTimeWhileDemoTurnsItOff() = runTest {
        var now = 1000L
        val hub = SignalHub(this) { now }
        hub.put(Signals.WIPER_ON, true); hub.emitIfNeeded()
        now += 20_000; hub.emitIfNeeded()
        hub.demoTiming(true); hub.overlay(Signals.WIPER_ON, false, override = true); hub.emitIfNeeded()
        now += 6000; hub.emitIfNeeded()
        hub.clearOverlay(); hub.emitIfNeeded()
        assertEquals(false, hub.signals.value.bool(Signals.RAIN_LIKELY))
        now += 5000; hub.emitIfNeeded()
        assertEquals(true, hub.signals.value.bool(Signals.RAIN_LIKELY))
    }

    @Test fun clearAlsoRestoresRealRainTimingWhenOtherStepsWillFollow() = runTest {
        var now = 1000L
        val hub = SignalHub(this) { now }
        hub.put(Signals.WIPER_ON, true); hub.emitIfNeeded()
        hub.demoTiming(true); now += 4000; hub.emitIfNeeded()
        assertEquals(true, hub.signals.value.bool(Signals.RAIN_LIKELY))
        hub.clearOverlay(); hub.emitIfNeeded(force = false)
        assertEquals(false, hub.signals.value.bool(Signals.RAIN_LIKELY))
    }

    @Test fun realMotionIsRestoredEvenIfUnrelatedSyntheticNavigationRemains() = runTest {
        var now = 1000L
        val hub = SignalHub(this) { now }
        hub.putAll(mapOf(Signals.GEAR to "D", Signals.NAV_ACTIVE to false))
        hub.overlay(Signals.SPEED, 60)
        hub.overlay(Signals.NAV_ACTIVE, true, override = true)
        hub.emitIfNeeded()
        now += 500; hub.put(Signals.SPEED, 0); hub.emitIfNeeded()
        assertEquals(0.0, hub.signals.value.num(Signals.ACCEL)!!, .001)
        assertEquals(false, hub.signals.value.bool(Signals.HARD_BRAKE))
        assertEquals(true, hub.signals.value.bool(Signals.NAV_ACTIVE))
        assertFalse(Signals.SPEED in hub.overlayKeys())
    }

    @Test fun restartingBeforeAnotherSnapshotCannotKeepOldSyntheticBrakeHistory() = runTest {
        var now = 1000L
        val hub = SignalHub(this) { now }
        hub.putAll(mapOf(Signals.GEAR to "D", Signals.SPEED to 30))
        hub.emitIfNeeded()
        hub.overlay(Signals.SPEED, 80, override = true); hub.emitIfNeeded()
        now += 1000; hub.overlay(Signals.SPEED, 0, override = true); hub.emitIfNeeded()
        now += 400; hub.emitIfNeeded()
        assertEquals(true, hub.signals.value.bool(Signals.HARD_BRAKE))
        hub.clearOverlay(); hub.overlay(Signals.SPEED, 0, override = true); hub.emitIfNeeded()
        assertEquals(0.0, hub.signals.value.num(Signals.ACCEL)!!, .001)
        assertEquals(false, hub.signals.value.bool(Signals.HARD_BRAKE))
    }

    @Test fun stoppingSyntheticTurnsDoesNotCreateARealPassedTurn() = runTest {
        var now = 1000L
        val hub = SignalHub(this) { now }
        hub.putAll(mapOf(Signals.NAV_ACTIVE to true, Signals.NAV_TURN_TYPE to "LEFT", Signals.NAV_TURN_DIST to 500))
        hub.emitIfNeeded()
        hub.overlay(Signals.NAV_TURN_DIST, 20, override = true); hub.emitIfNeeded()
        now += 400; hub.overlay(Signals.NAV_TURN_DIST, 600, override = true); hub.emitIfNeeded()
        assertEquals(true, hub.signals.value.bool(Signals.NAV_TURN_PASSED))
        hub.clearOverlay(); hub.emitIfNeeded()
        assertEquals(false, hub.signals.value.bool(Signals.NAV_TURN_PASSED))
    }

    @Test fun stoppingMatchesAControlHubThatOnlyEverSawTheActualStream() = runTest {
        var now = 1000L
        val subject = SignalHub(this) { now }
        val control = SignalHub(this) { now }
        val initial = mapOf(Signals.GEAR to "D", Signals.SPEED to 30, Signals.RECIRC to true,
            Signals.WIPER_ON to true, Signals.NAV_ACTIVE to true, Signals.NAV_TURN_TYPE to "LEFT", Signals.NAV_TURN_DIST to 400)
        subject.putAll(initial); control.putAll(initial)
        subject.emitIfNeeded(); control.emitIfNeeded()
        subject.demoTiming(true)
        subject.overlay(Signals.SPEED, 100, override = true)
        subject.overlay(Signals.RECIRC, false, override = true)
        subject.overlay(Signals.WIPER_ON, false, override = true)
        subject.overlay(Signals.NAV_TURN_DIST, 10, override = true)
        repeat(40) { i ->
            now += 1000
            val updates = mapOf(Signals.SPEED to (30 + i % 5), Signals.NAV_TURN_DIST to (400 - i * 5))
            subject.putAll(updates); control.putAll(updates)
            subject.emitIfNeeded(); control.emitIfNeeded()
        }
        subject.clearOverlay(); subject.emitIfNeeded(); control.emitIfNeeded()
        assertEquals(control.signals.value.values, subject.signals.value.values)
        now += 1000; subject.emitIfNeeded(); control.emitIfNeeded()
        assertEquals(control.signals.value.values, subject.signals.value.values)
    }

    @Test fun realSnapshotsRemainIndependentAndImmutableDuringInjection() = runTest {
        var now = 1000L
        val hub = SignalHub(this) { now }
        hub.putAll(mapOf(Signals.SPEED to 30, Signals.GEAR to "D")); hub.emitIfNeeded()
        val before = hub.realSignals.value
        hub.overlay(Signals.SPEED, 120, override = true)
        now += 1000; hub.emitIfNeeded()
        assertEquals(30.0, hub.realSignals.value.num(Signals.SPEED)!!, .01)
        assertEquals(120.0, hub.signals.value.num(Signals.SPEED)!!, .01)
        hub.put(Signals.SPEED, 45)
        now += 1000; hub.emitIfNeeded()
        assertEquals(45.0, hub.realSignals.value.num(Signals.SPEED)!!, .01)
        assertEquals(30.0, before.num(Signals.SPEED)!!, .01)
    }
}
