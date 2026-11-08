package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.coach.CoachUi
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.ui.driveCard
import io.github.beomsun0829.handsonguide.ui.nextHard
import org.junit.Assert.*
import org.junit.Test

class NavigationDriveCardTest {
    @Test fun navigationTurnsAreNotRedrawnAsTheNowCard() {
        val merge = Signals(mapOf(Signals.NAV_ACTIVE to true, Signals.NAV_TURN_TYPE to "MERGE",
            Signals.NAV_TURN_DIST to 450.0, Signals.NAV_TURN_DESC to "합류 안내"), 1000)
        assertNull(driveCard(CoachUi(), merge))
        val next = nextHard(CoachUi(), merge)!!
        assertEquals("합류", next.word)
        assertEquals("450 m", next.distance)
        val exit = Signals(merge.values + (Signals.NAV_TURN_TYPE to "HIGHWAY_EXIT"), 1000)
        assertEquals("고속도로 출구", nextHard(CoachUi(), exit)!!.word)
    }

    @Test fun plainTurnsAreLeftToNavigationButUnprotectedLeftIsHard() {
        val left = Signals(mapOf(Signals.NAV_ACTIVE to true, Signals.NAV_TURN_TYPE to "LEFT", Signals.NAV_TURN_DIST to 1200.0), 1000)
        assertNull(nextHard(CoachUi(), left))
        val unprotected = Signals(left.values + (Signals.NAV_UNPROTECTED to true), 1000)
        val next = nextHard(CoachUi(), unprotected)!!
        assertEquals("비보호 좌회전", next.word)
        assertEquals("1.2 km", next.distance)
    }

    @Test fun nearestHardSituationWins() {
        val s = Signals(mapOf(Signals.NAV_ACTIVE to true, Signals.NAV_TURN_TYPE to "TUNNEL", Signals.NAV_TURN_DIST to 900.0,
            Signals.NAV_SCHOOL_ZONE_DISTANCE to 300.0), 1000)
        assertEquals("어린이보호구역", nextHard(CoachUi(), s)!!.word)
    }

    @Test fun inactiveOrUnknownNavigationShowsNothingAhead() {
        val inactive = Signals(mapOf(Signals.NAV_ACTIVE to false, Signals.NAV_TURN_TYPE to "MERGE", Signals.NAV_TURN_DIST to 300.0), 1000)
        assertNull(nextHard(CoachUi(), inactive))
        assertNull(driveCard(CoachUi(), inactive))
        val unknown = Signals(mapOf(Signals.NAV_ACTIVE to true, Signals.NAV_TURN_TYPE to "UNKNOWN", Signals.NAV_TURN_DIST to 300.0), 1000)
        assertNull(nextHard(CoachUi(), unknown))
    }
}
