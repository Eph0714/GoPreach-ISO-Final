package com.emfitsolutions.gopreach.ui.screens.fieldservicereport

import org.junit.Assert.assertEquals
import org.junit.Test

/** The title of a Field Service Report, which is also the name of its Excel, Word and PDF files: the group (or All FS Groups) and the selected month. */
class FieldServiceReportTitleTest {
    private fun sheet(group: String, month: String) = FieldServiceReportSheet(
        groupName = group, monthLabel = month, isRange = false, overseer = "", servant = "", assistant = "",
        reportColumns = listOf(StatusColumn.PUB), hourColumns = emptyList(), rows = emptyList(),
    )

    @Test fun oneGroupNamesTheGroupAndTheMonth() {
        assertEquals("FS Group 5 Field Service Report for October 2026", FieldServiceReportExporter.reportTitle(listOf(sheet("FS Group 5", "October 2026"))))
    }

    @Test fun severalGroupsSayAllFsGroups() {
        assertEquals("All FS Groups Field Service Report for October 2026", FieldServiceReportExporter.reportTitle(listOf(sheet("FS Group 1", "October 2026"), sheet("FS Group 2", "October 2026"))))
    }

    @Test fun charactersAFileNameCannotHaveAreReplaced() {
        assertEquals("Group A B Field Service Report for Oct 2026", FieldServiceReportExporter.reportTitle(listOf(sheet("Group A/B", "Oct 2026"))))
    }
}
