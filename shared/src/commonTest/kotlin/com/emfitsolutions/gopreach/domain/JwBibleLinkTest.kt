package com.emfitsolutions.gopreach.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JwBibleLinkTest {
    @Test
    fun aSingleVerseLinkIsRead() {
        val link = JwBibleLink.parse("https://www.jw.org/finder?srcid=jwlshare&wtlocale=E&prefer=lang&bible=40024014")!!
        assertEquals(ParsedBibleLink(book = 40, chapter = 24, firstVerse = 14, lastVerse = 14, locale = "E"), link)
        assertEquals("14", link.verses)
    }

    @Test
    fun aVerseRangeInOneChapterIsRead() {
        val link = JwBibleLink.parse("Matthew 24:14-16\nhttps://www.jw.org/finder?wtlocale=TG&bible=40024014-40024016")!!
        assertEquals("14-16", link.verses)
        assertEquals("TG", link.locale)
    }

    @Test
    fun theJwLibraryLinkFormIsReadToo() {
        assertEquals(1, JwBibleLink.parse("jwlibrary:///finder?srcid=jwlshare&wtlocale=E&prefer=lang&bible=01001001")!!.book)
    }

    @Test
    fun aLinkWithoutALanguageStillParses() {
        assertNull(JwBibleLink.parse("https://www.jw.org/finder?bible=43003016")!!.locale)
    }

    @Test
    fun anythingElseIsNotABibleLink() {
        assertNull(JwBibleLink.parse(null))
        assertNull(JwBibleLink.parse("just some text"))
        assertNull(JwBibleLink.parse("https://www.jw.org/finder?lank=pub-jwbvod26_34_VIDEO"))
        assertNull(JwBibleLink.parse("https://www.jw.org/finder?bible=99001001"), "no such book")
        assertNull(JwBibleLink.parse("https://www.jw.org/finder?bible=40024014-40025002"), "across chapters")
        assertNull(JwBibleLink.parse("https://www.jw.org/finder?bible=40024016-40024014"), "backwards")
    }
}
