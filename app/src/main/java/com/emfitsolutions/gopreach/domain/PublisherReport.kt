package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.PublisherCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one finished report for a Publisher and month — what Send Report (Open My
 * Report), Preview Report and Send as Text all show and send. It is only ever built
 * by [PublisherReportCalculator], so none of those screens can disagree.
 *
 * A Pioneer reports [hours] (already converted to 30-minute steps — never a separate
 * minutes figure) and [bibleStudies]. Every other category reports
 * [attendedInPreaching] and [bibleStudies]; no hours or minutes at all.
 */
data class PublisherReport(
    val publisherName: String,
    val classification: String,
    val periodMonth: Long,
    val isPioneer: Boolean,
    val hours: Double?,
    val bibleStudies: Int,
    val attendedInPreaching: Boolean?,
    val remarks: String = "",
) {
    /** The Send as Text message and the Preview's text block — the same string. */
    fun toText(): String = buildString {
        appendLine("Name: $publisherName ($classification)")
        appendLine("Month: ${monthFormat().format(Date(periodMonth))}")
        if (isPioneer) {
            appendLine("Hours: ${PublisherReportCalculator.formatHours(hours ?: 0.0)}")
        } else {
            appendLine("Attended in Preaching: ${if (attendedInPreaching == true) "YES" else "NO"}")
        }
        append("Bible Study: $bibleStudies")
        if (remarks.isNotBlank()) append("\n\nRemarks:\n${remarks.trim()}")
    }

    private fun monthFormat() = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
}

object PublisherReportCalculator {

    /**
     * Total minutes → hours in half-hour steps: up to :15 rounds down to the whole hour,
     * :16–:44 is the half hour, :45 and over is the next whole hour.
     * 15:00 → 15, 15:15 → 15, 15:28 → 15.5, 15:30 → 15.5, 15:45 → 16, 15:58 → 16,
     * 16:10 → 16, 16:31 → 16.5.
     */
    fun convertToHours(totalMinutes: Int): Double {
        val whole = totalMinutes.coerceAtLeast(0) / 60
        val minutes = totalMinutes.coerceAtLeast(0) % 60
        return when {
            minutes <= 15 -> whole.toDouble()
            minutes < 45 -> whole + 0.5
            else -> (whole + 1).toDouble()
        }
    }

    /** "15" for a whole number, "15.5" otherwise. */
    fun formatHours(hours: Double): String =
        if (hours % 1.0 == 0.0) hours.toInt().toString() else hours.toString()

    /** The category as shown in a report, e.g. "Auxiliary Pioneer". */
    fun classificationLabel(category: PublisherCategory?): String =
        if (category == PublisherCategory.REGULAR_PUBLISHER) "Publisher" else category?.name?.split('_')?.joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) } ?: "Publisher"

    fun build(
        firstName: String,
        lastName: String,
        category: PublisherCategory?,
        periodMonth: Long,
        totalMinutes: Int,
        bibleStudies: Int,
        remarks: String = "",
    ): PublisherReport {
        val isPioneer = MonthlyReportCalculator.isPioneerCategory(category)
        return PublisherReport(
            publisherName = (firstName.trim() + " " + lastName.trim()).trim().uppercase(),
            classification = classificationLabel(category),
            periodMonth = periodMonth,
            isPioneer = isPioneer,
            hours = if (isPioneer) convertToHours(totalMinutes) else null,
            bibleStudies = bibleStudies,
            // A non-Pioneer attended when their Monthly Report has any hours at all.
            attendedInPreaching = if (isPioneer) null else totalMinutes > 0,
            remarks = remarks,
        )
    }
}
