package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.store.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StoreRecoveryTest {
    @Test fun cardAndOnboardingDatesSurviveRestartWithoutInventingLegacyDates() {
        var now = 1000L
        val store = MemoryStore { now }
        store.onboarded = true
        now = 2000L
        store.setCardResult("c1-belt", CardResult.CONFIRMED)
        val restored = MemoryStore { 9000L }.apply { load(store.toJson()) }
        assertEquals(1000L, restored.onboardingAt)
        assertEquals(2000L, restored.cardRecordedAt("c1-belt"))
        assertEquals(CardResult.CONFIRMED, restored.cardResult("c1-belt"))
        val old = MemoryStore { 9000L }.apply { load(JSONObject("""{"onboarded":true,"cards":{"c1-belt":"READ"}}""")) }
        assertTrue(old.onboarded)
        assertNull(old.onboardingAt)
        assertNull(old.cardRecordedAt("c1-belt"))
        assertEquals(CardResult.READ, old.cardResult("c1-belt"))
    }

    @Test fun rereadingAConfirmedCardPreservesItsConfirmationDate() {
        var now = 1000L
        val store = MemoryStore { now }
        store.setCardResult("c1-belt", CardResult.CONFIRMED)
        now = 9000L
        store.setCardResult("c1-belt", CardResult.READ)
        store.setCardResult("c1-belt", CardResult.SKIPPED)
        assertEquals(CardResult.CONFIRMED, store.cardResult("c1-belt"))
        assertEquals(1000L, store.cardRecordedAt("c1-belt"))
    }

    @Test fun brokenEntriesDoNotHideLaterGoodRecords() {
        val store = MemoryStore().apply { load(JSONObject("""{
          "cards":{"bad":{"result":"FUTURE"},"c1-belt":{"result":"READ","at":1000}},
          "stats":{"broken":7,"good":{"count":2,"okStreak":1,"lastAt":3000}},
          "pending":[7,null,"","c1-belt","c5-turn"],
          "drives":[null,{"startAt":0},{"startAt":20,"endAt":10},
            {"startAt":1000,"endAt":2000,"tips":[7,"good",null],"misses":[
              42,{"id":"bad-time","at":-1},{"id":"good","at":1500,"lat":37,"lon":127}]}]
        }""")) }
        assertNull(store.cardResult("bad"))
        assertEquals(CardResult.READ, store.cardResult("c1-belt"))
        assertEquals(2, store.stat("good").count)
        assertEquals(setOf("c5-turn"), store.pendingCards)
        assertEquals(listOf("good"), store.drives.single().tips)
        assertEquals(Miss("good",1500,37.0,127.0), store.drives.single().misses.single())
    }

    @Test fun loadingASnapshotReplacesPreviousStateAndIsIdempotent() {
        val store = MemoryStore()
        store.setCardResult("old", CardResult.CONFIRMED)
        store.settings = Settings(voiceOn=false)
        store.saveDrive(DriveRecord(10))
        val snapshot = JSONObject("""{"cards":{"new":"READ"},"drives":[{"startAt":100}]}""")
        repeat(2) { store.load(snapshot) }
        assertNull(store.cardResult("old"))
        assertEquals(CardResult.READ, store.cardResult("new"))
        assertEquals(Settings(), store.settings)
        assertEquals(listOf(100L), store.drives.map { it.startAt })
    }

    @Test fun legacyAliasCannotDowngradeAConfirmedCardOrReturnItToPending() {
        for (cards in listOf(
            """{"c3-nocross":"CONFIRMED","c3-noCross":"READ"}""",
            """{"c3-noCross":"READ","c3-nocross":"CONFIRMED"}"""
        )) {
            val store = MemoryStore().apply { load(JSONObject().put("cards",JSONObject(cards))
                .put("pending",JSONArray().put("c3-nocross")).put("pausedCard","c3-nocross")) }
            assertEquals(CardResult.CONFIRMED, store.cardResult("c3-noCross"))
            assertEquals("c3-noCross", store.pausedCard)
            assertTrue(store.pendingCards.isEmpty())
        }
    }

    @Test fun unorderedDriveSnapshotsKeepTheLatestThirtyAndDeduplicate() {
        val drives = JSONArray()
        for (i in 35 downTo 1) drives.put(JSONObject().put("startAt",i))
        drives.put(JSONObject().put("startAt",35).put("endAt",40))
        val store = MemoryStore().apply { load(JSONObject().put("drives",drives)) }
        assertEquals((6L..35L).toList(), store.drives.map { it.startAt })
        assertEquals(40L, store.drives.last().endAt)
    }

    @Test fun invalidCoordinatesAndSettingsFallBackWithoutDiscardingTheDrive() {
        val store = MemoryStore().apply { load(JSONObject("""{
          "settings":{"speechRate":99,"guideLevel":"FUTURE","theme":"FUTURE"},
          "stats":{"s":{"count":-5,"okStreak":-1,"lastAt":-3}},
          "drives":[{"startAt":1000,"misses":[{"id":"s","at":1500,"lat":91,"lon":127}]}]
        }""")) }
        assertEquals(Settings(), store.settings)
        assertEquals(SituationStat(), store.stat("s"))
        assertNull(store.drives.single().misses.single().lat)
        assertNull(store.drives.single().misses.single().lon)
    }

    @Test fun delayedOlderDriveDoesNotEvictAMoreRecentDrive() {
        val store = MemoryStore()
        (10L..39L).forEach { store.saveDrive(DriveRecord(it)) }
        store.saveDrive(DriveRecord(1))
        store.saveDrive(DriveRecord(15,100))
        assertEquals((10L..39L).toList(), store.drives.map { it.startAt })
        assertEquals(100L, store.drives.single { it.startAt == 15L }.endAt)
    }

    @Test fun clearAllRemovesDatesAndSettingsAsWellAsHistoryAndSurvivesRestart() {
        val store = MemoryStore { 1000L }
        store.onboarded = true; store.experienced = true; store.pausedCard = "c1-belt"
        store.settings = Settings(voiceOn=false,theme=ThemeMode.LIGHT)
        store.quietUntil = 9000
        store.setCardResult("c1-belt",CardResult.CONFIRMED)
        store.setStat("s",SituationStat(count=3)); store.addPending(listOf("c5-turn"))
        store.saveDrive(DriveRecord(500))
        store.clearAll()
        val restored = MemoryStore().apply { load(store.toJson()) }
        assertFalse(restored.onboarded); assertFalse(restored.experienced)
        assertNull(restored.pausedCard); assertNull(restored.onboardingAt)
        assertNull(restored.cardResult("c1-belt")); assertNull(restored.cardRecordedAt("c1-belt"))
        assertTrue(restored.drives.isEmpty()); assertTrue(restored.pendingCards.isEmpty())
        assertTrue(restored.allStats().isEmpty()); assertEquals(0L,restored.quietUntil)
        assertEquals(Settings(),restored.settings)
    }
}
