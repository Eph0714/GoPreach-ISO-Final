package com.emfitsolutions.gopreach.data.export

/**
 * Column widths and page setup for the hand-written .xlsx exports, worked out from the cells themselves: every column is as wide as its longest text (a title in a
 * row of its own spills over the empty cells beside it, so it does not widen its column), and the page is the paper that fits the table: A4 upright, A4 across, or A3
 * across, always scaled down to the page width so no column is cut off.
 */
object ExcelLayout {

    /** What to put into a worksheet: [sheetPr] first, [cols] before the sheet data, [pageSetup] (margins included) after it. */
    data class Parts(val sheetPr: String, val cols: String, val pageSetup: String)

    private const val PORTRAIT_CHARS = 88.0
    private const val LANDSCAPE_CHARS = 130.0

    private val cellPattern = Regex("""<c r="([A-Z]+)(\d+)"[^>]*?(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
    private val textPattern = Regex("""<t[^>]*>(.*?)</t>|<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)

    private fun columnIndex(letters: String): Int = letters.fold(0) { n, ch -> n * 26 + (ch - 'A' + 1) } - 1

    private fun decode(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    /** Width of a column that holds text of [length] characters, in Excel's character units. */
    fun width(length: Int): Double = (length * 1.15 + 2).coerceIn(6.0, 60.0)

    /** Widths of the columns A.. found in the cells of [sheetDataXml] (the rows between `<sheetData>` and `</sheetData>`), at least [minColumns] columns. */
    fun widths(sheetDataXml: String, minColumns: Int = 0): List<Double> {
        val perRow = HashMap<Int, MutableList<Pair<Int, Int>>>()
        cellPattern.findAll(sheetDataXml).forEach { m ->
            val body = m.groupValues[3]
            val raw = textPattern.find(body)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }.orEmpty()
            if (raw.isNotEmpty()) perRow.getOrPut(m.groupValues[2].toInt()) { mutableListOf() }.add(columnIndex(m.groupValues[1]) to decode(raw).length)
        }
        val widest = HashMap<Int, Int>()
        perRow.values.forEach { cells ->
            if (cells.size == 1) return@forEach // a lone cell is a title or a note: it spills over its neighbours
            cells.forEach { (c, len) -> widest[c] = maxOf(widest[c] ?: 0, len) }
        }
        val count = maxOf(minColumns, (widest.keys.maxOrNull() ?: -1) + 1)
        return List(count) { c -> widest[c]?.let { width(it) } ?: 8.0 }
    }

    /** Everything a worksheet needs, from the finished rows XML. */
    fun parts(sheetDataXml: String, minColumns: Int = 0): Parts {
        val w = widths(sheetDataXml, minColumns)
        val cols = "<cols>" + w.mapIndexed { i, x -> "<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"${"%.1f".format(java.util.Locale.US, x)}\" customWidth=\"1\"/>" }.joinToString("") + "</cols>"
        val total = w.sum()
        val (paper, orientation) = when {
            total <= PORTRAIT_CHARS -> 9 to "portrait"
            total <= LANDSCAPE_CHARS -> 9 to "landscape"
            else -> 8 to "landscape"
        }
        return Parts(
            sheetPr = "<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>",
            cols = cols,
            pageSetup = "<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>" +
                "<pageSetup paperSize=\"$paper\" orientation=\"$orientation\" fitToWidth=\"1\" fitToHeight=\"0\"/>",
        )
    }
}
