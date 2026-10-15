package com.emfitsolutions.gopreach.ui.screens.circuit

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

enum class NarrationState(val label: String) { STOPPED("Stopped"), PLAYING("Playing"), PAUSED("Paused"), UNAVAILABLE("Unavailable") }

/**
 * Reads a generated report aloud with the phone's own text-to-speech engine.
 *
 * - The report is spoken sentence by sentence, so Pause / Resume continue from the sentence that was interrupted and Read Again starts over.
 * - Only OFFLINE voices are used. An online voice would send the report text to a speech service, which GoPreach does not do; if only online
 *   voices exist for the language, narration says so instead of using one (the readable text stays available).
 * - It asks for audio focus while speaking and pauses when another app takes it (a call, another player) or when the app leaves the screen.
 * - Calling [play] while it is already playing does nothing, so repeated taps never start the report twice.
 */
class ReportNarrator(context: Context, private var sentences: List<String>) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var state by mutableStateOf(NarrationState.STOPPED)
        private set
    /** Why narration cannot run (no engine, no offline voice, an error), shown to the Circuit Overseer; null when fine. */
    var message by mutableStateOf<String?>(null)
        private set
    var rate by mutableFloatStateOf(1.0f)
        private set
    /** The sentence being spoken (or the one that Resume continues from). */
    var index by mutableIntStateOf(0)
        private set
    val count: Int get() = sentences.size

    private var tts: TextToSpeech? = null
    private var ready = false
    private var startWhenReady = false
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) main.post { pause() }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { utteranceId?.removePrefix("s")?.toIntOrNull()?.let { i -> main.post { if (state == NarrationState.PLAYING) index = i } } }
        override fun onDone(utteranceId: String?) {
            val i = utteranceId?.removePrefix("s")?.toIntOrNull() ?: return
            main.post { if (state == NarrationState.PLAYING && i >= sentences.lastIndex) finish() }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { main.post { fail("The phone's speech engine stopped while reading. Try Read Again, or use the text below.") } }
        override fun onError(utteranceId: String?, errorCode: Int) { main.post { fail("The phone's speech engine could not read this report (error $errorCode). Use the text below, or check Settings > Text-to-speech.") } }
    }

    /** Replaces what is read (the selection changed): whatever was playing stops first. */
    fun load(newSentences: List<String>) {
        if (newSentences == sentences) return
        stop()
        sentences = newSentences
    }

    fun play() {
        if (state == NarrationState.PLAYING || sentences.isEmpty()) return
        if (state == NarrationState.UNAVAILABLE) { message = message ?: "Reading aloud is not available on this phone." ; return }
        val engine = tts
        if (engine == null) {
            startWhenReady = true
            tts = TextToSpeech(app) { status -> main.post { onEngineReady(status) } }
            return
        }
        if (!ready) { startWhenReady = true; return }
        speakFrom(if (state == NarrationState.PAUSED) index else 0)
    }

    fun pause() {
        if (state != NarrationState.PLAYING) return
        tts?.stop()
        abandonFocus()
        state = NarrationState.PAUSED
    }

    fun resume() { if (state == NarrationState.PAUSED) play() }

    fun stop() {
        startWhenReady = false
        if (state == NarrationState.PLAYING || state == NarrationState.PAUSED) tts?.stop()
        abandonFocus()
        index = 0
        if (state != NarrationState.UNAVAILABLE) state = NarrationState.STOPPED
    }

    fun readAgain() { stop(); play() }

    /** Speech speed (1.0 = normal), applied from the next sentence. */
    fun changeRate(value: Float) {
        rate = value
        tts?.setSpeechRate(value)
    }

    /** Releases the speech engine; call when the report leaves the screen. */
    fun shutdown() {
        startWhenReady = false
        abandonFocus()
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        ready = false
    }

    // ---------------------------------------------------------------- internals

    private fun onEngineReady(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) { fail("Reading aloud is not available: this phone has no working text-to-speech engine. The text below has the same report."); state = NarrationState.UNAVAILABLE; return }
        val voice = chooseOfflineVoice(engine)
        if (voice == null) {
            state = NarrationState.UNAVAILABLE
            message = "No offline voice is installed for this language. GoPreach never sends a report to an online speech service, so install an offline voice in Settings > Text-to-speech, or use the text below."
            return
        }
        engine.voice = voice
        engine.setSpeechRate(rate)
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        engine.setOnUtteranceProgressListener(progress)
        ready = true
        message = null
        if (startWhenReady) { startWhenReady = false; speakFrom(0) }
    }

    /** The best installed voice for the app's language that works without a network; null when there is none. */
    private fun chooseOfflineVoice(engine: TextToSpeech): Voice? {
        val voices = runCatching { engine.voices?.toList() }.getOrNull().orEmpty().filter { !it.isNetworkConnectionRequired && !it.features.orEmpty().contains("notInstalled") }
        fun best(language: String) = voices.filter { it.locale.language == language }.maxByOrNull { it.quality }
        return best(Locale.getDefault().language) ?: best(Locale.ENGLISH.language)
    }

    private fun speakFrom(start: Int) {
        val engine = tts ?: return
        if (!requestFocus()) { fail("Another app is using the audio right now. Try again when it has finished."); return }
        state = NarrationState.PLAYING
        message = null
        index = start.coerceIn(0, sentences.lastIndex)
        engine.stop()
        for (i in index until sentences.size) engine.speak(sentences[i], TextToSpeech.QUEUE_ADD, null, "s$i")
    }

    private fun finish() {
        abandonFocus()
        index = 0
        state = NarrationState.STOPPED
    }

    private fun fail(text: String) {
        runCatching { tts?.stop() }
        abandonFocus()
        if (state != NarrationState.UNAVAILABLE) state = NarrationState.STOPPED
        message = text
    }

    private fun requestFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(focusListener).build()
            focusRequest = request
            audio.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) { focusRequest?.let { audio.abandonAudioFocusRequest(it) }; focusRequest = null }
        else @Suppress("DEPRECATION") audio.abandonAudioFocus(focusListener)
    }
}
