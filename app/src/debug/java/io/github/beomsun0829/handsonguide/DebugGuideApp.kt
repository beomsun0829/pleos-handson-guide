package io.github.beomsun0829.handsonguide

import io.github.beomsun0829.handsonguide.gleo.GleoBridge
import io.github.beomsun0829.handsonguide.gleo.GleoProbeReceiver
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.app.ActivityManager
import android.os.Build
import android.util.Log

/** Dynamic receiver captures the actual implicit callback despite Android's manifest limits. */
class DebugGuideApp : GuideApp() {
    override fun onCreate() {
        // Keep diagnostics off the UI and engine dispatchers, including when investigating
        // an ANR in either dispatcher. These are the app's own Android exit records only.
        if (Build.VERSION.SDK_INT >= 30) Thread({
            runCatching {
                val exits = getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, 0, 5)
                val exit = exits.firstOrNull { it.reason == android.app.ApplicationExitInfo.REASON_ANR }
                exit?.traceInputStream?.use { input ->
                    java.io.File(filesDir, "last-anr.txt").outputStream().use { input.copyTo(it) }
                }
            }.onFailure { Log.w("GuideDiagnostics", "Could not read own exit trace", it) }
        }, "guide-exit-diagnostics").start()
        Thread({
            repeat(3) {
                Thread.sleep(8_000)
                runCatching {
                    val dump = Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
                        "${thread.name} (${thread.state})\n" + stack.joinToString("\n") { "  at $it" }
                    }
                    java.io.File(filesDir, "live-threads.txt").writeText(dump)
                }
            }
        }, "guide-live-diagnostics").start()
        super.onCreate()
        ContextCompat.registerReceiver(this, GleoProbeReceiver(), IntentFilter(GleoBridge.RESPONSE), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
