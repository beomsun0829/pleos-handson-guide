package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.content.Guide
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GuideStructureTest {
    private val guide = Guide.parse(File("src/main/assets/content/guide.json").readText())

    @Test fun everyCardBelongsToATopicAndEveryPlaylistNamesRealTopics() = EngineFixture().use { f ->
        f.content.cards.forEach { assertNotNull(it.id, guide.topic(it.id)) }
        val lists = listOf(guide.essentials) + guide.road
        lists.flatMap { it.cards }.forEach { id ->
            assertNotNull(id, f.content.card(id))
            assertTrue(id, id in guide.topics)
        }
        assertEquals(17, guide.essentials.cards.size)
        assertTrue(guide.topics.size in 25..40)
        guide.topics.forEach { assertTrue(it, guide.notes(it).size <= 3) }
        // Every topic is reachable from home, so a jump always lands in a list the driver has seen.
        guide.topics.forEach { t -> assertTrue(t, lists.any { t in it.cards }) }
    }

    @Test fun aRoadCardOpensInItsRoadGroup() = EngineFixture().use { f ->
        f.app.jumpCard("c5-turn")    // absorbed into the lane change topic
        assertEquals("c11-order", f.tutorial.ui.value.card?.id)
        assertEquals(guide.road.first { "c11-order" in it.cards }.title, f.tutorial.ui.value.playlistTitle)
    }

    @Test fun anAbsorbedCardOpensItsTopicInsideItsGroup() = EngineFixture().use { f ->
        val app = f.app
        app.jumpCard("c6-why")
        assertEquals(Screen.CARD, app.ui.value.screen)
        assertEquals("c6-front", f.tutorial.ui.value.card?.id)
        assertEquals(guide.essentials.title, f.tutorial.ui.value.playlistTitle)
        // The last card of a group finishes back at home.
        repeat(f.tutorial.ui.value.total) { f.tutorial.next() }
        assertEquals(Screen.HOME, app.ui.value.screen)
    }
}
