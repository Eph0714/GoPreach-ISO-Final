package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.VoiceNote
import com.emfitsolutions.gopreach.data.model.VoiceNoteStatus
import com.emfitsolutions.gopreach.data.model.VoiceStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceNoteLogicTest {
    private fun note(
        t: VoiceStep = VoiceStep.NOT_STARTED, s: VoiceStep = VoiceStep.NOT_STARTED, wt: Boolean = false, wm: Boolean = false, title: String = "n",
    ) = VoiceNote(id = "i", title = title, transcriptionStatus = t, summaryStatus = s, wantsTranscription = wt, wantsMinutes = wm)

    @Test
    fun statusTellsThePersonWhereTheNoteStands() {
        assertEquals(VoiceNoteStatus.LOCAL_DRAFT, VoiceNoteLogic.status(note(), online = true))
        assertEquals(VoiceNoteStatus.WAITING, VoiceNoteLogic.status(note(VoiceStep.WAITING, wt = true), online = false))
        assertEquals(VoiceNoteStatus.TRANSCRIBING, VoiceNoteLogic.status(note(VoiceStep.PROCESSING, wt = true), online = true))
        assertEquals(VoiceNoteStatus.GENERATING, VoiceNoteLogic.status(note(VoiceStep.DONE, VoiceStep.PROCESSING, wm = true), online = true))
        assertEquals(VoiceNoteStatus.TRANSCRIBED, VoiceNoteLogic.status(note(VoiceStep.DONE), online = true))
        assertEquals(VoiceNoteStatus.COMPLETE, VoiceNoteLogic.status(note(VoiceStep.DONE, VoiceStep.DONE), online = true))
        assertEquals(VoiceNoteStatus.FAILED, VoiceNoteLogic.status(note(VoiceStep.FAILED, wt = true), online = true))
        assertEquals(VoiceNoteStatus.WAITING, VoiceNoteLogic.status(note(VoiceStep.WAITING, wt = true).copy(lastError = "The server does not have voice processing yet."), online = true), "waiting for the server is not transcribing")
    }

    @Test
    fun pendingMeansWorkIsStillWanted() {
        assertTrue(VoiceNoteLogic.isPending(note(VoiceStep.WAITING, wt = true)))
        assertFalse(VoiceNoteLogic.isPending(note(VoiceStep.DONE, VoiceStep.DONE)))
        assertFalse(VoiceNoteLogic.isPending(note()), "a plain draft has nothing to do")
    }

    @Test
    fun searchSortAndClock() {
        val n = VoiceNote(id = "1", title = "Circuit talk", transcriptText = "Magandang umaga po", meetingMinutesText = "## Purpose\nPlanning", category = "Meetings")
        assertTrue(VoiceNoteLogic.matches(n, "umaga"))
        assertTrue(VoiceNoteLogic.matches(n, "planning"))
        assertTrue(VoiceNoteLogic.matches(n, "meetings"))
        assertFalse(VoiceNoteLogic.matches(n, "xyz"))
        val a = VoiceNote(id = "a", title = "B", recordedAt = 10, updatedAt = 5)
        val b = VoiceNote(id = "b", title = "A", recordedAt = 20, updatedAt = 1)
        assertEquals(listOf("b", "a"), VoiceNoteLogic.sorted(listOf(a, b), VoiceNoteLogic.Sort.NEWEST).map { it.id })
        assertEquals(listOf("b", "a"), VoiceNoteLogic.sorted(listOf(a, b), VoiceNoteLogic.Sort.TITLE).map { it.id })
        assertEquals(listOf("a", "b"), VoiceNoteLogic.sorted(listOf(a, b), VoiceNoteLogic.Sort.MODIFIED).map { it.id })
        assertEquals("1:05", VoiceNoteLogic.clock(65))
        assertEquals("1:02:03", VoiceNoteLogic.clock(3723))
        assertEquals("0:00", VoiceNoteLogic.clock(-4))
    }

    @Test
    fun minutesAreSplitIntoSectionsAndReadAsPlainSentences() {
        val md = "# Weekly Meeting\n**Date and Time:** Oct 16\n## Purpose / Overview\nTo plan the week.\n## Decisions Made\nNo specific decision recorded.\n## Action Items / Follow-ups\n- **Ana** will call the hall.\n- Bring chairs."
        assertEquals("Weekly Meeting", VoiceNoteLogic.titleFromMinutes(md))
        val s = VoiceNoteLogic.sections(md)
        assertEquals(listOf(null, "Purpose / Overview", "Decisions Made", "Action Items / Follow-ups"), s.map { it.heading })
        assertEquals("To plan the week.", s[1].body)
        val spoken = VoiceNoteLogic.spoken(md)
        assertEquals("Weekly Meeting.", spoken.first())
        assertTrue(spoken.contains("Purpose / Overview."))
        assertTrue(spoken.contains("Ana will call the hall."), spoken.toString())
        assertTrue(spoken.none { it.contains("**") || it.startsWith("-") })
    }
}
