package com.emfitsolutions.gopreach.ui.screens.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.CreditHourRecord
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.PlannerDay
import com.emfitsolutions.gopreach.data.model.Visit
import com.emfitsolutions.gopreach.data.model.WeeklyPlannerGoal
import com.emfitsolutions.gopreach.data.repository.CreditHourRecordRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.PlannerDayRepository
import com.emfitsolutions.gopreach.data.repository.VisitRepository
import com.emfitsolutions.gopreach.data.repository.WeeklyPlannerGoalRepository
import com.emfitsolutions.gopreach.domain.DayBounds
import com.emfitsolutions.gopreach.domain.MinistryStatisticsService
import com.emfitsolutions.gopreach.domain.WeekBounds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/** One day's row in the Weekly Planner's 7-day-wide table (spec §5) — the
 * per-day figures shown side by side, Monday..Sunday. */
data class PlannerWeekDayRow(
    val dayStart: Long,
    val totalMinutes: Int = 0,
    val creditMinutes: Int = 0,
    val returnVisitCount: Int = 0,
    val bibleStudyCount: Int = 0,
    val isToday: Boolean = false,
)

/** My Planner → Week (Dashboard/My Planner integration spec §4-§7) —
 * [totalMinutes]/[creditHoursMinutes] are summed across the week's
 * [dayRows] (ministry/credit time is naturally additive), but
 * [returnVisitCount]/[bibleStudyCount] are independently computed for the
 * whole week (spec §6/§7 — "do not calculate Weekly Return Visit = Monday +
 * Tuesday + ..."), never derived by adding up the per-day counts. */
data class PlannerWeekUiState(
    val weekStart: Long = 0L,
    val dayRows: List<PlannerWeekDayRow> = emptyList(),
    val totalMinutes: Int = 0,
    val creditHoursMinutes: Int = 0,
    val returnVisitCount: Int = 0,
    val bibleStudyCount: Int = 0,
    val goalHours: Int = 0,
    /** The rows behind this week's figures — see [PlannerPeriodRecords]. */
    val records: PlannerPeriodRecords = PlannerPeriodRecords(),
    val isLoading: Boolean = true,
) {
    val goalMinutes: Int get() = goalHours * 60

    /** Spec §4/§13 — "Remaining = Goal Hours − (Hours/Minutes + Credit
     * Hours)": unlike the Day/Month/Year goal calcs (which only subtract
     * ministry minutes), the Weekly Goal explicitly also subtracts Credit
     * Hours. Never negative — spec §4: "prefer displaying 0 Hours 00
     * Minutes" once the goal is reached or exceeded. */
    val remainingMinutes: Int get() = (goalMinutes - (totalMinutes + creditHoursMinutes)).coerceAtLeast(0)
    val surplusMinutes: Int get() = ((totalMinutes + creditHoursMinutes) - goalMinutes).coerceAtLeast(0)
}

@HiltViewModel
class PlannerWeekViewModel @Inject constructor(
    private val plannerDayRepository: PlannerDayRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val visitRepository: VisitRepository,
    private val creditHourRecordRepository: CreditHourRecordRepository,
    private val weeklyGoalRepository: WeeklyPlannerGoalRepository,
) : ViewModel() {

    private val _weekStart = MutableStateFlow(WeekBounds.of(System.currentTimeMillis()).startInclusive)
    val weekStart: StateFlow<Long> = _weekStart

    fun goToPreviousWeek() = shiftWeek(-1)
    fun goToNextWeek() = shiftWeek(1)

    fun goToWeek(anyMillisInThatWeek: Long) {
        _weekStart.value = WeekBounds.of(anyMillisInThatWeek).startInclusive
    }

    private fun shiftWeek(deltaWeeks: Int) {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = _weekStart.value
            add(Calendar.DAY_OF_MONTH, deltaWeeks * 7)
        }
        _weekStart.value = WeekBounds.of(calendar.timeInMillis).startInclusive
    }

    fun stateFor(publisherPersonId: String): StateFlow<PlannerWeekUiState> {
        val creditAndGoalFlow = combine(
            creditHourRecordRepository.observeForPublisher(publisherPersonId),
            weeklyGoalRepository.observeForPublisher(publisherPersonId),
        ) { records, goals -> records to goals }

        return combine(
            _weekStart,
            plannerDayRepository.observeForPublisher(publisherPersonId),
            interestedPersonRepository.observeAll(),
            visitRepository.observeAllForPublisher(publisherPersonId),
            creditAndGoalFlow,
        ) { weekStart, allDays, people, visits, creditAndGoal ->
            val (creditRecords, goals) = creditAndGoal
            val bounds = WeekBounds.of(weekStart)
            val weekDays = allDays.filter { bounds.contains(it.dayStart) }
            val weekCredits = creditRecords.inPeriod(bounds)
            val todayStart = DayBounds.of(System.currentTimeMillis()).startInclusive

            val dayRows = (0..6).map { offset ->
                val dayStart = Calendar.getInstance().apply {
                    timeInMillis = weekStart
                    add(Calendar.DAY_OF_MONTH, offset)
                }.timeInMillis
                val dayId = PlannerDay.idFor(publisherPersonId, dayStart)
                val plannerDay = weekDays.firstOrNull { it.id == dayId }
                PlannerWeekDayRow(
                    dayStart = dayStart,
                    totalMinutes = plannerDay?.totalMinutes ?: 0,
                    creditMinutes = weekCredits.inPeriod(DayBounds.of(dayStart)).sumOf { it.totalMinutes },
                    returnVisitCount = MinistryStatisticsService.getDailyUniqueReturnVisits(publisherPersonId, people, visits, dayStart),
                    bibleStudyCount = MinistryStatisticsService.getDailyUniqueBibleStudies(publisherPersonId, people, visits, dayStart),
                    isToday = dayStart == todayStart,
                )
            }

            val goal = goals.firstOrNull { it.id == WeeklyPlannerGoal.idFor(publisherPersonId, weekStart) }

            PlannerWeekUiState(
                weekStart = weekStart,
                dayRows = dayRows,
                totalMinutes = weekDays.sumOf { it.totalMinutes },
                creditHoursMinutes = weekCredits.sumOf { it.totalMinutes },
                returnVisitCount = MinistryStatisticsService.getWeeklyUniqueReturnVisits(publisherPersonId, people, visits, weekStart),
                bibleStudyCount = MinistryStatisticsService.getWeeklyUniqueBibleStudies(publisherPersonId, people, visits, weekStart),
                goalHours = goal?.goalHours ?: 0,
                records = buildPeriodRecords(publisherPersonId, bounds, allDays, creditRecords, people, visits),
                isLoading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlannerWeekUiState())
    }

    fun setGoalHours(publisherPersonId: String, goalHours: Int) {
        viewModelScope.launch {
            val weekStart = _weekStart.value
            weeklyGoalRepository.save(
                WeeklyPlannerGoal(
                    id = WeeklyPlannerGoal.idFor(publisherPersonId, weekStart),
                    publisherPersonId = publisherPersonId,
                    weekStart = weekStart,
                    goalHours = goalHours.coerceAtLeast(0),
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}
