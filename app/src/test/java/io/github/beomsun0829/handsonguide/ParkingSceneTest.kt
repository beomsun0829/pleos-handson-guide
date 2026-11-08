package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.scene.*
import io.github.beomsun0829.handsonguide.signal.Signals
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ParkingSceneTest {
    private fun script() = SceneScript.parse(File("src/main/assets/scenes/parking-order.json").readText())
    private fun advance(p: ParkingPlayback, seconds: Float, input: ParkingInput = ParkingInput()): SceneFrame {
        var f=p.advance(0f,input)
        repeat((seconds*10).toInt()) { f=p.advance(.1f,input) }
        return f
    }

    @Test fun exampleShowsControlsThenWrongAndCorrectLoadThenWaitsForPractice() {
        val s=script()
        assertEquals(listOf("stop","parking-brake","neutral","release","park","pawl-load","brake-load","practice"),s.phases.map { it.view })
        assertTrue(s.phases.all { it.end-it.start in 3f..6f })
        assertTrue(s.sample(17f).parking!!.pawlLoaded)
        assertFalse(s.sample(17f).phase.correct)
        assertTrue(s.sample(21f).phase.correct)
        assertFalse(s.sample(21f).parking!!.pawlLoaded)
        val p=ParkingPlayback(s)
        assertEquals(7,advance(p,30f).phaseIndex)
        assertEquals(7,advance(p,30f).phaseIndex)
    }

    @Test fun narrationHoldsEachShotUntilNextSentence() {
        val p=ParkingPlayback(script())
        (0..7).forEach { i ->
            assertEquals(i,advance(p,7f,ParkingInput(narrationRun=1,sentence=i)).phaseIndex)
        }
    }

    @Test fun actualActionsTakePrecedenceAndCompletionShowsLoadThenActualControl() {
        val p=ParkingPlayback(script())
        val initial=ParkingInput("P",false,true,0.0)
        advance(p,1f,initial)
        val neutral=initial.copy(gear="N",parkingBrake=true,steps=2)
        val f=advance(p,.1f,neutral)
        assertTrue(f.parking!!.live)
        assertEquals("foot",f.parking!!.focus)
        assertEquals("N",f.parking!!.gear)
        val done=neutral.copy(gear="P",steps=4,complete=true)
        assertTrue(advance(p,.1f,done).parking!!.diagram)
        val final=advance(p,3.2f,done)
        assertEquals(7,final.phaseIndex)
        assertEquals("P",final.parking!!.gear)
        assertFalse(final.parking!!.diagram)
    }

    @Test fun missingActualValuesStayUnknownAndReplayDoesNotAdvanceExercise() {
        val p=ParkingPlayback(script())
        val unknown=ParkingInput(steps=2,pending=true)
        val f=advance(p,1f,unknown)
        assertNull(f.parking!!.gear)
        assertNull(f.parking!!.parkingBrake)
        p.replay()
        assertEquals(0,advance(p,.1f,unknown).phaseIndex)
        assertEquals(7,advance(p,25f,unknown).phaseIndex)
    }

    @Test fun neutralReleaseRequiresStoppedCarParkingBrakeAndCompletedPriorSteps() {
        fun safe(gear: String?="N",speed: Double?=0.0,pb: Boolean?=true,brake: Boolean?=false,
                 steps: Int=2,paused: Boolean=false,pending: Boolean=false,uxr: Boolean=false) =
            ParkingPlayback.canRender(Signals(mapOf(Signals.GEAR to gear,Signals.SPEED to speed,
                Signals.PARKING_BRAKE to pb,Signals.BRAKE to brake,Signals.UXR_DO to uxr),0L),steps,paused,pending)
        assertTrue(safe())
        assertTrue(safe(steps=0,brake=true))
        assertFalse(safe(steps=1))
        assertFalse(safe(speed=.5))
        assertFalse(safe(speed=null))
        assertFalse(safe(pb=false))
        assertFalse(safe(brake=null))
        assertFalse(safe(gear="D"))
        assertFalse(safe(gear="R"))
        assertFalse(safe(paused=true))
        assertFalse(safe(pending=true))
        assertFalse(safe(uxr=true))
    }
}
