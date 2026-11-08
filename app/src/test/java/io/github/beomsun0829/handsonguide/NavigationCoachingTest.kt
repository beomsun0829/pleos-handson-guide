package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.signal.Signals
import org.junit.Assert.*
import org.junit.Test

class NavigationCoachingTest {
    private fun turn(f: EngineFixture, route: String = "route-1", type: String = "LEFT") {
        f.emit(400, Signals.NAV_ACTIVE to true, Signals.NAV_ROUTE_ID to route,
            Signals.NAV_TURN_TYPE to type, Signals.NAV_TURN_DIST to 400.0, Signals.SPEED to 20)
    }

    @Test fun cancellationRemovesActiveAndWaitingGuidanceImmediately() = EngineFixture(setOf("TURN_PREP")).use { f ->
        f.drive(); turn(f)
        assertEquals("TURN_PREP", f.coach.ui.value.card?.situation?.id)
        f.emit(400, Signals.NAV_ACTIVE to false)
        assertNull(f.coach.ui.value.card)
        f.voice.pump()
        assertTrue(f.speech.spoken.isEmpty())
    }

    @Test fun expiredNavigationRemovesOldCardWithoutInventingAnotherEncounter() = EngineFixture(setOf("TURN_PREP")).use { f ->
        f.drive(); turn(f)
        assertNotNull(f.coach.ui.value.card)
        val count = f.store.stat("TURN_PREP").count
        f.emit(400, Signals.NAV_TURN_DIST to null, Signals.NAV_TURN_TYPE to null)
        assertNull(f.coach.ui.value.card)
        assertEquals(count, f.store.stat("TURN_PREP").count)
    }

    @Test fun aNewRouteReplacesOldDirectionWithoutWaitingForItsCooldown() = EngineFixture(setOf("TURN_PREP")).use { f ->
        f.drive(); turn(f)
        assertEquals("왼쪽 차로로", f.coach.ui.value.card?.word)
        turn(f, "route-2", "RIGHT")
        assertEquals("오른쪽 차로로", f.coach.ui.value.card?.word)
        assertEquals(2, f.store.stat("TURN_PREP").count)
    }

    @Test fun currentRoadSpeedTipsDoNotNeedAnActiveDestinationAndExpireWithLimit() = EngineFixture(setOf("SPEEDING")).use { f ->
        assertNotNull(f.content.situation("SPEEDING"))
        f.drive()
        f.emit(400, Signals.NAV_ACTIVE to false, Signals.NAV_SPEED_LIMIT to 30, Signals.SPEED to 45)
        f.emit(4000)
        assertEquals("SPEEDING", f.coach.ui.value.card?.situation?.id)
        f.emit(400, Signals.NAV_SPEED_LIMIT to null)
        assertNull(f.coach.ui.value.card)
    }
}
