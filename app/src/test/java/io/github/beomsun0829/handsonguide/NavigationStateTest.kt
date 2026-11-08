package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.signal.Derivations
import io.github.beomsun0829.handsonguide.signal.NavCamera
import io.github.beomsun0829.handsonguide.signal.NavCaution
import io.github.beomsun0829.handsonguide.signal.NavTurn
import io.github.beomsun0829.handsonguide.signal.NavigationState
import io.github.beomsun0829.handsonguide.signal.Signals
import org.junit.Assert.*
import org.junit.Test

class NavigationStateTest {
    private fun route() = NavigationState().apply { connection(true); status("ROUTE_DRIVE", 1000) }

    @Test fun disconnectedAndUnreportedAreUnknownRatherThanZero() {
        val s = NavigationState().snapshot(1000)
        assertEquals(false, s[Signals.NAV_CONNECTED])
        listOf(Signals.NAV_ACTIVE, Signals.NAV_TURN_DIST, Signals.NAV_SPEED_LIMIT,
            Signals.LATITUDE, Signals.NAV_REMAIN_TIME).forEach { assertNull(s[it]) }
    }

    @Test fun nearestValidTurnUsesMetresAndReviewedDirection() {
        val state = route()
        state.turns(listOf(NavTurn("TURN_LEFT", 450.5, "다음 교차로"), NavTurn("TURN_RIGHT", 80.0, "우회전"),
            NavTurn("TURN_LEFT", -1.0, "invalid"), NavTurn("TURN_UTURN", Double.NaN, "invalid")), 1000)
        val s = state.snapshot(1000)
        assertEquals("RIGHT", s[Signals.NAV_TURN_TYPE])
        assertEquals(80.0, s[Signals.NAV_TURN_DIST])
        assertEquals("우회전", s[Signals.NAV_TURN_DESC])
    }

    @Test fun sdkTurnKindsDoNotTurnForksIntoTurnsOrUnknownIntoPermission() {
        val cases = mapOf("TURN_ENTER_IC_R" to "HIGHWAY_ENTER", "TURN_EXIT_IC_L" to "HIGHWAY_EXIT",
            "TURN_DEVIDE_MERGE" to "MERGE", "TURN_ENTER_ROUNDABOUT_09" to "ROUNDABOUT",
            "TURN_KEEP_R" to "KEEP_RIGHT", "TURN_SHARP_L" to "LEFT", "TURN_UTURN" to "UTURN",
            "NEW_SDK_VALUE" to "UNKNOWN")
        cases.forEach { (raw, normalized) -> assertEquals(normalized, NavigationState.normalizeTurn(raw)) }
    }

    @Test fun unprotectedFlagRequiresExplicitNavigationDescription() {
        val state = route()
        state.turns(listOf(NavTurn("TURN_LEFT", 200.0, "좌회전")), 1000)
        assertEquals(false, state.snapshot(1000)[Signals.NAV_UNPROTECTED])
        state.turns(listOf(NavTurn("TURN_LEFT", 200.0, "비보호 좌회전")), 1000)
        assertEquals(true, state.snapshot(1000)[Signals.NAV_UNPROTECTED])
    }

    @Test fun turnExpiryAndEmptyListRemoveOldGuidance() {
        val state = route()
        state.turns(listOf(NavTurn("TURN_RIGHT", 40.0, "우회전")), 1000)
        assertNull(state.snapshot(11_001)[Signals.NAV_TURN_DIST])
        state.turns(emptyList(), 2000)
        assertNull(state.snapshot(2000)[Signals.NAV_TURN_TYPE])
    }

    @Test fun staleRouteStateCannotBeKeptAliveByLocationOrTbt() {
        val state = route()
        state.location(37.5, 127.0, "주소", "route", 1, 17_000)
        state.turns(listOf(NavTurn("TURN_RIGHT", 30.0, "우회전")), 17_000)
        val s = state.snapshot(17_000)
        assertNull(s[Signals.NAV_ACTIVE])
        assertNull(s[Signals.NAV_TURN_TYPE])
        assertEquals(37.5, s[Signals.LATITUDE])
    }

    @Test fun cancelAndLateResultsCannotReviveTheOldRoute() {
        val state = route()
        state.turns(listOf(NavTurn("TURN_LEFT", 40.0, "좌회전")), 1000)
        state.destination("목적지", 1000, 120, 1000)
        state.routeCancelled(2000)
        state.turns(listOf(NavTurn("TURN_LEFT", 20.0, "late")), 2001)
        state.destination("late", 900, 100, 2001)
        assertEquals(false, state.snapshot(2001)[Signals.NAV_ACTIVE])
        assertNull(state.snapshot(2001)[Signals.NAV_DESTINATION])
        state.status("ROUTE_DRIVE", 2100)
        assertNull(state.snapshot(2100)[Signals.NAV_TURN_TYPE])
    }

    @Test fun routeChangeClearsDestinationAndTurnUntilFreshData() {
        val state = route()
        state.destination("이전", 1000, 120, 1000)
        state.turns(listOf(NavTurn("TURN_LEFT", 40.0, "좌회전")), 1000)
        state.routeChanging(2000)
        assertNull(state.snapshot(2000)[Signals.NAV_TURN_TYPE])
        assertNull(state.snapshot(2000)[Signals.NAV_DESTINATION])
        state.routeState("ROUTE_GUIDING", true, 3000)
        state.turns(listOf(NavTurn("TURN_RIGHT", 70.0, "새 경로")), 3000)
        assertEquals("RIGHT", state.snapshot(3000)[Signals.NAV_TURN_TYPE])
    }

    @Test fun freeDrivingKeepsRoadSpeedLimitAndCautionsWithoutRouteGuidance() {
        val state = route()
        state.speedLimit(30, 1000)
        state.cautions(listOf(NavCaution("school", "SCHOOL_ZONE", 150), NavCaution("bump", "SPEED_BUMP", 50)), 1000)
        state.status("FREE_DRIVE", 1000)
        val s = state.snapshot(1000)
        assertEquals(false, s[Signals.NAV_ACTIVE])
        assertEquals(30, s[Signals.NAV_SPEED_LIMIT])
        assertEquals(150, s[Signals.NAV_SCHOOL_ZONE_DISTANCE])
        assertNull(state.snapshot(11_001)[Signals.NAV_SCHOOL_ZONE_DISTANCE])
    }

    @Test fun invalidLimitsAndCoordinatesDoNotCreateVehicleFacts() {
        val state = route()
        state.speedLimit(0, 1000)
        state.location(Double.NaN, 127.0, "invalid", null, null, 1000)
        assertNull(state.snapshot(1000)[Signals.NAV_SPEED_LIMIT])
        assertNull(state.snapshot(1000)[Signals.LATITUDE])
        assertNull(state.snapshot(1000)[Signals.LONGITUDE])
        state.location(0.0, 0.0, "valid coordinates", null, null, 2000)
        assertEquals(0.0, state.snapshot(2000)[Signals.LATITUDE])
        assertNull(state.snapshot(12_001)[Signals.LATITUDE])
    }

    @Test fun disconnectClearsAllRouteRoadAndPositionValuesUntilNewCallbacks() {
        val state = route()
        state.speedLimit(50, 1000)
        state.cautions(listOf(NavCaution("s", "SCHOOL_ZONE", 10)), 1000)
        state.cameras(listOf(NavCamera("c", "SPEED_CAMERA", 20, 30, true, false)), 1000)
        state.location(37.5, 127.0, "주소", "r", 1, 1000)
        state.connection(false); state.connection(true)
        val s = state.snapshot(1001)
        listOf(Signals.NAV_SPEED_LIMIT, Signals.NAV_CAUTIONS, Signals.NAV_CAMERAS,
            Signals.LATITUDE, Signals.NAV_ROUTE_ID).forEach { assertNull(s[it]) }
    }

    @Test fun cancellationMissingTbtAndNewRouteAreNotMissedTurns() {
        fun test(change: MutableMap<String, Any?>.() -> Unit) {
            val d = Derivations()
            val values = mutableMapOf<String, Any?>(Signals.SPEED to 20, Signals.GEAR to "D",
                Signals.NAV_ACTIVE to true, Signals.NAV_ROUTE_ID to "old", Signals.NAV_TURN_DIST to 30.0, Signals.NAV_TURN_TYPE to "LEFT")
            d.apply(values, 1000)
            values.change(); d.apply(values, 2000)
            assertEquals(false, values[Signals.NAV_TURN_PASSED])
        }
        test { put(Signals.NAV_TURN_DIST, null) }
        test { put(Signals.NAV_ACTIVE, false) }
        test { put(Signals.NAV_ROUTE_ID, "new"); put(Signals.NAV_TURN_DIST, 400.0) }
    }

    @Test fun knownNextTurnTransitionStillEmitsPassageForThreeSeconds() {
        val d = Derivations()
        val values = mutableMapOf<String, Any?>(Signals.SPEED to 20, Signals.GEAR to "D",
            Signals.NAV_ACTIVE to true, Signals.NAV_TURN_DIST to 30.0, Signals.NAV_TURN_TYPE to "LEFT")
        d.apply(values, 1000)
        values[Signals.NAV_TURN_DIST] = 400.0
        d.apply(values, 2000)
        assertEquals(true, values[Signals.NAV_TURN_PASSED])
        d.apply(values, 5001)
        assertEquals(false, values[Signals.NAV_TURN_PASSED])
    }
}
