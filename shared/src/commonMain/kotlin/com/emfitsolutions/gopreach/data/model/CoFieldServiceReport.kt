package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/**
 * Where a congregation's Field Service Report for one service month stands with the Circuit Overseer.
 *
 *  NOT_SUBMITTED → (the congregation sends it) SUBMITTED → (the overseer receives it) RECEIVED
 *  SUBMITTED → (undo, before the overseer receives it) NOT_SUBMITTED
 *  SUBMITTED / RECEIVED → (the overseer returns it, with remarks) RETURNED → (corrected, sent again) SUBMITTED
 *
 * The report itself is never copied: it stays the congregation's own records (the publishers' monthly reports), the single
 * source of truth. This status only decides who may change those records and whether the overseer can see them.
 */
@kotlinx.serialization.Serializable
enum class CoReportStatus {
    NOT_SUBMITTED, SUBMITTED, RECEIVED, RETURNED;

    val label: String
        get() = when (this) {
            NOT_SUBMITTED -> "Not Submitted"
            SUBMITTED -> "Submitted"
            RECEIVED -> "Received"
            RETURNED -> "Returned"
        }

    /** SUBMITTED and RECEIVED freeze the month's records for everyone but the Super-Admin. */
    val locksMonth: Boolean get() = this == SUBMITTED || this == RECEIVED

    /** The Circuit Overseer sees a month only once it was submitted. */
    val visibleToCircuitOverseer: Boolean get() = this != NOT_SUBMITTED

    /** The congregation may (re)send the month. */
    val canSend: Boolean get() = this == NOT_SUBMITTED || this == RETURNED

    /** The congregation may undo a send the overseer has not received yet. */
    val canUndo: Boolean get() = this == SUBMITTED
}

/**
 * One service month's workflow record (`coFieldServiceMonthStatus/{congregationId}_{periodMonth}`): the status, who moved it
 * and when, and the Circuit Overseer's remarks for THAT month. There is exactly one per congregation and month, which is what
 * prevents a duplicate submission. It holds no report figures. A month with no document is NOT_SUBMITTED.
 */
@kotlinx.serialization.Serializable
data class CoMonthStatus(
    @field:DocumentId val id: String = "",
    val congregationId: String = "",
    /** First-of-month epoch millis of the service month. */
    val periodMonth: Long = 0L,
    val status: CoReportStatus = CoReportStatus.NOT_SUBMITTED,
    /** How many times the month has been sent (1 = first send). */
    val version: Int = 1,
    val submittedAt: Long = 0L,
    val submittedByPersonId: String = "",
    val submittedByName: String = "",
    val receivedAt: Long? = null,
    val receivedByPersonId: String? = null,
    val receivedByName: String? = null,
    val returnedAt: Long? = null,
    val returnedByPersonId: String? = null,
    val returnedByName: String? = null,
    val returnReason: String? = null,
    /** The Circuit Overseer's remarks for this service month — kept when the status changes, cleared only by an undo. */
    val coRemarks: String? = null,
    val coRemarksAt: Long? = null,
    val coRemarksByName: String? = null,
    val undoneAt: Long? = null,
    val undoneByPersonId: String? = null,
    val undoneByName: String? = null,
    val updatedAt: Long = 0L,
) {
    /** SUBMITTED / RECEIVED: no record of the month is added, edited or deleted. */
    val isLocked: Boolean get() = status.locksMonth
}

/** One permanent audit line (`coFieldServiceReportEvents/{id}`) — written together with every move, never edited. */
@kotlinx.serialization.Serializable
data class CoReportEvent(
    @field:DocumentId val id: String = "",
    val reportId: String = "",
    val congregationId: String = "",
    val periodMonth: Long = 0L,
    val at: Long = 0L,
    val userId: String = "",
    val userName: String = "",
    val userRole: String = "",
    val action: String = "",
    val fromStatus: String? = null,
    val toStatus: String = "",
    val version: Int = 1,
    /** The Circuit Overseer's remarks (or the reason for a return) at this step. */
    val remarks: String? = null,
    /** The remarks that were active before this step, when it replaced or cleared them. */
    val previousRemarks: String? = null,
)

fun coReportId(congregationId: String, periodMonth: Long) = "${congregationId}_$periodMonth"

/** Whether [congregationId]'s service month starting at [periodMonth] is locked (Submitted or Received). */
fun isMonthLocked(statuses: List<CoMonthStatus>, congregationId: String?, periodMonth: Long): Boolean =
    congregationId != null && statuses.any { it.id == coReportId(congregationId, periodMonth) && it.isLocked }

/** The message a locked month shows. */
const val MONTH_LOCKED_MESSAGE =
    "This Field Service Report has already been submitted to the Circuit Overseer. " +
        "You can no longer add, edit, or delete field-service records for this month."

/** What a device sees when an old change for a locked month tries to synchronize. */
const val MONTH_LOCKED_SYNC_MESSAGE =
    "This service month has already been submitted to the Circuit Overseer. Your changes cannot be synchronized."

const val FUTURE_MONTH_TITLE = "Cannot Submit Report"
const val FUTURE_MONTH_MESSAGE =
    "You cannot submit a Field Service Report for a future service month. Please wait until the service month has started."

/** A report month can be sent only if it is the current month or an earlier one. */
fun isFutureServiceMonth(periodMonth: Long, nowMillis: Long): Boolean = periodMonth > nowMillis

/**
 * Thrown by the repositories when a write targets a locked service month. It is a [kotlinx.coroutines.CancellationException]
 * on purpose: a screen that does not handle it just stops that one action quietly (never a crash), while the screens that
 * can explain it ([MONTH_LOCKED_MESSAGE]) catch it by type.
 */
class MonthLockedException : kotlinx.coroutines.CancellationException(MONTH_LOCKED_MESSAGE)
