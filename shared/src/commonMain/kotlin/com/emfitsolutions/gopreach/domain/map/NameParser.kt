package com.emfitsolutions.gopreach.domain.map

/**
 * Finds the NAME of the person or household written in text read from a picture (the same on-device text reader that finds the
 * coordinates). A labelled line ("Name: Juan Dela Cruz", "Householder - ...") wins; otherwise the first line that looks like a
 * person's name (two to four plain words, no digits and none of the words an address, map or coordinate stamp uses). Returns null
 * when nothing looks like a name: it never invents one, and the person can always edit what was found.
 */
object NameParser {
    private val label = Regex("""^\s*(?:full\s*name|name|pangalan|householder|house\s*holder|hh|owner|resident|person|contact\s*person)\s*[:\-–]\s*(.+)$""", RegexOption.IGNORE_CASE)

    private val notNameWords = setOf(
        "street", "st", "road", "rd", "avenue", "ave", "brgy", "barangay", "bgy", "purok", "sitio", "zone", "city", "municipality", "province",
        "lat", "lng", "long", "latitude", "longitude", "gps", "map", "maps", "google", "date", "time", "coordinates", "coordinate", "location",
        "accuracy", "altitude", "elevation", "speed", "note", "notes", "phone", "mobile", "tel", "email", "philippines", "philippine", "camera",
        "photo", "image", "north", "south", "east", "west", "the", "of", "and", "at", "on", "in", "address", "highway", "village", "subdivision",
        "subd", "district", "region", "zip", "postal", "code", "no", "number", "house", "block", "lot", "phase", "building", "bldg", "app",
    )

    private val nameWord = Regex("""^[\p{L}][\p{L}.'’\-]*$""")

    fun find(text: String): String? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            val value = label.find(line)?.groupValues?.get(1)?.let(::clean) ?: continue
            if (value.isNotEmpty() && value.any { it.isLetter() } && value.none { it.isDigit() }) return value
        }
        return lines.asSequence().map(::clean).firstOrNull(::looksLikeName)
    }

    private fun clean(s: String): String = s.replace(Regex("""\s+"""), " ").trim(' ', ',', ';', ':', '.', '-', '|')

    private fun looksLikeName(line: String): Boolean {
        if (line.any { it.isDigit() }) return false
        val words = line.split(' ').filter { it.isNotEmpty() }
        if (words.size !in 2..4) return false
        if (!words.all { nameWord.matches(it) && it.count { c -> c.isLetter() } >= 2 }) return false
        return words.none { it.trim('.', ',').lowercase() in notNameWords }
    }
}
