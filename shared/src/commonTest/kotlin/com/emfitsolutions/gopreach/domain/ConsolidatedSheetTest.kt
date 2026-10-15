package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.CoReceivedReport
import com.emfitsolutions.gopreach.data.model.CoReceivedRow
import com.emfitsolutions.gopreach.domain.AcceptedComparative.MonthInput
import com.emfitsolutions.gopreach.domain.AcceptedComparative.MonthState
import com.emfitsolutions.gopreach.domain.AcceptedComparative.Period
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsolidatedSheetTest {
    private fun row(id: String, name: String, status: String, hours: Double? = null, studies: Int? = 0, remarks: String = "") =
        CoReceivedRow(number = 1, status = status, name = name, reportsCount = 1, hours = hours, bibleStudies = studies, remarks = remarks, personId = id)

    private fun accepted(month: Long, vararg rows: CoReceivedRow) =
        MonthInput(month, MonthState.ACCEPTED, CoReceivedReport(id = "c_${month}_1", congregationId = "c", periodMonth = month, version = 1, rows = rows.toList()))

    private fun sheet(period: Period, vararg known: MonthInput): ConsolidatedSheet {
        val result = AcceptedComparative.build("Cong", listOf(period, Period(900, 901)), known.toList(), { it + 1 }, { "M$it" })
        return ConsolidatedSheets.of("Cong", result.periods.first(), { "M$it" })
    }

    @Test
    fun aSingleMonthLooksLikeTheFieldServiceReport() {
        val s = sheet(
            Period(1, 1),
            accepted(
                1,
                row("a", "Canales, Aurora", "RP", hours = 62.0, studies = 6), row("b", "Fernandez, Ephraim", "AP", hours = 24.5, studies = 1, remarks = "Continues AP til December"),
                row("c", "Butac, Japhet", "Pub", studies = 1), row("d", "Butac, Chloe", "Pub", studies = 0), row("e", "David, Nathalia", "UP", studies = 0),
            ),
        )
        assertEquals("Report for the Month of M1", s.reportTitle)
        assertEquals(listOf("UP", "Pub", "AP", "RP"), s.reportColumns)
        assertEquals(listOf("AP", "RP"), s.hourColumns)
        assertEquals(mapOf("UP" to 1, "Pub" to 2, "AP" to 1, "RP" to 1), s.totalReports)
        assertEquals(24.5, s.totalHours["AP"])
        assertEquals(62.0, s.totalHours["RP"])
        assertEquals(mapOf("UP" to 0, "Pub" to 1, "AP" to 1, "RP" to 6), s.totalStudies)
        assertEquals(listOf("Butac, Chloe", "Butac, Japhet", "Canales, Aurora", "David, Nathalia", "Fernandez, Ephraim"), s.rows.map { it.name })
        assertEquals("Continues AP til December", s.rows.last().remarks)
        assertEquals("0", s.rows.first().studies["Pub"].toString(), "a Bible Study count of 0 is shown as 0, not blank")
    }

    @Test
    fun aRangeIsConsolidatedPerPersonUsingTheLastStatusAndSummedFigures() {
        val s = sheet(
            Period(1, 3),
            accepted(1, row("a", "Ana", "Pub", studies = 1), row("b", "Ben", "AP", hours = 30.0, studies = 2)),
            accepted(2, row("a", "Ana", "AP", hours = 30.0, studies = 2), row("b", "Ben", "AP", hours = 35.0, studies = 1)),
            accepted(3, row("a", "Ana", "RP", hours = 70.0, studies = 4)),
        )
        assertEquals("Report for the Period of M1 - M3", s.reportTitle)
        assertEquals(2, s.rows.size, "one line per person, however many months")
        val ana = s.rows.first { it.name == "Ana" }
        assertEquals("RP", ana.status, "the status of the last accepted month")
        assertEquals(100.0, ana.hours["RP"], "hours of the months summed")
        assertEquals(7, ana.studies["RP"], "Bible Studies of the months summed")
        val ben = s.rows.first { it.name == "Ben" }
        assertEquals("AP", ben.status)
        assertEquals(65.0, ben.hours["AP"])
        assertEquals(3, s.monthsAccepted)
        assertEquals(3, s.monthsExpected)
    }

    @Test
    fun hoursAreOnlyShownForAPioneerStatus() {
        val s = sheet(
            Period(1, 2),
            accepted(1, row("a", "Ana", "AP", hours = 30.0, studies = 1)),
            accepted(2, row("a", "Ana", "Pub", studies = 1)),
        )
        val ana = s.rows.single()
        assertEquals("Pub", ana.status)
        assertTrue(ana.hours.isEmpty())
    }

    @Test
    fun otherStatusesGetTheirOwnColumnsOnlyWhenPresent() {
        val s = sheet(Period(1, 1), accepted(1, row("a", "Ana", "SP", hours = 90.0), row("b", "Ben", "Inactive")))
        assertEquals(listOf("UP", "Pub", "AP", "RP", "SP", "Inactive"), s.reportColumns)
        assertEquals(listOf("AP", "RP", "SP"), s.hourColumns)
    }

    @Test
    fun aPeriodWithNoAcceptedMonthHasNoRowsAndNoTotals() {
        val s = sheet(Period(1, 2))
        assertTrue(s.rows.isEmpty())
        assertEquals(0, s.monthsAccepted)
        assertEquals(0, s.totalReports.values.sum())
    }

    @Test
    fun theTableHasHeadingRowThenPublishersThenTotal() {
        val s = sheet(Period(1, 1), accepted(1, row("a", "Ana", "RP", hours = 50.0, studies = 2), row("b", "Ben", "Pub", studies = 1)))
        val table = ConsolidatedSheets.toTable(s)
        assertEquals(2 + 2, table.size)
        assertEquals("Total:", table.last()[2])
        assertEquals(table.first().size, table.last().size)
        assertTrue(table.last().contains("50"))
    }
}
