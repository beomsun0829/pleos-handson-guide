package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.coach.CoachEngine
import io.github.beomsun0829.handsonguide.content.Content
import io.github.beomsun0829.handsonguide.signal.SignalHub
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.store.CardResult
import io.github.beomsun0829.handsonguide.store.GuideLevel
import io.github.beomsun0829.handsonguide.store.MemoryStore
import io.github.beomsun0829.handsonguide.tutorial.TutorialEngine
import io.github.beomsun0829.handsonguide.tutorial.chapterStart
import io.github.beomsun0829.handsonguide.tutorial.isComplete
import io.github.beomsun0829.handsonguide.voice.SpeechEngine
import io.github.beomsun0829.handsonguide.voice.VoiceQueue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class GuideFlowTest {
    private var now = 1_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val content = Content.parse(asset("cards"), asset("situations"), asset("telltales"))
    private val store = MemoryStore().apply { onboarded = true }
    private val hub = SignalHub(scope) { now }
    private val voice = VoiceQueue(scope, object : SpeechEngine {
        override val ready = false
        override fun speak(id: String, text: String, guidance: Boolean, onDone: () -> Unit) = Unit
        override fun stop() = Unit
        override fun setRate(rate: Float) = Unit
    }) { now }
    private val tutorial = TutorialEngine(content, store, voice) { now }
    private val coach = CoachEngine(content, store, voice) { now }
    private val app = AppController(scope, content, store, hub, voice, tutorial, coach) { now }

    private fun asset(name: String) = File("src/main/assets/content/$name.json").readText()
    private fun signal(vararg values: Pair<String, Any?>) {
        now += 400
        values.forEach { hub.put(it.first, it.second) }
        hub.emitIfNeeded()
    }
    private fun start(gear: String = "P") {
        app.start()
        signal(Signals.GEAR to gear, Signals.SPEED to 0, Signals.VEHICLE_CONNECTED to true)
    }

    @After fun cleanup() { scope.cancel() }

    @Test fun backgroundingKeepsThePageAndResumesItWithoutStoppingCoaching() {
        start()
        app.openChapter(6)
        app.onBackground()
        // The driver comes back to the page they were reading (Pleos review: restore the last state).
        assertEquals(Screen.CARD, app.ui.value.screen)
        assertEquals("c6-front", tutorial.ui.value.card?.id)
        app.onForeground()
        assertEquals("c6-front", tutorial.ui.value.card?.id)
        app.onBackground()
        signal(Signals.GEAR to "D")
        assertTrue(coach.driving)
        assertEquals(Screen.DRIVE, app.ui.value.screen)
    }

    @Test fun runtimeCanStopAndRestartWithoutRetainingAnActiveCard() {
        start()
        app.openChapter(6)
        app.stop()
        assertEquals(Screen.BOOT, app.ui.value.screen)
        assertFalse(tutorial.active)
        assertEquals("c6-front", store.pausedCard)
        app.start()
        signal(Signals.GEAR to "D")
        assertEquals(Screen.DRIVE, app.ui.value.screen)
        signal(Signals.GEAR to "P")
        app.resumePaused()
        assertEquals("c6-front", tutorial.ui.value.card?.id)
    }

    @Test fun skippedCardsDoNotCountAsCompleted() {
        assertFalse(CardResult.SKIPPED.isComplete())
        assertFalse((null as CardResult?).isComplete())
        listOf(CardResult.READ, CardResult.CONFIRMED, CardResult.UNSUPPORTED).forEach { assertTrue(it.isComplete()) }
    }

    @Test fun chapterPrefersItsPausedCardOverAnEarlierUnseenCard() {
        store.pausedCard = "c6-why"
        app.openChapter(6)
        assertEquals("c6-why", tutorial.ui.value.card?.id)
    }

    @Test fun pauseInAnotherChapterDoesNotChangeTheSelectedChapter() {
        store.pausedCard = "c1-distance"
        app.openChapter(6)
        assertEquals("c6-front", tutorial.ui.value.card?.id)
    }

    @Test fun skippedCardRemainsTheNextUnfinishedCard() {
        val cards = content.cardsOf(6)
        store.setCardResult(cards[0].id, CardResult.SKIPPED)
        assertEquals(0, chapterStart(cards, store))
    }

    @Test fun pendingCardsAreRemovedOnlyWhenCompleted() {
        store.addPending(listOf("c6-front", "c6-rear"))
        app.openCards(listOf("c6-front", "c6-rear"))
        assertEquals(setOf("c6-front", "c6-rear"), store.pendingCards)
        tutorial.skip()
        assertTrue("c6-front" in store.pendingCards)
        store.setCardResult("c6-front", CardResult.CONFIRMED)
        assertEquals(setOf("c6-rear"), store.pendingCards)
    }

    @Test fun bootInDriveSkipsOnboardingImmediately() {
        store.onboarded = false
        start("D")
        assertEquals(Screen.DRIVE, app.ui.value.screen)
    }

    @Test fun bootInReverseOpensReverseScreen() {
        start("R")
        assertEquals(Screen.REVERSE, app.ui.value.screen)
    }

    @Test fun uxrWhileStationaryPausesCardAndBlocksParkedNavigation() {
        start()
        app.openChapter(6)
        signal(Signals.UXR_DO to true)
        assertEquals(Screen.DRIVE, app.ui.value.screen)
        assertFalse(tutorial.active)
        assertEquals("c6-front", store.pausedCard)
        app.show(Screen.SETTINGS)
        app.openChapter(1)
        app.openCards(listOf("c6-why"))
        assertEquals(Screen.DRIVE, app.ui.value.screen)
        assertFalse(tutorial.active)
        signal(Signals.UXR_DO to false)
        // Nothing moved: the page the driver was on comes back.
        assertEquals(Screen.CARD, app.ui.value.screen)
        assertEquals("c6-front", tutorial.ui.value.card?.id)
    }

    @Test fun ordinaryCardPausesOnDriveEvenAtZeroSpeed() {
        start()
        app.openChapter(6)
        signal(Signals.GEAR to "D")
        assertEquals(Screen.DRIVE, app.ui.value.screen)
        assertEquals("c6-front", store.pausedCard)
    }

    @Test fun stationaryGearPracticeHoldsCardButUxrAlwaysWins() {
        start()
        app.openCards(listOf("c4-gear"))
        signal(Signals.GEAR to "R")
        assertEquals(Screen.CARD, app.ui.value.screen)
        signal(Signals.UXR_DO to true)
        assertEquals(Screen.REVERSE, app.ui.value.screen)
        assertFalse(tutorial.active)
    }

    @Test fun gearPracticeUnderRestrictionsCompletesWhenBackInPark() {
        // UX restrictions turn on in D at a standstill (as on the Pleos emulator): the card gives way to
        // the driving screen, and P brings the driver back to it with the D-then-P practice done.
        start()
        app.openCards(listOf("c4-gear"))
        signal(Signals.BRAKE to true)
        signal(Signals.GEAR to "D", Signals.UXR_DO to true)
        assertEquals(Screen.DRIVE, app.ui.value.screen)
        signal(Signals.GEAR to "P", Signals.UXR_DO to false)
        assertEquals(Screen.CARD, app.ui.value.screen)
        assertEquals("c4-gear", tutorial.ui.value.card?.id)
        assertEquals(CardResult.CONFIRMED, store.cardResult("c4-gear"))
    }

    @Test fun anyCardPageComesBackAfterOnlyShiftingButNotAfterDriving() {
        start()
        app.openChapter(6)
        signal(Signals.GEAR to "D"); signal(Signals.GEAR to "P")
        assertEquals("c6-front", tutorial.ui.value.card?.id)
        signal(Signals.GEAR to "D"); signal(Signals.SPEED to 20); signal(Signals.SPEED to 0); signal(Signals.GEAR to "P")
        assertNotEquals(Screen.CARD, app.ui.value.screen)
    }

    @Test fun movingGearPracticePausesBeforeItCanConfirm() {
        start()
        app.openCards(listOf("c4-gear"))
        signal(Signals.GEAR to "D", Signals.SPEED to 10)
        assertEquals(Screen.DRIVE, app.ui.value.screen)
        assertNull(store.cardResult("c4-gear"))
    }

    @Test fun longDriveReturnsHome() {
        start("D")
        now += 180_000
        signal(Signals.GEAR to "P")
        assertEquals(Screen.HOME, app.ui.value.screen)
    }

    @Test fun onboardingOnlySetsCoachingLevelAndOpensHome() {
        start()
        app.answerOnboarding(true)
        assertEquals(GuideLevel.FAMILIAR, store.settings.guideLevel)
        assertEquals(Screen.HOME, app.ui.value.screen)
        assertFalse(tutorial.active)
        app.answerOnboarding(false)
        assertEquals(GuideLevel.BEGINNER, store.settings.guideLevel)
    }
}
