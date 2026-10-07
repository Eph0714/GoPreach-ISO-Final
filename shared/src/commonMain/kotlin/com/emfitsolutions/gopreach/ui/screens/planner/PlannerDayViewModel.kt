package com.emfitsolutions.gopreach.ui.screens.planner

import com.emfitsolutions.gopreach.platform.nowMillis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.CreditHourRecord
import com.emfitsolutions.gopreach.data.model.MonthlyPlannerGoal
import com.emfitsolutions.gopreach.data.model.PlannerDay
import com.emfitsolutions.gopreach.data.repository.CreditHourRecordRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyPlannerGoalRepository
import com.emfitsolutions.gopreach.data.repository.PlannerDayRepository
import com.emfitsolutions.gopreach.data.repository.VisitRepository
import com.emfitsolutions.gopreach.domain.DayBounds
import com.emfitsolutions.gopreach.domain.MinistryStatisticsService
import com.emfitsolutions.gopreach.domain.MonthBounds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.emfitsolutions.gopreach.platform.Calendar

/** My Planner → Day (spec §17-§22) — everything the Day tab shows, all
 * derived from [dayStart] plus the Publisher's own data. [totalMinutes] is
 * the single internal representation [PlannerDay] itself stores (spec §18/
 * §29); [hours]/[minutes] here are purely derived for display. */
data class PlannerDayUiState(
    val dayStart: Long = 0L,
    val totalMinutes: Int = 0,
    val dailyGoalHours: Int = 0,
    val note: String? = null,
    val returnVisitCount: Int = 0,
    val bibleStudyCount: Int = 0,
    val creditHourRecords: List<CreditHourRecord> = emptyList(),
    /** The rows behind this day's figures — see [PlannerPeriodRecords]. */
    val records: PlannerPeriodRecords = PlannerPeriodRecords(),
    /** Footer "Hours goal for this month" — the month containing [dayStart],
     * independently sourced from [MonthlyPlannerGoalRepository]/the same
     * per-day totals the Month tab itself sums, not derived from anything
     * else on this screen. */
    val monthlyGoalHours: Int = 0,
    val monthlyConsumedMinutes: Int = 0,
    val isLoading: Boolean = true,
) {
    val hours: Int get() = totalMinutes / 60
    val minutes: Int get() = totalMinutes % 60
    val creditHoursTotalMinutes: Int get() = creditHourRecords.sumOf { it.totalMinutes }
    val goalMinutes: Int get() = dailyGoalHours * 60
    /** Spec §19 — "never show negative Remaining." */
    val remainingMinutes: Int get() = (goalMinutes - totalMinutes).coerceAtLeast(0)
    /** Spec §19 — only shown once consumed exceeds the goal. */
    val surplusMinutes: Int get() = (totalMinutes - goalMinutes).coerceAtLeast(0)
    val monthlyGoalMinutes: Int get() = monthlyGoalHours * 60
    val monthlyRemainingMinutes: Int get() = (monthlyGoalMinutes - monthlyConsumedMinutes).coerceAtLeast(0)
}

class PlannerDayViewModel(
    private val plannerDayRepository: PlannerDayRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val visitRepository: VisitRepository,
    private val creditHourRecordRepository: CreditHourRecordRepository,
    private val monthlyGoalRepository: MonthlyPlannerGoalRepository,
    private val monthlyReportRepository: com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository,
) : ViewModel() {

    private val _dayStart = MutableStateFlow(DayBounds.of(nowMillis()).startInclusive)
    val dayStart: StateFlow<Long> = _dayStart

    fun goToPreviousDay() = shiftDay(-1)
    fun goToNextDay() = shiftDay(1)
    fun goToDate(anyMillisOnThatDay: Long) {
        _dayStart.value = DayBounds.of(anyMillisOnThatDay).startInclusive
    }

    private fun shiftDay(deltaDays: Int) {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = _dayStart.value
            add(Calendar.DAY_OF_MONTH, deltaDays)
        }
        _dayStart.value = DayBounds.of(calendar.timeInMillis).startInclusive
    }

    fun stateFor(publisherPersonId: String): StateFlow<PlannerDayUiState> {
        val creditAndGoalFlow = combine(
            creditHourRecordRepository.observeForPublisher(publisherPersonId),
            monthlyGoalRepository.observeForPublisher(publisherPersonId),
        ) { records, goals -> records to goals }

        return combine(
            _dayStart,
            plannerDayRepository.observeForPublisher(publisherPersonId),
            interestedPersonRepository.observeAll(),
            visitRepository.observeAllForPublisher(publisherPersonId),
            creditAndGoalFlow,
        ) { dayStart, allDays, people, visits, creditAndGoal ->
            val (creditRecords, goals) = creditAndGoal
            val plannerDayId = PlannerDay.idFor(publisherPersonId, dayStart)
            val plannerDay = allDays.firstOrNull { it.id == plannerDayId }
            val periodRecords = buildPeriodRecords(publisherPersonId, DayBounds.of(dayStart), allDays, creditRecords, people, visits)

            val monthBounds = MonthBounds.of(dayStart)
            val calendar = Calendar.getInstance().apply { timeInMillis = dayStart }
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH) + 1
            val monthlyGoal = goals.firstOrNull { it.id == MonthlyPlannerGoal.idFor(publisherPersonId, year, month) }

            PlannerDayUiState(
                dayStart = dayStart,
                totalMinutes = plannerDay?.totalMinutes ?: 0,
                dailyGoalHours = plannerDay?.dailyGoalHours ?: 0,
                note = plannerDay?.note,
                returnVisitCount = MinistryStatisticsService.getDailyUniqueReturnVisits(publisherPersonId, people, visits, dayStart),
                bibleStudyCount = MinistryStatisticsService.getDailyUniqueBibleStudies(publisherPersonId, people, visits, dayStart),
                creditHourRecords = periodRecords.creditRecords,
                records = periodRecords,
                monthlyGoalHours = monthlyGoal?.goalHours ?: 0,
                monthlyConsumedMinutes = allDays.filter { monthBounds.contains(it.dayStart) }.sumOf { it.totalMinutes },
                isLoading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlannerDayUiState())
    }

    /** Footer "Hours goal for this month" Edit action — writes the same
     * [MonthlyPlannerGoal] document the Month tab's own goal stepper does,
     * for the month containing whatever day is currently showing. */
    fun setMonthlyGoalHours(publisherPersonId: String, goalHours: Int) {
        viewModelScope.launch {
            val calendar = Calendar.getInstance().apply { timeInMillis = _dayStart.value }
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH) + 1
            monthlyGoalRepository.save(
                MonthlyPlannerGoal(
                    id = MonthlyPlannerGoal.idFor(publisherPersonId, year, month),
                    publisherPersonId = publisherPersonId,
                    year = year,
                    month = month,
                    goalHours = goalHours.coerceAtLeast(0),
                    createdAt = nowMillis(),
                ),
            )
        }
    }

    /** Spec §18 — hour button moves by a full 60 minutes; [adjustMinutes] (the
     * minute button) moves by 1. Both share this one write path, so `1h 75m`-
     * style normalization is free (see [PlannerDayUiState.hours]/[minutes]) —
     * there is only ever one stored number. Spec §18 — "never allow negative
     * values": clamped at 0 total minutes, never below. */
    fun adjustHours(publisherPersonId: String, deltaHours: Int) = adjustTotalMinutes(publisherPersonId, deltaHours * 60)

    fun adjustMinutes(publisherPersonId: String, deltaMinutes: Int) = adjustTotalMinutes(publisherPersonId, deltaMinutes)

    private fun adjustTotalMinutes(publisherPersonId: String, delta: Int) {
        viewModelScope.launch { plannerDayRepository.addMinutes(publisherPersonId, _dayStart.value, delta) }
    }

    /** Manual entry alongside the +/- steppers — typing the Hours field
     * replaces just its own component of [PlannerDay.totalMinutes], leaving
     * the current Minutes value untouched (and vice versa for
     * [setMinutes]), so entering one never clobbers the other. */
    fun setHours(publisherPersonId: String, hours: Int) {
        viewModelScope.launch {
            val dayStart = _dayStart.value
            if (monthlyReportRepository.isMonthSubmitted(publisherPersonId, dayStart)) return@launch
            val current = plannerDayRepository.observeDay(publisherPersonId, dayStart).first()
            val minutesPart = (current?.totalMinutes ?: 0) % 60
            savePlannerDay(publisherPersonId, dayStart, current) { it.copy(totalMinutes = hours.coerceAtLeast(0) * 60 + minutesPart) }
        }
    }

    fun setMinutes(publisherPersonId: String, minutes: Int) {
        viewModelScope.launch {
            val dayStart = _dayStart.value
            if (monthlyReportRepository.isMonthSubmitted(publisherPersonId, dayStart)) return@launch
            val current = plannerDayRepository.observeDay(publisherPersonId, dayStart).first()
            val hoursPart = (current?.totalMinutes ?: 0) / 60
            savePlannerDay(publisherPersonId, dayStart, current) { it.copy(totalMinutes = hoursPart * 60 + minutes.coerceIn(0, 59)) }
        }
    }

    fun setDailyGoalHours(publisherPersonId: String, goalHours: Int) {
        viewModelScope.launch {
            val dayStart = _dayStart.value
            val current = plannerDayRepository.observeDay(publisherPersonId, dayStart).first()
            savePlannerDay(publisherPersonId, dayStart, current) { it.copy(dailyGoalHours = goalHours.coerceAtLeast(0)) }
        }
    }

    fun setNote(publisherPersonId: String, note: String?) {
        viewModelScope.launch {
            val dayStart = _dayStart.value
            val current = plannerDayRepository.observeDay(publisherPersonId, dayStart).first()
            savePlannerDay(publisherPersonId, dayStart, current) { it.copy(note = note?.ifBlank { null }) }
        }
    }

    private suspend fun savePlannerDay(
        publisherPersonId: String,
        dayStart: Long,
        current: PlannerDay?,
        transform: (PlannerDay) -> PlannerDay,
    ) {
        val base = current ?: PlannerDay(
            id = PlannerDay.idFor(publisherPersonId, dayStart),
            publisherPersonId = publisherPersonId,
            dayStart = dayStart,
            createdAt = nowMillis(),
        )
        plannerDayRepository.save(transform(base))
    }

    fun startVisitSync(publisherPersonId: String): Flow<Unit> = visitRepository.startRemoteSyncForPublisher(publisherPersonId)
}
