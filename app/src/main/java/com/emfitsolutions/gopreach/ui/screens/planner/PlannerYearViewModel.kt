package com.emfitsolutions.gopreach.ui.screens.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.YearlyPlannerGoal
import com.emfitsolutions.gopreach.data.repository.CreditHourRecordRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.PlannerDayRepository
import com.emfitsolutions.gopreach.data.repository.VisitRepository
import com.emfitsolutions.gopreach.data.repository.YearlyPlannerGoalRepository
import com.emfitsolutions.gopreach.domain.MinistryStatisticsService
import com.emfitsolutions.gopreach.domain.MonthBounds
import com.emfitsolutions.gopreach.domain.YearBounds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/** My Planner → Year (spec §26-§27). Every figure is independently computed
 * for the whole year (spec §3/§13 — never summed from the 12 monthly
 * values). [monthRows] backs the 12-month table (spec §27). */
data class PlannerYearMonthRow(
    val monthStart: Long,
    val label: String,
    val totalMinutes: Int,
    val goalHours: Int,
    val creditMinutes: Int = 0,
) {
    val surplusOrMissingMinutes: Int get() = totalMinutes - (goalHours * 60)
}

data class PlannerYearUiState(
    val yearStart: Long = 0L,
    val totalMinutes: Int = 0,
    val creditHoursMinutes: Int = 0,
    val returnVisitCount: Int = 0,
    val bibleStudyCount: Int = 0,
    val goalHours: Int = 0,
    val monthRows: List<PlannerYearMonthRow> = emptyList(),
    /** The rows behind this year's figures — see [PlannerPeriodRecords]. */
    val records: PlannerPeriodRecords = PlannerPeriodRecords(),
    val isLoading: Boolean = true,
) {
    val goalMinutes: Int get() = goalHours * 60
    val remainingMinutes: Int get() = (goalMinutes - totalMinutes).coerceAtLeast(0)
    val surplusMinutes: Int get() = (totalMinutes - goalMinutes).coerceAtLeast(0)
}

@HiltViewModel
class PlannerYearViewModel @Inject constructor(
    private val plannerDayRepository: PlannerDayRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val visitRepository: VisitRepository,
    private val creditHourRecordRepository: CreditHourRecordRepository,
    private val yearlyGoalRepository: YearlyPlannerGoalRepository,
    private val monthlyGoalRepository: com.emfitsolutions.gopreach.data.repository.MonthlyPlannerGoalRepository,
) : ViewModel() {

    private val _yearStart = MutableStateFlow(YearBounds.of(System.currentTimeMillis()).startInclusive)
    val yearStart: StateFlow<Long> = _yearStart

    fun goToPreviousYear() = shiftYear(-1)
    fun goToNextYear() = shiftYear(1)

    /** Dashboard/My Planner integration — snaps to the exact year the
     * Dashboard's "This Year" quick range resolves to, same "goTo" shape as
     * [PlannerDayViewModel.goToDate]/[PlannerMonthViewModel.goToMonth]/
     * [PlannerWeekViewModel.goToWeek]. */
    fun goToYear(anyMillisInThatYear: Long) {
        _yearStart.value = YearBounds.of(anyMillisInThatYear).startInclusive
    }

    private fun shiftYear(delta: Int) {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = _yearStart.value
            add(Calendar.YEAR, delta)
        }
        _yearStart.value = YearBounds.of(calendar.timeInMillis).startInclusive
    }

    fun stateFor(publisherPersonId: String): StateFlow<PlannerYearUiState> {
        val creditAndGoalsFlow = combine(
            creditHourRecordRepository.observeForPublisher(publisherPersonId),
            yearlyGoalRepository.observeForPublisher(publisherPersonId),
            monthlyGoalRepository.observeForPublisher(publisherPersonId),
        ) { records, yearlyGoals, monthlyGoals -> Triple(records, yearlyGoals, monthlyGoals) }

        return combine(
            _yearStart,
            plannerDayRepository.observeForPublisher(publisherPersonId),
            interestedPersonRepository.observeAll(),
            visitRepository.observeAllForPublisher(publisherPersonId),
            creditAndGoalsFlow,
        ) { yearStart, allDays, people, visits, creditAndGoals ->
            val (creditRecords, yearlyGoals, monthlyGoals) = creditAndGoals
            val bounds = YearBounds.of(yearStart)
            val yearDays = allDays.filter { bounds.contains(it.dayStart) }

            val yearCalendar = Calendar.getInstance().apply { timeInMillis = yearStart }
            val year = yearCalendar.get(Calendar.YEAR)

            val monthLabelFormat = java.text.SimpleDateFormat("MMMM", java.util.Locale.getDefault())
            val monthRows = (1..12).map { month ->
                val monthCalendar = Calendar.getInstance().apply {
                    set(year, month - 1, 1, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val monthStartMillis = monthCalendar.timeInMillis
                val monthBounds = MonthBounds.of(monthStartMillis)
                val monthMinutes = allDays.filter { monthBounds.contains(it.dayStart) }.sumOf { it.totalMinutes }
                val goalForMonth = monthlyGoals.firstOrNull {
                    it.id == com.emfitsolutions.gopreach.data.model.MonthlyPlannerGoal.idFor(publisherPersonId, year, month)
                }
                PlannerYearMonthRow(
                    monthStart = monthStartMillis,
                    label = monthLabelFormat.format(java.util.Date(monthStartMillis)),
                    totalMinutes = monthMinutes,
                    goalHours = goalForMonth?.goalHours ?: 0,
                    creditMinutes = creditRecords.inPeriod(monthBounds).sumOf { it.totalMinutes },
                )
            }

            val yearlyGoal = yearlyGoals.firstOrNull { it.id == YearlyPlannerGoal.idFor(publisherPersonId, year) }

            PlannerYearUiState(
                yearStart = yearStart,
                totalMinutes = yearDays.sumOf { it.totalMinutes },
                creditHoursMinutes = creditRecords.inPeriod(bounds).sumOf { it.totalMinutes },
                returnVisitCount = MinistryStatisticsService.getYearlyUniqueReturnVisits(publisherPersonId, people, visits, yearStart),
                bibleStudyCount = MinistryStatisticsService.getYearlyUniqueBibleStudies(publisherPersonId, people, visits, yearStart),
                goalHours = yearlyGoal?.goalHours ?: 0,
                monthRows = monthRows,
                records = buildPeriodRecords(publisherPersonId, bounds, allDays, creditRecords, people, visits),
                isLoading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlannerYearUiState())
    }

    fun setGoalHours(publisherPersonId: String, goalHours: Int) {
        viewModelScope.launch {
            val year = Calendar.getInstance().apply { timeInMillis = _yearStart.value }.get(Calendar.YEAR)
            yearlyGoalRepository.save(
                YearlyPlannerGoal(
                    id = YearlyPlannerGoal.idFor(publisherPersonId, year),
                    publisherPersonId = publisherPersonId,
                    year = year,
                    goalHours = goalHours.coerceAtLeast(0),
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}
