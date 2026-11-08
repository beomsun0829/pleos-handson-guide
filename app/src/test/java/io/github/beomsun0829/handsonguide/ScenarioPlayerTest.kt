package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.demo.ScenarioPlayer
import io.github.beomsun0829.handsonguide.signal.SignalHub
import io.github.beomsun0829.handsonguide.signal.Signals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioPlayerTest {
    private fun scenario(steps: String, policy: String = "false") = ScenarioPlayer.parse("""{"name":"test","override":$policy,"steps":$steps}""")

    @Test fun realValuesWinUnlessExplicitlyOverriddenAndClearRestoresThem() = runTest {
        val hub = SignalHub(this) { testScheduler.currentTime }
        hub.put(Signals.GEAR, "P")
        hub.overlay(Signals.GEAR, "D"); hub.emitIfNeeded()
        assertEquals("P", hub.signals.value.gear)
        assertFalse(Signals.GEAR in hub.overlayKeys())
        hub.overlay(Signals.GEAR, "D", override = true); hub.emitIfNeeded()
        assertEquals("D", hub.signals.value.gear)
        hub.put(Signals.GEAR, "R")
        hub.clearOverlay(); hub.emitIfNeeded()
        assertEquals("R", hub.signals.value.gear)
    }

    @Test fun missingOnlyInjectionYieldsWhenRealSignalArrivesAndExplicitNullMasksIt() = runTest {
        val hub = SignalHub(this)
        hub.overlay(Signals.NAV_ACTIVE, true); hub.emitIfNeeded()
        assertEquals(true, hub.signals.value.bool(Signals.NAV_ACTIVE))
        hub.put(Signals.NAV_ACTIVE, false); hub.emitIfNeeded()
        assertEquals(false, hub.signals.value.bool(Signals.NAV_ACTIVE))
        hub.overlay(Signals.NAV_ACTIVE, null, override = true); hub.emitIfNeeded()
        assertFalse(hub.signals.value.has(Signals.NAV_ACTIVE))
        hub.clearOverlay(); hub.emitIfNeeded()
        assertEquals(false, hub.signals.value.bool(Signals.NAV_ACTIVE))
    }

    @Test fun completionWaitsForRampsAndRestoresRealSignals() = runTest {
        val hub = SignalHub(this)
        hub.put(Signals.SPEED, 0)
        val player = ScenarioPlayer(this, hub, clock = { testScheduler.currentTime }) {}
        player.play(scenario("""[{"t":0,"ramp":{"speedKmh":[0,40],"dur":2}}]""", "true"))
        runCurrent(); advanceTimeBy(1000); runCurrent(); hub.emitIfNeeded()
        assertEquals("test", player.running.value)
        assertEquals(20.0, hub.signals.value.num(Signals.SPEED)!!, .01)
        advanceTimeBy(1000); runCurrent(); hub.emitIfNeeded()
        assertNull(player.running.value)
        assertEquals(0.0, hub.signals.value.num(Signals.SPEED)!!, .01)
        assertTrue(hub.overlayKeys().isEmpty())
    }

    @Test fun clearCancelsRampsSoTheyCannotReintroduceValues() = runTest {
        val hub = SignalHub(this)
        hub.put(Signals.SPEED, 7)
        val player = ScenarioPlayer(this, hub, clock = { testScheduler.currentTime }) {}
        player.play(scenario("""[{"t":0,"ramp":{"speedKmh":[0,40],"dur":2}},{"t":0.3,"clear":true},{"t":1,"wait":0}]""", "true"))
        runCurrent(); advanceTimeBy(500); runCurrent(); hub.emitIfNeeded()
        assertEquals(7.0, hub.signals.value.num(Signals.SPEED)!!, .01)
        assertTrue(hub.overlayKeys().isEmpty())
        advanceTimeBy(2000); runCurrent()
        assertTrue(hub.overlayKeys().isEmpty())
    }

    @Test fun aNewSetCancelsThePreviousRampForItsKey() = runTest {
        val hub = SignalHub(this)
        val player = ScenarioPlayer(this, hub, clock = { testScheduler.currentTime }) {}
        player.play(scenario("""[{"t":0,"ramp":{"speedKmh":[0,40],"dur":2}},{"t":0.3,"set":{"speedKmh":42}},{"t":3,"wait":0}]""", "true"))
        runCurrent(); advanceTimeBy(1500); runCurrent(); hub.emitIfNeeded()
        assertEquals(42.0, hub.signals.value.num(Signals.SPEED)!!, .01)
        player.stop(); runCurrent()
    }

    @Test fun restartingCannotLetOldCleanupClearTheNewSession() = runTest {
        val hub = SignalHub(this)
        val player = ScenarioPlayer(this, hub, clock = { testScheduler.currentTime }) {}
        player.play(scenario("""[{"t":0,"ramp":{"speedKmh":[0,40],"dur":10}}]""", "true"))
        runCurrent(); advanceTimeBy(1000)
        player.play(scenario("""[{"t":0,"set":{"gear":"R"}},{"t":2,"wait":0}]""", "true"))
        runCurrent(); hub.emitIfNeeded()
        assertEquals("R", hub.signals.value.gear)
        assertEquals("test", player.running.value)
        player.stop(); runCurrent(); advanceTimeBy(20_000); runCurrent()
        assertNull(player.running.value)
        assertTrue(hub.overlayKeys().isEmpty())
    }

    @Test fun waitDelaysSubsequentActionsAndSayIsForwarded() = runTest {
        val hub = SignalHub(this)
        val actions = ArrayList<String>()
        val player = ScenarioPlayer(this, hub, clock = { testScheduler.currentTime }) { actions += it }
        player.play(scenario("""[{"t":0,"say":"안내","wait":2},{"t":0,"action":"home"}]"""))
        runCurrent(); assertEquals(listOf("say:안내"), actions)
        advanceTimeBy(1999); runCurrent(); assertEquals(1, actions.size)
        advanceTimeBy(1); runCurrent(); assertEquals(listOf("say:안내", "home"), actions)
    }

    @Test fun overrideCanBeLimitedToNamedKeysAndChangedPerStep() = runTest {
        val hub = SignalHub(this)
        hub.putAll(mapOf(Signals.GEAR to "P", Signals.SPEED to 0))
        val player = ScenarioPlayer(this, hub, clock = { testScheduler.currentTime }) {}
        player.play(scenario("""[{"t":0,"set":{"gear":"D","speedKmh":30}},{"t":1,"override":false,"set":{"gear":"R"}},{"t":3,"wait":0}]""", """["gear"]"""))
        runCurrent(); hub.emitIfNeeded()
        assertEquals("D", hub.signals.value.gear)
        assertEquals(0.0, hub.signals.value.num(Signals.SPEED)!!, .01)
        advanceTimeBy(1000); runCurrent(); hub.emitIfNeeded()
        assertEquals("P", hub.signals.value.gear)
        player.stop(); runCurrent()
    }

    @Test fun invalidDurationsAndOverrideTypesAreRejectedBeforePlayback() {
        listOf("""[{"t":-1}]""", """[{"t":0,"wait":-1}]""", """[{"t":0,"ramp":{"x":[0,1],"dur":-1}}]""").forEach {
            assertThrows(IllegalArgumentException::class.java) { scenario(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { scenario("[]", "4") }
    }

    @Test fun derivedSignalsAlsoRequireExplicitOverride() = runTest {
        val hub = SignalHub(this)
        hub.put(Signals.WIPER_ON, false)
        hub.overlay(Signals.RAIN_LIKELY, true); hub.emitIfNeeded()
        assertEquals(false, hub.signals.value.bool(Signals.RAIN_LIKELY))
        assertFalse(Signals.RAIN_LIKELY in hub.overlayKeys())
        hub.overlay(Signals.RAIN_LIKELY, true, override = true); hub.emitIfNeeded()
        assertEquals(true, hub.signals.value.bool(Signals.RAIN_LIKELY))
        hub.clearOverlay(); hub.emitIfNeeded()
        assertEquals(false, hub.signals.value.bool(Signals.RAIN_LIKELY))
    }

    @Test fun demoRainTimingIsIsolatedAndRestoredAtCompletion() = runTest {
        val demo = SignalHub(this) { testScheduler.currentTime + 1000 }
        val normal = SignalHub(this) { testScheduler.currentTime + 1000 }
        demo.put(Signals.WIPER_ON, true); normal.put(Signals.WIPER_ON, true)
        val player = ScenarioPlayer(this, demo, clock = { testScheduler.currentTime }) {}
        player.play(scenario("""[{"t":4,"wait":0}]""")); runCurrent()
        demo.emitIfNeeded(); normal.emitIfNeeded()
        advanceTimeBy(3500); runCurrent(); demo.emitIfNeeded(); normal.emitIfNeeded()
        assertEquals(true, demo.signals.value.bool(Signals.RAIN_LIKELY))
        assertEquals(false, normal.signals.value.bool(Signals.RAIN_LIKELY))
        advanceTimeBy(500); runCurrent(); demo.emitIfNeeded()
        assertEquals(false, demo.signals.value.bool(Signals.RAIN_LIKELY))
    }

    @Test fun fallbackInjectionCannotHideHazardsDerivedFromRealTelltales() = runTest {
        val hub = SignalHub(this)
        hub.put(Signals.TELLTALES, listOf(
            io.github.beomsun0829.handsonguide.signal.Telltale("TURN_SIGNAL_LEFT", "BLINK", "GREEN"),
            io.github.beomsun0829.handsonguide.signal.Telltale("TURN_SIGNAL_RIGHT", "BLINK", "GREEN")))
        hub.overlay(Signals.HAZARD, false); hub.emitIfNeeded()
        assertEquals(true, hub.signals.value.bool(Signals.HAZARD))
        assertFalse(Signals.HAZARD in hub.overlayKeys())
    }
}
