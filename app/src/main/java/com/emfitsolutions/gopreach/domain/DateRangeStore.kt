package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.ui.components.DateRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Main Form Date Range Filtering" spec §8 — "the selected date range must be
 * remembered across navigation" — one selected [DateRange], shared by every
 * report screen (the Main Form's own Dashboard summary and the standalone
 * Reports Summary screen today; any future report screen tomorrow) via Hilt's
 * `@Singleton` scope, so switching between them never silently resets back to
 * a default the user didn't choose.
 *
 * Deliberately in-memory only, not persisted to disk/DataStore across a full
 * app restart — every report defaults to Today on cold start ("Make Today
 * the default date range in My Planner" — since My Planner and the
 * Dashboard summary share this one range, that default applies to both),
 * calculated live from the current date, so there is nothing a disk-backed
 * store would add beyond what a fresh in-memory default already gives for
 * free; the "remembered across navigation" requirement is about moving
 * *between screens in one session*, not surviving a process kill.
 */
@Singleton
class DateRangeStore @Inject constructor() {
    private val _range = MutableStateFlow(DateRange.today())
    val range: StateFlow<DateRange> = _range.asStateFlow()

    fun set(range: DateRange) {
        _range.value = range
    }
}
