package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId

/** [POSTED] — "allow the publisher to edit the record until the service
 * overseer will mark it as 'Posted', that's the time the publisher can no
 * longer edit the record": a Publisher may freely edit their own report
 * through both DRAFT and SUBMITTED; [POSTED] is the one status that
 * actually locks them out. Only a Service Overseer/Admin/Super-Admin marks
 * a report Posted (see [com.emfitsolutions.gopreach.ui.screens
 * .publisherreports.ManagePublisherReportsViewModel.markPosted]) — Submit
 * itself never sets this, unlike the old DRAFT/SUBMITTED-only model where
 * Submit alone was what locked the Publisher out.
 *
 * My Planner / Reporting upgrade spec §35 — extends this additively with
 * [RETURNED] and [CORRECTED], mapped onto the spec's own
 * DRAFT/SUBMITTED/REVIEWED/RETURNED/CORRECTED vocabulary as: [POSTED] is
 * spec's "REVIEWED" (a report an authorized reviewer has approved and
 * locked — unchanged name/meaning, since it's already persisted Firestore
 * data on every existing report; renaming it would break every historical
 * document's deserialization for zero benefit). [RETURNED] — new — is an
 * authorized reviewer explicitly sending a SUBMITTED report back with a
 * reason ([MonthlyReport.correctionReason]), distinct from the older
 * [com.emfitsolutions.gopreach.ui.screens.publisherreports
 * .ManagePublisherReportsViewModel.unlock] (POSTED→DRAFT, no reason
 * recorded, still kept for that simpler "let them re-edit" case). [CORRECTED]
 * — new — is what the Publisher's own resubmission from RETURNED becomes
 * (instead of a plain SUBMITTED), so the audit trail shows this was a
 * correction, not an original submission. */
enum class ReportStatus { DRAFT, SUBMITTED, POSTED, RETURNED, CORRECTED }

const val SOURCE_PUBLISHER = "PUBLISHER"
const val SOURCE_MANUAL = "MANUAL"

/**
 * One publisher's monthly ministry report (spec §5.2). Required fields differ by
 * [PublisherCategory] — the UI shows only the fields that category needs, but they
 * all share this shape so reporting/aggregation stays uniform:
 *
 * | Category              | Uses                                              |
 * |------------------------|---------------------------------------------------|
 * | Regular Pioneer        | bibleStudiesCount, hoursRendered (participatedInPreaching left null — not asked) |
 * | Auxiliary Pioneer      | bibleStudiesCount, hoursRendered (+ active date range, see [AuxiliaryPioneerRange]; participatedInPreaching left null — not asked) |
 * | Regular Publisher      | bibleStudiesCount, participatedInPreaching         |
 * | Unbaptized Publisher   | bibleStudiesCount, participatedInPreaching         |
 *
 * "Update Monthly Report Submission — Automatic Bible Study Count and
 * Preaching Participation" — [bibleStudiesCount] is no longer typed in by the
 * Publisher at all: it's the count of *distinct* [InterestedPerson] (see that
 * class's own doc comment on why a "Bible Study" is just one of its
 * [PipelineStage] values, not a separate collection) owned by
 * [publisherPersonId] with [InterestedPerson.pipelineStage] ==
 * [PipelineStage.BIBLE_STUDY] that had at least one qualifying [Visit] during
 * [periodMonth] — never the number of Visit rows themselves (see
 * [com.emfitsolutions.gopreach.domain.MonthlyReportCalculator] for the actual
 * calculation, shared with [participatedInPreaching]'s own suggested value
 * and [systemCalculatedHours]). [participatedInPreaching] is only asked of
 * the non-Pioneer categories (Regular Publisher, Unbaptized Publisher) — a
 * Pioneer already reports actual hours, so the question is left `null` for
 * them (see the table above) — pre-filled from the same calculation but
 * still a Publisher-editable Yes/No.
 *
 * Lock semantics: editable by [publisherPersonId] themselves while [status]
 * is DRAFT *or* SUBMITTED — locked out only once [ReportStatus.POSTED] (see
 * that enum value's own doc comment). A Service Overseer, Admin (own
 * congregation only), or Super-Admin (every congregation) may edit a report
 * at any status, Posted included — "if there is still a correction needed,
 * the Service Overseer will do the edition; the Admin and Super-Admin can
 * do the same."
 *
 * Firestore collection: `monthlyReports/{reportId}`
const val SOURCE_PUBLISHER = "PUBLISHER"
const val SOURCE_MANUAL = "MANUAL"

 */
data class MonthlyReport(
    @DocumentId val id: String = "",
    val publisherPersonId: String = "",
    val congregationId: String = "",
    val category: PublisherCategory = PublisherCategory.REGULAR_PUBLISHER,

    /** Report period, first-of-month epoch millis (e.g. 2026-08-01). */
    val periodMonth: Long = 0L,

    /** Automatically calculated (see this class's own doc comment) — never a
     * free-text field the Publisher types into. */
    val bibleStudiesCount: Int = 0,

    /** My Planner / Ministry Statistics upgrade — same "automatically
     * calculated, never free-text" treatment as [bibleStudiesCount], via
     * [com.emfitsolutions.gopreach.domain.MinistryStatisticsService]
     * .getMonthlyUniqueReturnVisits. `0` for a report saved before this field
     * existed, same as any other Firestore default on an old document —
     * never retroactively recalculated for a historical report. */
    val returnVisitsCount: Int = 0,

    // Pioneer-only field — the Publisher's own final, submitted total (what
    // every existing consumer of this field — Dashboard totals, Consolidated
    // Report, ManagePublisherReportsScreen — already reads). May equal
    // [systemCalculatedHours] (accepted as-is) or differ from it (a manual
    // adjustment, which then requires [hoursConfirmed] + non-blank
    // [hoursAdjustmentRemarks] — enforced both client-side and in
    // firestore.rules' `monthlyReports` write rule).
    val hoursRendered: Double? = null,

    /** Pioneer-only — the "My Total Hours" total for [periodMonth] at the
     * moment this report was last saved, kept separately from
     * [hoursRendered] purely for comparison/audit (spec §12/§19: "preserve
     * the system-calculated value... do not allow the Publisher to overwrite
     * or alter" it). `null` for a Non-Pioneer, or if this report predates
     * this field's introduction. */
    val systemCalculatedHours: Double? = null,

    /** Pioneer-only — `true` whenever [hoursRendered] differs from
     * [systemCalculatedHours] (spec §10/§19); meaningless (left `false`)
     * when they match, or for a Non-Pioneer. Set automatically once the
     * Publisher accepts [MonthlyReportScreen]'s one-off confirmation dialog
     * for a changed value — there is no separate on-form checkbox for this
     * (kept off the form on purpose; see that screen's own doc comment). */
    val hoursConfirmed: Boolean = false,

    /** Pioneer-only — a fixed, non-blank note recorded automatically
     * whenever [hoursRendered] differs from [systemCalculatedHours], purely
     * so firestore.rules' `hoursAdjustmentValid` same-document check is
     * satisfied (spec §10); distinct from the general [remarks] field below,
     * which stays optional, Publisher-typed, and shown on the form for every
     * category. */
    val hoursAdjustmentRemarks: String? = null,

    // Every category's field now (see this class's own doc comment) — was
    // Publisher-only (non-Pioneer) before this pass.
    val participatedInPreaching: Boolean? = null,

    val status: ReportStatus = ReportStatus.DRAFT,
    val submittedAt: Long? = null,

    /** My Planner / Reporting upgrade spec §35 — set alongside
     * [ReportStatus.POSTED] (spec's "REVIEWED"), so there's a real record of
     * who approved/locked the report and when, not just the status value
     * itself. */
    val reviewedByPersonId: String? = null,
    val reviewedAt: Long? = null,
    /** Set alongside [ReportStatus.RETURNED] — who sent it back, when, and
     * (required for that action) why. [correctionReason] is intentionally
     * never cleared once set, even after the Publisher resubmits
     * ([ReportStatus.CORRECTED]) — it stays as the historical record of what
     * was wrong the last time this report was returned. */
    val returnedByPersonId: String? = null,
    val returnedAt: Long? = null,
    val correctionReason: String? = null,

    /** Optional free-text note the publisher can attach to their own
     * submission — unlike every other field above, never required for any
     * [PublisherCategory]. */
    val remarks: String? = null,

    val lastEditedByPersonId: String? = null,
    val lastEditedAt: Long? = null,
    /** Where this report came from: "PUBLISHER" (entered by the publisher, the default) or "MANUAL" (entered on their behalf by an authorized admin-track user). */
    val source: String = SOURCE_PUBLISHER,
    /** Who first created the report and when — set for manual entries so an administrator can see who entered them. */
    val createdByPersonId: String? = null,
    val createdAt: Long? = null,
) {
    /** "Has this publisher actually submitted a report for this period" —
     * true for [ReportStatus.SUBMITTED], [ReportStatus.POSTED], and (My
     * Planner / Reporting upgrade spec §35) [ReportStatus.CORRECTED] — a
     * corrected resubmission is still a submission, just one with history.
     * [ReportStatus.RETURNED] is deliberately excluded: a returned report is
     * *not* currently submitted until the Publisher actually resubmits it.
     * Bug fix: several call sites (ReminderWorker's "already submitted, skip
     * the reminder" check, PublisherAutoStatus's irregular-publisher
     * detection, and the Dashboard/Consolidated Report totals) used to test
     * `status == ReportStatus.SUBMITTED` directly to mean exactly this —
     * which broke the moment POSTED was introduced as a *further* status
     * beyond Submitted: a Posted report would have silently stopped
     * counting as submitted at all (vanishing from report totals, and
     * wrongly re-triggering "you haven't submitted yet" reminders/
     * irregular-publisher flags for someone who very much had). Every one
     * of those now reads this property instead of comparing `status`
     * directly. */
    val isSubmittedOrPosted: Boolean
        get() = status == ReportStatus.SUBMITTED || status == ReportStatus.POSTED || status == ReportStatus.CORRECTED

    /** Entered on the publisher's behalf by an authorized user rather than by the publisher. */
    val isManualEntry: Boolean get() = source == SOURCE_MANUAL
}

/**
 * One active/extended date range for an Auxiliary Pioneer assignment. Per spec §7
 * open decision, an extension creates a *new* row rather than mutating the existing
 * one, keeping historical reporting clean.
 *
 * Firestore collection: `auxiliaryPioneerRanges/{rangeId}`
 */
data class AuxiliaryPioneerRange(
    @DocumentId val id: String = "",
    val publisherPersonId: String = "",
    val startDate: Long = 0L,
    /** Null while the range is open-ended pending confirmation of an end date. */
    val endDate: Long? = null,
    val createdByPersonId: String = "",
    val createdAt: Long = 0L,
)
