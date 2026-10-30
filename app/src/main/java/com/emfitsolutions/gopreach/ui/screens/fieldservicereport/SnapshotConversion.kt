package com.emfitsolutions.gopreach.ui.screens.fieldservicereport

import com.emfitsolutions.gopreach.data.model.FsGroupReportSnapshot
import com.emfitsolutions.gopreach.data.model.SnapshotLine
import com.emfitsolutions.gopreach.data.model.SnapshotReport
import com.emfitsolutions.gopreach.data.model.SnapshotSheet
import com.emfitsolutions.gopreach.data.model.SnapshotTotals

/**
 * A Monthly Report Snapshot is the group's Field Service Report sheet frozen as data. These two functions are the only bridge between the two forms, so the screen, the print and the
 * exports of a snapshot are the very same ones the live report uses, fed with the SAVED values - never with the current report.
 */
fun FieldServiceReportSheet.toSnapshotReport(): SnapshotReport {
    val summary = FieldServiceSummary.ofSheets(listOf(this)).drop(1)   // [label, value] pairs: the heading row (Metric | Value) is the screen's, not data
    return SnapshotReport(
        sheet = SnapshotSheet(
            name = groupName,
            title = titleLine,
            officers = officerRows.filter { it.first.isNotBlank() }.map { listOf(it.first, it.second) },
            columns = reportColumns.map { it.name },
            hourColumns = hourColumns.map { it.name },
            lines = rows.map { SnapshotLine(it.number, it.status.name, it.personId, it.name, it.reportsCount, it.hasReport, it.hours, it.bibleStudies, it.remarks) },
            participated = participatedCount,
        ),
        summary = summary,
        totals = SnapshotTotals(
            publishers = publisherCount,
            participated = participatedCount,
            pending = publisherCount - participatedCount,
            reports = reportColumns.sumOf { reportCount(it) },
            hours = hourColumns.sumOf { totalHours(it) },
            studies = reportColumns.sumOf { totalBibleStudies(it) },
        ),
    )
}

/** The saved snapshot as the sheet the report screens and exporters draw. Reads only the stored values. */
fun FsGroupReportSnapshot.toSheet(): FieldServiceReportSheet {
    fun column(key: String) = StatusColumn.entries.firstOrNull { it.name == key }
    val s = report.sheet
    val officers = s.officers.filter { it.size >= 2 }.map { it[0] to it[1] }
    fun officer(label: String) = officers.firstOrNull { it.first.startsWith(label) }?.second ?: "None"
    return FieldServiceReportSheet(
        groupName = s.name.ifBlank { groupName },
        monthLabel = s.title.removePrefix("Report for the Month of ").removePrefix("Report for the Period of "),
        isRange = false,
        overseer = officer("Group Overseer"),
        servant = officer("Group Servant"),
        assistant = officer("Group Assistant"),
        reportColumns = s.columns.mapNotNull(::column),
        hourColumns = s.hourColumns.mapNotNull(::column),
        rows = s.lines.mapNotNull { l ->
            val status = column(l.status) ?: return@mapNotNull null
            FieldServiceReportRow(l.number, status, l.name, l.reports, l.hours, l.studies, l.remarks, l.personId, l.hasReport)
        },
        officerRows = officers,
    )
}

/** The Summary card / print block of a snapshot: the saved pairs under the usual Metric | Value heading. */
fun FsGroupReportSnapshot.summaryRows(): List<List<String>> = listOf(listOf("Metric", "Value")) + report.summary.filter { it.size >= 2 }
