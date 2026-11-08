package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.gleo.GleoActions
import io.github.beomsun0829.handsonguide.gleo.GuideTopics
import io.github.beomsun0829.handsonguide.signal.Signals
import io.github.beomsun0829.handsonguide.store.GuideLevel
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GleoActionsTest {
    @Test fun schemaDefinesExactlyTheThreeSupportedFunctionsAndAllTopicsResolve() = EngineFixture().use { f ->
        val schema = JSONObject(File("src/main/assets/schema.json").readText())
        assertEquals("io.github.beomsun0829.handsonguide", schema.getString("packageName"))
        assertFalse(schema.has("invokeWord"))
        assertTrue(schema.getString("appDescription").contains("Hands-on Guide"))
        val actions = schema.getJSONArray("actionCalls")
        assertEquals(3, actions.length())
        assertEquals(GleoActions.ACTIONS, (0 until actions.length()).map { actions.getJSONObject(it).getJSONObject("function").getString("name") }.toSet())
        GuideTopics.cards.forEach { (topic, id) -> assertNotNull("$topic -> $id", f.content.card(id)) }
        val enum = actions.getJSONObject(0).getJSONObject("function").getJSONObject("parameters")
            .getJSONObject("properties").getJSONObject("topic").getJSONArray("enum")
        assertEquals(GuideTopics.all.toSet(), (0 until enum.length()).map { enum.getString(it) }.toSet())
    }

    @Test fun parameterParserAcceptsObjectOrJsonWrappedString() {
        val raw = """{"topic":"defog_front"}"""
        assertEquals("defog_front", GleoActions.parseParameters(raw).getString("topic"))
        val quoted = JSONArray().put(raw).toString().removePrefix("[").removeSuffix("]")
        assertEquals("defog_front", GleoActions.parseParameters(quoted).getString("topic"))
    }

    @Test fun invalidParametersAndUnknownTopicsDoNotCrashOrOpenCards() = EngineFixture().use { f ->
        val actions = GleoActions(f.app)
        assertEquals("fail", actions.handle(GleoActions.EXPLAIN, "[]").status)
        assertEquals("asking", actions.handle(GleoActions.EXPLAIN, "{}").status)
        assertEquals("asking", actions.handle(GleoActions.OPEN, """{"topic":"unknown"}""").status)
        assertEquals(Screen.HOME, f.app.ui.value.screen)
    }

    @Test fun parkedExplanationReturnsReviewedDataForGleoWithoutAppSpeech() = EngineFixture().use { f ->
        val actions = GleoActions(f.app)
        val result = actions.handle(GleoActions.EXPLAIN, """{"topic":"defog_front"}""")
        assertEquals("voice", result.mode)
        assertTrue(result.data.getJSONObject(0).getString("text").contains(f.content.card("c6-front")!!.why!!))
        f.voice.pump()
        assertTrue(f.speech.spoken.isEmpty())
        f.speech.ready = false
        assertEquals("voice", actions.handle(GleoActions.EXPLAIN, """{"topic":"defog_front"}""").mode)
        assertTrue(actions.handle(GleoActions.EXPLAIN, """{"topic":"defog_front"}""").data.length() > 0)
    }

    @Test fun everyTopicAnswersWithReviewedKoreanAndALessonGleoCanOpen() = EngineFixture().use { f ->
        val actions = GleoActions(f.app)
        val reachable = f.app.guide.topics
        for (topic in GuideTopics.all) {
            val r = actions.handle(GleoActions.EXPLAIN, """{"topic":"$topic"}""")
            assertEquals(topic, "success", r.status)
            if (topic == "current_warning_light") continue          // depends on the lights that are on
            val d = r.data.getJSONObject(0)
            val text = d.getString("text")
            assertTrue("$topic: Korean answer", text.any { it in '가'..'힣' } && text.length >= 20)
            assertFalse("$topic: no unfilled slot", text.contains("{"))
            // The card Gleo offers to open must be one the guide can actually show.
            val card = d.optString("cardId")
            if (card.isNotEmpty() && card != "null") assertNotNull("$topic -> $card is on a page", f.app.guide.topic(card)?.takeIf { it in reachable })
        }
    }

    @Test fun parkedGleoAnswerAsksToOpenTheCardButTheLocalDialogDoesNot() = EngineFixture().use { f ->
        val gleo = GleoActions(f.app).handle(GleoActions.EXPLAIN, """{"topic":"defog_front"}""").data.getJSONObject(0)
        assertTrue(gleo.getString("text").endsWith(GleoActions.CARD_OFFER))
        assertEquals("c6-front", gleo.getString("cardId"))
        val local = GleoActions(f.app, readOpenedLesson = true, askToOpenCard = false).handle(GleoActions.EXPLAIN, """{"topic":"defog_front"}""")
        assertFalse(local.data.getJSONObject(0).getString("text").contains(GleoActions.CARD_OFFER))
    }

    @Test fun drivingExplanationUsesOnlyHeadlineAndCannotOpenLessons() = EngineFixture().use { f ->
        f.drive()
        val actions = GleoActions(f.app)
        val response = actions.handle(GleoActions.EXPLAIN, """{"topic":"defog_front"}""")
        assertEquals(f.content.card("c6-front")!!.headline, response.data.getJSONObject(0).getString("text"))
        assertEquals("fail", actions.handle(GleoActions.OPEN, """{"topic":"defog_front"}""").status)
        assertEquals(Screen.DRIVE, f.app.ui.value.screen)
    }

    @Test fun uxrAndUnknownGearBlockLessonEvenAtZeroSpeed() = EngineFixture().use { f ->
        val actions = GleoActions(f.app)
        f.emit(0, Signals.UXR_DO to true)
        assertEquals("fail", actions.handle(GleoActions.OPEN, """{"chapter":6}""").status)
        f.emit(0, Signals.UXR_DO to false, Signals.GEAR to null)
        assertEquals("fail", actions.handle(GleoActions.OPEN, """{"chapter":6}""").status)
    }

    @Test fun openByTopicOpensItsGroupAndValidatesChapterNumbers() = EngineFixture().use { f ->
        val actions = GleoActions(f.app)
        assertEquals("success", actions.handle(GleoActions.OPEN, """{"topic":"school_zone"}""").status)
        assertEquals("c14-school", f.tutorial.ui.value.card?.id)
        assertEquals("보호구역", f.tutorial.ui.value.playlistTitle)
        assertEquals(1, f.tutorial.ui.value.total)
        f.voice.pump()
        assertTrue("Gleo and tutorial must not speak over each other", f.speech.spoken.isEmpty())
        assertEquals("asking", actions.handle(GleoActions.OPEN, """{"chapter":1.5}""").status)
        assertEquals("asking", actions.handle(GleoActions.OPEN, """{"chapter":15}""").status)
    }

    @Test fun permissionToChangeLanesAlwaysUsesTheFixedNonJudgmentAnswer() = EngineFixture().use { f ->
        val result = GleoActions(f.app).handle(GleoActions.EXPLAIN, """{"topic":"can_i_change_lane"}""")
        assertEquals("미러와 사각지대 경고등을 확인하세요. 깜빡이 먼저.", result.data.getJSONObject(0).getString("text"))
        assertFalse(GuideTopics.all.contains("can_i_change_lane"))
    }

    @Test fun quietResumeAndDetailCommandsAdjustOnlyTheirOwnSettings() = EngineFixture().use { f ->
        val actions = GleoActions(f.app)
        actions.handle(GleoActions.ADJUST, """{"command":"quiet_30min"}""")
        assertTrue(f.coach.isQuiet)
        assertEquals(f.now + 1_800_000, f.store.quietUntil)
        actions.handle(GleoActions.ADJUST, """{"command":"resume"}""")
        assertFalse(f.coach.isQuiet)
        actions.handle(GleoActions.ADJUST, """{"command":"less_detail"}""")
        assertEquals(GuideLevel.FAMILIAR, f.store.settings.guideLevel)
        actions.handle(GleoActions.ADJUST, """{"command":"more_detail"}""")
        assertEquals(GuideLevel.BEGINNER, f.store.settings.guideLevel)
        assertTrue(f.store.settings.voiceOn)
    }

    @Test fun responseDataIsAnArrayStringAndRequiredCallbackFieldsExist() = EngineFixture().use { f ->
        val json = GleoActions(f.app).handle(GleoActions.EXPLAIN, """{"topic":"defog_front"}""").toJson()
        assertEquals(GleoActions.EXPLAIN, json.getString("functionName"))
        assertEquals("success", json.getString("status"))
        assertTrue(json.getString("message").isNotBlank())
        assertEquals(1, JSONArray(json.getString("data")).length())
    }
}
