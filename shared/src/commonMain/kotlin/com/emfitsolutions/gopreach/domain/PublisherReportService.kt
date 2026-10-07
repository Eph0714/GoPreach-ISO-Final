package com.emfitsolutions.gopreach.domain

import kotlin.math.roundToLong

import com.emfitsolutions.gopreach.data.model.CreditHourCategory
import com.emfitsolutions.gopreach.data.model.CreditHourRecord
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PlannerDay
import com.emfitsolutions.gopreach.data.model.PreachingTimeRecord
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.ReportStatus
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.Visit
import com.emfitsolutions.gopreach.data.repository.CreditHourCategoryRepository
import com.emfitsolutions.gopreach.data.repository.CreditHourRecordRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.PlannerDayRepository
import com.emfitsolutions.gopreach.data.repository.PreachingTimeRecordRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.VisitRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Everything the report screens need for one Publisher and month, from one calculation. */
data class PublisherReportSource(
    val person: Person?,
    val category: PublisherCategory?,
    val congregationId: String?,
    val existingReport: MonthlyReport?,
    val periodMonth: Long,
    /** The month's total ministry time — the single figure everything else derives from. */
    val totalMinutes: Int,
    val bibleStudies: Int,
    val returnVisits: Int,
    /** Pioneer only: the Preaching Time Record total in minutes, for the differs-from-system check. */
    val systemMinutes: Int?,
    val defaultRemarks: String,
) {
    /** The finished report (Pioneer: converted hours; others: attended Yes/No). */
    fun toReport(remarks: String = defaultRemarks): PublisherReport = PublisherReportCalculator.build(
        firstName = person?.firstName.orEmpty(),
        lastName = person?.lastName.orEmpty(),
        category = category,
        periodMonth = periodMonth,
        totalMinutes = totalMinutes,
        bibleStudies = bibleStudies,
        remarks = remarks,
    )
}

/**
 * The one place a Publisher's report for a month is worked out. Send Report / Open My
 * Report ([com.emfitsolutions.gopreach.ui.screens.monthlyreport.MonthlyReportViewModel]),
 * Preview Report and Send as Text (the Planner) all read this, and it recalculates from
 * the latest records every time they open.
 */
class PublisherReportService(
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val monthlyReportRepository: MonthlyReportRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val visitRepository: VisitRepository,
    private val preachingTimeRecordRepository: PreachingTimeRecordRepository,
    private val plannerDayRepository: PlannerDayRepository,
    private val creditHourRecordRepository: CreditHourRecordRepository,
    private val creditHourCategoryRepository: CreditHourCategoryRepository,
) {
    /**
     * [useStoredValues] keeps the report's saved figures instead of recalculating — for
     * an Elder correcting a report, and for a report the Service Overseer has already
     * posted (what was actually recorded must not change underneath it).
     */
    fun observe(
        publisherPersonId: String,
        periodMonth: Flow<Long>,
        useStoredValues: Boolean = false,
    ): Flow<PublisherReportSource> = combine(
        personRepository.observeAll(),
        roleAssignmentRepository.observeForPerson(publisherPersonId),
        monthlyReportRepository.observeAll(),
        interestedPersonRepository.observeAll(),
        visitRepository.observeAllForPublisher(publisherPersonId),
        preachingTimeRecordRepository.observeForPublisher(publisherPersonId),
        plannerDayRepository.observeForPublisher(publisherPersonId),
        creditHourRecordRepository.observeForPublisher(publisherPersonId),
        creditHourCategoryRepository.observeAll(),
        periodMonth,
    ) { flows ->
        @Suppress("UNCHECKED_CAST") val people = flows[0] as List<Person>
        @Suppress("UNCHECKED_CAST") val assignments = flows[1] as List<RoleAssignment>
        @Suppress("UNCHECKED_CAST") val reports = flows[2] as List<MonthlyReport>
        @Suppress("UNCHECKED_CAST") val interestedPeople = flows[3] as List<InterestedPerson>
        @Suppress("UNCHECKED_CAST") val visits = flows[4] as List<Visit>
        @Suppress("UNCHECKED_CAST") val preachingTimeRecords = flows[5] as List<PreachingTimeRecord>
        @Suppress("UNCHECKED_CAST") val plannerDays = flows[6] as List<PlannerDay>
        @Suppress("UNCHECKED_CAST") val creditRecords = flows[7] as List<CreditHourRecord>
        @Suppress("UNCHECKED_CAST") val creditCategories = flows[8] as List<CreditHourCategory>
        val month = flows[9] as Long

        val publisherAssignment = assignments.firstOrNull { it.resolvedRoleTypeOrNull() is RoleType.Publisher }
        val category = (publisherAssignment?.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category
        val congregationId = publisherAssignment?.congregationId
        val existing = reports.firstOrNull { it.publisherPersonId == publisherPersonId && it.periodMonth == month }
        val isPioneer = MonthlyReportCalculator.isPioneerCategory(category)
        val bounds = MonthBounds.of(month)

        val ownPeople = interestedPeople.filter {
            it.publisherPersonId == publisherPersonId && (congregationId == null || it.congregationId == congregationId)
        }
        val ownPreachingTimeRecords = preachingTimeRecords.filter { congregationId == null || it.congregationId == congregationId }
        val calc = MonthlyReportCalculator.calculate(
            publisherPersonId = publisherPersonId,
            category = category,
            ownPeople = ownPeople,
            allVisitsForPublisher = visits,
            preachingTimeRecords = ownPreachingTimeRecords,
            periodMonthStart = month,
        )

        val plannerMinutes = plannerDays.filter { bounds.contains(it.dayStart) }.sumOf { it.totalMinutes }
        // A Pioneer's official hours are the Preaching Time Records; if none were
        // recorded for the month, the Planner's logged time is the Monthly Report's hours.
        val preachingMinutes = if (isPioneer) (((calc.systemCalculatedHours ?: 0.0) * 60)).roundToLong().toInt() else 0

        // A Pioneer's Credit Hours count toward their total hours.
        val monthCreditRecords = creditRecords.filter { it.publisherPersonId == publisherPersonId && bounds.contains(it.resolvedDayStart()) }
        val creditMinutes = if (isPioneer) monthCreditRecords.sumOf { it.totalMinutes } else 0
        val freshMinutes = (if (preachingMinutes > 0) preachingMinutes else plannerMinutes) + creditMinutes
        val systemMinutes = if (isPioneer) freshMinutes else null

        val keepStored = existing != null && (useStoredValues || existing.status == ReportStatus.POSTED)
        val totalMinutes = if (keepStored) (((existing!!.hoursRendered ?: 0.0) * 60)).roundToLong().toInt() else freshMinutes

        // The Credit Hour names go into the Remarks.
        val creditNames = monthCreditRecords
            .mapNotNull { record -> creditCategories.firstOrNull { it.id == record.categoryId }?.name }
            .distinct()
        val defaultRemarks = existing?.remarks?.takeIf { it.isNotBlank() }
            ?: if (isPioneer && creditNames.isNotEmpty()) "Credit Hour: ${creditNames.joinToString(", ")}" else ""

        PublisherReportSource(
            person = people.firstOrNull { it.id == publisherPersonId },
            category = category,
            congregationId = congregationId,
            existingReport = existing,
            periodMonth = month,
            totalMinutes = totalMinutes,
            bibleStudies = if (keepStored) existing!!.bibleStudiesCount else calc.bibleStudiesConducted,
            returnVisits = if (keepStored) existing!!.returnVisitsCount else calc.returnVisitsConducted,
            systemMinutes = systemMinutes,
            defaultRemarks = defaultRemarks,
        )
    }
}
