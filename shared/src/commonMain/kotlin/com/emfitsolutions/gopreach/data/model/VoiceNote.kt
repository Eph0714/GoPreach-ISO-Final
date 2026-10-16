package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/** Where one AI step (transcription, minutes) stands for a voice note. */
@kotlinx.serialization.Serializable
enum class VoiceStep { NOT_STARTED, WAITING, PROCESSING, DONE, FAILED }

/**
 * One recording with its transcript and meeting minutes (`voiceNotes/{id}`). Private to [publisherPersonId]: the server only ever shows it to its owner.
 * The audio itself is NOT in this document: it is a file on the device and, once uploaded, in the server's file store ([audioStoragePath]; [audioUrl]
 * is the private download link). [transcriptText] and [meetingMinutesText] are editable copies; the original audio is never changed by either.
 */
@kotlinx.serialization.Serializable
data class VoiceNote(
    @field:DocumentId val id: String = "",
    val publisherPersonId: String = "",
    val congregationId: String = "",
    val title: String = "",
    val category: String = "",
    val recordedAt: Long = 0L,
    val durationSeconds: Int = 0,
    val audioStoragePath: String = "",
    val audioUrl: String = "",
    /** True once the server holds this exact recording (so it is never uploaded twice). */
    val audioUploaded: Boolean = false,
    val transcriptText: String = "",
    val meetingMinutesText: String = "",
    /** The minutes as they were before the last regeneration - "Restore previous" brings this back. */
    val previousMinutesText: String = "",
    /** "short" or "detailed": the length the minutes were generated with. */
    val minutesDetail: String = "detailed",
    val transcriptionStatus: VoiceStep = VoiceStep.NOT_STARTED,
    val summaryStatus: VoiceStep = VoiceStep.NOT_STARTED,
    val language: String = "",
    /** What went wrong the last time, in words for the person; empty when nothing did. */
    val lastError: String = "",
    /** The person pressed Transcribe / Generate: it must run as soon as the phone is online. */
    val wantsTranscription: Boolean = false,
    val wantsMinutes: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** What the library shows for a note: the one word that tells the person where it stands. */
enum class VoiceNoteStatus(val label: String) {
    LOCAL_DRAFT("Local Draft"),
    WAITING("Waiting for Connection"),
    TRANSCRIBING("Transcribing"),
    GENERATING("Generating Minutes"),
    TRANSCRIBED("Transcribed"),
    COMPLETE("Synced"),
    FAILED("Failed"),
}
