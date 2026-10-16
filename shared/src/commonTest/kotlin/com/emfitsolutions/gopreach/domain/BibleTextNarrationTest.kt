package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.domain.BibleTextNarration.Group
import com.emfitsolutions.gopreach.domain.BibleTextNarration.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BibleTextNarrationTest {
    @Test
    fun referencesAreSpokenNaturally() {
        assertEquals("Revelation 21, verses 3 to 4", BibleTextNarration.spokenReference("Revelation", 21, "3-4"))
        assertEquals("Psalm 83, verse 18", BibleTextNarration.spokenReference("Psalm", 83, "18"))
        assertEquals("John 3, verses 16 and 17 to 18", BibleTextNarration.spokenReference("John", 3, "16, 17-18"))
        assertEquals("Genesis 1", BibleTextNarration.spokenReference("Genesis", 1, " "))
    }

    @Test
    fun textsAreCutIntoShortSentencesAndFootnoteMarksAreDropped() {
        val s = BibleTextNarration.sentences("3 God is with us.* He will wipe out every tear.\nNo more death!")
        assertEquals(listOf("3 God is with us.", "He will wipe out every tear.", "No more death!"), s)
        val long = BibleTextNarration.sentences("word ".repeat(200).trim())
        assertTrue(long.size > 1 && long.all { it.length <= 280 })
        assertTrue(BibleTextNarration.sentences("  ").isEmpty())
    }

    @Test
    fun aTextAndANoteAreReadAsSaved() {
        val verse = BibleTextNarration.textScript(Text("Psalm 83:18", "That people may know that you, whose name is Jehovah.", "Use at the door.", false))
        assertEquals("Psalm 83:18.", verse[0])
        assertTrue(verse.contains("That people may know that you, whose name is Jehovah."))
        assertTrue(verse.contains("My note.") && verse.contains("Use at the door."))
        assertTrue(BibleTextNarration.textScript(Text("Psalm 83:18", "", "", false)).contains("The verse text has not been saved for this record."))
        assertEquals(listOf("Note.", "Ask about family."), BibleTextNarration.textScript(Text("Note", "Ask about family.", "", true)))
        assertEquals(listOf("Note.", "This note is empty."), BibleTextNarration.textScript(Text("Note", "", "", true)))
    }

    @Test
    fun anEventIsReadWithItsSubTopics() {
        val s = BibleTextNarration.eventScript(
            "Memorial 2026", "Memorial", "Brother Cruz",
            listOf(Group(null, listOf(Text("John 3:16", "For God loved the world.", "", false))), Group("Hope", listOf(Text("Note", "Share this.", "", true)))),
        )
        assertEquals("Memorial 2026.", s[0])
        assertTrue(s.contains("Event: Memorial.") && s.contains("Speaker: Brother Cruz."))
        assertTrue(s.contains("This event has 2 Bible texts and notes."))
        assertTrue(s.indexOf("Sub topic: Hope.") > s.indexOf("For God loved the world."))
        assertTrue(s.last() == "End of the event.")
        assertTrue(BibleTextNarration.eventScript("E", "", null, emptyList()).contains("There are no Bible texts or notes in this event yet."))
    }
}
