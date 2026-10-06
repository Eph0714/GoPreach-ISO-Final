package com.emfitsolutions.gopreach.ui.screens.publisherreports

import com.emfitsolutions.gopreach.platform.rememberPlatformActions
import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.ui.components.RecordFound
import com.emfitsolutions.gopreach.data.model.displayName
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.ReportStatus
import com.emfitsolutions.gopreach.data.print.ReportTable
import com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown
import com.emfitsolutions.gopreach.ui.components.DateRange
import com.emfitsolutions.gopreach.ui.components.DateRangeFilterBar
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.QuickDateRange
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import java.text.SimpleDateFormat
import com.emfitsolutions.gopreach.platform.Calendar
import java.util.Locale
import androidx.compose.ui.window.DialogProperties

/**
 * "Manage Publisher Report" module. [fixedCongregationId] is the security
 * boundary, resolved by the caller from the enrolling session's own role
 * (null means Super-Admin — every congregation); [canPermanentlyDelete] is
 * Super-Admin-only, same convention as every other Manage screen.
 * [readOnly] hides Edit/Unlock/Mark Posted (and Delete, on top of
 * [canPermanentlyDelete]) — a grant-based Circuit Overseer with a report-view
 * permission reaches this screen but can never edit through it, since
 * firestore.rules blocks every restricted user's `monthlyReports` write
 * regardless of permission (see AdminHomeScreen.canManagePublisherReports's
 * doc comment).
 *
 * [canMarkPosted] — "the service overseer will click the POST button... the
 * super admin, admin, and coordinator elder, regular elder can do so [too],
 * however the admin, coordinator elder, regular elder can only do it under
 * their congregation" — same set as the general edit right ([readOnly]):
 * Super-Admin (every congregation), Admin/Coordinator Elder/Regular Elder
 * (own congregation only, via [fixedCongregationId]), and Service Overseer.
 * Actual enforcement of "once Posted, the Publisher can no longer edit it"
 * lives in firestore.rules' `monthlyReports` write rule, not this flag —
 * this only governs who in the admin track even sees the "Mark as Posted"/
 * "POST" actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagePublisherReportsScreen(
    currentPersonId: String,
    fixedCongregationId: String?,
    canPermanentlyDelete: Boolean,
    canMarkPosted: Boolean,
    readOnly: Boolean = false,
    /** "If [a] report from [a] Publisher will be open[ed] [from the
     * notification balloon], open the exact month, not the default month of
     * the module" — non-null only when arriving from the notification
     * balloon's Monthly Report item (see Destinations.manageReportsForMonth);
     * every other entry point (side panel, dashboard tile) passes null and
     * keeps the screen's own default "This Month" filter. */
    initialPeriodMonth: Long? = null,
    onBack: () -> Unit,
    viewModel: ManagePublisherReportsViewModel = koinViewModel(),
) {
    LaunchedEffect(fixedCongregationId) { viewModel.restrictTo(fixedCongregationId) }
    LaunchedEffect(initialPeriodMonth) {
        if (initialPeriodMonth != null) viewModel.setDateRange(DateRange.forMonth(initialPeriodMonth))
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val actions = rememberPlatformActions()
    var pendingEdit by remember { mutableStateOf<PublisherReportRow?>(null) }
    var pendingDelete by remember { mutableStateOf<PublisherReportRow?>(null) }
    var pendingReturnForCorrection by remember { mutableStateOf<PublisherReportRow?>(null) }
    var showPostAllConfirm by remember { mutableStateOf(false) }
    val showToast = rememberActionToast()

    val reportTitle = remember(uiState.dateRange) { reportTitleFor(uiState.dateRange.startMillis, uiState.dateRange.endMillis, uiState.dateRange.option) }
    val reportTable = remember(uiState.rows, reportTitle) { publisherReportTable(reportTitle, uiState) }

    // Bug fix ("I cannot see any PDF or Excel"): see ReportsScreen's matching
    // fix — the Storage Access Framework picker just saves and closes with
    // no feedback of its own; now it confirms and opens the file immediately.
    val exportCsvSuccess = stringResource(R.string.reports_export_csv_success)
    val exportFailedWrite = stringResource(R.string.reports_export_failed_write)
    val exportFailedUnknown = stringResource(R.string.reports_export_failed_unknown)
    val exportFailedGenericTemplate = stringResource(R.string.reports_export_failed_generic)
    val csvExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                val wrote = actions.writeCsv(uri.toString(), reportTable)
                if (wrote) {
                    showToast(exportCsvSuccess)
                    actions.openFile(uri.toString(), "text/csv")
                } else {
                    showToast(exportFailedWrite)
                }
            } catch (e: Exception) {
                showToast(exportFailedGenericTemplate.format(e.localizedMessage ?: exportFailedUnknown))
            }
        }
    }
    val csvExportFileName = "gopreach-publisher-reports-${SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date())}.csv"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manage_reports_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.dashboard_back_cd))
                    }
                },
                actions = {
                    // "Print preview" — Android's own print dialog always
                    // shows a preview before anything prints, and offers
                    // "Save as PDF" out of the box. Bug fix ("I cannot see
                    // export to excel or pdf"): these were disabled when
                    // there were no rows — a disabled IconButton's icon
                    // renders at reduced alpha, which on this TopAppBar read
                    // as "not there at all." Always enabled now.
                    IconButton(onClick = { actions.print(reportTable) }) {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = stringResource(R.string.reports_export_pdf_cd))
                    }
                    // "Export as ... excel" — CSV, opens directly in any
                    // spreadsheet app.
                    IconButton(onClick = { csvExportLauncher.launch(csvExportFileName) }) {
                        Icon(Icons.Rounded.TableChart, contentDescription = stringResource(R.string.reports_export_excel_cd))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DateRangeFilterBar(range = uiState.dateRange, onRangeChange = viewModel::setDateRange)

                // "Add a filter for Congregation" (Super-Admin only) — always
                // visible now, independent of "Show" below, rather than a
                // third mutually-exclusive show-mode (see ReportShowMode's own
                // doc comment for the change). An Admin/Coordinator Elder/
                // Service Overseer is already scoped to exactly one
                // congregation ([fixedCongregationId] non-null), so this
                // stays hidden for them — nothing for them to filter.
                if (fixedCongregationId == null) {
                    CongregationFilterDropdown(
                        congregations = uiState.congregationsInScope,
                        selectedCongregationId = uiState.selectedCongregationId,
                        onSelected = viewModel::selectCongregation,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text(stringResource(R.string.manage_reports_show_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = uiState.showMode == ReportShowMode.ALL,
                        onClick = { viewModel.setShowMode(ReportShowMode.ALL) },
                        label = { Text(stringResource(R.string.manage_reports_all)) },
                    )
                    FilterChip(
                        selected = uiState.showMode == ReportShowMode.BY_PUBLISHER,
                        onClick = { viewModel.setShowMode(ReportShowMode.BY_PUBLISHER) },
                        label = { Text(stringResource(R.string.manage_reports_by_publisher)) },
                    )
                }

                if (uiState.showMode == ReportShowMode.BY_PUBLISHER) {
                    PublisherPickerDropdown(
                        publishers = uiState.publishersInScope,
                        selectedId = uiState.selectedPublisherId,
                        onSelected = viewModel::selectPublisher,
                    )
                }

                val searchLabel = if (fixedCongregationId == null) {
                    stringResource(R.string.manage_reports_search_label_with_congregation)
                } else {
                    stringResource(R.string.manage_reports_search_label)
                }
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    label = { Text(searchLabel) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Report Summary spec §36 — Classification/Status filters,
                // alongside Congregation/Year-Month (date range, above)/
                // Publisher this screen already had.
                Text(stringResource(R.string.manage_reports_classification_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(
                        selected = uiState.selectedClassification == null,
                        onClick = { viewModel.selectClassification(null) },
                        label = { Text(stringResource(R.string.manage_reports_all)) },
                    )
                    PublisherCategory.entries.forEach { category ->
                        FilterChip(
                            selected = uiState.selectedClassification == category,
                            onClick = { viewModel.selectClassification(category) },
                            label = { Text(category.displayName) },
                        )
                    }
                }

                Text(stringResource(R.string.manage_reports_status_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(
                        selected = uiState.selectedStatus == null,
                        onClick = { viewModel.selectStatus(null) },
                        label = { Text(stringResource(R.string.manage_reports_all)) },
                    )
                    ReportStatus.entries.forEach { status ->
                        FilterChip(
                            selected = uiState.selectedStatus == status,
                            onClick = { viewModel.selectStatus(status) },
                            label = { Text(status.name) },
                        )
                    }
                }

                TextButton(onClick = viewModel::resetFilters, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.manage_reports_reset_filters))
                }

                // "Select a month, then see all publishers that submitted
                // their record within that month, then click the POST
                // button — all the record will now be locked" — one tap
                // posts every SUBMITTED report currently shown for whichever
                // month/filters are selected above, instead of the per-row
                // lock icon one publisher at a time.
                val submittedCount = uiState.rows.count { it.report.status == ReportStatus.SUBMITTED }
                if (canMarkPosted && submittedCount > 0) {
                    Button(onClick = { showPostAllConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.manage_reports_post_button, submittedCount, if (submittedCount == 1) "" else "s"))
                    }
                }
            }

            if (fixedCongregationId == null && uiState.selectedCongregationId == null) {
                com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt()
            } else if (uiState.rows.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    RecordFound(0)
                    Text(stringResource(R.string.manage_reports_no_reports), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(
                    reportTitle,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { RecordFound(uiState.rows.size) }
                    items(uiState.rows, key = { it.report.id }) { row ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(row.person.fullName, style = MaterialTheme.typography.titleMedium)
                                    Row {
                                        if (!readOnly) {
                                            IconButton(onClick = { pendingEdit = row }) {
                                                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.manage_reports_edit_cd))
                                            }
                                            if (row.isLocked) {
                                                IconButton(onClick = { viewModel.unlock(row.report, currentPersonId) }) {
                                                    Icon(Icons.Rounded.LockOpen, contentDescription = stringResource(R.string.manage_reports_unlock_cd))
                                                }
                                            } else if (canMarkPosted) {
                                                // "The service overseer will mark it as 'Posted', that's
                                                // the time the publisher can no longer edit the record."
                                                IconButton(onClick = { viewModel.markPosted(row.report, currentPersonId) }) {
                                                    Icon(Icons.Rounded.Lock, contentDescription = stringResource(R.string.manage_reports_mark_posted_cd))
                                                }
                                                // My Planner / Reporting upgrade spec §35 — "Return for
                                                // Correction," only offered for a SUBMITTED report (never
                                                // DRAFT, already-RETURNED, or POSTED/locked).
                                                if (row.report.status == ReportStatus.SUBMITTED) {
                                                    IconButton(onClick = { pendingReturnForCorrection = row }) {
                                                        Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = stringResource(R.string.manage_reports_return_for_correction_cd))
                                                    }
                                                }
                                            }
                                        }
                                        if (canPermanentlyDelete && !readOnly) {
                                            IconButton(onClick = { pendingDelete = row }) {
                                                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.manage_reports_delete_cd))
                                            }
                                        }
                                    }
                                }
                                if (row.report.isManualEntry) {
                                    // Administrative detail only: this report was entered on the publisher's behalf.
                                    Text("Source: Manual Entry", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(stringResource(R.string.manage_reports_status_prefix, row.category.displayName), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    if (row.isPioneer) {
                                        stringResource(R.string.manage_reports_bible_study_hours, row.report.bibleStudiesCount, (row.report.hoursRendered ?: 0.0).toString())
                                    } else {
                                        val yesNo = if (row.report.participatedInPreaching == true) stringResource(R.string.consolidated_yes) else stringResource(R.string.consolidated_no)
                                        stringResource(R.string.manage_reports_bible_study_participate, row.report.bibleStudiesCount, yesNo)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                // My Planner / Reporting upgrade spec §38/§39
                                // — shown on screen so it matches the export
                                // exactly, not just present in the CSV/PDF.
                                Text(
                                    stringResource(R.string.manage_reports_return_visits_prefix, row.report.returnVisitsCount),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(stringResource(R.string.manage_reports_congregation_prefix, row.congregationName), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    when (row.report.status) {
                                        ReportStatus.POSTED -> stringResource(R.string.manage_reports_posted_locked)
                                        ReportStatus.SUBMITTED -> stringResource(R.string.manage_reports_submitted_editable)
                                        ReportStatus.RETURNED -> stringResource(R.string.manage_reports_returned_status, row.report.correctionReason.orEmpty())
                                        ReportStatus.CORRECTED -> stringResource(R.string.manage_reports_corrected_status)
                                        ReportStatus.DRAFT -> stringResource(R.string.manage_reports_draft_editable)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (row.isPosted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.manage_reports_total_bible_study, uiState.totalBibleStudies), style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.manage_reports_total_hours_pioneers, uiState.totalHoursByPioneers.toString()), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    val reportSavedToast = stringResource(R.string.manage_reports_report_saved)
    val reportDeletedToast = stringResource(R.string.manage_reports_report_deleted)
    val toEdit = pendingEdit
    if (toEdit != null) {
        EditReportDialog(
            row = toEdit,
            onDismiss = { pendingEdit = null },
            onSave = { bibleStudies, hours, participated ->
                viewModel.updateReport(toEdit.report, bibleStudies, hours, participated, currentPersonId)
                showToast(reportSavedToast)
            },
        )
    }

    val toReturn = pendingReturnForCorrection
    if (toReturn != null) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { pendingReturnForCorrection = null },
            title = { Text(stringResource(R.string.manage_reports_return_for_correction_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.manage_reports_return_for_correction_message, toReturn.person.fullName))
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text(stringResource(R.string.manage_reports_return_for_correction_reason_label)) },
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = reason.isNotBlank(),
                    onClick = {
                        viewModel.returnForCorrection(toReturn.report, reason, currentPersonId)
                        pendingReturnForCorrection = null
                    },
                ) { Text(stringResource(R.string.manage_reports_return_for_correction_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pendingReturnForCorrection = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.manage_reports_delete_title)) },
            text = { Text(stringResource(R.string.manage_reports_delete_message, toDelete.person.fullName)) },
            confirmButton = {
                TextButton(onClick = { viewModel.permanentlyDelete(toDelete.report, currentPersonId); showToast(reportDeletedToast); pendingDelete = null }) {
                    Text(stringResource(R.string.manage_reports_delete_permanently))
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (showPostAllConfirm) {
        val submittedCount = uiState.rows.count { it.report.status == ReportStatus.SUBMITTED }
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { showPostAllConfirm = false },
            title = { Text(stringResource(R.string.manage_reports_post_confirm_title, submittedCount, if (submittedCount == 1) "" else "s")) },
            text = {
                Text(stringResource(R.string.manage_reports_post_confirm_message, reportTitle))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.markPostedAll(uiState.rows, currentPersonId)
                    showToast(context.getString(R.string.manage_reports_posted_toast, submittedCount, if (submittedCount == 1) "" else "s"))
                    showPostAllConfirm = false
                }) { Text(stringResource(R.string.manage_reports_post_action)) }
            },
            dismissButton = { TextButton(onClick = { showPostAllConfirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Shared shape for both Print and CSV export — one place that decides what
 * a "Publisher Report" table actually contains, so the two outputs never
 * drift apart. */
private fun publisherReportTable(title: String, uiState: ManagePublisherReportsUiState): ReportTable {
    // My Planner / Reporting upgrade spec §38/§39 — export must exactly
    // match what's on screen (report §35 status label used here too, not a
    // re-derived string). Fixes a pre-existing mislabel: this column was
    // called "Status" but held the publisher's Classification the whole
    // time — that's its own column now, and "Status" holds the report's
    // real ReportStatus.
    val submittedDateFormat = java.text.SimpleDateFormat("MMM d, yyyy", Locale.US)
    val rows = uiState.rows.mapIndexed { index, row ->
        listOf(
            (index + 1).toString(),
            row.person.fullName,
            row.category.displayName,
            row.report.bibleStudiesCount.toString(),
            row.report.returnVisitsCount.toString(),
            if (row.isPioneer) formatHoursForExport(row.report.hoursRendered ?: 0.0) else "N/A",
            if (row.isPioneer) "N/A" else if (row.report.participatedInPreaching == true) "YES" else "NO",
            row.congregationName,
            row.report.status.name,
            row.report.submittedAt?.let { submittedDateFormat.format(java.util.Date(it)) } ?: "—",
        )
    }
    return ReportTable(
        title = title,
        count = rows.size,
        countLabel = "Total Publisher Reports",
        columns = listOf(
            "#", "Publisher", "Classification", "Bible Study", "Return Visits", "Hours",
            "Participate in Preaching", "Congregation", "Status", "Submission Date",
        ),
        rows = rows,
        totals = listOf(
            "Total Bible Study" to uiState.totalBibleStudies.toString(),
            "Total Return Visits" to uiState.totalReturnVisits.toString(),
            "Total Hours by Pioneers" to formatHoursForExport(uiState.totalHoursByPioneers),
        ),
    )
}

private fun formatHoursForExport(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(Locale.US, value)

/** "PUBLISHER MINISTRY REPORT FOR THE MONTH OF AUGUST 2026" when the range is
 * exactly one calendar month (the spec's own example, and this screen's
 * "This Month" default); a plain date-range heading otherwise. */
private fun reportTitleFor(startMillis: Long, endMillis: Long, option: QuickDateRange): String {
    if (option == QuickDateRange.THIS_MONTH) {
        val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        return "PUBLISHER MINISTRY REPORT FOR THE MONTH OF ${monthFormat.format(startMillis).uppercase()}"
    }
    val start = Calendar.getInstance().apply { timeInMillis = startMillis }
    val end = Calendar.getInstance().apply { timeInMillis = endMillis }
    val isWholeCalendarMonth = start.get(Calendar.DAY_OF_MONTH) == 1 &&
        end.get(Calendar.MONTH) == start.get(Calendar.MONTH) &&
        end.get(Calendar.YEAR) == start.get(Calendar.YEAR) &&
        end.get(Calendar.DAY_OF_MONTH) == end.getActualMaximum(Calendar.DAY_OF_MONTH)
    if (isWholeCalendarMonth) {
        val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        return "PUBLISHER MINISTRY REPORT FOR THE MONTH OF ${monthFormat.format(startMillis).uppercase()}"
    }
    val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return "PUBLISHER MINISTRY REPORT (${dateFormat.format(startMillis)} - ${dateFormat.format(endMillis)})"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PublisherPickerDropdown(publishers: List<Person>, selectedId: String?, onSelected: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val allLabel = stringResource(R.string.manage_reports_all)
    val selectedName = publishers.firstOrNull { it.id == selectedId }?.fullName ?: allLabel
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.manage_reports_publisher_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(allLabel) }, onClick = { onSelected(null); expanded = false })
            publishers.forEach { person ->
                DropdownMenuItem(text = { Text(person.fullName) }, onClick = { onSelected(person.id); expanded = false })
            }
        }
    }
}

/** "Edit directly the report if there are changes" — Bible Study/Hours/
 * Participated only; doesn't touch [MonthlyReport.status]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditReportDialog(
    row: PublisherReportRow,
    onDismiss: () -> Unit,
    onSave: (bibleStudiesCount: Int, hoursRendered: Double?, participatedInPreaching: Boolean?) -> Unit,
) {
    var bibleStudies by remember { mutableStateOf(row.report.bibleStudiesCount.toString()) }
    var hours by remember { mutableStateOf((row.report.hoursRendered ?: 0.0).toString()) }
    var participated by remember { mutableStateOf(row.report.participatedInPreaching ?: false) }

    fun submit() {
        onSave(
            bibleStudies.toIntOrNull() ?: 0,
            if (row.isPioneer) hours.toDoubleOrNull() ?: 0.0 else null,
            if (row.isPioneer) null else participated,
        )
        onDismiss()
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.manage_reports_edit_title, row.person.fullName),
        onConfirm = ::submit,
        confirmLabel = stringResource(R.string.manage_reports_save_changes),
        maxContentHeight = 400.dp,
        hasUnsavedChanges = bibleStudies != row.report.bibleStudiesCount.toString() ||
            hours != (row.report.hoursRendered ?: 0.0).toString() ||
            participated != (row.report.participatedInPreaching ?: false),
    ) {
                OutlinedTextField(
                    value = bibleStudies,
                    onValueChange = { bibleStudies = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.manage_reports_bible_studies_field)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (row.isPioneer) {
                    OutlinedTextField(
                        value = hours,
                        onValueChange = { hours = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text(stringResource(R.string.manage_reports_hours_field)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.manage_reports_participated_question))
                        Switch(checked = participated, onCheckedChange = { participated = it })
                    }
                }
    }
}
