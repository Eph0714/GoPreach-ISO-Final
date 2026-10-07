package com.emfitsolutions.gopreach.ui.screens.attendance

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.model.AttendanceRounding
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics
import com.emfitsolutions.gopreach.data.model.MeetingAttendance
import com.emfitsolutions.gopreach.data.model.MeetingAttendanceEvent
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.repository.AttendanceResult
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.MeetingAttendanceInput
import com.emfitsolutions.gopreach.data.repository.MeetingAttendanceRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.SubmissionActor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Read model and actions behind the Meeting Attendance screen and the Congregation Comparative Report. */
class MeetingAttendanceViewModel(
    private val repository: MeetingAttendanceRepository,
    private val congregationRepository: CongregationRepository,
    private val personRepository: PersonRepository,
) : ViewModel() {

    val congregations: Flow<List<Congregation>> = congregationRepository.observeAll()
        .map { list -> list.filter { it.status == RecordStatus.ACTIVE }.sortedBy { it.name } }

    /** Every record of [congregationId], newest meeting first (deleted ones included — the screen decides whether to show them). */
    fun recordsFor(congregationId: String?): Flow<List<MeetingAttendance>> = repository.observeAll().map { list ->
        if (congregationId == null) emptyList() else list.filter { it.congregationId == congregationId }.sortedByDescending { it.meetingDate }
    }

    fun rounding(congregationId: String?): Flow<AttendanceRounding> = repository.observeSettings().map { list ->
        list.firstOrNull { it.congregationId == congregationId }?.roundingMode ?: AttendanceRounding.ROUNDED
    }

    /** The congregation's months whose statistics were received (frozen): their attendance is history. */
    fun frozenMonths(congregationId: String?): Flow<Set<Long>> = repository.observeStatistics().map { list ->
        list.filter { it.congregationId == congregationId && it.isFrozen }.map { it.serviceMonth }.toSet()
    }

    fun statisticsFor(congregationId: String?): Flow<List<CongregationMonthlyStatistics>> = repository.observeStatistics().map { list ->
        if (congregationId == null) emptyList() else list.filter { it.congregationId == congregationId }.sortedBy { it.serviceMonth }
    }

    fun history(recordId: String): Flow<List<MeetingAttendanceEvent>> = repository.observeEvents().map { list -> list.filter { it.recordId == recordId } }

    private suspend fun actor(personId: String, role: String): SubmissionActor =
        SubmissionActor(personId, personRepository.get(personId)?.fullName ?: personId, role)

    suspend fun save(input: MeetingAttendanceInput, editing: MeetingAttendance?, personId: String, role: String, reason: String?): AttendanceResult =
        repository.save(input, editing, actor(personId, role), reason)

    suspend fun delete(record: MeetingAttendance, personId: String, role: String, reason: String?): AttendanceResult =
        repository.delete(record, actor(personId, role), reason)

    suspend fun setRounding(congregationId: String, mode: AttendanceRounding, recalculateHistory: Boolean, personId: String, role: String): Int =
        repository.setRounding(congregationId, mode, recalculateHistory, actor(personId, role))

    suspend fun generatedBy(personId: String): String = personRepository.get(personId)?.fullName ?: personId
}
