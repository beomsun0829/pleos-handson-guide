package io.github.beomsun0829.handsonguide.voice

import io.github.beomsun0829.handsonguide.signal.WorkerConnectionContext
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Vehicle speech first, Android Korean voice second, screen-only when neither is available. */
class PleosSpeech(context: Context, private val callbackScope: CoroutineScope) : SpeechEngine {
    private val worker = HandlerThread("GuideSpeech").apply { start() }
    private val handler = Handler(worker.looper)
    private val serviceContext = WorkerConnectionContext(context.applicationContext, handler)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val completion = SpeechCompletion()
    private var primary: TextToSpeech? = null
    @Volatile private var primaryReady = false
    @Volatile private var fallback: AndroidSpeech? = null
    @Volatile private var rate = 1f
    private var serviceEpoch = 0L
    private var active: Request? = null
    private var listener: EventListener? = null
    private var focus: AudioFocusRequest? = null
    private var focusEpoch = 0L
    private var padding: AudioTrack? = null
    private var timeout: Runnable? = null

    private data class Request(val token: Long, val id: String, val text: String,
        val guidance: Boolean, val priority: Int, val done: () -> Unit, var focusRetries: Int = 0)

    override val ready: Boolean get() = (rate == 1f && primaryReady) || fallback?.ready == true

    init { handler.post { fallback = AndroidSpeech(serviceContext, manageFocus = false); initialize() } }

    private fun initialize() {
        val epoch = ++serviceEpoch
        try {
            val sdk = TextToSpeech(serviceContext, Mode.ON_DEVICE)
            primary = sdk
            val initTimeout = Runnable {
                if (epoch == serviceEpoch && !primaryReady) disablePrimary("initialization timeout")
            }
            handler.postDelayed(initTimeout, 10_000)
            sdk.initialize(object : OnInitializeListener {
                override fun onSuccess() { handler.post {
                    if (epoch != serviceEpoch) return@post
                    handler.removeCallbacks(initTimeout)
                    try {
                        sdk.setLocale(Locale.KOREA)
                        primaryReady = true
                        Log.i(TAG, "Pleos ON_DEVICE ready")
                    } catch (e: Exception) { disablePrimary("locale: ${e.javaClass.simpleName}") }
                } }
                override fun onFailure(error: ErrorType) { handler.post {
                    if (epoch == serviceEpoch) {
                        handler.removeCallbacks(initTimeout)
                        disablePrimary("initialization: $error")
                    }
                } }
            })
        } catch (e: Exception) { disablePrimary("initialization: ${e.javaClass.simpleName}") }
    }

    override fun speak(id: String, text: String, guidance: Boolean, onDone: () -> Unit) {
        speak(id, text, guidance, 3, onDone)
    }

    override fun speak(id: String, text: String, guidance: Boolean, priority: Int, onDone: () -> Unit) {
        val request = Request(completion.begin(), id, text, guidance, priority, onDone)
        handler.post {
            if (!completion.isCurrent(request.token)) return@post
            active = request
            if (rate != 1f || !primaryReady) speakFallback(request) else speakPrimary(request)
        }
    }

    private fun speakPrimary(request: Request) {
        val sdk = primary ?: return speakFallback(request)
        val attrs = attributes(request.guidance)
        withFocus(request) { beginPrimary(request, sdk, attrs) }
    }

    private fun withFocus(request: Request, proceed: () -> Unit) {
        if (active !== request || !completion.isCurrent(request.token)) return
        val attrs = attributes(request.guidance)
        val focusSession = ++focusEpoch
        val gain = if (request.guidance) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        val req = AudioFocusRequest.Builder(gain).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener({ change ->
                if (focusSession == focusEpoch && focus != null &&
                    (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
                    cancelAudio(request)
                }
            }, handler).build()
        if (runCatching { audio.requestAudioFocus(req) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.i(TAG, "Audio focus denied priority=${request.priority} retry=${request.focusRetries}")
            if (request.priority == 1 && request.focusRetries < 3) {
                request.focusRetries++
                handler.postDelayed({ withFocus(request, proceed) }, 3000)
            } else finish(request)
            return
        }
        focus = req
        proceed()
    }

    private fun beginPrimary(request: Request, sdk: TextToSpeech, attrs: AudioAttributes) {
        val epoch = serviceEpoch
        listener = object : EventListener {
            override fun onStart() { Log.i(TAG, "Pleos speech started") }
            override fun onReady() {}
            override fun onUpdatedRms(value: Double) {}
            override fun onDone() { handler.post { if (epoch == serviceEpoch) {
                Log.i(TAG, "Pleos speech completed"); finish(request)
            } } }
            override fun onStop() { handler.post { if (epoch == serviceEpoch) finish(request) } }
            override fun onError(error: ErrorType) { handler.post {
                if (epoch != serviceEpoch || active !== request) return@post
                disablePrimary("speech: $error")
                speakFallback(request)
            } }
        }
        try {
            sdk.addEventListener(listener!!)
            sdk.setAudioAttributes(attrs)
            armTimeout(request) {
                disablePrimary("speech timeout")
                speakFallback(request)
            }
            // Actual PCM padding, rather than a delay before opening the audio path.
            playPadding(request, attrs) {
                try { sdk.speak(request.text, true) }
                catch (e: Exception) {
                    disablePrimary("speak: ${e.javaClass.simpleName}")
                    speakFallback(request)
                }
            }
        } catch (e: Exception) {
            disablePrimary("audio: ${e.javaClass.simpleName}")
            speakFallback(request)
        }
    }

    private fun playPadding(request: Request, attrs: AudioAttributes, speak: () -> Unit) {
        val frames = 16_000 * 180 / 1000
        val pcm = ByteArray(frames * 2)
        val track = AudioTrack.Builder().setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size).setTransferMode(AudioTrack.MODE_STATIC).build()
        padding = track
        check(track.write(pcm, 0, pcm.size) == pcm.size) { "Silent PCM write failed" }
        track.play()
        val started = SystemClock.elapsedRealtime()
        val poll = object : Runnable {
            override fun run() {
                if (active !== request || !completion.isCurrent(request.token) || padding !== track) return
                if (track.playbackHeadPosition >= frames) {
                    releasePadding(); speak()
                } else if (SystemClock.elapsedRealtime() - started > 1500) {
                    disablePrimary("silent PCM playback timeout"); speakFallback(request)
                } else handler.postDelayed(this, 10)
            }
        }
        handler.post(poll)
    }

    private fun speakFallback(request: Request) {
        if (active !== request || !completion.isCurrent(request.token)) return
        clearTimeout()
        releaseAudio()
        val engine = fallback
        if (engine?.ready != true) { finish(request); return }
        withFocus(request) {
            Log.i(TAG, "Android fallback rate=$rate")
            armTimeout(request) { engine.stop(); finish(request) }
            try {
                engine.speak(request.id, request.text, request.guidance) { handler.post { finish(request) } }
            } catch (e: Exception) {
                Log.w(TAG, "Fallback unavailable", e)
                finish(request)
            }
        }
    }

    private fun armTimeout(request: Request, expired: () -> Unit) {
        clearTimeout()
        timeout = Runnable { if (active === request && completion.isCurrent(request.token)) expired() }
            .also { handler.postDelayed(it, 45_000) }
    }

    private fun clearTimeout() { timeout?.let(handler::removeCallbacks); timeout = null }

    private fun finish(request: Request) {
        if (active !== request) return
        active = null
        clearTimeout()
        listener?.let { l -> runCatching { primary?.removeEventListener(l) } }; listener = null
        releaseAudio()
        callbackScope.launch { completion.complete(request.token, request.done) }
    }

    private fun cancelAudio(request: Request) {
        if (active !== request) return
        val reconnect = primary != null
        fallback?.stop()
        disablePrimary("audio focus lost")
        finish(request)
        if (reconnect) initialize()
    }

    private fun disablePrimary(reason: String) {
        Log.w(TAG, "Pleos unavailable: $reason")
        primaryReady = false
        ++serviceEpoch
        val sdk = primary; primary = null
        listener?.let { l -> runCatching { sdk?.removeEventListener(l) } }; listener = null
        runCatching { sdk?.stop() }
        runCatching { sdk?.release() }
        releaseAudio()
    }

    private fun releasePadding() {
        padding?.let { track -> runCatching { track.stop() }; runCatching { track.release() } }
        padding = null
    }

    private fun releaseAudio() {
        ++focusEpoch
        releasePadding()
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }; focus = null
    }

    override fun stop() {
        completion.invalidate()
        handler.post {
            active = null
            clearTimeout()
            fallback?.stop()
            if (primary != null) {
                // The SDK has no utterance ID: a fresh connection isolates late onStop/onDone.
                disablePrimary("interrupted")
                initialize()
            } else releaseAudio()
        }
    }

    override fun setRate(rate: Float) {
        this.rate = rate.coerceIn(.5f, 2f)
        // SDK 3.3.1 has no public rate setter; use the Android engine for this preference.
        handler.post { fallback?.setRate(this.rate) }
    }

    private fun attributes(guidance: Boolean) = AudioAttributes.Builder()
        .setUsage(if (guidance) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    companion object { private const val TAG = "GuideSpeech" }
}
