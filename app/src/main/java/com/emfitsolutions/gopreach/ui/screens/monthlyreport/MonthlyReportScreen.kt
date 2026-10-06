package com.emfitsolutions.gopreach.ui.screens.monthlyreport

import android.content.Intent
import com.emfitsolutions.gopreach.data.model.displayName
import com.emfitsolutions.gopreach.domain.PublisherReportCalculator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.data.model.ReportStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.window.DialogProperties

/**
 * Spec §5.2, extended by the "Monthly Report Submission Module" pass — every
 * field auto-fills from My Planner/this Publisher's own records but stays
 * fully editable (never locked just because a value came from a
 * calculation), edits are never silently overwritten by a later
 * recalculation (see [MonthlyReportViewModel]'s own doc comment), and the
 * workflow is Review/Edit → Preview (showing exactly what will be
 * submitted) → Send. Submitting no longer locks the report by itself — the
 * Publisher can keep editing their own report through DRAFT and SUBMITTED;
 * it only locks once a Service Overseer/Admin/Super-Admin marks it Posted
 * (see [MonthlyReportUiState.isLocked] and
 * [com.emfitsolutions.gopreach.data.model.ReportStatus.POSTED]'s own doc
 * comment).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthlyReportScreen(
    publisherPersonId: String,
    /** True when the signed-in user is a Coordinator/Regular Elder editing a
     * publisher's report rather than the publisher editing their own (spec
     * §5.2's post-submission edit right). */
    allowEditWhenLocked: Boolean = false,
    /** "My Planner's selected Month/Year controls the initial Monthly
     * Report month" — non-null only when arriving from My Planner's own
     * Send Report action (see [Destinations.monthlyReportForMonth]); every
     * other entry point leaves this `null` and keeps the screen's own
     * default (current month). Applied once, and only if it's actually one
     * of the two periods this screen can report for — never used to force
     * the picker outside its own closed range. */
    initialPeriodMonth: Long? = null,
    /** "Allow the publisher to see all his submitted Report record" — shown
     * as a top-bar action only for the Publisher's own normal entry point
     * (null for the separate Elder-editing-someone-else's-report route,
     * where a report-history shortcut doesn't make sense). */
    onViewHistory: (() -> Unit)? = null,
    onBack: () -> Unit,
    viewModel: MonthlyReportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(publisherPersonId, allowEditWhenLocked) { viewModel.load(publisherPersonId, useStoredValues = allowEditWhenLocked) }
    // Applied once, right after load() establishes [viewModel.availableMonths]
    // — guarded to one of those real periods so an out-of-range month (e.g.
    // My Planner parked further back than the picker's lookback window)
    // never breaks this screen's own Select Month to Report dropdown.
    LaunchedEffect(initialPeriodMonth) {
        if (initialPeriodMonth != null && initialPeriodMonth in viewModel.availableMonths) {
            viewModel.onMonthSelected(initialPeriodMonth)
        }
    }

    val isPioneer = uiState.isPioneer
    val effectivelyLocked = uiState.isLocked && !allowEditWhenLocked
    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    val selectedMonthLabel = remember(uiState.selectedPeriodMonth) { monthFormat.format(Date(uiState.selectedPeriodMonth)) }
    // The submission-window gate only applies to a publisher's own normal
    // submit flow — an Elder editing on someone's behalf (allowEditWhenLocked)
    // isn't restricted to the last-2-days window.
    val submitBlockedByWindow = !uiState.canSubmitWindow && !allowEditWhenLocked && !uiState.isLocked
    val context = LocalContext.current

    fun shareAsText() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, reportText(uiState))
        }
        context.startActivity(Intent.createChooser(intent, "Send Report"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.monthly_report_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (uiState.showingPreview) viewModel.hidePreview() else onBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = ::shareAsText) {
                        Icon(Icons.Rounded.Share, contentDescription = "Share as Text")
                    }
                    if (onViewHistory != null) {
                        IconButton(onClick = onViewHistory) {
                            Icon(Icons.AutoMirrored.Rounded.ListAlt, contentDescription = stringResource(R.string.home_tile_my_reports_title))
                        }
                    }
                },
            )
        },
    ) { padding ->
        // "Use subtle Motion animations for... Preview transition, Edit →
        // Preview, Preview → Edit" — a quick horizontal slide+fade between
        // the form and the Preview step, not a full-screen navigation.
        AnimatedContent(
            targetState = uiState.showingPreview,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally(tween(220)) { it / 3 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(220)) { -it / 3 } + fadeOut(tween(220)))
                } else {
                    (slideInHorizontally(tween(220)) { -it / 3 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(220)) { it / 3 } + fadeOut(tween(220)))
                }
            },
            label = "monthlyReportStep",
            modifier = Modifier.padding(padding),
        ) { showingPreview ->
            if (showingPreview) {
                MonthlyReportPreview(
                    uiState = uiState,
                    selectedMonthLabel = selectedMonthLabel,
                    effectivelyLocked = effectivelyLocked,
                    onEdit = viewModel::hidePreview,
                    onSubmit = { viewModel.submit(publisherPersonId, allowEditWhenLocked) },
                )
            } else {
                MonthlyReportForm(
                    uiState = uiState,
                    viewModel = viewModel,
                    isPioneer = isPioneer,
                    effectivelyLocked = effectivelyLocked,
                    submitBlockedByWindow = submitBlockedByWindow,
                    selectedMonthLabel = selectedMonthLabel,
                    allowEditWhenLocked = allowEditWhenLocked,
                )
            }
        }
    }

    // Spec §15 Step 7 — "show a clear submission-success message and the
    // resulting report status" (a deliberate change from this screen's
    // earlier "stay silent while syncing" behavior — the new spec asks for
    // this explicitly). The write itself is still fire-and-forget against
    // the offline-first cache; this dialog is purely a courtesy
    // confirmation, not a wait for the network sync to finish.
    if (uiState.saved) {
        AlertDialog(
            onDismissRequest = onBack,
            icon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = SolidGreen) },
            title = { Text(stringResource(R.string.monthly_report_success_title)) },
            text = { Text(stringResource(R.string.monthly_report_success_message, selectedMonthLabel)) },
            confirmButton = { TextButton(onClick = onBack) { Text("Done") } },
        )
    }
}

// ---------------------------------------------------------------------------
// Solid semantic colors (spec §10/§13 — "use solid colors, avoid gradients")
// ---------------------------------------------------------------------------

/** The Preview text and Send as Text — one calculation ([PublisherReport]), the same one Submit saves. */
private fun reportText(uiState: MonthlyReportUiState): String = uiState.report.toText()

private val SolidGreen = Color(0xFF2E7D32)
private val SolidBlue = Color(0xFF1565C0)
private val SolidRed = Color(0xFFC62828)
private val SolidGray = Color(0xFF616161)
private val SolidOrange = Color(0xFFEF6C00)

private fun statusColor(status: ReportStatus?): Color = when (status) {
    null, ReportStatus.DRAFT -> SolidGray
    ReportStatus.SUBMITTED, ReportStatus.POSTED, ReportStatus.CORRECTED -> SolidGreen
    ReportStatus.RETURNED -> SolidRed
}

private fun statusLabel(status: ReportStatus?): Int = when (status) {
    null, ReportStatus.DRAFT -> R.string.monthly_report_status_draft
    ReportStatus.SUBMITTED -> R.string.monthly_report_status_submitted
    ReportStatus.POSTED -> R.string.monthly_report_status_posted
    ReportStatus.RETURNED -> R.string.monthly_report_status_returned
    ReportStatus.CORRECTED -> R.string.monthly_report_status_corrected
}

/** Solid-color status chip — spec §13: "always display the status as text
 * in addition to color," never color alone. */
@Composable
private fun ReportStatusChip(status: ReportStatus?) {
    val color = statusColor(status)
    Surface(shape = RoundedCornerShape(50), color = color) {
        Text(
            stringResource(statusLabel(status)),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Form (Review/Edit step)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonthlyReportForm(
    uiState: MonthlyReportUiState,
    viewModel: MonthlyReportViewModel,
    isPioneer: Boolean,
    effectivelyLocked: Boolean,
    submitBlockedByWindow: Boolean,
    selectedMonthLabel: String,
    allowEditWhenLocked: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.monthly_report_category, uiState.category?.displayName ?: stringResource(R.string.monthly_report_category_unknown)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            ReportStatusChip(uiState.existingReport?.status)
        }

        // "Select Month to Report" — the publisher can report for the
        // recent (previous) month or the current one, never anything
        // earlier or later; an Elder editing on someone's behalf never
        // needs to change it (they're always here for one specific
        // already-submitted period), so the picker is hidden for them. The
        // month itself is still just a default (spec §1) — it's an
        // ordinary editable dropdown like every other field, just one whose
        // valid range this app deliberately keeps to exactly two periods.
        if (!allowEditWhenLocked) {
            MonthPickerField(
                availableMonths = viewModel.availableMonths,
                selectedMonth = uiState.selectedPeriodMonth,
                onSelected = viewModel::onMonthSelected,
            )
        }

        if (effectivelyLocked) {
            Text(
                stringResource(R.string.monthly_report_posted_locked),
                color = SolidRed,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // My Planner / Reporting upgrade spec §35 — "Return for Correction"
        // shows the Publisher exactly why, right where they'll see it
        // before they start editing.
        val correctionReason = uiState.existingReport?.correctionReason
        if (uiState.existingReport?.status == ReportStatus.RETURNED && !correctionReason.isNullOrBlank()) {
            Card(modifier = Modifier.fillMaxWidth(), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = SolidOrange.copy(alpha = 0.12f))) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.monthly_report_returned_for_correction_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = SolidOrange,
                    )
                    Text(correctionReason, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Spec §24 — a genuine calculation failure shows a retry, not a
        // bare "0" that looks the same as a real zero-studies month.
        if (uiState.calculationFailed) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.monthly_report_calculation_failed, selectedMonthLabel),
                        color = SolidRed,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { viewModel.onMonthSelected(uiState.selectedPeriodMonth) }) {
                        Text(stringResource(R.string.monthly_report_calculation_retry))
                    }
                }
            }
        }

        if (allowEditWhenLocked) {
            // An Elder correcting a report can still change its figures.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = uiState.hoursText,
                    onValueChange = viewModel::onHoursChange,
                    label = { Text(stringResource(R.string.monthly_report_hours_label)) },
                    singleLine = true,
                    isError = uiState.hoursError != null,
                    supportingText = uiState.hoursError?.let { { Text(it, color = SolidRed) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = uiState.minutesText,
                    onValueChange = viewModel::onMinutesChange,
                    label = { Text(stringResource(R.string.monthly_report_minutes_label)) },
                    singleLine = true,
                    isError = uiState.minutesError != null,
                    supportingText = uiState.minutesError?.let { { Text(it, color = SolidRed) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = uiState.bibleStudiesRendered,
                onValueChange = viewModel::onBibleStudiesChange,
                label = { Text(stringResource(R.string.monthly_report_bible_studies_label)) },
                singleLine = true,
                isError = uiState.bibleStudiesError != null,
                supportingText = uiState.bibleStudiesError?.let { { Text(it, color = SolidRed) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Everyone else: the figures come straight from the Monthly Report and are
            // shown as they will be sent - a Pioneer's converted Hours (no minutes), a
            // non-Pioneer's Attended in Preaching (no hours or minutes), and Bible Study.
            val report = uiState.report
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (report.isPioneer) {
                        Text("Hours: ${PublisherReportCalculator.formatHours(report.hours ?: 0.0)}", style = MaterialTheme.typography.bodyLarge)
                    } else {
                        Text("Attended in Preaching: ${if (report.attendedInPreaching == true) "YES" else "NO"}", style = MaterialTheme.typography.bodyLarge)
                    }
                    Text("Bible Study: ${report.bibleStudies}", style = MaterialTheme.typography.bodyLarge)
                    Text("From your Monthly Report for $selectedMonthLabel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        OutlinedTextField(
            value = uiState.remarks,
            onValueChange = viewModel::onRemarksChange,
            label = { Text(stringResource(R.string.monthly_report_remarks)) },
            // Spec §4E — a Pioneer's Credit Hour category is only ever a
            // *suggested* starting value here; this field is a completely
            // ordinary editable text box, never disabled/read-only styling.
            enabled = !effectivelyLocked,
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )

        if (uiState.errorMessage != null) {
            Text(text = uiState.errorMessage!!, color = SolidRed)
        }

        if (submitBlockedByWindow) {
            Text(
                stringResource(R.string.monthly_report_submission_window_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // A Publisher's own report that is already submitted can't be sent again (an
        // Elder correcting it, or a Returned/Draft report, still can).
        val alreadySubmitted = !allowEditWhenLocked && when (uiState.existingReport?.status) {
            ReportStatus.SUBMITTED, ReportStatus.CORRECTED, ReportStatus.POSTED -> true
            else -> false
        }
        Button(
            onClick = viewModel::showPreview,
            enabled = !alreadySubmitted && !uiState.isSaving && !effectivelyLocked && !submitBlockedByWindow && !uiState.hasValidationError,
            colors = ButtonDefaults.buttonColors(containerColor = SolidBlue),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (alreadySubmitted) "This Record is Already Submitted" else stringResource(R.string.monthly_report_review_button))
        }
    }
}

// ---------------------------------------------------------------------------
// Preview (spec §6-§8 — exactly what will be submitted, compact layout)
// ---------------------------------------------------------------------------

@Composable
private fun MonthlyReportPreview(
    uiState: MonthlyReportUiState,
    selectedMonthLabel: String,
    effectivelyLocked: Boolean,
    onEdit: () -> Unit,
    onSubmit: () -> Unit,
) {
    var showHoursConfirmDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.monthly_report_preview_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        // "The Text Preview must display the exact information that will be
        // submitted" — this literal block IS the message [reportText] builds
        // (also what Share/Copy send out and what Submit saves), not a
        // separate compact-rows rendering that could drift from it.
        val text = remember(uiState) { reportText(uiState) }
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
            )
        }

        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        var showCopiedConfirmation by remember { mutableStateOf(false) }
        // Spec §16 — a quick, subtle confirmation animation for Copy Text,
        // not a blocking dialog.
        AnimatedVisibility(visible = showCopiedConfirmation, enter = fadeIn(tween(150)), exit = fadeOut(tween(400))) {
            Text("Copied to clipboard", style = MaterialTheme.typography.labelMedium, color = SolidGreen)
        }
        LaunchedEffect(showCopiedConfirmation) {
            if (showCopiedConfirmation) {
                kotlinx.coroutines.delay(1500)
                showCopiedConfirmation = false
            }
        }
        OutlinedButton(
            onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                showCopiedConfirmation = true
            },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SolidGray),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.monthly_report_preview_copy_button)) }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onEdit,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SolidBlue),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.monthly_report_preview_edit_button)) }
            Button(
                onClick = {
                    // "Not a system message on the form, a separate message
                    // confirmation" — a Pioneer's hours differing from the
                    // system-calculated total is confirmed through this
                    // one-off dialog, checked right here at the actual Send
                    // action so it always reflects whatever is currently on
                    // screen (spec §7: never a stale check).
                    if (uiState.isPioneer && uiState.hoursDifferFromSystem) showHoursConfirmDialog = true else onSubmit()
                },
                enabled = !uiState.isSaving && !effectivelyLocked,
                colors = ButtonDefaults.buttonColors(containerColor = SolidGreen),
                modifier = Modifier.weight(1f),
            ) {
                if (uiState.isSaving) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), color = Color.White)
                Text(stringResource(R.string.monthly_report_preview_send_button))
            }
        }
    }

    if (showHoursConfirmDialog) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { showHoursConfirmDialog = false },
            title = { Text(stringResource(R.string.monthly_report_hours_confirm_dialog_title)) },
            text = { Text(stringResource(R.string.monthly_report_hours_confirm_dialog_message, selectedMonthLabel)) },
            confirmButton = {
                TextButton(onClick = { showHoursConfirmDialog = false; onSubmit() }) {
                    Text(stringResource(R.string.monthly_report_hours_confirm_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showHoursConfirmDialog = false }) {
                    Text(stringResource(R.string.monthly_report_hours_confirm_dialog_cancel))
                }
            },
        )
    }
}

/** "Keep details close to their titles and labels... avoid excessive
 * spacing" — label and value sit right next to each other as one compact
 * pair ("Hours: 3"), not spread to opposite edges of the card. On a wide
 * screen, [Arrangement.SpaceBetween] across two `weight(1f)` texts (the
 * previous approach) stretches a huge gap between them — exactly the
 * "excessive spacing" this rule is about; packing the Row instead keeps
 * label and value together regardless of screen width. */
@Composable
private fun PreviewRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

/** "Select Month to Report [July 2026]" — [availableMonths] is a closed list
 * of recent months up to and including the current one (see
 * [MonthlyReportViewModel.availableMonths]'s doc comment), never a future
 * month; there's no way to pick one outside that range in the first place.
 * The initial selection is only ever a default — an ordinary editable
 * dropdown either way. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonthPickerField(availableMonths: List<Long>, selectedMonth: Long, onSelected: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = monthFormat.format(Date(selectedMonth)),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.monthly_report_select_month)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            availableMonths.forEach { month ->
                DropdownMenuItem(
                    text = { Text(monthFormat.format(Date(month))) },
                    onClick = { onSelected(month); expanded = false },
                )
            }
        }
    }
}
