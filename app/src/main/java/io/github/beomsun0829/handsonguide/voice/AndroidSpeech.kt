package io.github.beomsun0829.handsonguide.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Android TextToSpeech (ko-KR) with transient audio focus so music ducks under guidance.
 * Fallback for an unavailable Pleos voice service and for non-default speech rates.
 */
class AndroidSpeech(context: Context, private val manageFocus: Boolean = true) : SpeechEngine {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private var focus: AudioFocusRequest? = null
    @Volatile override var ready = false
        private set
    private var seq = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale.KOREAN)
            ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ready) Log.w(TAG, "Korean voice unavailable ($r)")
        } else Log.w(TAG, "TTS init failed $status")
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = finish(id)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finish(id)
            override fun onError(id: String?, errorCode: Int) = finish(id)
            override fun onStop(id: String?, interrupted: Boolean) = finish(id)
        })
    }

    private fun finish(id: String?) {
        if (id == null || !id.startsWith("s")) return
        callbacks.remove(id)?.invoke()
        if (callbacks.isEmpty()) abandonFocus()
    }

    override fun speak(id: String, text: String, guidance: Boolean, onDone: () -> Unit) {
        if (!ready) { onDone(); return }
        val usage = if (guidance) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_ASSISTANT
        val attrs = AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        if (manageFocus && !requestFocus(attrs, guidance)) { onDone(); return }
        tts.setAudioAttributes(attrs)
        val uid = "s${seq++}"
        callbacks[uid] = onDone
        // Pleos voice guidance guide: 150–200 ms of leading silence so the first syllable is not clipped.
        tts.playSilentUtterance(LEAD_SILENCE_MS, TextToSpeech.QUEUE_ADD, "lead$uid")
        tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), uid)
    }

    override fun stop() {
        tts.stop()
        val pending = callbacks.keys.toList()
        pending.forEach { finish(it) }
        abandonFocus()
    }

    override fun setRate(rate: Float) { tts.setSpeechRate(rate) }

    private fun requestFocus(attrs: AudioAttributes, guidance: Boolean): Boolean {
        if (focus != null) return true
        val gain = if (guidance) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        val req = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
            }.build()
        val granted = audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focus = req
        return granted
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    fun shutdown() { tts.stop(); tts.shutdown() }

    companion object {
        private const val TAG = "GuideSpeech"
        private const val LEAD_SILENCE_MS = 180L
    }
}
