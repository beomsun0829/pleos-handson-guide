package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.home.HomeWarnings
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.signal.Telltale
import org.junit.Assert.*
import org.junit.Test

class HomeWarningsTest {
    private val tpms = Telltale("TPMS","ON","YELLOW")
    private val engine = Telltale("ENGINE","ON","YELLOW")
    private val oil = Telltale("OIL_PRESSURE","ON","RED")
    private fun signals(ignition: String? = "ON", lights: List<Telltale>? = listOf(tpms)) =
        Signals(mapOf(Signals.IGNITION to ignition,Signals.TELLTALES to lights,Signals.GEAR to "P",
            Signals.SPEED to 0,Signals.DRIVE_STATE to Signals.PARKED),1000)

    @Test fun acknowledgementStaysCollapsedAcrossUpdatesAndCanBeExpandedAgain() {
        val policy = HomeWarnings()
        val warning = policy.update(signals()).single()
        assertFalse(warning.collapsed)
        assertTrue(policy.setCollapsed(warning,true)!!.single().collapsed)
        val after = policy.update(signals()).single()
        assertTrue(after.collapsed)
        assertFalse(policy.setCollapsed(after,false)!!.single().collapsed)
    }

    @Test fun multipleWarningsHaveIndependentAcknowledgementsAndRedComesFirst() {
        val policy = HomeWarnings()
        val warnings = policy.update(signals(lights=listOf(tpms,oil,engine)))
        assertEquals(listOf("OIL_PRESSURE","TPMS","ENGINE"),warnings.map { it.type })
        val folded = policy.setCollapsed(warnings[0],true)!!
        assertTrue(folded[0].collapsed); assertFalse(folded[1].collapsed); assertFalse(folded[2].collapsed)
        val changed = policy.update(signals(lights=listOf(tpms,oil)))
        assertTrue(changed.first().collapsed)
        assertFalse(changed.last().collapsed)
    }

    @Test fun newWarningAndEscalatedColorOpenEvenWhenPreviousWarningWasAcknowledged() {
        val policy = HomeWarnings()
        policy.setCollapsed(policy.update(signals()).single(),true)
        val added = policy.update(signals(lights=listOf(tpms,engine)))
        assertTrue(added.first().collapsed); assertFalse(added.last().collapsed)
        val red = policy.update(signals(lights=listOf(tpms.copy(color="RED"))))
        assertTrue(red.single().red); assertFalse(red.single().collapsed)
    }

    @Test fun staleClickCannotAcknowledgeTheNewWarningSet() {
        val policy = HomeWarnings()
        val old = policy.update(signals()).single()
        policy.update(signals(lights=listOf(tpms,engine)))
        assertNull(policy.setCollapsed(old,true))
        assertTrue(policy.update(signals(lights=listOf(tpms,engine))).none { it.collapsed })
    }

    @Test fun observedRestartReopensOnceAndCrankingToOnDoesNotReopenTwice() {
        for (off in listOf("OFF","LOCK","ACC")) {
            val policy = HomeWarnings()
            policy.setCollapsed(policy.update(signals()).single(),true)
            assertTrue(policy.update(signals(ignition=off)).single().collapsed)
            val starting = policy.update(signals(ignition="START")).single()
            assertFalse(starting.collapsed)
            policy.setCollapsed(starting,true)
            assertTrue(policy.update(signals(ignition="ON")).single().collapsed)
        }
    }

    @Test fun unknownIgnitionOrTemporaryMissingWarningDataDoesNotInventARestart() {
        val policy = HomeWarnings()
        policy.setCollapsed(policy.update(signals()).single(),true)
        assertTrue(policy.update(signals(ignition=null)).single().collapsed)
        assertTrue(policy.update(signals(ignition=null,lights=null)).isEmpty())
        assertTrue(policy.update(signals()).single().collapsed)
    }

    @Test fun aKnownOffWarningReappearsExpandedAndParkedNormalIndicatorsStayHidden() {
        val policy = HomeWarnings()
        policy.setCollapsed(policy.update(signals()).single(),true)
        assertTrue(policy.update(signals(lights=emptyList())).isEmpty())
        assertFalse(policy.update(signals()).single().collapsed)
        val ignored = listOf("PARKING_BRAKE","DOOR_OPEN","TRUNK_OPEN","SEAT_BELT","HEADLAMP_HIGH").map {
            Telltale(it,"ON","RED")
        }
        assertEquals(listOf("TPMS"),policy.update(signals(lights=ignored+tpms)).map { it.type })
    }

    @Test fun collapseSurvivesScreenNavigationButIsRejectedDuringDrivingAndUxr() = EngineFixture(emptySet()).use { f ->
        f.emit(100,Signals.IGNITION to "ON",Signals.TELLTALES to listOf(tpms))
        val warning = f.app.ui.value.homeWarnings.single()
        f.app.setWarningCollapsed(warning,true)
        f.app.openChapter(1); f.app.back()
        assertTrue(f.app.ui.value.homeWarnings.single().collapsed)
        f.app.setWarningCollapsed(f.app.ui.value.homeWarnings.single(),false)
        f.emit(100,Signals.GEAR to "D")
        f.app.setWarningCollapsed(f.app.ui.value.homeWarnings.single(),true)
        assertFalse(f.app.ui.value.homeWarnings.single().collapsed)
        f.emit(100,Signals.GEAR to "P",Signals.UXR_DO to true)
        f.app.setWarningCollapsed(f.app.ui.value.homeWarnings.single(),true)
        assertFalse(f.app.ui.value.homeWarnings.single().collapsed)
        f.emit(100,Signals.UXR_DO to false)
        f.app.setWarningCollapsed(f.app.ui.value.homeWarnings.single(),true)
        assertTrue(f.app.ui.value.homeWarnings.single().collapsed)
    }
}
