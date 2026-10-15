package com.emfitsolutions.gopreach.domain

/**
 * Cleans verse text that another app (JW Library's Share) sent to GoPreach: the lines that are only a web link are dropped, line breaks
 * are tidied, and what is left is the text the publisher will see and confirm. Returns null when nothing but links or blanks remain, so
 * an empty or unrelated share is never treated as a verse.
 */
object SharedVerseText {
    fun clean(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val kept = raw.replace("\r", "").split('\n')
            .map { it.trim() }
            .filterNot { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        val tidy = buildList<String> {
            kept.forEach { line -> if (line.isNotEmpty() || (isNotEmpty() && last().isNotEmpty())) add(line) }
        }.joinToString("\n").trim()
        return tidy.takeIf { it.isNotEmpty() }
    }
}
