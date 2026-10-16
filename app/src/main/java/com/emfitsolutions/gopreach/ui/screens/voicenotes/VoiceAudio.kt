package com.emfitsolutions.gopreach.ui.screens.voicenotes

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.emfitsolutions.gopreach.data.voice.VoiceAudioStore
import java.io.File

/** The recordings on this phone: app-private files (no other app can read them), one per note, named by the note's id. */
class AndroidVoiceAudioStore(context: Context) : VoiceAudioStore {
    private val dir = File(context.applicationContext.filesDir, "voice_notes").apply { mkdirs() }
    fun file(noteId: String) = File(dir, "$noteId.m4a")
    override fun exists(noteId: String) = file(noteId).let { it.exists() && it.length() > 0 }
    override fun read(noteId: String): ByteArray? = file(noteId).takeIf { it.exists() }?.readBytes()
    override fun delete(noteId: String) { file(noteId).delete() }
    /** Free space on the phone's storage for recordings, in bytes. */
    fun freeBytes(): Long = dir.usableSpace
}

enum class RecordingState { IDLE, RECORDING, PAUSED }

/**
 * Records the microphone to an AAC file (mono, 16 kHz, 48 kbit/s: about 0.36 MB a minute, speech-quality and small enough to upload).
 * The microphone is open only between [start] and [stop] / [discard]; [pause] closes the stream's capture but keeps the file, [resume] continues it.
 * [tick] must be called regularly (the screen does it every 100 ms) to refresh the timer and the level meter.
 */
class VoiceRecorder(private val context: Context, private val store: AndroidVoiceAudioStore) {
    var state by mutableStateOf(RecordingState.IDLE)
        private set
    var elapsedMs by mutableLongStateOf(0L)
        private set
    /** 0..1 loudness of the last moment, for the animated bars. */
    var level by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var noteId by mutableStateOf<String?>(null)
        private set
    val levels = mutableStateOf(List(28) { 0f })

    private var recorder: MediaRecorder? = null
    private var segmentStart = 0L
    private var accumulated = 0L

    /** Longest recording: keeps the file under the server's upload limit (about 66 minutes at this quality). */
    val maxMs = 60 * 60 * 1000L

    fun start(id: String): Boolean {
        if (state != RecordingState.IDLE) return false
        error = null
        if (store.freeBytes() < 30L * 1024 * 1024) { error = "There is not enough free storage on this phone to record."; return false }
        return try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(48_000)
            r.setOutputFile(store.file(id).absolutePath)
            r.prepare()
            r.start()
            recorder = r
            noteId = id
            accumulated = 0L
            segmentStart = System.currentTimeMillis()
            elapsedMs = 0L
            levels.value = List(28) { 0f }
            state = RecordingState.RECORDING
            true
        } catch (e: Exception) {
            release()
            error = "Recording could not start: ${e.message ?: "the microphone is not available"}."
            false
        }
    }

    fun pause() {
        if (state != RecordingState.RECORDING) return
        runCatching { recorder?.pause() }
        accumulated += System.currentTimeMillis() - segmentStart
        level = 0f
        state = RecordingState.PAUSED
    }

    fun resume() {
        if (state != RecordingState.PAUSED) return
        runCatching { recorder?.resume() }
        segmentStart = System.currentTimeMillis()
        state = RecordingState.RECORDING
    }

    /** Refreshes the timer and the level meter; stops by itself at [maxMs]. Returns true when it stopped itself. */
    fun tick(): Boolean {
        if (state == RecordingState.RECORDING) {
            elapsedMs = accumulated + (System.currentTimeMillis() - segmentStart)
            val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
            level = (amp / 16000f).coerceIn(0f, 1f)
            levels.value = (levels.value.drop(1) + level)
            if (elapsedMs >= maxMs) { error = "The longest recording (60 minutes) was reached and saved."; return true }
        }
        return false
    }

    /** Ends the recording and returns its length in seconds, or null if there is nothing usable. The file stays on the phone. */
    fun stop(): Int? {
        val id = noteId ?: return null
        if (state == RecordingState.IDLE) return null
        if (state == RecordingState.RECORDING) accumulated += System.currentTimeMillis() - segmentStart
        val ok = runCatching { recorder?.stop() }.isSuccess
        val seconds = (accumulated / 1000L).toInt()
        release()
        if (!ok || seconds < 1 || !store.exists(id)) { store.delete(id); error = "The recording was too short to keep."; return null }
        return seconds
    }

    /** Throws the recording away (the file is deleted). */
    fun discard() {
        val id = noteId
        runCatching { recorder?.stop() }
        release()
        id?.let { store.delete(it) }
    }

    private fun release() {
        runCatching { recorder?.release() }
        recorder = null
        state = RecordingState.IDLE
        level = 0f
        noteId = null
    }
}

enum class PlayerState { IDLE, LOADING, READY, PLAYING, PAUSED, ERROR }

/** Plays one recording - from the file on this phone, or from its private server link when the file is only on another device. */
class VoicePlayer(private val context: Context) {
    var state by mutableStateOf(PlayerState.IDLE)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private var player: MediaPlayer? = null

    fun load(file: File?, url: String?) {
        release()
        message = null
        val source = when {
            file != null && file.exists() && file.length() > 0 -> file.absolutePath
            !url.isNullOrBlank() -> url
            else -> { state = PlayerState.ERROR; message = "The recording is not on this phone and has not been uploaded."; return }
        }
        state = PlayerState.LOADING
        val p = MediaPlayer()
        player = p
        try {
            p.setDataSource(source)
            p.setOnPreparedListener { durationMs = it.duration; state = PlayerState.READY; applySpeed() }
            p.setOnCompletionListener { state = PlayerState.READY; positionMs = 0; runCatching { it.seekTo(0) } }
            p.setOnErrorListener { _, _, _ -> state = PlayerState.ERROR; message = "This recording could not be played."; true }
            p.prepareAsync()
        } catch (e: Exception) {
            state = PlayerState.ERROR
            message = "This recording could not be played."
        }
    }

    fun play() {
        val p = player ?: return
        if (state != PlayerState.READY && state != PlayerState.PAUSED) return
        runCatching { p.start(); applySpeed(); state = PlayerState.PLAYING }
    }

    fun pause() { if (state == PlayerState.PLAYING) { runCatching { player?.pause() }; state = PlayerState.PAUSED } }

    fun stop() {
        val p = player ?: return
        if (state == PlayerState.PLAYING || state == PlayerState.PAUSED) { runCatching { p.pause(); p.seekTo(0) }; positionMs = 0; state = PlayerState.READY }
    }

    fun seekTo(ms: Int) { runCatching { player?.seekTo(ms.coerceIn(0, durationMs)) }; positionMs = ms.coerceIn(0, durationMs) }

    fun changeSpeed(value: Float) { speed = value; applySpeed() }

    private fun applySpeed() {
        val p = player ?: return
        // Changing the speed of a paused player would start it on some phones: only set it while playing, otherwise it is applied on the next play().
        if (state == PlayerState.PLAYING) runCatching { p.playbackParams = PlaybackParams().setSpeed(speed) }
    }

    fun tick() { val p = player ?: return; if (state == PlayerState.PLAYING) positionMs = runCatching { p.currentPosition }.getOrDefault(positionMs) }

    fun release() {
        runCatching { player?.release() }
        player = null
        state = PlayerState.IDLE
        positionMs = 0
        durationMs = 0
    }
}
