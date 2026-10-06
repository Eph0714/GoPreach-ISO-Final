package com.emfitsolutions.gopreach.ui.screens.planner

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import com.emfitsolutions.gopreach.domain.MonthBounds
import com.emfitsolutions.gopreach.domain.PublisherReportCalculator

/**
 * The months whose report is already submitted, with the Hours that report carries. My Planner is
 * closed for those months: nothing can be added, edited or removed, and what was submitted is shown
 * read-only as "Submitted".
 */
@Immutable
class PlannerLock(private val submittedHoursByMonth: Map<Long, Double>) {
    /** True when the month containing [anyMillis] has a submitted report. */
    fun isLocked(anyMillis: Long): Boolean = MonthBounds.of(anyMillis).startInclusive in submittedHoursByMonth

    /** "Submitted: 15.5 Hours" for the month containing [anyMillis], or null if it isn't submitted. */
    fun submittedHoursLabel(anyMillis: Long): String? =
        submittedHoursByMonth[MonthBounds.of(anyMillis).startInclusive]?.let { "Submitted: ${PublisherReportCalculator.formatHours(it)} Hours" }
}

val LocalPlannerLock = compositionLocalOf { PlannerLock(emptyMap()) }

const val PLANNER_LOCKED_MESSAGE = "This Record is Already Submitted"
