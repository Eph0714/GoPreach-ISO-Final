package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.AttendanceRounding
import com.emfitsolutions.gopreach.data.model.CongregationMonthlyStatistics
import com.emfitsolutions.gopreach.data.model.MeetingAttendance
import com.emfitsolutions.gopreach.data.model.MeetingAttendanceEvent
import com.emfitsolutions.gopreach.data.model.MeetingAttendanceSettings
import com.emfitsolutions.gopreach.data.model.MeetingType
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import com.emfitsolutions.gopreach.domain.AttendanceCalculator
import com.emfitsolutions.gopreach.domain.DayBounds
import com.emfitsolutions.gopreach.domain.MonthBounds
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** What the Add / Edit attendance form hands over. [meetingDateAnyMillis] is any instant inside the meeting's day. */
data class MeetingAttendanceInput(
    val congregationId: String,
    val meetingType: MeetingType,
    val meetingDateAnyMillis: Long,
    /** The counts typed, in order (Midweek: Treasures, Apply Yourself, Living as Christians; Weekend: Public Meeting, Watchtower Study). */
    val parts: List<Int>,
    val remarks: String?,
)

sealed class AttendanceResult {
    data class Saved(val record: MeetingAttendance) : AttendanceResult()
    /** Refused with a message to show (invalid counts, duplicate date, frozen month, ...). */
    data class Refused(val message: String) : AttendanceResult()
}

const val FROZEN_MONTH_MESSAGE =
    "This month's statistics were received by the Circuit Overseer and are now historical. Its attendance can no longer be changed."

/**
 * Weekly meeting attendance, offline-first like every other collection: records are saved to the device at once and synchronized when
 * there is a connection; the server rules then re-check permissions, the calculation, the duplicate rule and the frozen months (an
 * offline device can never bypass them — a change the server refuses simply fails to sync). Every change also appends an audit line
 * (previous values, new values, who, when, why).
 */
class MeetingAttendanceRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
) {
    fun observeAll(): Flow<List<MeetingAttendance>> = offline.observeCollection(ATTENDANCE)
    fun observeSettings(): Flow<List<MeetingAttendanceSettings>> = offline.observeCollection(SETTINGS)
    fun observeEvents(): Flow<List<MeetingAttendanceEvent>> = offline.observeCollection<MeetingAttendanceEvent>(EVENTS).map { it.sortedByDescending { e -> e.at } }
    fun observeStatistics(): Flow<List<CongregationMonthlyStatistics>> = offline.observeCollection(STATISTICS)

    fun idFor(congregationId: String, type: MeetingType, meetingDate: Long) = "${congregationId}_${type.name}_$meetingDate"
    fun statisticsIdFor(congregationId: String, serviceMonth: Long) = "${congregationId}_$serviceMonth"

    suspend fun roundingFor(congregationId: String): AttendanceRounding =
        observeSettings().first().firstOrNull { it.congregationId == congregationId }?.roundingMode ?: AttendanceRounding.ROUNDED

    private fun describe(r: MeetingAttendance): String =
        r.parts.joinToString("/") + " → avg " + AttendanceCalculator.formatTwo(r.calculatedAverage) + " → official " +
            AttendanceCalculator.display(r.officialAttendance, r.roundingMode) + " (" + r.roundingMode.name + ")" + (r.remarks?.let { " · $it" } ?: "") +
            if (r.deleted) " · DELETED" else ""

    private suspend fun frozen(congregationId: String, serviceMonth: Long): Boolean =
        observeStatistics().first().any { it.id == statisticsIdFor(congregationId, serviceMonth) && it.isFrozen }

    private suspend fun audit(
        congregationId: String, recordId: String, action: String, actor: SubmissionActor, previous: String?, new: String?, reason: String?,
    ) {
        val id = remote.newId(EVENTS)
        offline.save(
            EVENTS, id,
            MeetingAttendanceEvent(
                id = id, congregationId = congregationId, recordId = recordId, action = action, at = nowMillis(),
                userId = actor.personId, userName = actor.name, userRole = actor.role, previousValues = previous, newValues = new, reason = reason,
            ),
        )
    }

    /** Adds a new meeting record, or — when [editing] — changes that one. The mean is always taken from the original counts first. */
    suspend fun save(input: MeetingAttendanceInput, editing: MeetingAttendance?, actor: SubmissionActor, reason: String? = null): AttendanceResult {
        if (input.parts.size != input.meetingType.partCount || input.parts.any { it < 0 }) {
            return AttendanceResult.Refused("Enter whole-number attendance (zero or more) for every part of the ${input.meetingType.label} Meeting.")
        }
        val date = DayBounds.of(input.meetingDateAnyMillis).startInclusive
        val month = MonthBounds.of(date).startInclusive
        val id = idFor(input.congregationId, input.meetingType, date)
        val existing = observeAll().first().firstOrNull { it.id == id }
        if (editing == null && existing != null && !existing.deleted) {
            return AttendanceResult.Refused("An attendance record for the ${input.meetingType.label} Meeting on that date already exists — edit it instead.")
        }
        if (editing != null && editing.id != id) return AttendanceResult.Refused("The meeting date and type of a record cannot be changed — delete it and add a new one.")
        if (frozen(input.congregationId, month)) return AttendanceResult.Refused(FROZEN_MONTH_MESSAGE)

        // A record keeps the rounding mode it was entered with; a new one (or a revived deleted one) uses the congregation's current setting.
        val mode = editing?.roundingMode ?: roundingFor(input.congregationId)
        val now = nowMillis()
        val base = (existing ?: MeetingAttendance(
            id = id, congregationId = input.congregationId, meetingType = input.meetingType, meetingDate = date, serviceMonth = month,
            createdBy = actor.personId, createdAt = now,
        )).copy(remarks = input.remarks?.trim()?.ifBlank { null }, deleted = false, updatedBy = actor.personId, updatedAt = now)
        val record = AttendanceCalculator.build(base, input.parts, mode)
        offline.save(ATTENDANCE, id, record)
        val wasActive = existing != null && !existing.deleted
        audit(
            input.congregationId, id, if (wasActive) "Attendance edited" else if (existing != null) "Attendance re-added" else "Attendance added", actor,
            existing?.takeIf { wasActive }?.let(::describe), describe(record), reason,
        )
        return AttendanceResult.Saved(record)
    }

    /** Soft delete: the record stays (marked deleted) with its audit trail, and the date can be entered again later. */
    suspend fun delete(record: MeetingAttendance, actor: SubmissionActor, reason: String?): AttendanceResult {
        if (frozen(record.congregationId, record.serviceMonth)) return AttendanceResult.Refused(FROZEN_MONTH_MESSAGE)
        val updated = record.copy(deleted = true, updatedBy = actor.personId, updatedAt = nowMillis())
        offline.save(ATTENDANCE, record.id, updated)
        audit(record.congregationId, record.id, "Attendance deleted", actor, describe(record), describe(updated), reason)
        return AttendanceResult.Saved(updated)
    }

    /**
     * Changes the congregation's rounding preference. Existing records are left alone by default (history never changes silently);
     * with [recalculateHistory] every record that is not frozen is re-derived from its original counts. Returns how many records changed.
     */
    suspend fun setRounding(congregationId: String, mode: AttendanceRounding, recalculateHistory: Boolean, actor: SubmissionActor): Int {
        val previous = roundingFor(congregationId)
        offline.save(
            SETTINGS, congregationId,
            MeetingAttendanceSettings(id = congregationId, congregationId = congregationId, roundingMode = mode, updatedBy = actor.personId, updatedAt = nowMillis()),
        )
        var changed = 0
        if (recalculateHistory) {
            val frozenMonths = observeStatistics().first().filter { it.congregationId == congregationId && it.isFrozen }.map { it.serviceMonth }.toSet()
            observeAll().first().filter { it.congregationId == congregationId && !it.deleted && it.serviceMonth !in frozenMonths && it.roundingMode != mode }.forEach { r ->
                val updated = AttendanceCalculator.recalculated(r, mode).copy(updatedBy = actor.personId, updatedAt = nowMillis())
                offline.save(ATTENDANCE, r.id, updated)
                changed++
            }
        }
        audit(
            congregationId, congregationId, "Rounding preference changed", actor, previous.name, mode.name,
            if (recalculateHistory) "Historical records recalculated: $changed" else "New records only",
        )
        return changed
    }

    /** The signed-in person's own congregation's weekly records. */
    fun startAttendanceSync(congregationId: String): Flow<Unit> =
        remote.mirror(ATTENDANCE, MeetingAttendance::class, equalTo = "congregationId" to congregationId) { it.id }

    /** The audit trail — only the congregation-wide roles may read it. */
    fun startEventsSync(congregationId: String): Flow<Unit> =
        remote.mirror(EVENTS, MeetingAttendanceEvent::class, equalTo = "congregationId" to congregationId) { it.id }

    /** The Super-Admin: every congregation. */
    fun startRemoteSyncAll(): Flow<Unit> = kotlinx.coroutines.flow.merge(
        remote.mirror(ATTENDANCE, MeetingAttendance::class) { it.id },
        remote.mirror(EVENTS, MeetingAttendanceEvent::class) { it.id },
    )

    /** Settings and the historical statistics are open to every signed-in account (they hold no personal data). */
    fun startSharedSync(): Flow<Unit> = kotlinx.coroutines.flow.merge(
        remote.mirror(SETTINGS, MeetingAttendanceSettings::class) { it.id },
        remote.mirror(STATISTICS, CongregationMonthlyStatistics::class) { it.id },
    )

    companion object {
        const val ATTENDANCE = "meetingAttendance"
        const val SETTINGS = "meetingAttendanceSettings"
        const val EVENTS = "meetingAttendanceEvents"
        const val STATISTICS = "congregationMonthlyStatistics"
    }
}
