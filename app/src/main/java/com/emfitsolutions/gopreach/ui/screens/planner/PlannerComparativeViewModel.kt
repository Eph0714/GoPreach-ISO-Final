package com.emfitsolutions.gopreach.ui.screens.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.PlannerDayRepository
import com.emfitsolutions.gopreach.data.repository.VisitRepository
import com.emfitsolutions.gopreach.domain.MinistryStatisticsService
import com.emfitsolutions.gopreach.domain.MonthBounds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import javax.inject.Inject

/** One month's worth of the three metrics the Comparative Report tracks —
 * each independently computed the same way every other Planner period is
 * (spec's own "never sum daily/monthly counts" rule), never derived from a
 * neighboring month's row. */
data class ComparativeMonthRow(
    val monthStart: Long,
    val totalMinutes: Int,
    val returnVisitCount: Int,
    val bibleStudyCount: Int,
)

data class PlannerComparativeUiState(
    val startMonth: Long = 0L,
    val endMonth: Long = 0L,
    val rows: List<ComparativeMonthRow> = emptyList(),
    val isLoading: Boolean = true,
)

/** My Planner → Compare — "Add a multi-month Comparative Report... Compare
 * Hours, Minutes, Return Visits, and Bible Studies" (My Planner enhancement
 * spec). The Publisher picks any Start/End month; every month in between is
 * computed the same way [PlannerMonthViewModel] computes a single month. */
@HiltViewModel
class PlannerComparativeViewModel @Inject constructor(
    private val plannerDayRepository: PlannerDayRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val visitRepository: VisitRepository,
) : ViewModel() {

    private val _startMonth = MutableStateFlow(monthStartMonthsAgo(5))
    val startMonth: StateFlow<Long> = _startMonth

    private val _endMonth = MutableStateFlow(monthStartMonthsAgo(0))
    val endMonth: StateFlow<Long> = _endMonth

    /** Keeps Start/End from ever crossing — picking a Start after the
     * current End pulls End forward to match, and vice versa, rather than
     * silently producing an empty or reversed range. */
    fun setStartMonth(anyMillisInMonth: Long) {
        val normalized = MonthBounds.of(anyMillisInMonth).startInclusive
        _startMonth.value = normalized
        if (normalized > _endMonth.value) _endMonth.value = normalized
    }

    fun setEndMonth(anyMillisInMonth: Long) {
        val normalized = MonthBounds.of(anyMillisInMonth).startInclusive
        _endMonth.value = normalized
        if (normalized < _startMonth.value) _startMonth.value = normalized
    }

    fun stateFor(publisherPersonId: String): StateFlow<PlannerComparativeUiState> = combine(
        _startMonth,
        _endMonth,
        plannerDayRepository.observeForPublisher(publisherPersonId),
        interestedPersonRepository.observeAll(),
        visitRepository.observeAllForPublisher(publisherPersonId),
    ) { start, end, allDays, people, visits ->
        val rows = monthsBetween(start, end).map { monthStart ->
            val bounds = MonthBounds.of(monthStart)
            ComparativeMonthRow(
                monthStart = monthStart,
                totalMinutes = allDays.filter { bounds.contains(it.dayStart) }.sumOf { it.totalMinutes },
                returnVisitCount = MinistryStatisticsService.getMonthlyUniqueReturnVisits(publisherPersonId, people, visits, monthStart),
                bibleStudyCount = MinistryStatisticsService.getMonthlyUniqueBibleStudies(publisherPersonId, people, visits, monthStart),
            )
        }
        PlannerComparativeUiState(startMonth = start, endMonth = end, rows = rows, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlannerComparativeUiState())

    /** Month-by-month figures for one [range] — the same computation as the single-range report, so Period A and Period B always agree with it. */
    fun rowsFor(publisherPersonId: String, range: com.emfitsolutions.gopreach.ui.components.MonthRange): kotlinx.coroutines.flow.Flow<List<ComparativeMonthRow>> = combine(
        plannerDayRepository.observeForPublisher(publisherPersonId),
        interestedPersonRepository.observeAll(),
        visitRepository.observeAllForPublisher(publisherPersonId),
    ) { allDays, people, visits ->
        range.months().map { monthStart ->
            val bounds = MonthBounds.of(monthStart)
            ComparativeMonthRow(
                monthStart = monthStart,
                totalMinutes = allDays.filter { bounds.contains(it.dayStart) }.sumOf { it.totalMinutes },
                returnVisitCount = MinistryStatisticsService.getMonthlyUniqueReturnVisits(publisherPersonId, people, visits, monthStart),
                bibleStudyCount = MinistryStatisticsService.getMonthlyUniqueBibleStudies(publisherPersonId, people, visits, monthStart),
            )
        }
    }

    private fun monthsBetween(start: Long, end: Long): List<Long> {
        val result = mutableListOf<Long>()
        var cursor = start
        // A picked range this large would make the report unreadable and
        // the query unbounded — 36 months (3 years) is generous for a
        // "compare recent trends" report without either problem.
        var guard = 0
        while (cursor <= end && guard < 36) {
            result.add(cursor)
            cursor = Calendar.getInstance().apply { timeInMillis = cursor; add(Calendar.MONTH, 1) }.timeInMillis
            guard++
        }
        return result
    }

    companion object {
        private fun monthStartMonthsAgo(monthsAgo: Int): Long =
            MonthBounds.of(Calendar.getInstance().apply { add(Calendar.MONTH, -monthsAgo) }.timeInMillis).startInclusive
    }
}
