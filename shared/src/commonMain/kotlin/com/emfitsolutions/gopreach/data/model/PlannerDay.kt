package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId
import com.emfitsolutions.gopreach.platform.padded

/**
 * My Planner spec §16-§19 — one Publisher's own Day tab record: consumed
 * ministry time (Hours/Minutes steppers) + that day's goal + an optional
 * note. [totalMinutes] is the single internal representation (spec §18/§29:
 * "use total minutes... internally") — the Day screen derives its displayed
 * Hours/Minutes from this by division/modulo rather than storing them
 * separately, so `1h 75m` normalizing to `2h 15m` is just arithmetic on one
 * number, never two fields that could drift out of sync with each other.
 *
 * [id] is deterministic ([idFor]), not auto-generated — `personId_yyyyMMdd`,
 * same "one document per (publisher, day), looked up directly rather than
 * queried" shape [id] gives [com.emfitsolutions.gopreach.data.model
 * .UserAccessGrant] for its own personId-keyed document — so a
 * [CreditHourRecord.plannerDayId] can reference "today's planner day" before
 * any [PlannerDay] document has ever actually been saved for it (the id is
 * computable from just the publisher and date, never round-tripped through a
 * create call first).
 *
 * Firestore collection: `plannerDays/{publisherPersonId_yyyyMMdd}`
 */
data class PlannerDay(
    @DocumentId val id: String = "",
    val publisherPersonId: String = "",
    /** Start-of-day epoch millis for the date this record belongs to (same
     * `[start, end)`-window anchor [com.emfitsolutions.gopreach.domain
     * .DayBounds.of] resolves from). */
    val dayStart: Long = 0L,
    val totalMinutes: Int = 0,
    val dailyGoalHours: Int = 0,
    val note: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    companion object {
        /** `yyyyMMdd` derived directly from [dayStart] rather than a
         * separately-formatted string field, so this id is always
         * recomputable from the date alone with no formatting drift. */
        fun idFor(publisherPersonId: String, dayStart: Long): String {
            val (year, month, day) = com.emfitsolutions.gopreach.platform.ymdOf(dayStart)
            return "${publisherPersonId}_${year.padded(4)}${month.padded(2)}${day.padded(2)}"
        }
    }
}
