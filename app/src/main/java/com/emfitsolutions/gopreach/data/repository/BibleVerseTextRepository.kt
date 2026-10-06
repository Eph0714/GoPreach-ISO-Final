package com.emfitsolutions.gopreach.data.repository

import android.content.Context
import android.text.Html
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Looks up the text of Bible verses in the New World Translation from the
 * official jw.org online library, on demand, so a Bible Text record's Remarks
 * can be filled with the actual verse (e.g. Revelation 21:3-4).
 *
 * Nothing is bundled in the app. The text is read from jw.org's public chapter
 * page (in the chosen language) at the moment the publisher asks for it, in
 * keeping with the reference data's own rule of not shipping licensed verse
 * text (see [com.emfitsolutions.gopreach.domain.NwtBibleReferenceData]). Every
 * chapter that has been opened is then kept on the device (see [chapterFile]),
 * so it can be used again with no internet; a chapter that has never been
 * opened does need a connection the first time. It depends on the page's
 * markup: every verse is a `<span class="verse" id="vBCCCVVV">` (book number,
 * then chapter and verse to three digits), which is what [parseChapter] reads.
 * Any failure (offline and not yet opened, page changed, verse not found)
 * returns null and the caller just leaves Remarks for the publisher to type.
 */
@Singleton
class BibleVerseTextRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** A few recently used chapters kept in memory on top of the files. */
    private val memoryCache = object : LinkedHashMap<String, Map<Int, String>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<Int, String>>?): Boolean = size > MAX_CACHED_CHAPTERS
    }

    /** The formatted text for [verses] ("3", "3-4", "3, 5", "3-4, 8") of book
     * number [bookNumber] (canonical 1-66 order) chapter [chapter] in the
     * language whose jw.org locale code is [jwLocale] (e.g. "E", "TG", "CV").
     * Verses read as "3 <text> 4 <text>"; non-adjacent groups go on separate
     * lines. Null if any requested verse couldn't be found, or the chapter is
     * neither saved on the device nor reachable online. */
    suspend fun fetchVerses(jwLocale: String, bookNumber: Int, chapter: Int, verses: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "fetchVerses($jwLocale, book $bookNumber, chapter $chapter, verses '$verses')")
        val numbers = parseVerseNumbers(verses) ?: return@withContext null
        val chapterVerses = loadChapter(jwLocale, bookNumber, chapter) ?: return@withContext null
        val parts = numbers.map { verse ->
            val text = chapterVerses[verse] ?: run {
                Log.w(TAG, "verse $verse not found in $jwLocale $bookNumber:$chapter")
                return@withContext null
            }
            verse to "$verse $text"
        }
        buildString {
            parts.forEachIndexed { index, (verse, text) ->
                if (index > 0) append(if (verse == parts[index - 1].first + 1) " " else "\n")
                append(text)
            }
        }
    }

    /** "3-4, 8" -> [3, 4, 8]; null for anything that isn't a valid verse list
     * (or asks for an unreasonable number of verses at once). */
    private fun parseVerseNumbers(verses: String): List<Int>? {
        val result = mutableListOf<Int>()
        for (part in verses.split(",").map { it.trim() }) {
            val bounds = part.split("-").map { it.trim().toIntOrNull() ?: return null }
            when (bounds.size) {
                1 -> result += bounds[0]
                2 -> if (bounds[0] <= bounds[1]) result += (bounds[0]..bounds[1]) else return null
                else -> return null
            }
        }
        return result.distinct().takeIf { it.isNotEmpty() && it.size <= MAX_VERSES }
    }

    /** Every verse of one chapter, from memory, then the saved file, then
     * jw.org (saving what it got for next time). */
    private fun loadChapter(jwLocale: String, bookNumber: Int, chapter: Int): Map<Int, String>? {
        val key = "$jwLocale/$bookNumber/$chapter"
        synchronized(memoryCache) { memoryCache[key] }?.let { return it }
        readChapterFile(jwLocale, bookNumber, chapter)?.let { saved ->
            synchronized(memoryCache) { memoryCache[key] = saved }
            return saved
        }
        val html = downloadChapterHtml(jwLocale, bookNumber, chapter) ?: return null
        val parsed = parseChapter(html, bookNumber, chapter)
        if (parsed.isEmpty()) {
            Log.w(TAG, "No verses found on the page for $key")
            return null
        }
        writeChapterFile(jwLocale, bookNumber, chapter, parsed)
        synchronized(memoryCache) { memoryCache[key] = parsed }
        return parsed
    }

    private fun chapterFile(jwLocale: String, bookNumber: Int, chapter: Int): File =
        File(File(context.filesDir, "bible_cache"), "${jwLocale}_${bookNumber}_$chapter.txt")

    /** One line per verse: the verse number, a tab, then its text. */
    private fun readChapterFile(jwLocale: String, bookNumber: Int, chapter: Int): Map<Int, String>? = runCatching {
        val file = chapterFile(jwLocale, bookNumber, chapter)
        if (!file.isFile) return@runCatching null
        file.readLines(Charsets.UTF_8)
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab).toIntOrNull()?.let { it to line.substring(tab + 1) }
            }
            .toMap()
            .takeIf { it.isNotEmpty() }
    }.onFailure { Log.w(TAG, "Could not read the saved chapter", it) }.getOrNull()

    private fun writeChapterFile(jwLocale: String, bookNumber: Int, chapter: Int, verses: Map<Int, String>) {
        runCatching {
            val file = chapterFile(jwLocale, bookNumber, chapter)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(verses.entries.joinToString("\n") { (number, text) -> "$number\t$text" }, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
                temp.delete()
            }
        }.onFailure { Log.w(TAG, "Could not save the chapter for offline use", it) }
    }

    private fun downloadChapterHtml(jwLocale: String, bookNumber: Int, chapter: Int): String? = runCatching {
        // jw.org's scripture finder redirects to that language's own chapter page
        // (whatever its localized URL is), which avoids keeping a per-language
        // table of book path names here.
        val finderUrl = "https://www.jw.org/finder?wtlocale=$jwLocale&pub=nwt&bible=" + "%02d%03d%03d".format(bookNumber, chapter, 1)
        val connection = (URL(finderUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "GoPreach/1.0 (Android; congregation ministry app)")
        }
        try {
            if (connection.responseCode != 200) {
                Log.w(TAG, "jw.org HTTP ${connection.responseCode} for $jwLocale $bookNumber:$chapter")
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.w(TAG, "Could not load $jwLocale $bookNumber:$chapter from jw.org", it) }.getOrNull()

    /** Every verse on a chapter page, keyed by verse number. */
    private fun parseChapter(html: String, bookNumber: Int, chapter: Int): Map<Int, String> {
        // jw.org ids are the book number (not zero-padded: Genesis is "1", Revelation "66"),
        // then chapter and verse padded to three digits — e.g. v1001001, v66021003.
        val opening = Regex("<span class=\"verse\" id=\"v" + bookNumber + "%03d".format(chapter) + "(\\d{3})\">")
        val result = linkedMapOf<Int, String>()
        for (match in opening.findAll(html)) {
            val verse = match.groupValues[1].toInt()
            if (verse in result) continue
            extractVerse(html, match.range.first)?.let { result[verse] = it }
        }
        return result
    }

    /** The plain text of the verse whose span starts at [start]: read up to its
     * matching closing tag (spans nest for poetry lines), then stripped of the
     * verse/chapter number, cross-reference and footnote markers and any
     * markup. */
    private fun extractVerse(html: String, start: Int): String? {
        var depth = 0
        var end = -1
        val tags = SPAN_TAG.toPattern().matcher(html)
        var searchFrom = start
        while (tags.find(searchFrom)) {
            if (tags.group() == "</span>") {
                depth--
                if (depth == 0) {
                    end = tags.end()
                    break
                }
            } else {
                depth++
            }
            searchFrom = tags.end()
        }
        if (end < 0) return null
        val text = html.substring(start, end)
            .replace(VERSE_NUMBER, " ")
            .replace(CHAPTER_NUMBER, " ")
            .replace(REFERENCE_MARKER, "")
            .replace(LINE_BREAK, " ")
            .replace(ANY_TAG, "")
        return Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(' ', ' ')
            .replace(WHITESPACE, " ")
            .trim()
            .ifEmpty { null }
    }

    private companion object {
        const val TAG = "BibleVerseText"
        const val MAX_CACHED_CHAPTERS = 6
        const val MAX_VERSES = 60

        val SPAN_TAG = Regex("<span\\b|</span>")
        val VERSE_NUMBER = Regex("<sup class=\"verseNum\">.*?</sup>", RegexOption.DOT_MATCHES_ALL)
        val CHAPTER_NUMBER = Regex("<span class=\"chapterNum\">.*?</span>", RegexOption.DOT_MATCHES_ALL)
        val REFERENCE_MARKER = Regex("<a class=\"(?:xrefLink|footnoteLink)[^>]*>.*?</a>", RegexOption.DOT_MATCHES_ALL)
        val LINE_BREAK = Regex("<span class=\"(?:newblock|parabreak)\"></span>")
        val ANY_TAG = Regex("<[^>]+>")
        val WHITESPACE = Regex("\\s+")
    }
}
