package io.github.beomsun0829.handsonguide.tutorial

import io.github.beomsun0829.handsonguide.content.Card
import io.github.beomsun0829.handsonguide.store.CardResult
import io.github.beomsun0829.handsonguide.store.Store

/** A skipped card is still available to learn; it never counts as completed. */
fun CardResult?.isComplete(): Boolean = this != null && this != CardResult.SKIPPED

fun chapterStart(cards: List<Card>, store: Store, requested: String? = null): Int {
    val resume = requested ?: store.pausedCard
    return cards.indexOfFirst { it.id == resume }.takeIf { it >= 0 }
        ?: cards.indexOfFirst { !store.cardResult(it.id).isComplete() }.takeIf { it >= 0 }
        ?: 0
}
