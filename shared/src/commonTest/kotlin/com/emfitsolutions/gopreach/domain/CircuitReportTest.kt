package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.ui.screens.circuit.CIRCUIT_REPORT_COLUMNS
import com.emfitsolutions.gopreach.ui.screens.circuit.CongregationPeopleSummary
import com.emfitsolutions.gopreach.ui.screens.circuit.QuickAccessCounts
import com.emfitsolutions.gopreach.ui.screens.circuit.circuitReportRows
import com.emfitsolutions.gopreach.ui.screens.circuit.circuitReportTotal
import kotlin.test.Test
import kotlin.test.assertEquals

class CircuitReportTest {
    private fun summary(id: String, code: String, name: String, city: String, publishers: Int, aux: Int, regular: Int, unbaptized: Int, elders: Int, servants: Int, coordinator: String) =
        CongregationPeopleSummary(
            congregation = Congregation(id = id, name = name, code = code, cityMunicipality = city),
            publishers = publishers,
            counts = QuickAccessCounts(mapOf(PublisherCategory.AUXILIARY_PIONEER to aux, PublisherCategory.UNBAPTIZED_PUBLISHER to unbaptized)),
            elders = elders, servants = servants, regularPublishers = regular, coordinator = coordinator,
        )

    private val summaries = listOf(
        summary("b", "02", "Bayombong", "Bayombong", 102, 10, 82, 5, 8, 6, "Pedro Santos"),
        summary("a", "01", "Tagalog Solano", "Solano", 85, 8, 70, 4, 7, 5, "Juan Dela Cruz"),
    )

    @Test
    fun `rows come from the live summaries, ordered by congregation number`() {
        val rows = circuitReportRows(summaries)
        assertEquals(listOf("01", "02"), rows.map { it.code })
        assertEquals("Solano", rows[0].city)
        assertEquals("Juan Dela Cruz", rows[0].coordinator)
        assertEquals(85, rows[0].totalPublishers)
        assertEquals(8, rows[0].auxiliaryPioneers)
        assertEquals(70, rows[0].regularPublishers)
        assertEquals(4, rows[0].unbaptized)
    }

    @Test
    fun `the TOTAL row sums every count column across the circuit`() {
        val total = circuitReportTotal(circuitReportRows(summaries))
        assertEquals("TOTAL", total.name)
        assertEquals(187, total.totalPublishers)
        assertEquals(18, total.auxiliaryPioneers)
        assertEquals(152, total.regularPublishers)
        assertEquals(9, total.unbaptized)
        assertEquals(15, total.elders)
        assertEquals(11, total.servants)
    }

    @Test
    fun `an empty circuit totals zero and the table has the ten required columns`() {
        assertEquals(0, circuitReportTotal(emptyList()).totalPublishers)
        assertEquals(
            listOf("Cong #", "Cong Name", "City", "Coordinator Name", "Total Publisher", "Auxiliary Pioneer", "Regular Publishers", "Unbaptized Publisher", "Elders", "Ministerial Servants"),
            CIRCUIT_REPORT_COLUMNS.map { it.first },
        )
    }
}
