package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.CoReceivedRow

/** One publisher's line of a consolidated sheet. [reports] / [hours] / [studies] are keyed by status column ("UP", "Pub", "AP", "RP", ...). */
data class SheetRow(
    val number: Int,
    val status: String,
    val name: String,
    val reports: Map<String, Int>,
    val hours: Map<String, Double>,
    val studies: Map<String, Int>,
    val remarks: String,
)

/**
 * A congregation's Field Service Report sheet for one chosen period, consolidated as the whole congregation (every FS Group together),
 * laid out like the Field Service Report: Status, Publisher's Name, then No. of Reports / Hours / Bible Studies under each status
 * column, Remarks, and a Total row. It is built only from the ACCEPTED monthly snapshots of the period.
 */
data class ConsolidatedSheet(
    val congregationName: String,
    /** "October 2026" or "January 2025 - June 2025". */
    val periodLabel: String,
    val isRange: Boolean,
    val monthsExpected: Int,
    val monthsAccepted: Int,
    /** Status columns shown under No. of Reports and Bible Studies: UP, Pub, AP, RP always, then any other status present. */
    val reportColumns: List<String>,
    /** The pioneer statuses among [reportColumns]: the columns under Hours. */
    val hourColumns: List<String>,
    val rows: List<SheetRow>,
    val totalReports: Map<String, Int>,
    val totalHours: Map<String, Double>,
    val totalStudies: Map<String, Int>,
) {
    /** "Report for the Month of October 2026" / "Report for the Period of January 2025 - June 2025". */
    val reportTitle: String get() = if (isRange) "Report for the Period of $periodLabel" else "Report for the Month of $periodLabel"
}

object ConsolidatedSheets {
    private val ORDER = listOf("UP", "Pub", "AP", "RP", "SP", "Irr", "Inactive", "Reproof")
    private val ALWAYS = listOf("UP", "Pub", "AP", "RP")
    private val PIONEER = setOf("AP", "RP", "SP")

    private fun keyOf(row: CoReceivedRow) = if (row.personId.isNotBlank()) "id:" + row.personId else "name:" + row.name.trim().lowercase()

    /**
     * Consolidates [period]'s accepted months into one sheet, following the Field Service Report's own rules for a period: one line per
     * person; the status is the one in the person's LAST accepted month of the period; "No. of Reports" is 1 under that status; Bible
     * Studies are summed over the accepted months; hours are summed and shown only for a pioneer status; Remarks are the distinct entered texts.
     * A month without an accepted snapshot contributes nothing (it is never treated as zero).
     */
    fun of(congregationName: String, period: AcceptedComparative.PeriodResult, monthLabel: (Long) -> String): ConsolidatedSheet {
        val accepted = period.rows.filter { it.accepted && it.input.report != null }.sortedBy { it.month }
        val byPerson = linkedMapOf<String, MutableList<CoReceivedRow>>()
        accepted.forEach { m -> m.input.report!!.rows.forEach { byPerson.getOrPut(keyOf(it)) { mutableListOf() } += it } }

        val lines = byPerson.values.map { monthsOfPerson ->
            val latest = monthsOfPerson.last()
            val status = latest.status
            val hours = monthsOfPerson.mapNotNull { it.hours }
            val studies = monthsOfPerson.mapNotNull { it.bibleStudies }
            Triple(
                latest.name, status,
                Triple(
                    if (status in PIONEER && hours.isNotEmpty()) hours.sum() else null,
                    if (studies.isNotEmpty()) studies.sum() else null,
                    monthsOfPerson.map { it.remarks.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("; "),
                ),
            )
        }.sortedBy { it.first.lowercase() }

        val present = lines.map { it.second }.toSet()
        val columns = ORDER.filter { it in ALWAYS || it in present } + present.filter { it !in ORDER }.sorted()
        val rows = lines.mapIndexed { i, (name, status, rest) ->
            val (hours, studies, remarks) = rest
            SheetRow(
                number = i + 1, status = status, name = name,
                reports = mapOf(status to 1),
                hours = if (hours != null) mapOf(status to hours) else emptyMap(),
                studies = if (studies != null) mapOf(status to studies) else emptyMap(),
                remarks = remarks,
            )
        }
        return ConsolidatedSheet(
            congregationName = congregationName,
            periodLabel = period.label,
            isRange = period.expected > 1,
            monthsExpected = period.expected,
            monthsAccepted = period.available,
            reportColumns = columns,
            hourColumns = columns.filter { it in PIONEER },
            rows = rows,
            totalReports = columns.associateWith { c -> rows.sumOf { it.reports[c] ?: 0 } },
            totalHours = columns.filter { it in PIONEER }.associateWith { c -> rows.sumOf { it.hours[c] ?: 0.0 } },
            totalStudies = columns.associateWith { c -> rows.sumOf { it.studies[c] ?: 0 } },
        )
    }

    /** The sheet's cells as text, for tables and exports: heading rows first, then one row per publisher, then Total. */
    fun toTable(sheet: ConsolidatedSheet): List<List<String>> {
        fun hours(v: Double) = AcceptedComparative.oneDecimal(v).removeSuffix(".0")
        val head = listOf("", "Status", "Publisher's Name") + sheet.reportColumns + sheet.hourColumns + sheet.reportColumns + "Remarks"
        val body = sheet.rows.map { r ->
            listOf(r.number.toString(), r.status, r.name) +
                sheet.reportColumns.map { c -> r.reports[c]?.toString().orEmpty() } +
                sheet.hourColumns.map { c -> r.hours[c]?.let(::hours).orEmpty() } +
                sheet.reportColumns.map { c -> r.studies[c]?.toString().orEmpty() } + r.remarks
        }
        val total = listOf("", "", "Total:") +
            sheet.reportColumns.map { c -> sheet.totalReports[c]?.takeIf { it != 0 }?.toString().orEmpty() } +
            sheet.hourColumns.map { c -> sheet.totalHours[c]?.takeIf { it != 0.0 }?.let(::hours).orEmpty() } +
            sheet.reportColumns.map { c -> sheet.totalStudies[c]?.takeIf { it != 0 }?.toString().orEmpty() } + ""
        return listOf(head) + body + listOf(total)
    }
}
