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

    /** The language the text is in (a language code such as "fil" or "en"), or null for the app language. Set by [load]. */
    private var language: String? = null
    /** A gentle note (not an error): the text's own language has no installed offline voice, so another voice reads it. */
    var notice by mutableStateOf<String?>(null)
        private set

    /** Replaces what is read (the selection changed): whatever was playing stops first. */
    fun load(newSentences: List<String>, language: String? = null) {
        val languageChanged = language != this.language
        if (newSentences == sentences && !languageChanged) return
        stop()
        sentences = newSentences
        this.language = language
        notice = null
        if (ready && languageChanged) tts?.let { e -> chooseOfflineVoice(e)?.let { v -> e.voice = v }; e.setPitch(pitch) }
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
        engine.setPitch(pitch)
        engine.setSpeechRate(rate)
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        engine.setOnUtteranceProgressListener(progress)
        ready = true
        message = null
        if (startWhenReady) { startWhenReady = false; speakFrom(0) }
    }

    /** What the Publisher chose (Settings or the player): a language to read in (null = the text's own / the app's) and a kind of voice. */
    private var preferredLanguage: String? = null
    private var gender = com.emfitsolutions.gopreach.domain.VoiceGender.ANY
    /** Speech pitch for the chosen voice kind: normal when a real male / female voice was found, otherwise raised (female) or lowered (male). */
    private var pitch = 1.0f

    /** Applies the Publisher's voice choices. Changing either stops what is playing (a voice cannot change mid-sentence). */
    fun applyPreferences(gender: com.emfitsolutions.gopreach.domain.VoiceGender, languageCode: String?) {
        if (gender == this.gender && languageCode == preferredLanguage) return
        stop()
        this.gender = gender
        preferredLanguage = languageCode
        notice = null
        if (ready) tts?.let { e -> chooseOfflineVoice(e)?.let { v -> e.voice = v }; e.setPitch(pitch) }
    }

    /**
     * The best installed offline voice: the language the Publisher chose, else the text's own language, else the app language, else English. When a male
     * or female voice was asked for it is matched from the voice's name (Android has no gender field); when the phone does not label its voices, a
     * DIFFERENT voice is used for each kind (when the phone has more than one) and the pitch is lowered for male / raised for female, so the choice is
     * always audible. A note says which of these happened.
     */
    private fun chooseOfflineVoice(engine: TextToSpeech): Voice? {
        val voices = runCatching { engine.voices?.toList() }.getOrNull().orEmpty().filter { !it.isNetworkConnectionRequired && !it.features.orEmpty().contains("notInstalled") }
        fun codes(l: String): List<String> = if (l == "fil" || l == "tl") listOf("fil", "tl") else listOf(l)
        fun inLanguage(l: String) = voices.filter { v -> codes(l).any { v.locale.language == it } }
        val wanted = preferredLanguage ?: language
        val ownList = wanted?.let { inLanguage(it) }.orEmpty()
        val pool = ownList.ifEmpty { inLanguage(Locale.getDefault().language).ifEmpty { inLanguage(Locale.ENGLISH.language) } }
        var chosen: Voice? = null
        var stretched = false
        if (pool.isNotEmpty()) {
            val ranked = pool.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
            if (gender == com.emfitsolutions.gopreach.domain.VoiceGender.ANY) {
                chosen = ranked.first()
            } else {
                val named = ranked.filter { com.emfitsolutions.gopreach.domain.VoiceNames.gender(it.name) == gender }
                if (named.isNotEmpty()) {
                    chosen = named.first()
                } else {
                    // Unlabelled voices: a different one for each kind, then a pitch change on top.
                    val unlabelled = ranked.filter { com.emfitsolutions.gopreach.domain.VoiceNames.gender(it.name) == null }.ifEmpty { ranked }
                    chosen = if (gender == com.emfitsolutions.gopreach.domain.VoiceGender.FEMALE) unlabelled.first() else unlabelled.getOrElse(1) { unlabelled.first() }
                    stretched = true
                }
            }
        }
        pitch = if (stretched) (if (gender == com.emfitsolutions.gopreach.domain.VoiceGender.MALE) 0.78f else 1.2f) else 1.0f
        android.util.Log.i("ReportNarrator", "voices for ${wanted ?: "default"}: ${pool.joinToString { it.name }} -> ${chosen?.name} gender=$gender pitch=$pitch")
        val notes = buildList {
            if (wanted != null && ownList.isEmpty()) add("No offline voice is installed for this language, so another voice reads it and may pronounce it poorly. You can install one in Settings > Text-to-speech.")
            if (stretched) add("This phone does not say which of its voices are male or female, so the ${gender.label.lowercase()} choice changes the voice's pitch.")
        }
        notice = notes.joinToString(" ").ifBlank { null }
        return chosen
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
