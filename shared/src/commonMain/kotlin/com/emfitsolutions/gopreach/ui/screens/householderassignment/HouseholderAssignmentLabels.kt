package com.emfitsolutions.gopreach.ui.screens.householderassignment

import com.emfitsolutions.gopreach.data.model.HouseholderAssignmentStatus
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PipelineStage

/** "House Holder Assignment" module — spec's own exact record-type labels
 * (never the pipeline's internal "Searching"). */
fun PipelineStage.assignmentLabel(): String = when (this) {
    PipelineStage.SEARCHING -> "Interested Person"
    PipelineStage.RETURN_VISIT -> "Return Visit"
    PipelineStage.BIBLE_STUDY -> "Bible Study"
}

fun HouseholderAssignmentStatus.label(): String = when (this) {
    HouseholderAssignmentStatus.PENDING -> "Pending"
    HouseholderAssignmentStatus.ACCEPTED -> "Accepted"
    HouseholderAssignmentStatus.REJECTED -> "Rejected"
    HouseholderAssignmentStatus.CANCELLED -> "Cancelled"
    HouseholderAssignmentStatus.COMPLETED -> "Completed"
}

/** Barangay/City-Municipality/Province, comma-joined, skipping blanks —

 * never silently blank, so a Service Overseer isn't left guessing whether
 * that means "available every day" or "never asked." */
// Not private — reused by PublisherSchedulesScreen (Account Settings' own
// "View Other Publishers' Schedules" link) so a Publisher can see this same
// summary for fellow publishers in their congregation, not just a Service
// Overseer/Admin picking who to assign a record to.
fun availabilitySummary(publisher: Person): String {
    val days = publisher.preachingAvailableDays
        .mapNotNull { runCatching { com.emfitsolutions.gopreach.data.model.PreachingDay.valueOf(it) }.getOrNull() }
        .sortedBy { it.ordinal }
    val daysText = if (days.isEmpty()) null else days.joinToString(", ") { it.shortLabel }
    return when {
        daysText == null && publisher.preachingAvailabilityRemarks.isNullOrBlank() -> "No availability set"
        daysText == null -> publisher.preachingAvailabilityRemarks!!
        publisher.preachingAvailabilityRemarks.isNullOrBlank() -> "Available: $daysText"
        else -> "Available: $daysText — ${publisher.preachingAvailabilityRemarks}"
    }
}
