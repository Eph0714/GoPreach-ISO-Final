package com.emfitsolutions.gopreach.ui.screens.circuit

/** One congregation's line on the Circuit Report. */
data class CircuitReportRow(
    val congregationId: String,
    val code: String,
    val name: String,
    val city: String,
    val coordinator: String,
    val totalPublishers: Int,
    val auxiliaryPioneers: Int,
    val regularPublishers: Int,
    val unbaptized: Int,
    val elders: Int,
    val servants: Int,
)

/** The Circuit Report's header: which circuit, whose, and how many congregations it covers. */
data class CircuitReportHeader(val circuit: String, val overseer: String, val totalCongregations: Int)

/** The Circuit Report's rows come straight from the live people summaries — there is no separate report dataset. */
fun circuitReportRows(summaries: List<CongregationPeopleSummary>): List<CircuitReportRow> =
    summaries.map { s ->
        CircuitReportRow(
            congregationId = s.congregation.id,
            code = s.congregation.code,
            name = s.congregation.name,
            city = s.congregation.cityMunicipality.orEmpty(),
            coordinator = s.coordinator,
            totalPublishers = s.publishers,
            auxiliaryPioneers = s.counts[com.emfitsolutions.gopreach.data.model.PublisherCategory.AUXILIARY_PIONEER],
            regularPublishers = s.regularPublishers,
            unbaptized = s.counts[com.emfitsolutions.gopreach.data.model.PublisherCategory.UNBAPTIZED_PUBLISHER],
            elders = s.elders,
            servants = s.servants,
        )
    }.sortedWith(compareBy({ it.code.toIntOrNull() ?: Int.MAX_VALUE }, { it.code }, { it.name }))

/** The TOTAL row: each count column summed across the circuit's congregations (the columns are disjoint, so nothing is counted twice). */
fun circuitReportTotal(rows: List<CircuitReportRow>): CircuitReportRow = CircuitReportRow(
    congregationId = "", code = "", name = "TOTAL", city = "", coordinator = "",
    totalPublishers = rows.sumOf { it.totalPublishers },
    auxiliaryPioneers = rows.sumOf { it.auxiliaryPioneers },
    regularPublishers = rows.sumOf { it.regularPublishers },
    unbaptized = rows.sumOf { it.unbaptized },
    elders = rows.sumOf { it.elders },
    servants = rows.sumOf { it.servants },
)

val CIRCUIT_REPORT_COLUMNS: List<Pair<String, (CircuitReportRow) -> String>> = listOf(
    "Cong #" to { r -> r.code },
    "Cong Name" to { r -> r.name },
    "City" to { r -> r.city },
    "Coordinator Name" to { r -> r.coordinator },
    "Total Publisher" to { r -> r.totalPublishers.toString() },
    "Auxiliary Pioneer" to { r -> r.auxiliaryPioneers.toString() },
    "Regular Publishers" to { r -> r.regularPublishers.toString() },
    "Unbaptized Publisher" to { r -> r.unbaptized.toString() },
    "Elders" to { r -> r.elders.toString() },
    "Ministerial Servants" to { r -> r.servants.toString() },
)
