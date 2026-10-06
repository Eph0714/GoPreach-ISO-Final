package com.emfitsolutions.gopreach.ui.screens.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.CreditHourCategory
import com.emfitsolutions.gopreach.data.model.CreditHourRecord
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.PlannerDay
import com.emfitsolutions.gopreach.data.model.Visit
import com.emfitsolutions.gopreach.data.repository.CreditHourCategoryRepository
import com.emfitsolutions.gopreach.data.repository.CreditHourRecordRepository
import com.emfitsolutions.gopreach.domain.DayBounds
import com.emfitsolutions.gopreach.domain.MinistryStatisticsService
import com.emfitsolutions.gopreach.domain.MinistryStatisticsService.PersonActivitySummary
import com.emfitsolutions.gopreach.domain.TimeBounds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The individual records behind one planner period's summary figures, so
 * every Day/Week/Month/Year total can be expanded into the rows it was
 * computed from and each row tapped open. Built from the exact same lists
 * (and, for Return Visits/Bible Studies, the exact same unique-person rule
 * via [MinistryStatisticsService.personActivitySummaries]) the totals
 * themselves use — never a separate copy of the data.
 */
data class PlannerPeriodRecords(
    /** Days in the period with any logged ministry minutes, newest first. */
    val hourDays: List<PlannerDay> = emptyList(),
    val creditRecords: List<CreditHourRecord> = emptyList(),
    /** One row per unique person — `size` always equals the period's
     * Return Visit count. */
    val returnVisits: List<PersonActivitySummary> = emptyList(),
    /** One row per unique person — `size` always equals the period's Bible
     * Study count. */
    val bibleStudies: List<PersonActivitySummary> = emptyList(),
)

fun buildPeriodRecords(
    publisherPersonId: String,
    bounds: TimeBounds,
    allDays: List<PlannerDay>,
    creditRecords: List<CreditHourRecord>,
    people: List<InterestedPerson>,
    visits: List<Visit>,
): PlannerPeriodRecords = PlannerPeriodRecords(
    hourDays = allDays.filter { it.totalMinutes > 0 && bounds.contains(it.dayStart) }.sortedByDescending { it.dayStart },
    creditRecords = creditRecords.inPeriod(bounds).sortedWith(compareByDescending<CreditHourRecord> { it.resolvedDayStart() }.thenByDescending { it.createdAt }),
    returnVisits = MinistryStatisticsService.personActivitySummaries(publisherPersonId, people, visits, PipelineStage.RETURN_VISIT, bounds),
    bibleStudies = MinistryStatisticsService.personActivitySummaries(publisherPersonId, people, visits, PipelineStage.BIBLE_STUDY, bounds),
)

/** Credit Hour entries whose own date falls in [bounds] — keyed off
 * [CreditHourRecord.resolvedDayStart], so an entry counts toward its period
 * whether or not a [PlannerDay] document exists for that date. */
fun List<CreditHourRecord>.inPeriod(bounds: TimeBounds): List<CreditHourRecord> =
    filter { bounds.contains(it.resolvedDayStart()) }

/**
 * Credit Hour add/edit/delete plus the category list, shared by every
 * planner view so the dropdown and the write path exist exactly once.
 * Categories come from the admin-managed `creditHourCategories` collection
 * (see [CreditHourCategoryRepository]) — never a hard-coded list.
 */
private const val SUBMITTED_MONTH_MESSAGE = "This Record is Already Submitted"

class CreditHourEntryViewModel(
    private val recordRepository: CreditHourRecordRepository,
    private val categoryRepository: CreditHourCategoryRepository,
    private val monthlyReportRepository: com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository,
) : ViewModel() {

    init {
        // Makes sure the starting list (LDC, HCL, Bethel, ...) exists — a
        // server-checked, one-time, idempotent write; no-op once seeded.
        viewModelScope.launch { categoryRepository.ensureDefaultCategories() }
    }

    /** `null` until the local cache has answered at least once, so the
     * dialog can tell "still loading" apart from "none configured". */
    val activeCategories: StateFlow<List<CreditHourCategory>?> = categoryRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Includes deactivated categories, so an older entry whose category
     * was later switched off still shows its real name. */
    val allCategories: StateFlow<List<CreditHourCategory>> = categoryRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Creates a new entry when [existing] is null, otherwise updates that
     * same document in place (same id) — every planner view reads the one
     * `creditHourRecords` collection, so the change shows up everywhere. */
    fun save(
        publisherPersonId: String,
        existing: CreditHourRecord?,
        categoryId: String,
        dayStart: Long,
        hours: Int,
        minutes: Int,
        note: String?,
    ) {
        val alignedDay = DayBounds.of(dayStart).startInclusive
        val normalizedTotal = (hours.coerceAtLeast(0) * 60) + minutes.coerceAtLeast(0)
        val now = System.currentTimeMillis()
        val base = existing ?: CreditHourRecord(
            publisherPersonId = publisherPersonId,
            createdAt = now,
            createdByPersonId = publisherPersonId,
        )
        val record = base.copy(
            plannerDayId = PlannerDay.idFor(base.publisherPersonId.ifBlank { publisherPersonId }, alignedDay),
            dayStart = alignedDay,
            categoryId = categoryId,
            hours = normalizedTotal / 60,
            minutes = normalizedTotal % 60,
            note = note?.trim()?.ifBlank { null },
            updatedAt = now,
        )
        viewModelScope.launch {
            // A month whose report is already submitted is closed — for the new date and the old one.
            if (monthlyReportRepository.isMonthSubmitted(publisherPersonId, alignedDay) ||
                (existing != null && monthlyReportRepository.isMonthSubmitted(publisherPersonId, existing.resolvedDayStart()))
            ) {
                _messages.trySend(SUBMITTED_MONTH_MESSAGE)
                return@launch
            }
            runCatching { recordRepository.save(record) }
                .onSuccess { _messages.trySend(if (existing == null) "Credit Hours saved." else "Credit Hours updated.") }
                .onFailure { _messages.trySend("Could not save Credit Hours: ${it.message ?: "unknown error"}") }
        }
    }

    fun delete(record: CreditHourRecord) {
        viewModelScope.launch {
            if (monthlyReportRepository.isMonthSubmitted(record.publisherPersonId, record.resolvedDayStart())) {
                _messages.trySend(SUBMITTED_MONTH_MESSAGE)
                return@launch
            }
            runCatching { recordRepository.delete(record.id) }
                .onSuccess { _messages.trySend("Credit Hours entry deleted.") }
                .onFailure { _messages.trySend("Could not delete entry: ${it.message ?: "unknown error"}") }
        }
    }
}

/** Per-day regular ministry minutes for every [PlannerDay] in [bounds] that
 * has any (Monthly Calendar/List view's per-day figure) — Credit Hours are
 * computed separately via [dailyCreditMinutes] and never merged into this,
 * matching the app's existing "Credit Hours stay separate" rule. */
fun dailyMinutes(allDays: List<PlannerDay>, bounds: TimeBounds): Map<Long, Int> =
    allDays.filter { it.totalMinutes > 0 && bounds.contains(it.dayStart) }.associate { it.dayStart to it.totalMinutes }

/** Per-day Credit Hours total for every day in [bounds] that has any. */
fun dailyCreditMinutes(creditRecords: List<CreditHourRecord>, bounds: TimeBounds): Map<Long, Int> =
    creditRecords.inPeriod(bounds).groupBy { it.resolvedDayStart() }.mapValues { (_, records) -> records.sumOf { it.totalMinutes } }

/** Per-day unique-person count at [stage] (Return Visit or Bible Study) for
 * each of [dayStarts] that has any — same [MinistryStatisticsService
 * .uniqueVisitedPersons] rule every other count in this app uses, just
 * independently re-run per day (spec §32/§37: a daily count is its own
 * day's unique persons, never a slice of the month's). Days with zero are
 * left out, matching [dailyMinutes]/[dailyCreditMinutes]'s own shape. */
fun dailyUniquePersonCounts(
    publisherPersonId: String,
    people: List<InterestedPerson>,
    visits: List<Visit>,
    stage: PipelineStage,
    dayStarts: List<Long>,
): Map<Long, Int> = dayStarts
    .associateWith { day -> MinistryStatisticsService.uniqueVisitedPersons(publisherPersonId, people, visits, stage, DayBounds.of(day)) }
    .filterValues { it > 0 }
