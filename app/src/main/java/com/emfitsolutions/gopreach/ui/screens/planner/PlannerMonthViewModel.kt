package com.emfitsolutions.gopreach.ui.screens.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.CreditHourRecord
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.MonthlyPlannerGoal
import com.emfitsolutions.gopreach.data.model.PlannerDay
import com.emfitsolutions.gopreach.data.model.Visit
import com.emfitsolutions.gopreach.data.repository.CreditHourRecordRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyPlannerGoalRepository
import com.emfitsolutions.gopreach.data.repository.PlannerDayRepository
import com.emfitsolutions.gopreach.data.repository.VisitRepository
import com.emfitsolutions.gopreach.domain.DayBounds
import com.emfitsolutions.gopreach.domain.MinistryStatisticsService
import com.emfitsolutions.gopreach.domain.MonthBounds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/** My Planner → Month (spec §23-§25). [totalMinutes]/[returnVisitCount]/
 * [bibleStudyCount] are each independently computed for the whole month
 * (spec §3/§24 — "do not sum daily counts"), never derived by adding up
 * Day-tab numbers. */
data class PlannerMonthUiState(
    val monthStart: Long = 0L,
    val totalMinutes: Int = 0,
    val creditHoursMinutes: Int = 0,
    val returnVisitCount: Int = 0,
    val bibleStudyCount: Int = 0,
    val goalHours: Int = 0,
    /** Start-of-day millis for every day this month with any Ministry Time,
     * Timer Session (already reflected in that day's minutes once stopped),
     * Return Visit, Bible Study, Credit Hour, or Note (spec §23). */
    val activeDayStarts: Set<Long> = emptySet(),
    /** Regular ministry Hours/Minutes for each day in the month that has any
     * (Credit Hours kept separate — spec's own "Credit Hours remain
     * separate unless already combined" rule; this app never combines
     * them). Backs the Monthly Calendar/List view's per-day figure; the
     * monthly total ([totalMinutes]) is this map's own values summed, so
     * the two can never disagree. */
    val dailyMinutes: Map<Long, Int> = emptyMap(),
    /** Credit Hours total for each day in the month that has any — shown as
     * a small secondary figure next to the regular hours, never merged
     * into [dailyMinutes]. */
    val dailyCreditMinutes: Map<Long, Int> = emptyMap(),
    /** Unique Return Visit / Bible Study person counts for each day in the
     * month that has any (Monthly List View's daily summary — spec §30/§32).
     * Each day is independently computed, never a slice of [returnVisitCount]
     * / [bibleStudyCount] (spec §37: "do not sum daily counts to calculate
     * the monthly total" — this is the same rule stated the other way
     * around: a day's own count is never derived from the month's). */
    val dailyReturnVisits: Map<Long, Int> = emptyMap(),
    val dailyBibleStudies: Map<Long, Int> = emptyMap(),
    /** The rows behind this month's figures — see [PlannerPeriodRecords]. */
    val records: PlannerPeriodRecords = PlannerPeriodRecords(),
    val isLoading: Boolean = true,
) {
    val goalMinutes: Int get() = goalHours * 60
    val remainingMinutes: Int get() = (goalMinutes - totalMinutes).coerceAtLeast(0)
    val surplusMinutes: Int get() = (totalMinutes - goalMinutes).coerceAtLeast(0)
}

@HiltViewModel
class PlannerMonthViewModel @Inject constructor(
    private val plannerDayRepository: PlannerDayRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val visitRepository: VisitRepository,
    private val creditHourRecordRepository: CreditHourRecordRepository,
    private val monthlyGoalRepository: MonthlyPlannerGoalRepository,
) : ViewModel() {

    private val _monthStart = MutableStateFlow(MonthBounds.of(System.currentTimeMillis()).startInclusive)
    val monthStart: StateFlow<Long> = _monthStart

    fun goToPreviousMonth() = shiftMonth(-1)
    fun goToNextMonth() = shiftMonth(1)

    /** Spec §27 — tapping a month in the Year tab's 12-month table opens
     * that exact month here. */
    fun goToMonth(anyMillisInThatMonth: Long) {
        _monthStart.value = MonthBounds.of(anyMillisInThatMonth).startInclusive
    }

    private fun shiftMonth(delta: Int) {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = _monthStart.value
            add(Calendar.MONTH, delta)
        }
        _monthStart.value = MonthBounds.of(calendar.timeInMillis).startInclusive
    }

    fun stateFor(publisherPersonId: String): StateFlow<PlannerMonthUiState> {
        val creditAndGoalFlow = combine(
            creditHourRecordRepository.observeForPublisher(publisherPersonId),
            monthlyGoalRepository.observeForPublisher(publisherPersonId),
        ) { records, goals -> records to goals }

        return combine(
            _monthStart,
            plannerDayRepository.observeForPublisher(publisherPersonId),
            interestedPersonRepository.observeAll(),
            visitRepository.observeAllForPublisher(publisherPersonId),
            creditAndGoalFlow,
        ) { monthStart, allDays, people, visits, creditAndGoal ->
            val (creditRecords, goals) = creditAndGoal
            val bounds = MonthBounds.of(monthStart)
            val monthDays = allDays.filter { bounds.contains(it.dayStart) }
            val monthCredits = creditRecords.inPeriod(bounds)

            val calendar = Calendar.getInstance().apply { timeInMillis = monthStart }
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH) + 1
            val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

            val monthDayStarts = (1..daysInMonth).map { day -> dayStartFor(year, month, day) }
            val dailyReturnVisits = dailyUniquePersonCounts(publisherPersonId, people, visits, com.emfitsolutions.gopreach.data.model.PipelineStage.RETURN_VISIT, monthDayStarts)
            val dailyBibleStudies = dailyUniquePersonCounts(publisherPersonId, people, visits, com.emfitsolutions.gopreach.data.model.PipelineStage.BIBLE_STUDY, monthDayStarts)

            val activeDays = buildSet {
                monthDays.filter { it.totalMinutes > 0 || !it.note.isNullOrBlank() }.forEach { add(it.dayStart) }
                monthCredits.forEach { add(it.resolvedDayStart()) }
                addAll(dailyReturnVisits.keys)
                addAll(dailyBibleStudies.keys)
            }

            val goal = goals.firstOrNull { it.id == MonthlyPlannerGoal.idFor(publisherPersonId, year, month) }

            PlannerMonthUiState(
                monthStart = monthStart,
                totalMinutes = monthDays.sumOf { it.totalMinutes },
                creditHoursMinutes = monthCredits.sumOf { it.totalMinutes },
                returnVisitCount = MinistryStatisticsService.getMonthlyUniqueReturnVisits(publisherPersonId, people, visits, monthStart),
                bibleStudyCount = MinistryStatisticsService.getMonthlyUniqueBibleStudies(publisherPersonId, people, visits, monthStart),
                goalHours = goal?.goalHours ?: 0,
                activeDayStarts = activeDays,
                dailyMinutes = dailyMinutes(allDays, bounds),
                dailyCreditMinutes = dailyCreditMinutes(creditRecords, bounds),
                dailyReturnVisits = dailyReturnVisits,
                dailyBibleStudies = dailyBibleStudies,
                records = buildPeriodRecords(publisherPersonId, bounds, allDays, creditRecords, people, visits),
                isLoading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlannerMonthUiState())
    }

    private fun dayStartFor(year: Int, month: Int, day: Int): Long = Calendar.getInstance().apply {
        set(year, month - 1, day, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun setGoalHours(publisherPersonId: String, goalHours: Int) {
        viewModelScope.launch {
            val calendar = Calendar.getInstance().apply { timeInMillis = _monthStart.value }
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH) + 1
            monthlyGoalRepository.save(
                MonthlyPlannerGoal(
                    id = MonthlyPlannerGoal.idFor(publisherPersonId, year, month),
                    publisherPersonId = publisherPersonId,
                    year = year,
                    month = month,
                    goalHours = goalHours.coerceAtLeast(0),
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}
