package com.emfitsolutions.gopreach.data.voice

import com.emfitsolutions.gopreach.data.model.VoiceNote
import com.emfitsolutions.gopreach.data.model.VoiceStep
import com.emfitsolutions.gopreach.data.remote.SyncApi
import com.emfitsolutions.gopreach.data.remote.SyncTransportException
import com.emfitsolutions.gopreach.data.repository.VoiceNoteRepository
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.domain.VoiceNoteLogic
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** Where a recording's audio file lives on this phone. Android implements it with files; the processor only needs these four things. */
interface VoiceAudioStore {
    fun exists(noteId: String): Boolean
    fun read(noteId: String): ByteArray?
    fun delete(noteId: String)
}

/**
 * Runs the AI steps of a voice note, safely and at most once at a time: upload the recording (once), transcribe it, write the minutes.
 *
 * - Offline-first: with no connection a note simply waits (its wanted steps stay switched on) and runs when the phone is online again.
 * - Nothing is ever lost: the recording stays on the phone and in the note; a failure only records the reason and leaves a Retry.
 * - No duplicates: the upload path is fixed per note (re-uploading replaces), and a step that is DONE is not run again.
 * - Interrupted work (the app was closed mid-step) is picked up by [recover], which puts PROCESSING steps back to waiting.
 */
class VoiceNoteProcessor(
    private val repository: VoiceNoteRepository,
    private val api: SyncApi,
    private val connectivity: ConnectivityObserver,
    private val audio: VoiceAudioStore,
) {
    private val lock = Mutex()

    /** Puts steps that were running when the app stopped back to waiting. Call once when the module opens. */
    suspend fun recover(personId: String) {
        repository.all(personId).forEach { n ->
            val t = if (n.transcriptionStatus == VoiceStep.PROCESSING) VoiceStep.WAITING else n.transcriptionStatus
            val s = if (n.summaryStatus == VoiceStep.PROCESSING) VoiceStep.WAITING else n.summaryStatus
            if (t != n.transcriptionStatus || s != n.summaryStatus) repository.save(n.copy(transcriptionStatus = t, summaryStatus = s, updatedAt = nowMillis()))
        }
    }

    /** Runs every note of [personId] that has work waiting. Safe to call whenever the phone comes online or the person taps Retry. */
    suspend fun runPending(personId: String) {
        if (!connectivity.isOnline()) return
        repository.all(personId).filter { VoiceNoteLogic.isPending(it) }.forEach { run(it.id) }
    }

    /** Asks for the steps and runs them as far as the connection allows. */
    suspend fun request(noteId: String, transcribe: Boolean, minutes: Boolean, detail: String? = null) {
        val n = repository.get(noteId) ?: return
        repository.save(
            n.copy(
                wantsTranscription = n.wantsTranscription || transcribe,
                wantsMinutes = n.wantsMinutes || minutes,
                transcriptionStatus = if (transcribe && n.transcriptionStatus != VoiceStep.PROCESSING) VoiceStep.WAITING else n.transcriptionStatus,
                summaryStatus = if (minutes && n.summaryStatus != VoiceStep.PROCESSING) VoiceStep.WAITING else n.summaryStatus,
                minutesDetail = detail ?: n.minutesDetail,
                lastError = "",
                updatedAt = nowMillis(),
            ),
        )
        run(noteId)
    }

    /** The person cancelled: nothing more is requested and a step that was running goes back to not started. */
    suspend fun cancel(noteId: String) {
        val n = repository.get(noteId) ?: return
        repository.save(
            n.copy(
                wantsTranscription = false, wantsMinutes = false,
                transcriptionStatus = if (n.transcriptionStatus == VoiceStep.DONE) VoiceStep.DONE else VoiceStep.NOT_STARTED,
                summaryStatus = if (n.summaryStatus == VoiceStep.DONE) VoiceStep.DONE else VoiceStep.NOT_STARTED,
                lastError = "", updatedAt = nowMillis(),
            ),
        )
    }

    /** Deletes the recording from the phone, its file on the server, and the note itself. The server file goes first so a failure there is reported, not hidden. */
    suspend fun deleteEverything(noteId: String): String? {
        val n = repository.get(noteId)
        if (n != null && n.audioStoragePath.isNotBlank()) {
            try {
                api.deleteFile(n.audioStoragePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!connectivity.isOnline()) return "You are offline. Connect to the internet to delete the recording from the server too, then delete it again."
            }
        }
        audio.delete(noteId)
        repository.delete(noteId)
        return null
    }

    suspend fun run(noteId: String) = lock.withLock {
        var note = repository.get(noteId) ?: return@withLock
        if (!(note.wantsTranscription && note.transcriptionStatus != VoiceStep.DONE) && !(note.wantsMinutes && note.summaryStatus != VoiceStep.DONE)) return@withLock
        if (!connectivity.isOnline()) {
            repository.save(note.copy(transcriptionStatus = waiting(note.wantsTranscription, note.transcriptionStatus), summaryStatus = waiting(note.wantsMinutes, note.summaryStatus), updatedAt = nowMillis()))
            return@withLock
        }
        try {
            if (note.wantsTranscription && note.transcriptionStatus != VoiceStep.DONE) note = transcribe(note) ?: return@withLock
            if (note.wantsMinutes && note.summaryStatus != VoiceStep.DONE) writeMinutes(note)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncTransportException) {
            fail(noteId, offlineLike = e.statusCode == null, message = e.message ?: "The connection was lost. It will continue when you are online.")
        }
    }

    private fun waiting(wanted: Boolean, current: VoiceStep) = if (wanted && current != VoiceStep.DONE) VoiceStep.WAITING else current

    private suspend fun fail(noteId: String, offlineLike: Boolean, message: String, transcription: Boolean? = null) {
        val n = repository.get(noteId) ?: return
        val target = if (offlineLike) VoiceStep.WAITING else VoiceStep.FAILED
        val tAffected = n.transcriptionStatus == VoiceStep.PROCESSING || transcription == true
        val sAffected = n.summaryStatus == VoiceStep.PROCESSING || transcription == false
        repository.save(
            n.copy(
                transcriptionStatus = if (tAffected) target else n.transcriptionStatus,
                summaryStatus = if (sAffected) target else n.summaryStatus,
                // A real failure stops asking for the step (the person taps Retry); waiting keeps asking so it runs when the phone is online / the server is ready.
                wantsTranscription = if (tAffected && !offlineLike) false else n.wantsTranscription,
                wantsMinutes = if (sAffected && !offlineLike) false else n.wantsMinutes,
                lastError = message, updatedAt = nowMillis(),
            ),
        )
    }

    private suspend fun transcribe(start: VoiceNote): VoiceNote? {
        var n = start
        if (!n.audioUploaded) {
            val bytes = audio.read(n.id)
            if (bytes == null || bytes.isEmpty()) {
                fail(n.id, offlineLike = false, message = "The recording file is missing from this phone, so it cannot be transcribed here.", transcription = true)
                return null
            }
            n = n.copy(transcriptionStatus = VoiceStep.PROCESSING, lastError = "", updatedAt = nowMillis())
            repository.save(n)
            val path = "voice-notes/${n.publisherPersonId}/${n.id}.m4a"
            val url = api.uploadFile(path, bytes, "audio/mp4")
            n = n.copy(audioStoragePath = path, audioUrl = url, audioUploaded = true, updatedAt = nowMillis())
            repository.save(n)
        } else {
            n = n.copy(transcriptionStatus = VoiceStep.PROCESSING, lastError = "", updatedAt = nowMillis())
            repository.save(n)
        }
        val reply = api.postJson("/v1/voice/transcribe", buildJsonObject { put("path", n.audioStoragePath) })
        val text = (reply.body?.get("text") as? JsonPrimitive)?.content
        if (reply.status == 200 && !text.isNullOrBlank()) {
            val language = (reply.body?.get("language") as? JsonPrimitive)?.content.orEmpty()
            val latest = repository.get(n.id) ?: n
            val done = latest.copy(
                // A transcript the person already edited by hand is not overwritten by a retry.
                transcriptText = if (latest.transcriptText.isBlank()) text else latest.transcriptText,
                language = language, transcriptionStatus = VoiceStep.DONE, wantsTranscription = false, lastError = "", updatedAt = nowMillis(),
            )
            repository.save(done)
            return done
        }
        fail(n.id, offlineLike = reply.status == 503 || reply.status == 404, message = errorOf(reply.body, reply.status), transcription = true)
        return null
    }

    private suspend fun writeMinutes(start: VoiceNote) {
        val n = repository.get(start.id) ?: start
        if (n.transcriptText.isBlank()) {
            fail(n.id, offlineLike = false, message = "There is no transcript yet, so no minutes can be written. Transcribe the recording first.", transcription = false)
            return
        }
        repository.save(n.copy(summaryStatus = VoiceStep.PROCESSING, lastError = "", updatedAt = nowMillis()))
        val reply = api.postJson(
            "/v1/voice/minutes",
            buildJsonObject {
                put("transcript", n.transcriptText)
                put("detail", if (n.minutesDetail == "short") "short" else "detailed")
                put("title", n.title)
                put("recordedAt", n.recordedAt.toString())
            },
        )
        val minutes = (reply.body?.get("minutes") as? JsonPrimitive)?.content
        if (reply.status == 200 && !minutes.isNullOrBlank()) {
            val latest = repository.get(n.id) ?: n
            repository.save(
                latest.copy(
                    previousMinutesText = latest.meetingMinutesText.ifBlank { latest.previousMinutesText },
                    meetingMinutesText = minutes,
                    title = latest.title.ifBlank { VoiceNoteLogic.titleFromMinutes(minutes).orEmpty() },
                    summaryStatus = VoiceStep.DONE, wantsMinutes = false, lastError = "", updatedAt = nowMillis(),
                ),
            )
            return
        }
        fail(n.id, offlineLike = reply.status == 503 || reply.status == 404, message = errorOf(reply.body, reply.status), transcription = false)
    }

    private fun errorOf(body: kotlinx.serialization.json.JsonObject?, status: Int): String =
        if (status == 404) "The server does not have voice processing yet. Your recording is safe and will be processed as soon as it is set up." else (body?.get("error") as? JsonPrimitive)?.content ?: when (status) {
            401, 403 -> "You are not allowed to do this. Sign in again."
            404 -> "The server does not have voice processing yet. Your recording is safe and will be processed as soon as it is set up."
            else -> "The server could not do this (error $status). Try again."
        }
}
