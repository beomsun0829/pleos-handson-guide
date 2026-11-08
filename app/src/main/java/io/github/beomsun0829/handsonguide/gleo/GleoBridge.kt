package io.github.beomsun0829.handsonguide.gleo

import io.github.beomsun0829.handsonguide.GuideApp
import io.github.beomsun0829.handsonguide.GuideService
import io.github.beomsun0829.handsonguide.signal.Signals
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

object GleoBridge {
    const val RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

    fun dispatch(context: Context, intent: Intent?, finished: () -> Unit = {}) {
        val action = intent?.action
        if (action !in GleoActions.ACTIONS) { finished(); return }
        val receivedAt = SystemClock.elapsedRealtime()
        Log.i("GuideGleo", "Received $action elapsed=$receivedAt")
        val app = context.applicationContext as GuideApp
        // A voice request may cold-start the process. Gleo owns spoken action responses.
        runCatching { GuideService.start(context) }.onFailure { Log.w("GuideGleo", "Background service start deferred", it) }
        app.scope.launch {
            try {
                val response = withTimeoutOrNull(5_500) {
                    app.awaitReady()
                    if (action == GleoActions.OPEN && !app.controller.signals.value.has(Signals.GEAR)) {
                        withTimeoutOrNull(4_000) { app.controller.signals.first { it.has(Signals.GEAR) } }
                    }
                    val raw = intent?.getStringExtra("ACTION_PARAM") ?: JSONObject().apply {
                        listOf("topic", "chapter", "command").forEach { key ->
                            @Suppress("DEPRECATION") val value = intent?.extras?.get(key)
                            if (value != null) put(key, value)
                        }
                    }.toString()
                    GleoActions(app.controller).handle(action!!, raw)
                } ?: GleoResponse(action!!, "fail", "The request timed out. Please try again.")
                send(context, response, receivedAt)
                if (response.status == "success" && action != GleoActions.OPEN) {
                    response.data.optJSONObject(0)?.let { answer ->
                        app.voiceInput.presentExternalAnswer(answer.optString("text").removeSuffix(" ${GleoActions.CARD_OFFER}"),
                            answer.optString("cardId").takeIf { it.isNotBlank() && it != "null" })
                    }
                }
                app.persistNow()
            } catch (error: Exception) {
                Log.w("GuideGleo", "Action failed: $action", error)
                send(context, GleoResponse(action!!, "fail", "The request could not be completed."), receivedAt)
            } finally { finished() }
        }
    }

    // The response is for Gleo, in the Pleos system package: an implicit broadcast is the SDK's protocol.
    // Setting our own package would keep it from ever reaching Gleo.
    @android.annotation.SuppressLint("UnsafeImplicitIntentLaunch")
    private fun send(context: Context, response: GleoResponse, receivedAt: Long) {
        context.sendBroadcast(Intent(RESPONSE).putExtra("packageName", context.packageName)
            .putExtra("response", response.toJson().toString()))
        Log.i("GuideGleo", "${response.functionName}: ${response.status} (${response.mode}) elapsedMs=${SystemClock.elapsedRealtime() - receivedAt}")
    }
}

class GleoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(GleoActions.EXPLAIN, GleoActions.ADJUST)) return
        val pending = goAsync()
        GleoBridge.dispatch(context, intent) { pending.finish() }
    }
}
