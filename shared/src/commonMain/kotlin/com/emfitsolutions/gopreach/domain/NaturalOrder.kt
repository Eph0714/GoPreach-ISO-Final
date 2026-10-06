package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.Group

/**
 * Alphabetical order that reads the way people expect: case-insensitive, and numbers inside a name compare by
 * value — "FS GROUP 2" comes before "FS GROUP 10" (plain alphabetical would put 10 first).
 */
object NaturalOrder {
    val comparator: Comparator<String> = Comparator { a, b -> compare(a, b) }

    fun <T> by(selector: (T) -> String): Comparator<T> = Comparator { x, y -> compare(selector(x), selector(y)) }

    fun compare(a: String, b: String): Int {
        val ca = chunks(a.trim())
        val cb = chunks(b.trim())
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val bothNumbers = x[0].isDigit() && y[0].isDigit()
            val result = if (bothNumbers) compareNumbers(x, y) else x.lowercase().compareTo(y.lowercase())
            if (result != 0) return result
        }
        if (ca.size != cb.size) return ca.size.compareTo(cb.size)
        // Same letters and numbers: fall back to exact text so the order is stable and total.
        return a.compareTo(b)
    }

    /** Compares two digit strings by numeric value without a size limit (leading zeros ignored). */
    private fun compareNumbers(x: String, y: String): Int {
        val a = x.trimStart('0')
        val b = y.trimStart('0')
        return if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
    }

    /** "FS GROUP 10" -> ["FS GROUP ", "10"]: runs of digits and runs of everything else. */
    private fun chunks(s: String): List<String> {
        if (s.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        var start = 0
        for (i in 1..s.length) {
            if (i == s.length || s[i].isDigit() != s[start].isDigit()) {
                out += s.substring(start, i)
                start = i
            }
        }
        return out
    }
}

/** Field Service Groups, alphabetically (numbers by value). Used everywhere groups are listed. */
val GroupNameOrder: Comparator<Group> = NaturalOrder.by { it.name }
