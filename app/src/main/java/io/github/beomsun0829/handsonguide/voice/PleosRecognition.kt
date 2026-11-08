package io.github.beomsun0829.handsonguide.voice

import io.github.beomsun0829.handsonguide.signal.WorkerConnectionContext
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

interface RecognitionEngine {
    fun start(onText: (String, Boolean) -> Unit, onError: (String) -> Unit)
    fun stop()
}

/** Each listening session owns its connection so cancelled SDK events cannot enter the next one. */
class PleosRecognition(context: Context, private val callbacks: CoroutineScope) : RecognitionEngine {
    private val worker = HandlerThread("GuideRecognition").apply { start() }
    private val handler = Handler(worker.looper)
    private val serviceContext = WorkerConnectionContext(context.applicationContext, handler)
    private val epoch = AtomicLong()
    private var sdk: SpeechToText? = null
    private var listener: ResultListener? = null

    override fun start(onText: (String, Boolean) -> Unit, onError: (String) -> Unit) {
        val session = epoch.incrementAndGet()
        handler.post {
            release()
            if (epoch.get() != session) return@post
            fun error(reason: String) {
                Log.w(TAG, "Recognition unavailable: $reason")
                callbacks.launch {
                    if (epoch.get() == session) onError("음성 입력을 사용할 수 없어요. Gleo에게 궁금한 기능을 물어보세요.")
                }
            }
            try {
                val engine = SpeechToText(serviceContext, Mode.ON_DEVICE)
                sdk = engine
                listener = object : ResultListener {
                    override fun onUpdated(text: String, completed: Boolean) {
                        callbacks.launch { if (epoch.get() == session) onText(text, completed) }
                    }
                    override fun onUpdatedEpdData(start: Long, end: Long) {}
                    override fun onStartedRecognition() { Log.i(TAG, "Recognition started") }
                    override fun onEndedRecognition() {}
                    override fun onReady() {}
                    override fun onUpdatedRms(rms: Double) {}
                    override fun onError(type: ErrorType) { error(type.toString()) }
                }.also(engine::addListener)
                engine.initialize(object : OnInitializeListener {
                    override fun onSuccess() { handler.post {
                        if (epoch.get() != session) return@post
                        try {
                            engine.setLocale(Locale.KOREA)
                            Log.i(TAG, "Pleos ON_DEVICE ready")
                            engine.request()
                        } catch (e: Exception) { error(e.javaClass.simpleName) }
                    } }
                    override fun onFailure(type: ErrorType) { error(type.toString()) }
                })
            } catch (e: Exception) { error(e.javaClass.simpleName) }
        }
    }

    override fun stop() { epoch.incrementAndGet(); handler.post { release() } }

    private fun release() {
        val engine = sdk; sdk = null
        listener?.let { l -> runCatching { engine?.removeListener(l) } }; listener = null
        runCatching { engine?.stop() }
        runCatching { engine?.release() }
    }

    companion object { private const val TAG = "GuideRecognition" }
}
