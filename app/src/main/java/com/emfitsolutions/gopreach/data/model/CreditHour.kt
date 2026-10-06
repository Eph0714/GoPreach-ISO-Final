package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId

/**
 * Credit Hour Categories (spec §28) — "do not permanently hard-code the
 * categories... create Credit Hour Categories with CRUD functionality for
 * authorized users." Same admin-editable lookup-table shape as
 * [ElderTitleEntity] (name + active flag, full CRUD, never a fixed enum).
 *
 * Firestore collection: `creditHourCategories/{categoryId}`
 */
data class CreditHourCategory(
    @DocumentId val id: String = "",
    val name: String = "",
    val description: String? = null,
    val active: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * One Credit Hour entry logged against a Publisher's [PlannerDay] (spec §28).
 * "Support multiple records and aggregation" — a flat, unaggregated list;
 * any screen that needs a day/month/year total sums these itself, the same
 * way [PreachingTimeRecord] totals are already summed rather than
 * pre-rolled-up into a single field anywhere.
 *
 * Firestore collection: `creditHourRecords/{recordId}`
 */
data class CreditHourRecord(
    @DocumentId val id: String = "",
    val publisherPersonId: String = "",
    /** [PlannerDay.id] this entry belongs to — see that class's own doc
     * comment for the deterministic `personId_yyyyMMdd` id shape. */
    val plannerDayId: String = "",
    /** Start-of-day millis of the date this entry counts toward. Stored
     * directly (rather than only inside [plannerDayId]) so Week/Month/Year
     * totals can range-check it without needing a [PlannerDay] document to
     * exist for that date — a day with only a Credit Hour entry and no
     * ministry minutes never gets one. `0` on records saved before this
     * field existed; [resolvedDayStart] falls back to [plannerDayId]. */
    val dayStart: Long = 0L,
    val categoryId: String = "",
    val hours: Int = 0,
    val minutes: Int = 0,
    val note: String? = null,
    val createdAt: Long = 0L,
    val createdByPersonId: String = "",
    val updatedAt: Long = 0L,
) {
    /** Internal total-minutes representation (spec §29 — "use total minutes/
     * seconds internally"), used for both this record's own display and
     * whenever a caller sums several records together. */
    val totalMinutes: Int get() = hours * 60 + minutes

    /** [dayStart], or — for records saved before that field existed — the
     * date parsed back out of [plannerDayId]'s `personId_yyyyMMdd` suffix
     * (see [PlannerDay.idFor]). `0` only if neither is usable. */
    fun resolvedDayStart(): Long {
        if (dayStart > 0L) return dayStart
        val suffix = plannerDayId.substringAfterLast('_', missingDelimiterValue = "")
        if (suffix.length != 8 || !suffix.all(Char::isDigit)) return 0L
        return java.util.Calendar.getInstance().apply {
            set(suffix.substring(0, 4).toInt(), suffix.substring(4, 6).toInt() - 1, suffix.substring(6, 8).toInt(), 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
