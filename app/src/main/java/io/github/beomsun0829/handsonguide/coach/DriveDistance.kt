package io.github.beomsun0829.handsonguide.coach

import io.github.beomsun0829.handsonguide.signal.Signals

/** Speed-integral estimate; a missing interval invalidates the whole-trip figure. */
class DriveDistance {
    private data class Sample(val at: Long, val speed: Double)
    private var previous: Sample? = null
    private var estimate = 0.0
    private var complete = true
    private var intervals = 0
    val km: Double? get() = estimate.takeIf { complete && intervals > 0 }

    fun observe(s: Signals) {
        val speed = s.num(Signals.SPEED)
        val received = s.receivedAt(Signals.SPEED)
        val valid = !s.isDemo && s.bool(Signals.VEHICLE_CONNECTED) != false &&
            s.gear in setOf("D","R","N","P") && speed != null && speed.isFinite() && speed in 0.0..400.0 &&
            received != null && s.at - received in 0..MAX_INTERVAL_MS
        if (!valid) { complete=false; previous=null; return }
        val current = Sample(s.at,speed!!)
        previous?.let { last ->
            val dt = current.at - last.at
            when {
                dt == 0L -> Unit
                dt in 1..MAX_INTERVAL_MS -> {
                    estimate += (last.speed + current.speed) * .5 * dt / 3_600_000.0
                    intervals++
                }
                else -> complete=false
            }
        }
        previous = current
    }

    companion object { const val MAX_INTERVAL_MS = 1500L }
}
