package com.emfitsolutions.gopreach.ui.screens.voicenotes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.VoiceNote
import com.emfitsolutions.gopreach.data.model.VoiceStep
import com.emfitsolutions.gopreach.data.repository.VoiceNoteRepository
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.data.voice.VoiceNoteProcessor
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The Voice Notes module's logic: keeps the notes, starts the AI steps (queued while offline), and edits what the person writes. */
class VoiceNotesViewModel(
    private val repository: VoiceNoteRepository,
    private val processor: VoiceNoteProcessor,
    private val connectivity: ConnectivityObserver,
    val store: AndroidVoiceAudioStore,
) : ViewModel() {
    private val _online = MutableStateFlow(connectivity.isOnline())
    val online: StateFlow<Boolean> = _online

    private var started = false
    private val jobs = mutableMapOf<String, Job>()

    fun notes(personId: String): Flow<List<VoiceNote>> = repository.observeFor(personId)

    /** Opens the module for [personId]: keeps notes in step with the server, recovers interrupted work, and runs queued work whenever the phone is online. */
    fun start(personId: String) {
        if (started) return
        started = true
        viewModelScope.launch { repository.startRemoteSync().catch { }.collect { } }
        viewModelScope.launch {
            processor.recover(personId)
            connectivity.observe().distinctUntilChanged().collect { on ->
                _online.value = on
                if (on) processor.runPending(personId)
            }
        }
    }

    fun newNoteId(): String = "vn_" + nowMillis().toString(36) + "_" + (0..9999).random().toString(36)

    /** Saves a finished recording as a note (a local draft: nothing is sent anywhere until the person asks). */
    fun saveRecording(id: String, personId: String, congregationId: String, durationSeconds: Int, title: String) {
        val now = nowMillis()
        viewModelScope.launch {
            repository.save(
                VoiceNote(
                    id = id, publisherPersonId = personId, congregationId = congregationId, title = title, recordedAt = now - durationSeconds * 1000L,
                    durationSeconds = durationSeconds, createdAt = now, updatedAt = now,
                ),
            )
        }
    }

    private fun edit(id: String, change: (VoiceNote) -> VoiceNote) {
        viewModelScope.launch { repository.get(id)?.let { repository.save(change(it).copy(updatedAt = nowMillis())) } }
    }

    fun rename(id: String, title: String) = edit(id) { it.copy(title = title.trim()) }
    fun setCategory(id: String, category: String) = edit(id) { it.copy(category = category.trim()) }
    fun saveTranscript(id: String, text: String) = edit(id) { it.copy(transcriptText = text) }

    /** The person's own edit of the minutes. The text before the edit is kept so it can be restored. */
    fun saveMinutes(id: String, text: String) = edit(id) { it.copy(previousMinutesText = it.meetingMinutesText.ifBlank { it.previousMinutesText }, meetingMinutesText = text, summaryStatus = if (text.isBlank()) it.summaryStatus else VoiceStep.DONE) }

    fun restorePreviousMinutes(id: String) = edit(id) { it.copy(meetingMinutesText = it.previousMinutesText, previousMinutesText = it.meetingMinutesText) }

    fun transcribe(id: String) = launchJob(id) { processor.request(id, transcribe = true, minutes = false) }

    /** Writes the minutes; when there is no transcript yet this transcribes first, then writes them. */
    fun generateMinutes(id: String, detail: String) = launchJob(id) {
        val n = repository.get(id)
        processor.request(id, transcribe = n?.transcriptText.isNullOrBlank(), minutes = true, detail = detail)
    }

    fun retry(id: String) = launchJob(id) {
        val n = repository.get(id) ?: return@launchJob
        processor.request(id, transcribe = n.transcriptionStatus == VoiceStep.FAILED || (n.transcriptText.isBlank() && n.wantsTranscription), minutes = n.summaryStatus == VoiceStep.FAILED || n.wantsMinutes, detail = n.minutesDetail)
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        viewModelScope.launch { processor.cancel(id) }
    }

    private fun launchJob(id: String, block: suspend () -> Unit) {
        jobs[id]?.cancel()
        jobs[id] = viewModelScope.launch { block() }
    }

    fun runPendingNow(personId: String) { viewModelScope.launch { processor.runPending(personId) } }

    /** Returns a message when the server copy could not be removed (the note then stays so nothing is half-deleted). */
    fun deleteNote(id: String, onDone: (String?) -> Unit) {
        jobs.remove(id)?.cancel()
        viewModelScope.launch { onDone(processor.deleteEverything(id)) }
    }
}
