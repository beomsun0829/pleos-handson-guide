package io.github.beomsun0829.handsonguide.voice

import java.util.concurrent.atomic.AtomicLong

/** A stop invalidates callbacks immediately, before asynchronous platform stop/release calls. */
internal class SpeechCompletion {
    private val generation = AtomicLong()
    private var completed = -1L

    fun begin(): Long = generation.incrementAndGet()
    fun invalidate() { generation.incrementAndGet() }
    fun isCurrent(token: Long) = generation.get() == token

    @Synchronized fun complete(token: Long, callback: () -> Unit): Boolean {
        if (!isCurrent(token) || completed == token) return false
        completed = token
        callback()
        return true
    }
}
