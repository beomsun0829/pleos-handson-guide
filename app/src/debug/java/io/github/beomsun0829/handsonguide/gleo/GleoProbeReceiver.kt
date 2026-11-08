package io.github.beomsun0829.handsonguide.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Debug-only wire probe. Production builds have no receiver or callback logging. */
class GleoProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra("packageName") != context.packageName) return
        Log.i("GuideGleoProbe", intent.getStringExtra("response") ?: return)
    }
}
