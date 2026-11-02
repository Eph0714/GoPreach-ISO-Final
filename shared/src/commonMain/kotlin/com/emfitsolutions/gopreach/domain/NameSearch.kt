package com.emfitsolutions.gopreach.domain

/**
 * The one way every GoPreach list matches what a person typed against a name (the website's gpHit() and its Search class follow the same rule): capitals, commas and extra spaces do not
 * matter and EVERY word typed must be found somewhere in the text, in any order. So "efren martin", "Martin, Efren", "mart" and " EFREN " all find Efren Martin; nothing typed finds everything.
 */
object NameSearch {
    private val spaces = Regex("\\s+")

    fun normalize(text: String): String = text.lowercase().replace(',', ' ').replace(spaces, " ").trim()

    fun matches(query: String, vararg texts: String?): Boolean {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = normalize(texts.filterNotNull().joinToString(" "))
        return words.all { hay.contains(it) }
    }
}
