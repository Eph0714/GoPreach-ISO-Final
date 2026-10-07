package com.emfitsolutions.gopreach.ui.screens.fieldservicereport

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The Field Service Report sheets as lazy-list items: each sheet's title block scrolls away, but its two heading rows
 * are pinned to the top while its publisher lines scroll underneath (the next sheet's headings replace them). Every row
 * shares [hScroll], so the whole table scrolls sideways together and the headings stay aligned with their columns.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.sheetItems(
    sheets: List<FieldServiceReportSheet>,
    hScroll: ScrollState,
    keyPrefix: String = "sheet",
    /** Non-null for a user allowed to edit the month's records: an Actions column with Edit / Delete on every line. */
    actions: RowActions? = null,
) {
    sheets.forEachIndexed { si, sheet ->
        val rc = sheet.reportColumns
        val hc = sheet.hourColumns
        item(key = "$keyPrefix-title-$si") {
            Column(modifier = Modifier.padding(top = if (si == 0) 0.dp else 24.dp)) {
                Text(sheet.groupName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(sheet.titleLine, style = MaterialTheme.typography.bodyMedium)
                Text(sheet.countLine, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                sheet.officerRows.forEachIndexed { index, (label, value) ->
                    if (label.isNotBlank()) {
                        Text(
                            "$label  $value",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = if (index == sheet.officerRows.lastIndex) Modifier.padding(bottom = 10.dp) else Modifier,
                        )
                    }
                }
            }
        }
        stickyHeader(key = "$keyPrefix-head-$si") {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface).horizontalScroll(hScroll)) {
                Row {
                    Cell("", W_NO); Cell("", W_STATUS); Cell("", W_NAME)
                    Cell("No. of Reports", W_NUM * rc.size, fill = FILL_REPORTS, bold = true)
                    Cell("Hours", W_NUM * hc.size, fill = FILL_HOURS, bold = true)
                    Cell("Bible Studies", W_NUM * rc.size, fill = FILL_BIBLE, bold = true)
                    Cell("", W_REMARKS)
                    if (actions != null) Cell("", W_ACTIONS)
                }
                Row {
                    Cell("", W_NO)
                    Cell("Status", W_STATUS, bold = true)
                    Cell("Publisher's Name", W_NAME, bold = true, left = true)
                    rc.forEach { Cell(it.label, W_NUM, fill = FILL_REPORTS, bold = true) }
                    hc.forEach { Cell(it.label, W_NUM, fill = FILL_HOURS, bold = true) }
                    rc.forEach { Cell(it.label, W_NUM, fill = FILL_BIBLE, bold = true) }
                    Cell("Remarks", W_REMARKS, bold = true)
                    if (actions != null) Cell("Actions", W_ACTIONS, bold = true)
                }
            }
        }
        items(count = sheet.rows.size, key = { "$keyPrefix-row-$si-$it" }) { i ->
            val line = sheet.rows[i]
            Row(modifier = Modifier.horizontalScroll(hScroll)) {
                Cell(line.number.toString(), W_NO)
                Cell(line.status.label, W_STATUS)
                Cell(line.name, W_NAME, left = true)
                rc.forEach { s -> Cell(if (line.reported && line.status == s) line.reportsCount.toString() else "", W_NUM) }
                hc.forEach { s -> Cell(if (line.status == s) line.hours?.let { formatHours(it) }.orEmpty() else "", W_NUM) }
                rc.forEach { s -> Cell(if (line.status == s) line.bibleStudies?.toString().orEmpty() else "", W_NUM) }
                Cell(line.remarks, W_REMARKS, left = true)
                if (actions != null) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.width(W_ACTIONS).height(34.dp).border(0.5.dp, androidx.compose.ui.graphics.Color(0xFF444444)),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.TextButton(onClick = { actions.onEdit(line) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { Text(if (line.bibleStudies == null) "Add" else "Edit", style = MaterialTheme.typography.labelSmall) }
                        androidx.compose.material3.TextButton(onClick = { actions.onDelete(line) }, enabled = line.bibleStudies != null, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { Text("Delete", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
        item(key = "$keyPrefix-total-$si") {
            Row(modifier = Modifier.horizontalScroll(hScroll)) {
                Cell("", W_NO, fill = FILL_TOTAL); Cell("", W_STATUS, fill = FILL_TOTAL)
                Cell("Total:", W_NAME, fill = FILL_TOTAL, bold = true, left = true)
                rc.forEach { Cell(sheet.reportCount(it).toString(), W_NUM, fill = FILL_TOTAL, bold = true) }
                hc.forEach { Cell(formatHours(sheet.totalHours(it)), W_NUM, fill = FILL_TOTAL, bold = true) }
                rc.forEach { Cell(sheet.totalBibleStudies(it).toString(), W_NUM, fill = FILL_TOTAL, bold = true) }
                Cell("", W_REMARKS, fill = FILL_TOTAL)
                if (actions != null) Cell("", W_ACTIONS, fill = FILL_TOTAL)
            }
        }
    }
}
