package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.content.Condition
import org.junit.Assert.*
import org.junit.Test

class PracticeStepsTest {
    @Test fun everyMultiStepPracticeNamesEachStepInOrder() = EngineFixture().use { f ->
        f.content.cards.forEach { card ->
            val v = card.verify ?: return@forEach
            val expected = when (val cond = v.condition) {
                is Condition.Sequence -> cond.steps.size
                is Condition.All -> cond.items.size
                else -> if (v.restore != null) 2 else 0
            }
            if (expected > 1) assertEquals(card.id, expected, v.steps.size)
            assertTrue(card.id, v.steps.all { it.isNotBlank() && it.length <= 24 })
        }
    }
}
