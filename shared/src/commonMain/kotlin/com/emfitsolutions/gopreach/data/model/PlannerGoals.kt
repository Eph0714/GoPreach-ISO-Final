package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId
import com.emfitsolutions.gopreach.platform.padded

/**
 * My Planner → Month spec §24-§25 — one goal per (publisher, year, month).
 * [id] is deterministic (`personId_yyyyMM`), same "no query needed, just
 * compute the id and look it up" shape [PlannerDay.idFor] already uses.
 * "Do not overwrite historical monthly goals" (spec §25) is true by
 * construction here: a different month is always a different document, so
 * setting October's goal can never touch September's.
 *
 * Firestore collection: `monthlyPlannerGoals/{publisherPersonId_yyyyMM}`
 */
@kotlinx.serialization.Serializable
data class MonthlyPlannerGoal(
    @field:DocumentId val id: String = "",
    val publisherPersonId: String = "",
    val year: Int = 0,
    val month: Int = 0,
    val goalHours: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    companion object {
        fun idFor(publisherPersonId: String, year: Int, month: Int): String =
            "${publisherPersonId}_${year.padded(4)}${month.padded(2)}"
    }
}

/**
 * My Planner → Week (Dashboard/My Planner integration) — same shape as
 * [MonthlyPlannerGoal], one level down. [id] keys off the week's own Monday
 * start millis rather than a year/month pair (a week can span two months),
 * same "deterministic id, no query" approach.
 *
 * Firestore collection: `weeklyPlannerGoals/{publisherPersonId_weekStartMillis}`
 */
@kotlinx.serialization.Serializable
data class WeeklyPlannerGoal(
    @field:DocumentId val id: String = "",
    val publisherPersonId: String = "",
    val weekStart: Long = 0L,
    val goalHours: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    companion object {
        fun idFor(publisherPersonId: String, weekStart: Long): String = "${publisherPersonId}_$weekStart"
    }
}

/**
 * My Planner → Year spec §26 — same shape as [MonthlyPlannerGoal], one level
 * up.
 *
 * Firestore collection: `yearlyPlannerGoals/{publisherPersonId_yyyy}`
 */
@kotlinx.serialization.Serializable
data class YearlyPlannerGoal(
    @field:DocumentId val id: String = "",
    val publisherPersonId: String = "",
    val year: Int = 0,
    val goalHours: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    companion object {
        fun idFor(publisherPersonId: String, year: Int): String = "${publisherPersonId}_${year.padded(4)}"
    }
}
