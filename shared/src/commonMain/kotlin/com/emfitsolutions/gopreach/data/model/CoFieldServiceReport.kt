package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/**
 * Where a congregation's Field Service Report for one service month stands with the Circuit Overseer. Every month has its own status.
 *
 *  NOT_SUBMITTED → (the congregation sends a snapshot) SUBMITTED, "Sent to CO - awaiting receipt" → (the overseer receives it) RECEIVED
 *  SUBMITTED → (the congregation sends an updated snapshot) SUBMITTED with the next version
 *  SUBMITTED → (undo, before the overseer receives it) NOT_SUBMITTED
 *  SUBMITTED / RECEIVED → (the overseer returns it, with a reason) RETURNED → (corrected, sent again) SUBMITTED
 *  SUBMITTED / RECEIVED → (the overseer cancels the submission, with a reason) CANCELLED → (sent again) SUBMITTED
 *
 * What the overseer reads is the frozen snapshot of each send (see [CoReceivedReport]); the congregation's own records stay the live
 * data. While a snapshot is only SUBMITTED the live records remain editable; RECEIVED locks them until the overseer returns the month.
 */
@kotlinx.serialization.Serializable
enum class CoReportStatus {
    NOT_SUBMITTED, SUBMITTED, RECEIVED, RETURNED, CANCELLED;

    val label: String
        get() = when (this) {
            NOT_SUBMITTED -> "Not Submitted"
            SUBMITTED -> "Submitted"
            RECEIVED -> "Received"
            RETURNED -> "Returned"
            CANCELLED -> "Cancelled"
        }

    /** What the congregation sees for the month ("Sent to CO - Awaiting Receipt", ...). */
    val workflowLabel: String
        get() = when (this) {
            NOT_SUBMITTED -> "Draft"
            SUBMITTED -> "Sent to CO - Awaiting Receipt"
            RECEIVED -> "Received by CO"
            RETURNED -> "Returned for Editing"
            CANCELLED -> "Cancelled by CO"
        }

    /** Only RECEIVED freezes the month's records for everyone but the Super-Admin; a snapshot that is just awaiting receipt does not. */
    val locksMonth: Boolean get() = this == RECEIVED

    /** The Circuit Overseer sees a month only once it was submitted. */
    val visibleToCircuitOverseer: Boolean get() = this != NOT_SUBMITTED

    /** The congregation may (re)send the month. */
    val canSend: Boolean get() = this == NOT_SUBMITTED || this == RETURNED || this == CANCELLED

    /** The congregation may send an updated snapshot (the next version) while the previous one is still awaiting receipt. */
    val canSendUpdate: Boolean get() = this == SUBMITTED

    /** The Circuit Overseer may return the month for editing. */
    val canReturn: Boolean get() = this == SUBMITTED || this == RECEIVED

    /** The Circuit Overseer may cancel the submission. */
    val canCancel: Boolean get() = this == SUBMITTED || this == RECEIVED

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
    /** The Circuit Overseer cancelled the submission: who, when, why, and which status it was cancelled from (a Received month stays locked). */
    val cancelledAt: Long? = null,
    val cancelledByPersonId: String? = null,
    val cancelledByName: String? = null,
    val cancelReason: String? = null,
    val cancelledFromStatus: String? = null,
    val updatedAt: Long = 0L,
) {
    /**
     * RECEIVED: no record of the month is added, edited or deleted. Cancelling a received submission never silently unlocks the month,
     * so CANCELLED keeps the lock when it was cancelled from RECEIVED; only the overseer returning the month for editing unlocks it.
     */
    val isLocked: Boolean get() = status.locksMonth || (status == CoReportStatus.CANCELLED && cancelledFromStatus == CoReportStatus.RECEIVED.name)

    /**
     * The congregation may delete a send's snapshots (taking the month back to Draft) while the Circuit Overseer has NOT received it: awaiting receipt,
     * returned for editing, or cancelled from a state that was not locked. A Received month - or one cancelled from Received - can never be deleted.
     */
    val canDeleteSubmission: Boolean get() = status == CoReportStatus.SUBMITTED || status == CoReportStatus.RETURNED || (status == CoReportStatus.CANCELLED && !isLocked)
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
    "This Field Service Report has already been received by the Circuit Overseer. " +
        "You can no longer add, edit, or delete field-service records for this month unless the Circuit Overseer returns it for editing."

/** What a device sees when an old change for a locked month tries to synchronize. */
const val MONTH_LOCKED_SYNC_MESSAGE =
    "This service month has already been received by the Circuit Overseer. Your changes cannot be synchronized."

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
