package com.emfitsolutions.gopreach.domain

/**
 * The publisher checklist of Meeting and Cart Assignment. The assignment keeps its people as ONE comma-separated text ("Eva, Lita"), exactly as before; this reads that text into the selected
 * names, and turns the selection back into it. The congregation's roster (what the server lets this person see) is what the list offers; a name that was typed by hand earlier and is not on
 * the roster stays selected until it is unticked. The website's roster picker follows the same rules.
 */
object RosterSelection {
    fun parse(value: String): List<String> = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.fold(mutableListOf<String>()) { out, n ->
        if (out.none { it.equals(n, ignoreCase = true) }) out.add(n)
        out
    }

    fun format(names: List<String>): String = names.joinToString(", ")

    /** The roster names that match what was typed in the search box (the same words in any order). */
    fun visible(roster: List<String>, query: String): List<String> = roster.filter { NameSearch.matches(query, it) }

    fun isSelected(selected: List<String>, name: String): Boolean = selected.any { it.equals(name, ignoreCase = true) }

    fun toggle(selected: List<String>, name: String): List<String> =
        if (isSelected(selected, name)) selected.filterNot { it.equals(name, ignoreCase = true) } else selected + name

    /** Select All: every roster name that is shown (after the search) is added; nothing outside the roster is ever added by it. */
    fun selectAll(selected: List<String>, shown: List<String>): List<String> = shown.fold(selected) { acc, n -> if (isSelected(acc, n)) acc else acc + n }

    /** Clear Selection. */
    fun clear(): List<String> = emptyList()

    /** The selected names that are not on the roster (typed by hand): they are listed first so they can be unticked. */
    fun outsideRoster(selected: List<String>, roster: List<String>): List<String> = selected.filter { s -> roster.none { it.equals(s, ignoreCase = true) } }
}
