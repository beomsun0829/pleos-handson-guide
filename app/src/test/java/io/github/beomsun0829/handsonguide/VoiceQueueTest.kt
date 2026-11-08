package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.voice.Utterance
import io.github.beomsun0829.handsonguide.voice.VoiceState
import org.junit.Assert.*
import org.junit.Test

class VoiceQueueTest {
    @Test fun urgentGuidanceInterruptsImmediatelyWithoutStartingCancelledNextSentence() = EngineFixture().use { f ->
        f.voice.say(Utterance("첫 문장. 취소돼야 하는 문장.", 4, "preview"))
        f.voice.pump()
        f.voice.say(Utterance("지금 멈춰요.", 1, "urgent"))
        f.voice.pump()
        assertEquals(listOf("첫 문장.", "지금 멈춰요."), f.speech.spoken)
        assertEquals(1, f.speech.stopped)
    }

    @Test fun higherPriorityWaitsForCurrentSentenceBoundary() = EngineFixture().use { f ->
        f.voice.say(Utterance("첫 문장. 남은 문장.", 4, "preview"))
        f.voice.pump()
        f.voice.say(Utterance("크루즈가 풀렸어요.", 2, "car"))
        assertEquals(listOf("첫 문장."), f.speech.spoken)
        f.speech.complete()
        f.voice.pump()
        assertEquals(listOf("첫 문장.", "크루즈가 풀렸어요."), f.speech.spoken)
    }

    @Test fun informationTipsExpireAndDuplicateWaitingKeysKeepTheNewestText() = EngineFixture().use { f ->
        f.voice.say(Utterance("오래된 예고.", 4, "old"))
        f.now += 4001
        f.voice.pump()
        assertTrue(f.speech.spoken.isEmpty())
        f.voice.say(Utterance("이전 팁.", 3, "same"))
        f.voice.say(Utterance("최신 팁.", 3, "same"))
        f.voice.pump()
        assertEquals(listOf("최신 팁."), f.speech.spoken)
    }

    @Test fun duplicateTextIsSuppressedForThirtySecondsUnlessExplicitlyRequested() = EngineFixture().use { f ->
        val utterance = Utterance("같은 안내.", 3, "repeat")
        f.voice.say(utterance); f.voice.pump(); f.speech.complete()
        assertFalse(f.voice.say(utterance))
        assertTrue(f.voice.say(utterance, force = true))
    }

    @Test fun stopAndSelectiveDiscardDoNotLeakOldAudioCallbacks() = EngineFixture().use { f ->
        f.voice.say(Utterance("정차 카드. 다음 문장.", 3, "tutorial")); f.voice.pump()
        f.voice.clearKey("tutorial")
        assertEquals(listOf("정차 카드."), f.speech.spoken)
        assertEquals(VoiceState.IDLE, f.voice.state.value)
        f.voice.say(Utterance("대기 안내.", 5, "tip"))
        f.voice.stop()
        f.now += 10_000; f.voice.pump()
        assertEquals(1, f.speech.spoken.size)
    }
}
