package com.emfitsolutions.gopreach.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedVerseTextTest {
    @Test
    fun theLinkLineIsDroppedAndTheVerseKept() {
        val shared = "“This good news of the Kingdom will be preached.” — Matthew 24:14\n\nhttps://www.jw.org/finder?srcid=jwlshare&wtlocale=E&prefer=lang&bible=40024014"
        assertEquals("“This good news of the Kingdom will be preached.” — Matthew 24:14", SharedVerseText.clean(shared))
    }

    @Test
    fun windowsLineEndingsAndExtraBlankLinesAreTidied() {
        assertEquals("Line one\n\nLine two", SharedVerseText.clean("  Line one  \r\n\r\n\r\n\r\nLine two\r\n"))
    }

    @Test
    fun aLinkAloneOrBlankTextIsNotAVerse() {
        assertNull(SharedVerseText.clean("https://www.jw.org/finder?bible=40024014"))
        assertNull(SharedVerseText.clean("   \n  "))
        assertNull(SharedVerseText.clean(""))
        assertNull(SharedVerseText.clean(null))
    }
}
