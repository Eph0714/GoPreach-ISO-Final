package com.emfitsolutions.gopreach.domain

/** A Bible reference found in a JW Library / jw.org link: the book number (1-66), chapter, first and last verse, and the language code if the link carries one. */
data class ParsedBibleLink(val book: Int, val chapter: Int, val firstVerse: Int, val lastVerse: Int, val locale: String?) {
    /** "24" or "24-26": the verses as GoPreach's Verses field takes them. */
    val verses: String get() = if (lastVerse > firstVerse) "$firstVerse-$lastVerse" else firstVerse.toString()
}

/**
 * Reads the Bible reference out of the link JW Library's Share sends for a verse, for example
 * `https://www.jw.org/finder?srcid=jwlshare&wtlocale=E&prefer=lang&bible=40024014`. The `bible` value is the book (2 digits), chapter (3) and
 * verse (3) of the first verse, optionally followed by `-` and the same eight digits for the last verse. Returns null when the text has no such
 * link, or the reference spans more than one chapter (which GoPreach's single chapter field cannot hold), so nothing is guessed.
 */
object JwBibleLink {
    private val BIBLE = Regex("""[?&;]bible=(\d{8})(?:-(\d{8}))?""", RegexOption.IGNORE_CASE)
    private val LOCALE = Regex("""[?&;]wtlocale=([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE)

    fun parse(text: String?): ParsedBibleLink? {
        if (text.isNullOrBlank()) return null
        val match = BIBLE.find(text) ?: return null
        fun book(code: String) = code.substring(0, 2).toInt()
        fun chapter(code: String) = code.substring(2, 5).toInt()
        fun verse(code: String) = code.substring(5, 8).toInt()
        val first = match.groupValues[1]
        val last = match.groupValues[2].ifEmpty { first }
        if (book(first) !in 1..66 || chapter(first) < 1 || verse(first) < 1) return null
        if (book(last) != book(first) || chapter(last) != chapter(first) || verse(last) < verse(first)) return null
        return ParsedBibleLink(book(first), chapter(first), verse(first), verse(last), LOCALE.find(text)?.groupValues?.get(1))
    }
}
