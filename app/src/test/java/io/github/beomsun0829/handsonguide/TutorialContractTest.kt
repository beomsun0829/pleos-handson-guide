package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.content.Content
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.store.CardResult
import io.github.beomsun0829.handsonguide.store.MemoryStore
import io.github.beomsun0829.handsonguide.tutorial.TutorialEngine
import io.github.beomsun0829.handsonguide.tutorial.VerifyState
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

class TutorialContractTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val content = Content.parse(asset("cards"), asset("situations"), asset("telltales"))
    private val store = MemoryStore().apply { settings = settings.copy(tutorialReadAloud = false) }
    private var now = 10_000L
    private val values = mutableMapOf<String, Any?>(Signals.GEAR to "P", Signals.SPEED to 0,
        Signals.BRAKE to true, Signals.PARKING_BRAKE to false, Signals.VEHICLE_CONNECTED to true)
    private val spoken = mutableListOf<String>()
    private val voice = VoiceQueue(scope, object : SpeechEngine {
        override val ready = true
        override fun speak(id: String, text: String, guidance: Boolean, onDone: () -> Unit) { spoken += text; onDone() }
        override fun stop() = Unit
        override fun setRate(rate: Float) = Unit
    }) { now }
    private val tutorial = TutorialEngine(content, store, voice) { now }

    private fun asset(name: String) = File("src/main/assets/content/$name.json").readText()
    private fun tick(ms: Long = 400, vararg pairs: Pair<String, Any?>) {
        now += ms
        values.putAll(pairs)
        tutorial.onSignals(Signals(values.toMap(), now))
    }
    private fun stable(vararg pairs: Pair<String, Any?>) { tick(0, *pairs); tick() }
    private fun open(id: String) { tick(); tutorial.startChapter(content.card(id)!!.chapter, id) }
    @After fun cleanup() { scope.cancel() }

    /** The written specification lives beside the project in the working tree; a copy of the app alone skips these checks. */
    private fun specification(): String? = File("../../docs/beginner-guide-spec.md").takeIf { it.exists() }?.readText()

    @Test fun contentIncludesEveryCardNamedInTheSpecification() {
        val specification = specification() ?: return
        val ids = Regex("(?m)^\\| (c\\d+-[A-Za-z0-9]+) \\|").findAll(specification).map { it.groupValues[1] }.toSet()
        assertTrue("The specification must supply the coverage set", ids.size > 80)
        assertEquals(emptySet<String>(), ids - content.cards.map { it.id }.toSet())
        assertEquals((1..14).toList(), content.chapters.map { it.number })
        assertEquals(content.cards.size, content.cards.map { it.id }.distinct().size)
        content.cards.forEach { card ->
            assertNotNull(content.chapter(card.chapter))
            assertTrue(card.stage in 1..3)
            card.deeper?.let { assertNotNull("${card.id} deeper reference $it", content.card(it)) }
        }
        content.situations.forEach { sit -> sit.relatedCards.forEach { assertNotNull("${sit.id} related $it", content.card(it)) } }
    }

    @Test fun roadChaptersMatchTheSpecificationTextAndBasicCounts() {
        val specification = specification() ?: return
        Regex("(?m)^\\| (c(?:11|12|13|14)-[^ ]+) \\| ([12]) \\| ([^|]+) \\| ([^|]+) \\|")
            .findAll(specification).forEach { match ->
                val card = content.card(match.groupValues[1])!!
                assertEquals(match.groupValues[3].trim(), card.headline)
                assertEquals(match.groupValues[4].trim(), card.why?.replace('\n', ' '))    // one sentence per line in the app
                assertEquals(match.groupValues[2].toInt(), card.stage)
            }
        assertEquals(listOf(3, 2, 2, 1), (11..14).map { content.cardsOf(it, 1).size })
        assertEquals(1, content.cardsOf(10, 1).size)
        assertEquals("c10-reverse", content.cardsOf(10, 1).single().id)
    }

    @Test fun aValueTheCarNeverSendsStopsWaitingAndComesBackWhenItArrives() {
        values[Signals.VEHICLE_CONNECTED] = true; values.remove(Signals.HAZARD)
        open("c5-hazard")
        repeat(5) { tick(400) }
        assertTrue(tutorial.ui.value.signalPending)
        tick(TutorialEngine.SILENT_SIGNAL_MS)
        assertEquals(VerifyState.UNSUPPORTED, tutorial.ui.value.verify)
        tick(400, Signals.HAZARD to false)
        assertEquals(VerifyState.IDLE, tutorial.ui.value.verify)
    }

    @Test fun nextStaysInTheChapterAndFinishEndsIt() {
        open("c6-front")
        tutorial.next()
        assertEquals("c6-rear", tutorial.ui.value.card?.id)
        tutorial.next()
        assertEquals("c6-why", tutorial.ui.value.card?.id)
        open("c6-rear")
        tutorial.finishChapter()
        assertFalse(tutorial.active)
        assertTrue(tutorial.ui.value.basicFinished)
        assertEquals(6, tutorial.ui.value.finishedChapter)
        assertEquals(8, tutorial.ui.value.moreCount)
        assertEquals("c6-why", store.pausedCard)
        tutorial.continueMore()
        assertEquals("c6-why", tutorial.ui.value.card?.id)
    }

    @Test fun cardJumpPreservesTheWholeChapterAndReturnsToTheSelectedCard() {
        open("c11-order")
        tutorial.jumpTo("c11-gap")
        assertEquals("c11-gap", tutorial.ui.value.card?.id)
        assertEquals(9, tutorial.ui.value.total)
        tutorial.prev()
        assertEquals("c11-wait", tutorial.ui.value.card?.id)
    }

    @Test fun adjustedSeatCanBeReadWithoutInventingVehicleConfirmation() {
        open("c1-distance")
        tutorial.alreadyAdjusted()
        assertEquals(VerifyState.DONE, tutorial.ui.value.verify)
        assertEquals(CardResult.READ, store.cardResult("c1-distance"))
        open("c6-front")
        tutorial.alreadyAdjusted()
        assertNull(store.cardResult("c6-front"))
    }

    @Test fun readAloudPreferenceSurvivesStorageWithoutDisablingCoaching() {
        tutorial.setReadAloud(false)
        val restored = MemoryStore().apply { load(store.toJson()) }
        assertFalse(restored.settings.tutorialReadAloud)
        assertTrue(restored.settings.voiceOn)
        assertTrue(voice.enabled)
    }

    @Test fun alreadyOnEqualsConditionNeedsOffThenOn() {
        values[Signals.BELT] = true
        open("c1-sit")
        tick(); tick()
        assertTrue(tutorial.ui.value.alreadyMet)
        assertNull(store.cardResult("c1-sit"))
        stable(Signals.BELT to false)
        stable(Signals.BELT to true)
        assertEquals(CardResult.CONFIRMED, store.cardResult("c1-sit"))
    }

    @Test fun restoreRequiresAnObservedTransitionAfterSuccess() {
        values[Signals.DEFROST_FRONT] = false
        open("c6-front")
        stable(Signals.DEFROST_FRONT to true)
        assertEquals(VerifyState.RESTORE, tutorial.ui.value.verify)
        stable(Signals.DEFROST_FRONT to false)
        assertEquals(VerifyState.DONE, tutorial.ui.value.verify)
    }

    @Test fun gearPracticePausesWhenBrakeIsReleasedAndResumesAtSameStep() {
        open("c4-gear")
        tick()
        assertEquals(0, tutorial.ui.value.sequenceDone)
        stable(Signals.GEAR to "D", Signals.BRAKE to false)
        assertTrue(tutorial.ui.value.practicePaused)
        assertEquals(0, tutorial.ui.value.sequenceDone)
        stable(Signals.BRAKE to true)
        assertFalse(tutorial.ui.value.practicePaused)
        assertEquals(1, tutorial.ui.value.sequenceDone)
    }

    @Test fun creepRequiresSpaceConsentAndShowsNoControlModeWhenMoving() {
        open("c4-creep")
        assertTrue(tutorial.ui.value.needsSpaceConfirmation)
        assertFalse(tutorial.allowsPractice(Signals(mapOf(Signals.GEAR to "D"), now)))
        tutorial.confirmSpace()
        stable(Signals.GEAR to "D", Signals.SPEED to 3)
        assertTrue(tutorial.ui.value.compact)
        assertTrue(tutorial.allowsPractice(Signals(mapOf(Signals.GEAR to "D", Signals.SPEED to 3), now)))
        assertFalse(tutorial.allowsPractice(Signals(mapOf(Signals.GEAR to "D", Signals.SPEED to 9), now)))
    }

    @Test fun decliningMovingPracticeRecordsReadAndDoesNotEnableDrivingPractice() {
        open("c10-reverse")
        tutorial.readOnlyPractice()
        assertEquals(CardResult.READ, store.cardResult("c10-reverse"))
        assertFalse(tutorial.ui.value.needsSpaceConfirmation)
        assertFalse(tutorial.allowsPractice(Signals(mapOf(Signals.GEAR to "R"), now)))
    }

    @Test fun parkingOrderIsParkingBrakeThenParkThenFootOff() {
        open("c4-park")
        stable(Signals.PARKING_BRAKE to true)
        assertEquals(1, tutorial.ui.value.sequenceDone)
        stable(Signals.GEAR to "P")
        assertEquals(2, tutorial.ui.value.sequenceDone)
        stable(Signals.BRAKE to false)
        assertEquals(CardResult.CONFIRMED, store.cardResult("c4-park"))
    }

    @Test fun allBasicsMilestoneAppearsOnceAndPersists() {
        content.cards.filter { it.stage == 1 }.forEach { store.setCardResult(it.id, CardResult.READ) }
        open("c14-school")
        tutorial.finishChapter()
        assertTrue(tutorial.ui.value.allBasicsJustCompleted)
        assertTrue(MemoryStore().apply { load(store.toJson()) }.basicsAnnounced)
        tutorial.continueMore()
        open("c14-school")
        tutorial.finishChapter()
        assertFalse(tutorial.ui.value.allBasicsJustCompleted)
    }

    @Test fun automaticPowerNoticeIsSpokenOncePerOpenEvenAfterMultiplePowerCycles() {
        store.settings=store.settings.copy(tutorialReadAloud=true)
        tick(0,Signals.IGNITION to "OFF",Signals.DEFROST_FRONT to false)
        open("c6-front")
        voice.pump()
        tick(40_000); voice.pump()
        tick(0,Signals.IGNITION to "ON")
        tick(40_000,Signals.IGNITION to "OFF"); voice.pump()
        assertEquals(1,spoken.count { it.contains(TutorialEngine.POWER_REQUIRED_MESSAGE) })
    }

    @Test fun poweredCardsWaitWithoutTimeoutAndIgnoreActionsWhileOff() {
        tick(0,Signals.IGNITION to "OFF",Signals.DEFROST_FRONT to false)
        open("c6-front")
        assertTrue(tutorial.ui.value.powerRequired)
        tick(90_000,Signals.DEFROST_FRONT to true)
        assertTrue(tutorial.ui.value.powerRequired)
        assertNull(store.cardResult("c6-front"))
        tick(0,Signals.IGNITION to "ON")
        assertFalse(tutorial.ui.value.powerRequired)
        tick(25_000)
        assertNull(store.cardResult("c6-front"))
        stable(Signals.DEFROST_FRONT to false)
        stable(Signals.DEFROST_FRONT to true)
        assertEquals(VerifyState.RESTORE,tutorial.ui.value.verify)
    }

    @Test fun powerReturnRebaselinesChangedValuesInsteadOfConfirmingStaleHeatLevel() {
        tick(0,Signals.IGNITION to "ACC","seatHeatDriver" to 0)
        open("c7-heat")
        tick(1000,"seatHeatDriver" to 2)
        tick(0,Signals.IGNITION to "ON")
        tick(1000)
        assertNull(store.cardResult("c7-heat"))
        stable("seatHeatDriver" to 3)
        assertEquals(CardResult.CONFIRMED,store.cardResult("c7-heat"))
    }

    @Test fun restoreWaitsForPowerWithoutDiscardingAlreadyConfirmedResult() {
        tick(0,Signals.IGNITION to "ON",Signals.DEFROST_FRONT to false)
        open("c6-front")
        stable(Signals.DEFROST_FRONT to true)
        tick(0,Signals.IGNITION to "LOCK")
        tick(1000,Signals.DEFROST_FRONT to false)
        assertTrue(tutorial.ui.value.powerRequired)
        assertEquals(VerifyState.RESTORE,tutorial.ui.value.verify)
        assertEquals(CardResult.CONFIRMED,store.cardResult("c6-front"))
        tick(0,Signals.IGNITION to "ON")
        tick()
        assertEquals(VerifyState.RESTORE,tutorial.ui.value.verify)
        stable(Signals.DEFROST_FRONT to true)
        stable(Signals.DEFROST_FRONT to false)
        assertEquals(VerifyState.DONE,tutorial.ui.value.verify)
    }

    @Test fun unknownPowerDoesNotInventOffButCannotResumeAnObservedPowerLoss() {
        tick(0,Signals.DEFROST_FRONT to false)
        open("c6-front")
        assertFalse(tutorial.ui.value.powerRequired)
        tick(0,Signals.IGNITION to "OFF")
        tick(1000,Signals.IGNITION to null,Signals.DEFROST_FRONT to true)
        assertTrue(tutorial.ui.value.powerRequired)
        assertNull(store.cardResult("c6-front"))
        tick(0,Signals.IGNITION to "ON")
        assertFalse(tutorial.ui.value.powerRequired)
    }

    @Test fun nextRemainsAvailableWhilePowerIsOffAndMechanicalCardsAreUnaffected() {
        tick(0,Signals.IGNITION to "OFF")
        open("c6-front")
        tutorial.next()
        assertEquals(CardResult.SKIPPED,store.cardResult("c6-front"))
        open("c4-park")
        assertFalse(tutorial.ui.value.powerRequired)
        stable(Signals.PARKING_BRAKE to true)
        assertEquals(1,tutorial.ui.value.sequenceDone)
    }

    @Test fun connectionLossHasPriorityOverPowerAndReconnectReturnsToPowerWait() {
        tick(0,Signals.IGNITION to "OFF")
        open("c6-front")
        tick(0,Signals.VEHICLE_CONNECTED to false)
        assertFalse(tutorial.ui.value.powerRequired)
        assertEquals(VerifyState.NOT_CONNECTED,tutorial.ui.value.verify)
        stable(Signals.VEHICLE_CONNECTED to true)
        assertTrue(tutorial.ui.value.powerRequired)
    }

    @Test fun missingSignalWaitsAndLateArrivalCanStillConfirmTheExercise() {
        open("c6-front")
        tick(7000)
        assertNull(store.cardResult("c6-front"))
        assertTrue(tutorial.ui.value.signalPending)
        tick(100,Signals.DEFROST_FRONT to false)
        assertFalse(tutorial.ui.value.signalPending)
        stable(Signals.DEFROST_FRONT to true)
        assertEquals(VerifyState.RESTORE,tutorial.ui.value.verify)
        assertEquals(CardResult.CONFIRMED,store.cardResult("c6-front"))
    }

    @Test fun explicitUnsupportedCapabilityIsDifferentFromNotReceivedYet() {
        values[Signals.UNSUPPORTED_FIELDS] = setOf(Signals.DEFROST_FRONT)
        open("c6-front")
        tick(1600)
        assertEquals(VerifyState.UNSUPPORTED, tutorial.ui.value.verify)
        assertEquals(CardResult.UNSUPPORTED, store.cardResult("c6-front"))
    }

    @Test fun disconnectedVehicleWaitsWithoutCountingUnsupported() {
        values[Signals.VEHICLE_CONNECTED] = false
        open("c6-front")
        tick(2000)
        assertEquals(VerifyState.NOT_CONNECTED, tutorial.ui.value.verify)
        assertNull(store.cardResult("c6-front"))
        tick(100, Signals.VEHICLE_CONNECTED to true, Signals.DEFROST_FRONT to false)
        stable(Signals.DEFROST_FRONT to true)
        assertEquals(VerifyState.RESTORE, tutorial.ui.value.verify)
    }

    @Test fun reconnectionDuringRestoreResumesRestoreInsteadOfRepeatingTheExercise() {
        values[Signals.DEFROST_FRONT] = false
        open("c6-front")
        stable(Signals.DEFROST_FRONT to true)
        assertEquals(VerifyState.RESTORE, tutorial.ui.value.verify)
        tick(100, Signals.VEHICLE_CONNECTED to false)
        tick(100, Signals.VEHICLE_CONNECTED to true)
        assertEquals(VerifyState.RESTORE, tutorial.ui.value.verify)
        stable(Signals.DEFROST_FRONT to false)
        assertEquals(VerifyState.DONE, tutorial.ui.value.verify)
    }
}
