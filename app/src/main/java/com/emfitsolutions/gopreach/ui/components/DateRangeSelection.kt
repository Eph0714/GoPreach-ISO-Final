package com.emfitsolutions.gopreach.ui.components

import com.emfitsolutions.gopreach.domain.TimeBounds
import java.util.Calendar

/** Which quick-select is currently active — [CUSTOM] once the user has
 * manually changed either the Start or End date away from what a preset
 * computed (spec: "the quick-selection buttons should update their selected
 * state accordingly"). */
enum class QuickDateRange(val label: String) {
    TODAY("Today"),
    THIS_WEEK("This Week"),
    THIS_MONTH("This Month"),
    THIS_YEAR("This Year"),
    CUSTOM("Custom"),
}

/** [startMillis]/[endMillis] always span whole days — start of day through
 * end of day (23:59:59.999) — so a same-day record and an end-of-day record
 * are both correctly included by a plain `in startMillis..endMillis` check
 * against a real stored timestamp (spec §9: "do not filter based only on
 * formatted display text"). */
data class DateRange(
    val startMillis: Long,
    val endMillis: Long,
    val option: QuickDateRange,
) : TimeBounds {
    /** Whether [millis] falls within this range, inclusive of both ends. */
    override operator fun contains(millis: Long): Boolean = millis in startMillis..endMillis

    /** Whether a whole month starting at [periodMonthMillis] (the first-of-month
     * shape [com.emfitsolutions.gopreach.data.model.MonthlyReport.periodMonth]
     * stores) overlaps this range at all — used for filtering monthly-grain
     * report records, which have no finer-than-a-month timestamp to compare
     * directly. A "This Week"/"Today" range still correctly pulls in whichever
     * month it falls inside, rather than matching nothing. */
    fun overlapsMonth(periodMonthMillis: Long): Boolean {
        val monthStart = Calendar.getInstance().apply {
            timeInMillis = periodMonthMillis
            startOfDay()
            set(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
        val monthEnd = Calendar.getInstance().apply {
            timeInMillis = monthStart
            add(Calendar.MONTH, 1)
            add(Calendar.MILLISECOND, -1)
        }.timeInMillis
        return monthStart <= endMillis && monthEnd >= startMillis
    }

    companion object {
        /** This Month — the app's default on first load (spec §4/§16). All
         * presets are computed live from [Calendar.getInstance] (the current
         * device date), never hard-coded. */
        fun thisMonth(): DateRange {
            val start = Calendar.getInstance().apply {
                startOfDay()
                set(Calendar.DAY_OF_MONTH, 1)
            }
            val end = Calendar.getInstance().apply {
                timeInMillis = start.timeInMillis
                add(Calendar.MONTH, 1)
                add(Calendar.MILLISECOND, -1)
            }
            return DateRange(start.timeInMillis, end.timeInMillis, QuickDateRange.THIS_MONTH)
        }

        fun today(): DateRange {
            val start = Calendar.getInstance().apply { startOfDay() }
            val end = Calendar.getInstance().apply { timeInMillis = start.timeInMillis; endOfDay() }
            return DateRange(start.timeInMillis, end.timeInMillis, QuickDateRange.TODAY)
        }

        /** Monday through Sunday — this app has no separate "first day of week"
         * setting anywhere else, so Monday is picked here and used consistently
         * (spec §5: "use the application's configured definition... consistently"). */
        fun thisWeek(): DateRange {
            val start = Calendar.getInstance().apply {
                startOfDay()
                firstDayOfWeek = Calendar.MONDAY
                val currentDow = get(Calendar.DAY_OF_WEEK)
                // DAY_OF_WEEK is 1=Sunday..7=Saturday regardless of firstDayOfWeek;
                // compute how many days to step back to reach this week's Monday.
                val daysSinceMonday = ((currentDow - Calendar.MONDAY) + 7) % 7
                add(Calendar.DAY_OF_MONTH, -daysSinceMonday)
            }
            val end = Calendar.getInstance().apply {
                timeInMillis = start.timeInMillis
                add(Calendar.DAY_OF_MONTH, 6)
                endOfDay()
            }
            return DateRange(start.timeInMillis, end.timeInMillis, QuickDateRange.THIS_WEEK)
        }

        fun thisYear(): DateRange {
            val start = Calendar.getInstance().apply {
                startOfDay()
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            val end = Calendar.getInstance().apply {
                timeInMillis = start.timeInMillis
                add(Calendar.YEAR, 1)
                add(Calendar.MILLISECOND, -1)
            }
            return DateRange(start.timeInMillis, end.timeInMillis, QuickDateRange.THIS_YEAR)
        }

        /** The single whole month containing [periodMonthMillis] — used to
         * jump a report-list screen straight to one specific month (e.g. the
         * notification balloon's "Monthly Report" item opening the exact
         * month that report belongs to, rather than the screen's own
         * default "This Month" range, which would show nothing if that
         * report is from a different month). Reported as [QuickDateRange
         * .CUSTOM] since it's neither "This Month" (the current calendar
         * month) nor any other fixed preset. */
        fun forMonth(periodMonthMillis: Long): DateRange {
            val start = Calendar.getInstance().apply {
                timeInMillis = periodMonthMillis
                startOfDay()
                set(Calendar.DAY_OF_MONTH, 1)
            }
            val end = Calendar.getInstance().apply {
                timeInMillis = start.timeInMillis
                add(Calendar.MONTH, 1)
                add(Calendar.MILLISECOND, -1)
            }
            return DateRange(start.timeInMillis, end.timeInMillis, QuickDateRange.CUSTOM)
        }

        /** A user-picked Start/End (spec §6) — swaps them if entered backwards
         * rather than rejecting outright, since spec §6 only requires Start ≤
         * End be *true* in the end, not that the app scold the user for which
         * field they touched first. */
        fun custom(startMillis: Long, endMillis: Long): DateRange {
            val start = Calendar.getInstance().apply { timeInMillis = minOf(startMillis, endMillis); startOfDay() }
            val end = Calendar.getInstance().apply { timeInMillis = maxOf(startMillis, endMillis); endOfDay() }
            return DateRange(start.timeInMillis, end.timeInMillis, QuickDateRange.CUSTOM)
        }
    }
}

private fun Calendar.startOfDay() {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}

private fun Calendar.endOfDay() {
    set(Calendar.HOUR_OF_DAY, 23)
    set(Calendar.MINUTE, 59)
    set(Calendar.SECOND, 59)
    set(Calendar.MILLISECOND, 999)
}
