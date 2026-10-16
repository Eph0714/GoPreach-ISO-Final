package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.VoiceNote
import com.emfitsolutions.gopreach.data.model.VoiceNoteStatus
import com.emfitsolutions.gopreach.data.model.VoiceStep

/** Pure rules of the Voice Notes module: what a note's status is, how it is searched and sorted, how its minutes are split into sections. */
object VoiceNoteLogic {
    fun status(n: VoiceNote, online: Boolean): VoiceNoteStatus = when {
        n.transcriptionStatus == VoiceStep.FAILED || n.summaryStatus == VoiceStep.FAILED -> VoiceNoteStatus.FAILED
        n.transcriptionStatus == VoiceStep.PROCESSING -> VoiceNoteStatus.TRANSCRIBING
        n.summaryStatus == VoiceStep.PROCESSING -> VoiceNoteStatus.GENERATING
        // Asked for, but the server is not ready (or the phone is offline): it waits, it is not running.
        (n.transcriptionStatus == VoiceStep.WAITING || n.summaryStatus == VoiceStep.WAITING) && (n.lastError.isNotBlank() || !online) -> VoiceNoteStatus.WAITING
        (n.wantsTranscription && n.transcriptionStatus != VoiceStep.DONE) || (n.wantsMinutes && n.summaryStatus != VoiceStep.DONE) ->
            if (online) VoiceNoteStatus.TRANSCRIBING else VoiceNoteStatus.WAITING
        n.summaryStatus == VoiceStep.DONE -> VoiceNoteStatus.COMPLETE
        n.transcriptionStatus == VoiceStep.DONE -> VoiceNoteStatus.TRANSCRIBED
        else -> VoiceNoteStatus.LOCAL_DRAFT
    }

    /** A note still has AI work to do (it is what "Pending Transcriptions" counts). */
    fun isPending(n: VoiceNote): Boolean =
        (n.wantsTranscription && n.transcriptionStatus != VoiceStep.DONE) || (n.wantsMinutes && n.summaryStatus != VoiceStep.DONE) ||
            n.transcriptionStatus == VoiceStep.PROCESSING || n.summaryStatus == VoiceStep.PROCESSING

    fun matches(n: VoiceNote, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return n.title.contains(q, true) || n.transcriptText.contains(q, true) || n.meetingMinutesText.contains(q, true) || n.category.contains(q, true)
    }

    enum class Sort(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), TITLE("Title"), MODIFIED("Last modified") }

    fun sorted(list: List<VoiceNote>, sort: Sort): List<VoiceNote> = when (sort) {
        Sort.NEWEST -> list.sortedByDescending { it.recordedAt }
        Sort.OLDEST -> list.sortedBy { it.recordedAt }
        Sort.TITLE -> list.sortedBy { it.title.lowercase() }
        Sort.MODIFIED -> list.sortedByDescending { it.updatedAt }
    }

    /** "1:05" or "1:02:03". */
    fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}" else "$m:${sec.toString().padStart(2, '0')}"
    }

    /** The first heading of the minutes, used as the note's title when the person has not named it. */
    fun titleFromMinutes(minutes: String): String? =
        minutes.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()?.takeIf { it.isNotEmpty() }?.take(120)

    /** One block of the minutes: a heading (null for the opening lines) and its text. */
    data class Section(val heading: String?, val body: String)

    /** Splits Markdown minutes into sections at each "## " heading; the "# Title" line is left out. Empty sections are dropped. */
    fun sections(minutes: String): List<Section> {
        val out = mutableListOf<Section>()
        var heading: String? = null
        val body = StringBuilder()
        fun flush() {
            val b = body.toString().trim()
            if (b.isNotEmpty()) out += Section(heading, b)
            body.clear()
        }
        minutes.lines().forEach { line ->
            if (line.startsWith("## ")) {
                flush()
                heading = line.removePrefix("## ").trim()
            } else if (!line.startsWith("# ")) {
                body.appendLine(line)
            }
        }
        flush()
        return out
    }

    /** The minutes as plain sentences for the read-aloud voice (Markdown marks removed, one section at a time). */
    fun spoken(minutes: String): List<String> = buildList {
        titleFromMinutes(minutes)?.let { add(it.trimEnd('.') + ".") }
        sections(minutes).forEach { s ->
            s.heading?.let { add(it.trimEnd('.') + ".") }
            addAll(BibleTextNarration.sentences(s.body.replace(Regex("(?m)^\\s*[-*]\\s+"), "").replace("**", "").replace("#", "")))
        }
    }
}
