package com.emfitsolutions.gopreach.ui.screens.monthlyreport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.ReportStatus
import com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository
import com.emfitsolutions.gopreach.domain.MonthlyReportCalculator
import com.emfitsolutions.gopreach.domain.PublisherReport
import com.emfitsolutions.gopreach.domain.PublisherReportCalculator
import com.emfitsolutions.gopreach.domain.PublisherReportService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.emfitsolutions.gopreach.platform.Calendar

/** Midnight on the 1st of whichever month [monthsAgo] months before the
 * current one — 0 = this month, 1 = last month. Shared by every "which
 * period does this belong to" computation in this file so they can never
 * drift out of sync with each other. */
private fun monthStart(monthsAgo: Int): Long = Calendar.getInstance().apply {
    add(Calendar.MONTH, -monthsAgo)
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun currentMonthStart(): Long = monthStart(0)

/** How far back "Select Month to Report" lets a Publisher go. */
private const val MONTHS_BACK = 12

private fun daysUntilMonthEnd(): Int {
    val cal = Calendar.getInstance()
    return cal.getActualMaximum(Calendar.DAY_OF_MONTH) - cal.get(Calendar.DAY_OF_MONTH)
}

private fun isPioneer(category: PublisherCategory?) = MonthlyReportCalculator.isPioneerCategory(category)

data class MonthlyReportUiState(
    val category: PublisherCategory? = null,
    val firstName: String = "",
    val lastName: String = "",
    val congregationId: String? = null,
    val existingReport: MonthlyReport? = null,
    val selectedPeriodMonth: Long = currentMonthStart(),

    /** Pre-filled from [MonthlyReportCalculator.calculate] but, per the
     * simplified report form, still Publisher-editable — same "automatic but
     * can be edited" treatment as [hoursText]/[minutesText]. Once the
     * Publisher changes a field, [MonthlyReportViewModel] stops overwriting
     * it on every later recalculation (spec: "never overwrite a value after
     * the Publisher manually edits it"). */
    val bibleStudiesRendered: String = "0",
    /** My Planner / Reporting upgrade spec §30 — same "automatic but
     * editable" treatment as [bibleStudiesRendered], now that
     * [MonthlyReport.returnVisitsCount] exists to save it into. */
    val returnVisitsRendered: String = "0",
    /** Spec §24 — `false` means the calculation genuinely failed (a source
     * Flow error), not "zero qualifying records"; the screen shows a retry
     * affordance instead of a bare `0`/`No` in that case. */
    val calculationFailed: Boolean = false,
    val isCalculating: Boolean = true,

    /** Only shown/used for the non-Pioneer categories (Regular Publisher,
     * Unbaptized Publisher) — a Pioneer already reports actual hours below,
     * so this question doesn't apply to them. Pre-filled from
     * [MonthlyReportCalculator.didParticipateInPreaching] (itself widened
     * here to also count any My Planner Hours/Minutes logged that month —
     * see [MonthlyReportViewModel.load]) but still Publisher-editable. */
    val participatedInPreaching: Boolean = false,

    /** Pioneer-only — the "My Total Hours" total for the selected month at
     * the moment this report was last saved (from Preaching Time Records,
     * this app's existing official hours source for Pioneers), kept purely
     * for the differs-from-system comparison/confirmation below; `null` for
     * a Non-Pioneer. */
    val systemCalculatedHours: Double? = null,
    /** Hours/Minutes are always shown as two separate editable fields now
     * (spec §1/§3C-D/§4B-C), for every category — a Pioneer's own default
     * still comes from [systemCalculatedHours] (unchanged data source, just
     * split into two fields for display); a Non-Pioneer's default is new,
     * summed from their own My Planner daily totals for the month (see
     * [MonthlyReportViewModel.load]). */
    val hoursText: String = "0",
    val minutesText: String = "0",

    /** Optional free-text note — see [MonthlyReport.remarks]. A Pioneer's
     * default is auto-populated with the month's Credit Hour category (or
     * categories) if any exist, spec §4E — but only until the Publisher
     * edits it themselves, same never-overwrite rule as every other field. */
    val remarks: String = "",
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val saved: Boolean = false,

    /** "Preview must show exactly what will be submitted" (spec §6/§7) —
     * `true` once the Publisher taps through to the Preview step; Submit
     * only ever actually fires from there. Editing returns here without
     * losing any entered value (spec §5). */
    val showingPreview: Boolean = false,
) {
    /** "Allow the publisher to edit the record until the service overseer
     * will mark it as 'Posted'" — the Publisher may keep editing their own
     * report through DRAFT *and* SUBMITTED; only [ReportStatus.POSTED]
     * actually locks them out (see that enum value's own doc comment for
     * who marks it and why Submit itself no longer does). [MonthlyReportScreen]
     * passes `allowEditWhenLocked = true` for the separate Service Overseer/
     * Admin/Super-Admin edit-anytime entry point. */
    val isLocked: Boolean get() = existingReport?.status == ReportStatus.POSTED

    /** "Submission of report is done each month... available 2 days before
     * the end of each month" spec — this restriction only makes sense for
     * the *current*, still-in-progress month (spec: "refer to the
     * restriction of sending a report for the current month"). "Select
     * Month to Report" — the publisher can also report for the recent
     * (previous, already fully elapsed) month; nothing is left to wait for
     * there, so it's always open. Checked against the device clock, not
     * [existingReport], so it stays correct across the month-boundary reset
     * with no extra state to track. */
    val canSubmitWindow: Boolean get() = selectedPeriodMonth != currentMonthStart() || daysUntilMonthEnd() <= 2

    val isPioneer: Boolean get() = isPioneer(category)

    /** The month's total ministry time — hours and minutes as one figure. */
    val totalMinutes: Int get() = (hoursText.toIntOrNull() ?: 0) * 60 + (minutesText.toIntOrNull() ?: 0)

    /** What gets stored as [MonthlyReport.hoursRendered]: a Pioneer's converted hours
     * (the same figure Preview and Send as Text show), everyone else's raw hours. */
    val reportedHoursDecimal: Double
        get() = if (isPioneer) PublisherReportCalculator.convertToHours(totalMinutes) else totalMinutes / 60.0

    /** The finished report — the Preview text and Send as Text come from this alone. */
    val report: PublisherReport
        get() = PublisherReportCalculator.build(firstName, lastName, category, selectedPeriodMonth, totalMinutes, bibleStudiesRendered.toIntOrNull() ?: 0, remarks)

    /** Spec §10/§19 — a Pioneer's manual hours differing from the system
     * total is what actually *requires* [MonthlyReport.hoursConfirmed] +
     * non-blank [MonthlyReport.hoursAdjustmentRemarks]; accepting the system
     * value as-is needs neither (spec: "If the Publisher accepts the
     * automatically calculated value without changing it, additional
     * remarks are not required"). Compared in whole minutes, not the raw
     * Double, so `12.5` vs `12.5000000001` float noise from the
     * hours+minutes round-trip never falsely reads as "differs". */
    val hoursDifferFromSystem: Boolean get() {
        if (!isPioneer) return false
        val system = systemCalculatedHours ?: return false
        // Both sides are converted hours, so 15:28 vs a system 15:28 never reads as "differs".
        return Math.round(reportedHoursDecimal * 60) != Math.round(system * 60)
    }

    /** Spec §12 — non-negative, valid numbers; shown beside the relevant
     * field, and never clears what the Publisher typed. */
    val hoursError: String? get() = if (hoursText.toIntOrNull()?.let { it >= 0 } != true) "Enter a valid number of hours" else null
    val minutesError: String? get() = when (val m = minutesText.toIntOrNull()) {
        null -> "Enter a valid number of minutes"
        else -> if (m !in 0..59) "Minutes must be 0–59" else null
    }
    val bibleStudiesError: String? get() = if (bibleStudiesRendered.toIntOrNull()?.let { it >= 0 } != true) "Enter a valid number" else null
    val returnVisitsError: String? get() = if (returnVisitsRendered.toIntOrNull()?.let { it >= 0 } != true) "Enter a valid number" else null
    val hasValidationError: Boolean get() = hoursError != null || minutesError != null || bibleStudiesError != null || returnVisitsError != null
}

/**
 * Spec §5.2, extended by "Update Monthly Report Submission — Automatic Bible
 * Study Count and Preaching Participation" and later the "Monthly Report
 * Submission Module — UI, Logic & Editable Data Enhancement" pass (every
 * entry field stays editable after auto-fill, edits are never silently
 * overwritten, and a Preview step shows exactly what Submit will send) —
 * monthly ministry report, required fields varying by [PublisherCategory].
 * Bible Study count, suggested preaching participation, and Hours/Minutes
 * are all derived from this Publisher's own already-synced records (see
 * [MonthlyReportCalculator]) rather than typed in from nothing — the
 * client-side half of what spec §15/§26 ask for "authoritative server
 * values"; this app has no Cloud Functions/custom backend to recompute
 * aggregates a second time server-side (see firestore.rules' `monthlyReports`
 * rule for the one thing that block *can* actually enforce — hours-
 * confirmation/remarks-required-when-different, a same-document check —
 * and this class's own doc comment for the honest limit of what's possible
 * without introducing one).
 *
 * "Select Month to Report" — a publisher may submit for the current month
 * (subject to [MonthlyReportUiState.canSubmitWindow]'s last-2-days rule) or
 * any of the recent months before it, but never a month later than the
 * current one: [availableMonths] goes back [MONTHS_BACK] months and stops at
 * the current month, so the dropdown itself is the enforcement — there's no
 * way to even construct a request for a future month.
 */
class MonthlyReportViewModel(
    private val monthlyReportRepository: MonthlyReportRepository,
    private val publisherReportService: PublisherReportService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MonthlyReportUiState())
    val uiState: StateFlow<MonthlyReportUiState> = _uiState

    private val _selectedPeriodMonth = MutableStateFlow(currentMonthStart())

    /** "Never overwrite a value after the Publisher manually edits it" —
     * which of this period's fields the Publisher has actually touched.
     * Deliberately *not* part of [MonthlyReportUiState]/[_uiState] itself:
     * it only ever controls how [load]'s recalculation merges into that
     * state, the UI never reads it directly. Cleared on every month switch
     * (a fresh period gets fresh defaults, same as opening the screen new —
     * see [onMonthSelected]). */
    private val touchedFields = mutableSetOf<String>()

    /** All recent months up to and including the current one, oldest to
     * newest — never a month later than the current month. */
    val availableMonths: List<Long> = (MONTHS_BACK downTo 0).map(::monthStart)

    /** [useStoredValues] — an Elder correcting a report keeps its saved figures; every
     * other entry recalculates from the latest records each time (see
     * [PublisherReportService]), so a stale figure can never be submitted. */
    fun load(publisherPersonId: String, useStoredValues: Boolean = false) {
        viewModelScope.launch {
            publisherReportService.observe(publisherPersonId, _selectedPeriodMonth, useStoredValues).map { source ->
                MonthlyReportUiState(
                    category = source.category,
                    firstName = source.person?.firstName.orEmpty(),
                    lastName = source.person?.lastName.orEmpty(),
                    congregationId = source.congregationId,
                    existingReport = source.existingReport,
                    selectedPeriodMonth = source.periodMonth,
                    bibleStudiesRendered = source.bibleStudies.toString(),
                    returnVisitsRendered = source.returnVisits.toString(),
                    calculationFailed = false,
                    isCalculating = false,
                    participatedInPreaching = source.totalMinutes > 0,
                    systemCalculatedHours = source.systemMinutes?.let { PublisherReportCalculator.convertToHours(it) },
                    hoursText = (source.totalMinutes / 60).toString(),
                    minutesText = (source.totalMinutes % 60).toString(),
                    remarks = source.defaultRemarks,
                )
            }.collect { fresh ->
                // Only an Elder correcting a report can touch a figure; those edits survive
                // a recalculation. Everyone else's figures always come from the Monthly Report.
                val current = _uiState.value
                _uiState.value = fresh.copy(
                    hoursText = if ("hours" in touchedFields) current.hoursText else fresh.hoursText,
                    minutesText = if ("minutes" in touchedFields) current.minutesText else fresh.minutesText,
                    bibleStudiesRendered = if ("bibleStudies" in touchedFields) current.bibleStudiesRendered else fresh.bibleStudiesRendered,
                    remarks = if ("remarks" in touchedFields) current.remarks else fresh.remarks,
                    showingPreview = current.showingPreview,
                )
            }
        }
    }

    /** Switching the selected month re-populates the form from whatever
     * report (if any) already exists for that period — same as opening the
     * screen fresh for it, so a publisher who already submitted last
     * month's report sees it (locked) instead of a blank form. This also
     * means a manual edit tied to the *previous* selection is never
     * silently carried over: a fresh period starts with nothing touched, so
     * every field re-derives its own default for the newly selected month. */
    fun onMonthSelected(periodMonth: Long) {
        touchedFields.clear()
        _selectedPeriodMonth.value = periodMonth
    }

    fun onHoursChange(value: String) { touchedFields.add("hours"); update { it.copy(hoursText = value.filter(Char::isDigit).take(4)) } }
    fun onMinutesChange(value: String) { touchedFields.add("minutes"); update { it.copy(minutesText = value.filter(Char::isDigit).take(2)) } }
    fun onBibleStudiesChange(value: String) { touchedFields.add("bibleStudies"); update { it.copy(bibleStudiesRendered = value.filter(Char::isDigit)) } }
    fun onReturnVisitsChange(value: String) { touchedFields.add("returnVisits"); update { it.copy(returnVisitsRendered = value.filter(Char::isDigit)) } }
    fun onParticipatedChange(value: Boolean) { touchedFields.add("participated"); update { it.copy(participatedInPreaching = value) } }
    fun onRemarksChange(value: String) { touchedFields.add("remarks"); update { it.copy(remarks = value) } }

    /** Edit/Review → Preview (spec §6). No recalculation happens here —
     * whatever is currently on screen is exactly what Preview shows. */
    fun showPreview() = update { it.copy(showingPreview = true) }

    /** Preview → Edit — returns to the form with every entered value intact
     * (spec §5's "Allow the Publisher to return to the form without losing
     * any entered values"). */
    fun hidePreview() = update { it.copy(showingPreview = false) }

    private fun update(block: (MonthlyReportUiState) -> MonthlyReportUiState) {
        _uiState.value = block(_uiState.value)
    }

    /** [allowEditWhenLocked] must be the same value the screen itself is
     * showing (its Elder/Admin "edit anyone's report" entry point) — the
     * Publisher's own normal screen always passes false. Belt-and-suspenders
     * on top of that screen's own disabled Submit button: this used to only
     * document "the caller is responsible for not calling this when locked"
     * rather than actually checking, so any bug in the UI's own enabled/
     * disabled wiring had nothing else standing between it and a save going
     * through anyway. Firestore's own security rules are still the real,
     * server-side backstop against a genuinely malicious client — this is
     * about this app's own UI never doing it by accident.
     *
     * Reaching this at all, for a Pioneer whose hours differ from the
     * system-calculated total, already implies confirmation — the screen
     * gates its Submit button behind a one-off confirmation dialog (spec's
     * "must be a separate message confirmation", not an inline checkbox +
     * required-remarks field cluttering the form) before ever calling this.
     * firestore.rules' `hoursAdjustmentValid` still requires a non-blank
     * `hoursAdjustmentRemarks` alongside `hoursConfirmed` on the same
     * document, so a fixed note is recorded automatically instead of asking
     * the Publisher to type one.
     *
     * "Submitted data must exactly match the Preview" — every value saved
     * below is read straight from [state], the exact same values the
     * Preview step (and the form before it) displayed; nothing here
     * recalculates or substitutes a fresh value at Submit time. */
    fun submit(publisherPersonId: String, allowEditWhenLocked: Boolean = false) {
        val state = _uiState.value
        if (state.isLocked && !allowEditWhenLocked) return
        _uiState.value = state.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            val report = MonthlyReport(
                id = state.existingReport?.id ?: "",
                publisherPersonId = publisherPersonId,
                congregationId = state.congregationId ?: "",
                category = state.category ?: PublisherCategory.REGULAR_PUBLISHER,
                periodMonth = state.selectedPeriodMonth,
                bibleStudiesCount = state.bibleStudiesRendered.toIntOrNull() ?: 0,
                returnVisitsCount = state.returnVisitsRendered.toIntOrNull() ?: 0,
                // Hours/Minutes are now reported for every category (spec
                // §3C-D) — the stored decimal is just this pair's storage
                // conversion, same field/shape every existing reader
                // (Dashboard totals, Consolidated Report, ...) already reads.
                hoursRendered = state.reportedHoursDecimal,
                systemCalculatedHours = if (state.isPioneer) state.systemCalculatedHours else null,
                hoursConfirmed = state.isPioneer && state.hoursDifferFromSystem,
                hoursAdjustmentRemarks = if (state.isPioneer && state.hoursDifferFromSystem) {
                    "Publisher confirmed the adjusted hours."
                } else {
                    null
                },
                // Attended in Preaching follows from the month's hours, never a manual answer.
                participatedInPreaching = if (state.isPioneer) null else state.totalMinutes > 0,
                // My Planner / Reporting upgrade spec §35 — a report the
                // Publisher is resubmitting after it was RETURNED becomes
                // CORRECTED instead of a plain SUBMITTED, so the report's own
                // status shows this was a correction. correctionReason/
                // returnedBy/returnedAt are carried forward, never cleared —
                // they're the historical record of what was wrong last time,
                // not a live "currently returned" flag once status moves on.
                status = if (state.existingReport?.status == ReportStatus.RETURNED) ReportStatus.CORRECTED else ReportStatus.SUBMITTED,
                submittedAt = System.currentTimeMillis(),
                returnedByPersonId = state.existingReport?.returnedByPersonId,
                returnedAt = state.existingReport?.returnedAt,
                correctionReason = state.existingReport?.correctionReason,
                remarks = state.remarks.trim().ifBlank { null },
            )
            monthlyReportRepository.save(report)
            _uiState.value = _uiState.value.copy(isSaving = false, saved = true, showingPreview = false, existingReport = report)
        }
    }
}
