package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.voice.ParkedVoiceInput
import io.github.beomsun0829.handsonguide.voice.RecognitionEngine
import io.github.beomsun0829.handsonguide.voice.Utterance
import io.github.beomsun0829.handsonguide.voice.VoiceIntent
import io.github.beomsun0829.handsonguide.voice.VoiceState
import org.junit.Assert.*
import org.junit.Test

class ParkedVoiceInputTest {
    private class Recognizer : RecognitionEngine {
        var starts = 0
        var stops = 0
        var text: (String, Boolean) -> Unit = { _, _ -> }
        var error: (String) -> Unit = {}
        override fun start(onText: (String, Boolean) -> Unit, onError: (String) -> Unit) {
            starts++; text = onText; error = onError
        }
        override fun stop() { stops++ }
        fun final(text: String) = this.text(text, true)
    }

    private fun input(f: EngineFixture, r: Recognizer) = ParkedVoiceInput(f.app, f.scope, r).apply {
        setForeground(true); start()
    }

    @Test fun keywordsDistinguishAdviceStateAndCommands() {
        assertEquals(VoiceIntent(VoiceIntent.Kind.HOWTO, "defog_front"), VoiceIntent.parse("김서림 어떻게 해?"))
        // Narrow phrases win over the broad word inside them.
        assertEquals("steering_position", VoiceIntent.parse("핸들 높이 어떻게 맞춰?").topic)
        assertEquals("steering_grip", VoiceIntent.parse("핸들 어떻게 잡아?").topic)
        assertEquals("bus_lane", VoiceIntent.parse("버스전용차로 들어가도 돼?").topic)
        assertEquals("trunk", VoiceIntent.parse("트렁크 어떻게 열어?").topic)
        assertEquals("toll_gate", VoiceIntent.parse("하이패스 차로 어디야").topic)
        assertEquals(VoiceIntent.Kind.STATE, VoiceIntent.parse("지금 내기야 외기야?").kind)
        assertEquals(VoiceIntent.Kind.OPEN, VoiceIntent.parse("김서림 카드 열어줘").kind)
        assertEquals(VoiceIntent.Kind.RESUME, VoiceIntent.parse("조용히 하지 마").kind)
        assertEquals(VoiceIntent.Kind.REPEAT, VoiceIntent.parse("다시 말해줘").kind)
        assertEquals(VoiceIntent.Kind.QUIET, VoiceIntent.parse("조용히 해줘").kind)
        assertEquals(VoiceIntent.Kind.QUIET, VoiceIntent.parse("운전 안내 조용히 해줘").kind)
        assertEquals(VoiceIntent.Kind.RESUME, VoiceIntent.parse("운전 안내 다시 켜줘").kind)
        assertEquals(VoiceIntent.Kind.UNKNOWN, VoiceIntent.parse("오늘 점심").kind)
    }

    @Test fun finalAnswerOffersReviewedCardAndStopsRecognition() = EngineFixture().use { f ->
        val r = Recognizer(); val input = input(f, r)
        input.listen()
        assertEquals(VoiceState.LISTENING, f.voice.state.value)
        r.text("김서림", false)
        assertNull(input.ui.value.answer)
        r.final("김서림 어떻게 해?")
        assertFalse(input.ui.value.listening)
        assertEquals("c6-front", input.ui.value.cardId)
        assertTrue(input.ui.value.answer!!.contains(f.content.card("c6-front")!!.headline))
        input.openCard()
        assertEquals("c6-front", f.tutorial.ui.value.card?.id)
        assertNull(input.ui.value.answer)
    }

    @Test fun driveCancelsListeningAndRejectsLateFinalResult() = EngineFixture().use { f ->
        val r = Recognizer(); val input = input(f, r)
        input.listen(); val late = r.text
        f.drive()
        late("조용히 해줘", true)
        assertFalse(input.ui.value.listening)
        assertNull(input.ui.value.answer)
        assertFalse(f.coach.isQuiet)
        input.listen()
        assertEquals(1, r.starts)
    }

    @Test fun restrictionAndBackgroundCancelAndInvalidateCallbacks() = EngineFixture().use { f ->
        val r = Recognizer(); val input = input(f, r)
        input.listen(); val first = r.text
        input.setForeground(false)
        first("다시 알려줘", true)
        assertNull(input.ui.value.answer)
        input.setForeground(true); input.listen()
        f.emit(400, Signals.UXR_DO to true)
        r.final("김서림 어떻게 해?")
        assertNull(input.ui.value.answer)
        assertFalse(input.ui.value.listening)
    }

    @Test fun cancelledSessionCannotReplaceANewTranscript() = EngineFixture().use { f ->
        val r = Recognizer(); val input = input(f, r)
        input.listen(); val old = r.text
        input.dismiss(); input.listen()
        r.text("현재 질문", false); old("지난 질문", true)
        assertEquals("현재 질문", input.ui.value.transcript)
        assertTrue(input.ui.value.listening)
    }

    @Test fun stateAnswersPreserveUnknownAndRespectActualValue() = EngineFixture().use { f ->
        val r = Recognizer(); val input = input(f, r)
        input.listen(); r.final("지금 내기야 외기야?")
        assertTrue(input.ui.value.answer!!.contains("확인할 수 없어요"))
        f.emit(400, Signals.RECIRC to false)
        input.listen(); r.final("지금 내기야 외기야?")
        assertEquals("지금은 외기 유입이에요.", input.ui.value.answer)
    }

    @Test fun lanePermissionUsesFixedAnswerAndQuietCanBeResumed() = EngineFixture().use { f ->
        val r = Recognizer(); val input = input(f, r)
        input.listen(); r.final("지금 차선 바꿔도 돼?")
        assertEquals("미러와 사각지대 경고등을 확인하세요. 깜빡이 먼저.", input.ui.value.answer)
        input.listen(); r.final("조용히 해줘"); assertTrue(f.coach.isQuiet)
        input.listen(); r.final("다시 알려줘"); assertFalse(f.coach.isQuiet)
    }

    @Test fun urgentGuidanceInterruptsRecognitionButInformationWaits() = EngineFixture().use { f ->
        val r = Recognizer(); val input = input(f, r)
        input.listen()
        f.voice.say(Utterance("정보 팁.", 4, "tip")); f.voice.pump()
        assertTrue(input.ui.value.listening)
        f.voice.say(Utterance("지금 멈춰요.", 1, "urgent")); f.voice.pump()
        assertFalse(input.ui.value.listening)
        assertEquals(listOf("지금 멈춰요."), f.speech.spoken)
    }

    @Test fun errorsDoNotExecuteAndUnknownSpeedCannotOpenMicrophone() = EngineFixture().use { f ->
        val r = Recognizer(); val input = input(f, r)
        input.listen(); r.error("음성 입력을 사용할 수 없어요.")
        assertEquals("음성 입력을 사용할 수 없어요.", input.ui.value.answer)
        assertEquals(VoiceState.IDLE, f.voice.state.value)
        f.emit(400, Signals.SPEED to null)
        input.listen()
        assertEquals(1, r.starts)
    }

    @Test fun gleoAnswerShowsCardLinkWithoutAppSpeechOrRecognition() = EngineFixture().use { f ->
        val r = Recognizer(); val input = input(f, r)
        input.presentExternalAnswer("검수된 안내", "c6-front")
        assertEquals("검수된 안내", input.ui.value.answer)
        assertEquals("c6-front", input.ui.value.cardId)
        f.voice.pump()
        assertTrue(f.speech.spoken.isEmpty())
        assertEquals(0, r.starts)
        input.openCard()
        assertEquals("c6-front", f.tutorial.ui.value.card?.id)
    }

    @Test fun gleoAnswerCannotOpenAnOverlayWhileDrivingOrInBackground() = EngineFixture().use { f ->
        val r = Recognizer(); val input = input(f, r)
        input.setForeground(false)
        input.presentExternalAnswer("배경 답", "c6-front")
        assertNull(input.ui.value.answer)
        input.setForeground(true); f.drive()
        input.presentExternalAnswer("주행 답", "c6-front")
        assertNull(input.ui.value.answer)
        assertEquals(Screen.DRIVE, f.app.ui.value.screen)
    }

    @Test fun gleoAnswerEndsLocalListeningAndRejectsItsLateCommand() = EngineFixture().use { f ->
        val r = Recognizer(); val input = input(f, r)
        input.listen(); val late = r.text
        input.presentExternalAnswer("Gleo 답", "c6-front")
        late("운전 안내 조용히 해줘", true)
        assertFalse(input.ui.value.listening)
        assertEquals("Gleo 답", input.ui.value.answer)
        assertFalse(f.coach.isQuiet)
    }
}
