package com.emfitsolutions.gopreach.data.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speaks turn-by-turn instructions with the device's own text-to-speech. The audio is tagged as navigation guidance and asks other audio
 * (music, a podcast) to lower its volume while an instruction is spoken, then gives it back. A newer instruction replaces an older one that has
 * not been spoken yet. Prefers an offline voice, so spoken guidance keeps working with no signal (the route itself is fetched online once).
 */
class VoiceGuide(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var ready = false
    private var pending: String? = null
    private var focus: AudioFocusRequest? = null
    private var utterance = 0

    /** False when the device has no usable speech engine or voice - the screen then says guidance is silent instead of pretending. */
    var available = true
        private set

    var muted = false

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(app) { status ->
        if (status != TextToSpeech.SUCCESS) { available = false; return@TextToSpeech }
        val engine = tts
        val set = engine.setLanguage(Locale.US)
        if (set == TextToSpeech.LANG_MISSING_DATA || set == TextToSpeech.LANG_NOT_SUPPORTED) { available = false; return@TextToSpeech }
        runCatching {
            engine.voices
                ?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                ?.firstOrNull { it.locale == Locale.US }
                ?.let { engine.voice = it }
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == "g$utterance") release() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == "g$utterance") release() }
        })
        ready = true
        pending?.let { pending = null; speak(it) }
        }
    }

    /** Says [text] now, cutting off an older instruction. Nothing is spoken while [muted]. */
    fun speak(text: String) {
        if (muted || text.isBlank() || !available) return
        if (!ready) { pending = text; return }
        requestFocus()
        utterance++
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "g$utterance")
    }

    fun stopSpeaking() {
        pending = null
        if (ready) tts.stop()
        release()
    }

    fun shutdown() {
        stopSpeaking()
        runCatching { tts.shutdown() }
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build().also { focus = it }
            audio.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun release() {
        if (Build.VERSION.SDK_INT >= 26) focus?.let { audio.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") audio.abandonAudioFocus(null)
    }
}
