package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.store.SituationStat
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RoadGuidanceTest {
    private fun turn(f: EngineFixture, type: String, distance: Double, vararg extra: Pair<String, Any?>) {
        f.emit(400, Signals.SPEED to 25, Signals.NAV_ACTIVE to true, Signals.NAV_TURN_TYPE to type,
            Signals.NAV_TURN_DIST to distance, Signals.TURN_SIGNAL to "LEFT", *extra)
    }

    @Test fun unprotectedRequiresExplicitClassificationAndReplacesGenericPrep() = EngineFixture(setOf("TURN_PREP", "UNPROTECTED_LEFT_PREP")).use { f ->
        f.drive(); turn(f, "LEFT", 300.0)
        assertEquals("TURN_PREP", f.coach.ui.value.card?.situation?.id)
        assertEquals(0, f.store.stat("UNPROTECTED_LEFT_PREP").count)
        turn(f, "LEFT", 280.0, Signals.NAV_UNPROTECTED to true)
        assertEquals("UNPROTECTED_LEFT_PREP", f.coach.ui.value.card?.situation?.id)
        f.voice.pump()
        assertTrue(f.speech.spoken.single().contains("비보호"))
        assertTrue("c12-unprotected" in f.store.pendingCards)
        assertTrue(f.coach.currentDrive()!!.misses.isEmpty())
    }

    @Test fun routeGuidanceCannotTriggerWithoutAnActiveDestination() = EngineFixture(setOf("UTURN_PREP", "ROUNDABOUT_PREP", "HIGHWAY_EXIT_PREP")).use { f ->
        f.drive()
        listOf("UTURN" to 200, "ROUNDABOUT" to 200, "HIGHWAY_EXIT" to 1500).forEach { (type, distance) ->
            f.emit(400, Signals.NAV_ACTIVE to false, Signals.NAV_TURN_TYPE to type, Signals.NAV_TURN_DIST to distance, Signals.SPEED to 30)
            assertNull(f.coach.ui.value.card)
        }
    }

    @Test fun roundaboutAndUturnLinkTheirOwnCardsAndNeverGrantPermission() = EngineFixture(setOf("UTURN_PREP", "ROUNDABOUT_PREP")).use { f ->
        f.drive(); turn(f, "ROUNDABOUT", 250.0)
        assertEquals("ROUNDABOUT_PREP", f.coach.ui.value.card?.situation?.id)
        assertTrue("c12-roundabout" in f.store.pendingCards)
        turn(f, "UTURN", 200.0)
        assertEquals("UTURN_PREP", f.coach.ui.value.card?.situation?.id)
        f.voice.pump()
        assertTrue(f.speech.spoken.single().contains("조건"))
        assertFalse(f.speech.spoken.single().contains("돌아도"))
    }

    @Test fun schoolZoneWorksWithoutDestinationAndUsesLowerReportedLimit() = EngineFixture(setOf("SCHOOL_ZONE")).use { f ->
        f.drive()
        f.emit(400, Signals.NAV_ACTIVE to false, Signals.NAV_SCHOOL_ZONE_DISTANCE to 80, Signals.NAV_SPEED_LIMIT to 20, Signals.SPEED to 15)
        assertEquals("SCHOOL_ZONE", f.coach.ui.value.card?.situation?.id)
        assertTrue(f.coach.ui.value.card!!.sub!!.contains("20"))
        assertFalse(f.coach.ui.value.card!!.sub!!.contains("30"))
        f.voice.pump()
        assertTrue(f.speech.spoken.single().contains("20"))
        f.emit(400, Signals.NAV_SCHOOL_ZONE_DISTANCE to null)
        assertNull(f.coach.ui.value.card)
    }

    @Test fun quietModeKeepsSchoolSafetyButSuppressesInformationalTurnPreview() = EngineFixture(setOf("SCHOOL_ZONE", "ROUNDABOUT_PREP")).use { f ->
        f.drive(); f.coach.quietForThirtyMinutes()
        turn(f, "ROUNDABOUT", 300.0)
        assertNull(f.coach.ui.value.card)
        f.emit(400, Signals.NAV_SCHOOL_ZONE_DISTANCE to 50)
        assertEquals("SCHOOL_ZONE", f.coach.ui.value.card?.situation?.id)
    }

    @Test fun highwayExitStartsEarlyAndDoesNotInventAnExitSide() = EngineFixture(setOf("HIGHWAY_EXIT_PREP")).use { f ->
        f.drive(); turn(f, "HIGHWAY_EXIT", 2500.0)
        assertNull(f.coach.ui.value.card)
        turn(f, "HIGHWAY_EXIT", 1500.0)
        assertEquals("HIGHWAY_EXIT_PREP", f.coach.ui.value.card?.situation?.id)
        assertFalse(f.content.situation("HIGHWAY_EXIT_PREP")!!.say.first.contains("오른쪽"))
        assertTrue("c13-missed" in f.store.pendingCards)
    }

    @Test fun tunnelLightRequiresKnownOffAndEndsWhenItTurnsOn() = EngineFixture(setOf("TUNNEL_LIGHT")).use { f ->
        f.drive(); turn(f, "TUNNEL", 350.0, Signals.LOW_BEAM to null)
        assertNull(f.coach.ui.value.card)
        turn(f, "TUNNEL", 330.0, Signals.LOW_BEAM to false)
        assertEquals("TUNNEL_LIGHT", f.coach.ui.value.card?.situation?.id)
        f.emit(400, Signals.LOW_BEAM to true); f.emit(400)
        assertNull(f.coach.ui.value.card)
    }

    @Test fun allSixSituationsHaveValidCardsAndBriefLevelsRemainShort() = EngineFixture().use { f ->
        val ids = listOf("UNPROTECTED_LEFT_PREP", "SCHOOL_ZONE", "ROUNDABOUT_PREP", "UTURN_PREP", "HIGHWAY_EXIT_PREP", "TUNNEL_LIGHT")
        ids.forEach { id ->
            val sit = f.content.situation(id)!!
            assertTrue(sit.relatedCards.isNotEmpty())
            sit.relatedCards.forEach { assertNotNull(f.content.card(it)) }
            assertEquals(io.github.beomsun0829.handsonguide.coach.HintLevel.BRIEF,
                f.coach.levelFor(sit, SituationStat(count = 5, okStreak = 3, lastMissed = false)))
            assertTrue(sit.say.brief!!.length <= 20)
        }
    }

    @Test fun shippedDemoAssetsParseWithExplicitOverrideAndFourMinutePtTimeline() {
        val dir = File("src/main/assets/demo")
        dir.listFiles()!!.filter { it.extension == "json" }.forEach { io.github.beomsun0829.handsonguide.demo.ScenarioPlayer.parse(it.readText()) }
        val pt = io.github.beomsun0829.handsonguide.demo.ScenarioPlayer.parse(File(dir, "01-pt-main.json").readText())
        assertEquals(240.0, pt.steps.maxOf { it.getDouble("t") }, .01)
        assertFalse(pt.overrides.allows(Signals.GEAR))
        assertTrue(pt.overrides.allows(Signals.NAV_ACTIVE))
    }
}
