package com.emfitsolutions.gopreach.ui.screens.fieldservicereport

import com.emfitsolutions.gopreach.ui.components.GraphMetric
import com.emfitsolutions.gopreach.ui.components.countFormat
import com.emfitsolutions.gopreach.ui.components.hoursFormat

/** One month of Field Service Report figures for the graph (see [FieldServiceReportViewModel.monthlyMetrics]). */
data class MonthMetrics(
    val monthStart: Long,
    val hours: Double,
    val creditMinutes: Int,
    val returnVisits: Int,
    val bibleStudies: Int,
    val reports: Int,
)

/**
 * The Field Service Report's graphable metrics for the two selected month ranges, as the real month-by-month values
 * from the report data. The shared [com.emfitsolutions.gopreach.ui.components.ComparativeGraphReport] draws them.
 */
fun fieldServiceGraphMetrics(a: List<MonthMetrics>, b: List<MonthMetrics>): List<GraphMetric> = listOf(
    GraphMetric("Hours / Minutes", ::hoursFormat, a.map { it.hours }, b.map { it.hours }),
    GraphMetric("Credit Hours", ::hoursFormat, a.map { it.creditMinutes / 60.0 }, b.map { it.creditMinutes / 60.0 }),
    GraphMetric("Return Visits", ::countFormat, a.map { it.returnVisits.toDouble() }, b.map { it.returnVisits.toDouble() }),
    GraphMetric("Bible Studies", ::countFormat, a.map { it.bibleStudies.toDouble() }, b.map { it.bibleStudies.toDouble() }),
    GraphMetric("Reports Submitted", ::countFormat, a.map { it.reports.toDouble() }, b.map { it.reports.toDouble() }),
)
