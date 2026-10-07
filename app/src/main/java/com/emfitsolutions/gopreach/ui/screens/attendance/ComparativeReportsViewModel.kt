package com.emfitsolutions.gopreach.ui.screens.attendance

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.AttendanceRounding
import com.emfitsolutions.gopreach.data.model.ComparativeReport
import com.emfitsolutions.gopreach.data.model.ComparativeReportHistory
import com.emfitsolutions.gopreach.data.model.ComparativeReportRemark
import com.emfitsolutions.gopreach.data.model.ComparativeSnapshot
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.repository.CircuitResult
import com.emfitsolutions.gopreach.data.repository.ComparativePeriodsInput
import com.emfitsolutions.gopreach.data.repository.ComparativeReportRepository
import com.emfitsolutions.gopreach.data.repository.ComparativeReportService
import com.emfitsolutions.gopreach.data.repository.ComparativeSaveResult
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.MeetingAttendanceRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.SubmissionActor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Read model and actions behind the Comparative Reports list, the report view and the Circuit Overseer's review. */
class ComparativeReportsViewModel(
    private val reports: ComparativeReportRepository,
    private val service: ComparativeReportService,
    private val attendance: MeetingAttendanceRepository,
    private val congregationRepository: CongregationRepository,
    private val personRepository: PersonRepository,
    private val preferences: com.emfitsolutions.gopreach.data.repository.ReportSubmissionPreferences,
) : ViewModel() {

    /** The month range saved in Report Submission for this user, role and congregation (null when none was chosen yet). */
    fun savedRange(userId: String, role: String, congregationId: String?) = preferences.range(userId, role, congregationId)

    val congregations: Flow<List<Congregation>> = congregationRepository.observeAll()
        .map { list -> list.filter { it.status == RecordStatus.ACTIVE }.sortedBy { it.name } }

    /** The congregation's reports (the Circuit Overseer never receives drafts, so none show for them). */
    fun reportsFor(congregationId: String?): Flow<List<ComparativeReport>> = reports.observeReports().map { list ->
        if (congregationId == null) emptyList() else list.filter { it.congregationId == congregationId }
    }

    fun history(reportId: String): Flow<List<ComparativeReportHistory>> = reports.observeHistory(reportId)
    fun remarks(reportId: String): Flow<List<ComparativeReportRemark>> = reports.observeRemarks(reportId)

    fun statisticsFor(congregationId: String?): Flow<List<CongregationMonthlyStatistics>> = attendance.observeStatistics().map { list ->
        if (congregationId == null) emptyList() else list.filter { it.congregationId == congregationId }.sortedBy { it.serviceMonth }
    }

    fun rounding(congregationId: String?): Flow<AttendanceRounding> = attendance.observeSettings().map { list ->
        list.firstOrNull { it.congregationId == congregationId }?.roundingMode ?: AttendanceRounding.ROUNDED
    }

    suspend fun actor(personId: String, role: String): SubmissionActor = SubmissionActor(personId, personRepository.get(personId)?.fullName ?: personId, role)
    suspend fun generatedBy(personId: String): String = personRepository.get(personId)?.fullName ?: personId

    suspend fun createDraft(
        congregationId: String, periods: ComparativePeriodsInput, snapshot: ComparativeSnapshot, sourceIds: List<String>, mode: AttendanceRounding,
        personId: String, role: String, year: Int,
    ): ComparativeSaveResult = reports.createDraft(congregationId, periods, snapshot, sourceIds, mode.name, actor(personId, role), year)

    suspend fun updateSnapshot(
        report: ComparativeReport, snapshot: ComparativeSnapshot, sourceIds: List<String>, mode: AttendanceRounding, personId: String, role: String, action: String,
    ): ComparativeSaveResult = reports.updateSnapshot(report, snapshot, sourceIds, mode.name, actor(personId, role), action)

    suspend fun changePeriods(
        report: ComparativeReport, periods: ComparativePeriodsInput, snapshot: ComparativeSnapshot, sourceIds: List<String>, mode: AttendanceRounding,
        personId: String, role: String, year: Int,
    ): ComparativeSaveResult = reports.changePeriods(report, periods, snapshot, sourceIds, mode.name, actor(personId, role), year)

    suspend fun deleteDraft(report: ComparativeReport, personId: String, role: String) = reports.deleteDraft(report, actor(personId, role))
    suspend fun logExport(report: ComparativeReport, kind: String, personId: String, role: String) = reports.logExport(report, kind, actor(personId, role))

    suspend fun submit(report: ComparativeReport, personId: String, role: String): CircuitResult = service.submit(report, actor(personId, role))
    suspend fun receive(reportId: String, personId: String, role: String): CircuitResult = service.receive(reportId, actor(personId, role))
    suspend fun returnForCorrection(reportId: String, reason: String, personId: String, role: String): CircuitResult =
        service.returnForCorrection(reportId, reason, actor(personId, role))
    suspend fun addRemark(reportId: String, remark: String, personId: String, role: String): CircuitResult = service.addRemark(reportId, remark, actor(personId, role))
}
