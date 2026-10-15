package com.emfitsolutions.gopreach.ui.screens.fieldservicereport

import com.emfitsolutions.gopreach.data.model.CoCategoryChange
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.domain.ResolvedCategory

/** The layout / calculation rules new snapshots are produced under (2: each line carries the publisher id and the category of that month). */
const val SNAPSHOT_SCHEMA_VERSION = 2

/** A publisher whose service category in [month] is not on record and must be confirmed by the Admin before that month is sent. */
data class CategoryNeed(
    val personId: String,
    val name: String,
    val month: Long,
    /** The publisher's category in their profile today. Offered as a one-tap choice, but only used if the Admin confirms it. */
    val current: com.emfitsolutions.gopreach.data.model.PublisherCategory? = null,
)

/**
 * What sending one service month's snapshot involves, worked out BEFORE anything is sent: how many publishers it covers, the
 * category each had in that month (and where that came from), the ones that need the Admin's confirmation, and the publishers
 * whose category differs from the month before.
 */
data class MonthSnapshotPlan(
    val month: Long,
    val publisherCount: Int,
    /** publisher id -> that month's category resolution (category null = needs confirmation). */
    val resolved: Map<String, ResolvedCategory>,
    val needs: List<CategoryNeed>,
    val changes: List<CoCategoryChange>,
)

enum class MonthSendState { SENT, ALREADY_SENT, FAILED }

/** The outcome of one month of a (possibly multi-month) send. A month that was already saved is never sent a second time. */
data class MonthSendResult(
    val month: Long,
    val state: MonthSendState,
    val message: String? = null,
    /** The snapshot version that was sent (or the month's current one when it was not sent). */
    val version: Int? = null,
    val at: Long? = null,
)

/** What checking one selected month found, before sending. */
enum class MonthCheck(val label: String) {
    READY("Ready"),
    SEND_UPDATE("Updated snapshot"),
    RESEND_AFTER_RETURN("Corrected report"),
    RESEND_AFTER_CANCEL("Send again"),
    NO_CHANGES("Nothing new"),
    ALREADY_RECEIVED("Already received"),
    FUTURE("Future month"),
    NO_DATA("No report data"),
    PUBLISHERS_PENDING("Publishers pending"),
}

/** One month's pre-submission validation. [version] is the month's current version (0 = never sent): the send is made against exactly that. */
data class MonthValidation(
    val month: Long,
    val check: MonthCheck,
    val status: CoReportStatus,
    val version: Int,
    val message: String,
    val needsConfirmation: Int = 0,
) {
    val sendable: Boolean get() = check == MonthCheck.READY || check == MonthCheck.SEND_UPDATE || check == MonthCheck.RESEND_AFTER_RETURN || check == MonthCheck.RESEND_AFTER_CANCEL
}
