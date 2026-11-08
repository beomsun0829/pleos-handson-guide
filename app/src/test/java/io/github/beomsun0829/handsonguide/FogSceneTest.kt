package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.scene.*
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.voice.Utterance
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FogSceneTest {
    private fun script() = SceneScript.parse(File("src/main/assets/scenes/front-fog.json").readText())
    private fun advance(player: FogPlayback,seconds: Float,input: FogInput=FogInput(false)): SceneFrame {
        var frame=player.advance(0f,input)
        repeat((seconds*10).toInt()) { frame=player.advance(.1f,input) }
        return frame
    }

    @Test fun lessonShowsDriverThenButtonThenClearingThenPracticeCloseup() {
        val s=script()
        assertEquals(listOf("driver","controls","clear","practice"),s.phases.map { it.view })
        assertTrue(s.phases.all { it.end-it.start in 3f..6f })
        assertEquals(.84f,s.sample(0f).fog!!.opacity,.001f)
        val pressed=s.sample(5.7f)
        assertTrue(pressed.fog!!.buttonOn); assertTrue(pressed.fog!!.handPress!!>.99f)
        assertTrue(s.sample(8.1f).fog!!.opacity>s.sample(11.9f).fog!!.opacity)
        assertTrue(s.sample(10f).fog!!.airflow)
        assertEquals(s.phases[1].camera,s.sample(15f).camera)
    }

    @Test fun silentPlaybackEndsAtTheRealControlAndWaitsRatherThanLoopingFogForever() {
        val player=FogPlayback(script())
        val waiting=advance(player,20f)
        assertEquals(3,waiting.phaseIndex); assertEquals("practice",waiting.phase.view)
        assertFalse(waiting.fog!!.buttonOn)
        assertEquals(3,advance(player,20f).phaseIndex)
    }

    @Test fun sentenceEventsHoldTheMatchingShotUntilTheNextSentenceAndFinishAtControls() {
        val player=FogPlayback(script())
        assertEquals(0,advance(player,6f,FogInput(false,1,0)).phaseIndex)
        assertEquals(1,advance(player,5f,FogInput(false,1,1)).phaseIndex)
        val effect=advance(player,4f,FogInput(false,1,2))
        assertEquals(2,effect.phaseIndex); assertTrue(effect.fog!!.opacity<.05f)
        assertEquals(3,advance(player,0f,FogInput(false,1,3)).phaseIndex)
        assertEquals(3,advance(player,0f,FogInput(false,1,3,true)).phaseIndex)
    }

    @Test fun actualTogglePressesTheButtonBeforeShowingItsEffectThenReturnsToRestoreControl() {
        val player=FogPlayback(script())
        advance(player,13f)
        val pressed=advance(player,.1f,FogInput(true))
        assertEquals(1,pressed.phaseIndex); assertTrue(pressed.fog!!.buttonOn)
        val start=advance(player,.7f,FogInput(true))
        assertEquals(2,start.phaseIndex); assertTrue(start.fog!!.opacity>.7f)
        val nearEnd=advance(player,3.5f,FogInput(true))
        assertEquals(2,nearEnd.phaseIndex); assertTrue(nearEnd.fog!!.opacity<.1f)
        assertEquals(3,advance(player,.6f,FogInput(true)).phaseIndex)
        val restored=advance(player,.8f,FogInput(false))
        assertEquals(3,restored.phaseIndex); assertFalse(restored.fog!!.buttonOn)
    }

    @Test fun alreadyOnOrFirstKnownOnShowsTheCurrentButtonWithoutInventingAUserAction() {
        val initial=FogPlayback(script()).advance(0f,FogInput(true))
        assertEquals(3,initial.phaseIndex); assertTrue(initial.fog!!.buttonOn)
        val unknown=FogPlayback(script())
        advance(unknown,2f,FogInput(null))
        assertEquals(3,unknown.advance(0f,FogInput(true)).phaseIndex)
    }

    @Test fun tappingReplayWorksAfterSilentPracticeAndDoesNotChangeTheRealButtonInput() {
        val player=FogPlayback(script())
        advance(player,15f)
        player.replay()
        assertEquals(0,advance(player,1f).phaseIndex)
        assertEquals(1,advance(player,4f).phaseIndex)
        val on=FogPlayback(script())
        on.advance(0f,FogInput(true)); on.replay()
        val frame=advance(on,1f,FogInput(true))
        assertEquals(0,frame.phaseIndex); assertTrue(frame.fog!!.buttonOn)
    }

    @Test fun aNewNarrationRunCannotLeaveTheLiveEffectClockInTheFuture() {
        val player=FogPlayback(script())
        advance(player,4f,FogInput(false,1,0))
        advance(player,1f,FogInput(true,1,0))
        val restarted=advance(player,1f,FogInput(true,2,1))
        assertEquals(1,restarted.phaseIndex)
        assertTrue(restarted.fog!!.buttonOn)
        assertTrue(restarted.time>=0f)
    }

    @Test fun cabinAssetHasSeparateControlSymbolAndHandWithValidNormals() {
        val meshes=RoadModel.parse(File("src/main/assets/scenes/cabin-kit.glb").readBytes())
        assertEquals(setOf("cabin","wheel","windshield","button","defrost-symbol","hand","box"),meshes.keys)
        assertTrue(meshes.getValue("defrost-symbol").primitives.all { it.role=="unlit" })
        meshes.values.flatMap { it.primitives }.forEach { p ->
            for(i in p.vertices.indices step 6) {
                val len=(3..5).sumOf { j -> (p.vertices[i+j]*p.vertices[i+j]).toDouble() }
                assertEquals(1.0,len,.001)
            }
        }
    }

    @Test fun malformedFogShotSequenceIsRejected() {
        val json=JSONObject(File("src/main/assets/scenes/front-fog.json").readText())
        json.getJSONArray("phases").getJSONObject(1).put("view","driver")
        assertThrows(IllegalArgumentException::class.java) { SceneScript.parse(json.toString()) }
    }

    @Test fun queueReportsOnlySentencesActuallyDispatchedAndDoesNotAdvanceAfterCancellation() = EngineFixture(emptySet()).use { f ->
        val indices=ArrayList<Int>()
        f.voice.say(Utterance("하나. 둘. 셋.",3,"tutorial",onSentence={indices+=it}))
        f.voice.pump(); assertEquals(listOf(0),indices)
        f.speech.complete(); assertEquals(listOf(0,1),indices)
        f.voice.clearKey("tutorial")
        f.speech.complete(); assertEquals(listOf(0,1),indices)
    }

    @Test fun tutorialPublishesFourNarrationStepsAndCancelsTheOldCardCursor() = EngineFixture(emptySet()).use { f ->
        f.emit(100,Signals.DEFROST_FRONT to false)
        f.tutorial.setReadAloud(true)          // off by default; the narration machinery still works when enabled
        f.app.openCards(listOf("c6-front")); f.voice.pump()
        assertEquals(0,f.tutorial.ui.value.narration?.sentence)
        f.speech.complete(); assertEquals(1,f.tutorial.ui.value.narration?.sentence)
        f.speech.complete(); assertEquals(2,f.tutorial.ui.value.narration?.sentence)
        f.speech.complete(); assertEquals(3,f.tutorial.ui.value.narration?.sentence)
        f.speech.complete(); assertTrue(f.tutorial.ui.value.narration!!.finished)
        f.tutorial.stopReading(); assertNull(f.tutorial.ui.value.narration)
        f.app.openChapter(1)
        f.speech.complete()
        assertNotEquals("c6-front",f.tutorial.ui.value.card?.id)
    }
}
