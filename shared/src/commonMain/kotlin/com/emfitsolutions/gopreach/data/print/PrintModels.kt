package com.emfitsolutions.gopreach.data.print

/** A table-shaped report (title, columns, rows, optional totals and signature lines) that can be printed, shared or exported. */
data class ReportTable(
    val title: String,
    val columns: List<String>,
    val rows: List<List<String>>,
    val totals: List<Pair<String, String>> = emptyList(),
    val subtitle: String? = null,
    val signatureLabels: List<String> = emptyList(),
    val count: Int? = null,
    val countLabel: String = "Total Records",
) {
    val countText: String? get() = count?.let { "$countLabel: $it" }

    fun shareText(): String = listOfNotNull(title, subtitle?.takeIf { it.isNotBlank() }, countText).joinToString("\n")
}

/** Paper sizes GoPreach users print on. The system print dialog (which doubles as the print preview) can still change it per print. */
enum class PaperSize(val label: String) {
    A4("A4"),
    LETTER("Letter (8.5 x 11 in)"),
    LEGAL("Legal (8.5 x 14 in)");
}

enum class OrientationMode(val label: String) {
    AUTO("Automatic (best fit)"), PORTRAIT("Portrait"), LANDSCAPE("Landscape")
}

/** A report's own hints to the layout; everything else comes from the user's print settings. */
data class PrintOptions(
    /** Force an orientation for this report (e.g. a wide sheet); null follows the user's setting / the content. */
    val orientation: OrientationMode? = null,
)

fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
