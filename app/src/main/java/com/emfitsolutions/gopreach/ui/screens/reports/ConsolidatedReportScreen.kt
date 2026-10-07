package com.emfitsolutions.gopreach.ui.screens.reports

import com.emfitsolutions.gopreach.shared.resources.Res
import com.emfitsolutions.gopreach.shared.resources.action_close
import com.emfitsolutions.gopreach.shared.resources.consolidated_bible_studies_return_visits
import com.emfitsolutions.gopreach.shared.resources.consolidated_bible_study_records
import com.emfitsolutions.gopreach.shared.resources.consolidated_hours_suffix
import com.emfitsolutions.gopreach.shared.resources.consolidated_loading
import com.emfitsolutions.gopreach.shared.resources.consolidated_no
import com.emfitsolutions.gopreach.shared.resources.consolidated_no_publishers_found
import com.emfitsolutions.gopreach.shared.resources.consolidated_no_report_yet
import com.emfitsolutions.gopreach.shared.resources.consolidated_none_recorded
import com.emfitsolutions.gopreach.shared.resources.consolidated_participated_ministry_value
import com.emfitsolutions.gopreach.shared.resources.consolidated_per_publisher
import com.emfitsolutions.gopreach.shared.resources.consolidated_preaching_hours_value
import com.emfitsolutions.gopreach.shared.resources.consolidated_preaching_time_records
import com.emfitsolutions.gopreach.shared.resources.consolidated_return_visit_records
import com.emfitsolutions.gopreach.shared.resources.consolidated_stat_bible_studies
import com.emfitsolutions.gopreach.shared.resources.consolidated_stat_participated_ministry
import com.emfitsolutions.gopreach.shared.resources.consolidated_stat_preaching_hours
import com.emfitsolutions.gopreach.shared.resources.consolidated_tap_publisher_hint
import com.emfitsolutions.gopreach.shared.resources.consolidated_title
import com.emfitsolutions.gopreach.shared.resources.consolidated_yes
import com.emfitsolutions.gopreach.shared.resources.reports_congregation_group_label
import com.emfitsolutions.gopreach.shared.resources.reports_export_csv_success
import com.emfitsolutions.gopreach.shared.resources.reports_export_excel_cd
import com.emfitsolutions.gopreach.shared.resources.reports_export_failed_generic
import com.emfitsolutions.gopreach.shared.resources.reports_export_failed_unknown
import com.emfitsolutions.gopreach.shared.resources.reports_export_failed_write
import com.emfitsolutions.gopreach.shared.resources.reports_export_pdf_cd
import com.emfitsolutions.gopreach.platform.rememberFileCreator
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import kotlinx.coroutines.flow.collect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.print.ReportTable
import com.emfitsolutions.gopreach.ui.components.DateRangeFilterBar
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.charts.StatCard
import com.emfitsolutions.gopreach.ui.screens.home.isPioneerCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.window.DialogProperties

/**
 * "Consolidated Monthly Report" spec — reachable by Service Overseer,
 * Coordinator Elder, Admin (own congregation), and Super-Admin (every
 * congregation, with a picker — same convention as the Dashboard).
 * [visibleCongregationIds] is the actual access-control boundary, resolved
 * once by the caller from the session's own role (see GoPreachNavGraph),
 * exactly like every other scoped report in this app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsolidatedReportScreen(
    visibleCongregationIds: Set<String>?,
    onBack: () -> Unit,
    viewModel: ConsolidatedReportViewModel = koinViewModel(),
) {
    LaunchedEffect(visibleCongregationIds) { viewModel.restrictTo(visibleCongregationIds) }
    // Started only while this screen is open — see VisitRepository's doc
    // comment on why this isn't an app-wide listener.
    // Bug fix: startVisitSync() returns a cold Flow — must be collected or
    // the underlying Firestore listener never actually registers.
    LaunchedEffect(Unit) { viewModel.startVisitSync().collect {} }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedPublisherId by remember { mutableStateOf<String?>(null) }

    // "Make all reports have a print preview [and] export as pdf or excel,
    // put a heading" — same shared ReportTable/CsvExporter/ReportPrinter
    // shape every other report screen uses.
    val actions = rememberPlatformActions()
    val showToast = rememberActionToast()
    val reportTable = remember(uiState.visibleEntries, uiState.dateRange) { consolidatedReportTableFor(uiState) }
    // Bug fix ("I cannot see any PDF or Excel"): see ReportsScreen's matching
    // fix — the Storage Access Framework picker just saves and closes with
    // no feedback of its own; now it confirms and opens the file immediately.
    val exportCsvSuccess = stringResource(Res.string.reports_export_csv_success)
    val exportFailedWrite = stringResource(Res.string.reports_export_failed_write)
    val exportFailedUnknown = stringResource(Res.string.reports_export_failed_unknown)
    val exportFailedGenericTemplate = stringResource(Res.string.reports_export_failed_generic)
    val exportLauncher = rememberFileCreator("text/csv") { uri ->
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
    val exportFileName = "gopreach-consolidated-report-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.csv"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.consolidated_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    // Bug fix ("I cannot see export to excel or pdf"): these
                    // were disabled when there were no visible entries — a
                    // disabled IconButton's icon renders at reduced alpha,
                    // which on this TopAppBar read as "not there at all."
                    // Always enabled now; printing/exporting with nothing to
                    // show just produces a heading-only result.
                    IconButton(onClick = { actions.print(reportTable) }) {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = stringResource(Res.string.reports_export_pdf_cd))
                    }
                    IconButton(onClick = { exportLauncher.launch(exportFileName) }) {
                        Icon(Icons.Rounded.TableChart, contentDescription = stringResource(Res.string.reports_export_excel_cd))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (uiState.congregationsInScope.size > 1) {
                        CongregationScopeDropdown(
                            congregationNames = uiState.congregationsInScope.map { it.id to it.name },
                            selectedId = uiState.selectedCongregationId,
                            onSelected = viewModel::selectCongregation,
                        )
                    }
                    DateRangeFilterBar(range = uiState.dateRange, onRangeChange = viewModel::setDateRange)
                    Text(
                        "${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(uiState.dateRange.startMillis))} – " +
                            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(uiState.dateRange.endMillis)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard(stringResource(Res.string.consolidated_stat_bible_studies), uiState.totalBibleStudies.toString(), onClick = {}, modifier = Modifier.weight(1f))
                        StatCard(stringResource(Res.string.consolidated_stat_preaching_hours), "%.1f".format(uiState.totalPreachingHours), onClick = {}, modifier = Modifier.weight(1f))
                    }
                    if (uiState.regularPublisherEntries.isNotEmpty()) {
                        StatCard(
                            stringResource(Res.string.consolidated_stat_participated_ministry),
                            "${uiState.participatedYesCount} / ${uiState.regularPublisherEntries.size}",
                            onClick = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                    Text(stringResource(Res.string.consolidated_per_publisher), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(Res.string.consolidated_tap_publisher_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (uiState.isLoading) {
                item { Text(stringResource(Res.string.consolidated_loading), style = MaterialTheme.typography.bodyMedium) }
            } else if (uiState.needsCongregation) {
                item { com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt(Modifier.fillMaxWidth()) }
            } else if (uiState.visibleEntries.isEmpty()) {
                item { Text(stringResource(Res.string.consolidated_no_publishers_found), style = MaterialTheme.typography.bodyMedium) }
            }
            item { RecordFound(uiState.visibleEntries.size) }
            items(uiState.visibleEntries, key = { it.person.id }) { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { selectedPublisherId = entry.person.id },
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.person.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "${entry.category.displayName} · ${entry.congregationName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(Res.string.consolidated_bible_studies_return_visits, entry.bibleStudiesCount, entry.returnVisitsCount),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (isPioneerCategory(entry.category)) {
                            Text(
                                stringResource(Res.string.consolidated_preaching_hours_value, "%.1f".format(entry.preachingHours)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            val ministryValue = entry.participatedInMinistry?.let {
                                if (it) stringResource(Res.string.consolidated_yes) else stringResource(Res.string.consolidated_no)
                            } ?: stringResource(Res.string.consolidated_no_report_yet)
                            Text(
                                stringResource(Res.string.consolidated_participated_ministry_value, ministryValue),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            item(key = "end-summary") {
                val e = uiState.visibleEntries
                com.emfitsolutions.gopreach.ui.components.EndSummary(
                    listOf(
                        "Total Publishers" to e.size.toString(),
                        "Total Hours" to e.sumOf { it.preachingHours }.let { h -> if (h % 1.0 == 0.0) h.toLong().toString() else "%.1f".format(h) },
                        "Total Return Visits" to e.sumOf { it.returnVisitsCount }.toString(),
                        "Total Bible Studies" to e.sumOf { it.bibleStudiesCount }.toString(),
                        "Participated in the Ministry" to e.count { it.participatedInMinistry == true }.toString(),
                    ),
                    Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    val entry = uiState.entries.firstOrNull { it.person.id == selectedPublisherId }
    if (entry != null) {
        PublisherRecordsDialog(entry = entry, viewModel = viewModel, onDismiss = { selectedPublisherId = null })
    }
}

/** Shared shape for both Print and CSV export — "put a heading" (the title
 * plus the period line) is baked in here so it's identical for both. */
private fun consolidatedReportTableFor(uiState: ConsolidatedReportUiState): ReportTable {
    val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
    val periodLabel = "${dateFormat.format(Date(uiState.dateRange.startMillis))} - ${dateFormat.format(Date(uiState.dateRange.endMillis))}"
    return ReportTable(
        title = "GoPreach Consolidated Monthly Report ($periodLabel)",
        count = uiState.visibleEntries.size,
        countLabel = "Total Publishers",
        // My Planner / Reporting upgrade spec §37 — fixes a pre-existing
        // mislabel: this column was called "Status" but always held the
        // publisher's Classification, never a report ReportStatus (this
        // screen shows a multi-report date-range aggregate per publisher, so
        // there's no single report status to show here at all).
        columns = listOf("Publisher", "Classification", "Congregation", "Bible Studies", "Return Visits", "Preaching Hours", "Participated in Ministry"),
        rows = uiState.visibleEntries.map { entry ->
            listOf(
                entry.person.fullName,
                entry.category.displayName,
                entry.congregationName,
                entry.bibleStudiesCount.toString(),
                entry.returnVisitsCount.toString(),
                if (isPioneerCategory(entry.category)) "%.1f".format(Locale.US, entry.preachingHours) else "N/A",
                if (isPioneerCategory(entry.category)) "N/A" else entry.participatedInMinistry?.let { if (it) "YES" else "NO" } ?: "No report yet",
            )
        },
        totals = listOf(
            "Total Return Visits" to uiState.visibleEntries.sumOf { it.returnVisitsCount }.toString(),
            "Total Bible Studies" to uiState.totalBibleStudies.toString(),
            "Total Preaching Hours" to "%.1f".format(Locale.US, uiState.totalPreachingHours),
            "Participated in Ministry" to "${uiState.participatedYesCount} / ${uiState.regularPublisherEntries.size}",
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CongregationScopeDropdown(congregationNames: List<Pair<String, String>>, selectedId: String?, onSelected: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = congregationNames.firstOrNull { it.first == selectedId }?.second ?: "Select Congregation"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(Res.string.reports_congregation_group_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            congregationNames.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelected(id); expanded = false })
            }
        }
    }
}

/** Spec items 3-5 — "see the record of Bible Study/Return Visit/Preaching
 * [Time] per publisher," read-only. Preaching Time only shown for a
 * Pioneer, matching the rest of this app's "not applicable to Regular/
 * Unbaptized Publishers" rule. */
@Composable
private fun PublisherRecordsDialog(
    entry: PublisherReportEntry,
    viewModel: ConsolidatedReportViewModel,
    onDismiss: () -> Unit,
) {
    val bibleStudiesFlow = remember(entry.person.id) { viewModel.bibleStudiesFor(entry.person.id) }
    val bibleStudies by bibleStudiesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val visitsFlow = remember(entry.person.id) { viewModel.visitsFor(entry.person.id) }
    val visits by visitsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val preachingFlow = remember(entry.person.id) { viewModel.preachingRecordsFor(entry.person.id) }
    val preachingRecords by preachingFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        onDismissRequest = onDismiss,
        title = { Text(entry.person.fullName) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val noneRecorded = stringResource(Res.string.consolidated_none_recorded)
                Text(stringResource(Res.string.consolidated_bible_study_records), style = MaterialTheme.typography.titleSmall)
                if (bibleStudies.isEmpty()) {
                    Text(noneRecorded, style = MaterialTheme.typography.bodySmall)
                } else {
                    bibleStudies.forEach { record ->
                        Text("• ${record.name} — ${record.address}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(stringResource(Res.string.consolidated_return_visit_records), style = MaterialTheme.typography.titleSmall)
                if (visits.isEmpty()) {
                    Text(noneRecorded, style = MaterialTheme.typography.bodySmall)
                } else {
                    visits.sortedByDescending { it.visitDate }.forEach { visit ->
                        Text(
                            "• ${dateFormat.format(Date(visit.visitDate))} — ${visit.outcome.name.replace('_', ' ')}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (isPioneerCategory(entry.category)) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(stringResource(Res.string.consolidated_preaching_time_records), style = MaterialTheme.typography.titleSmall)
                    if (preachingRecords.isEmpty()) {
                        Text(noneRecorded, style = MaterialTheme.typography.bodySmall)
                    } else {
                        preachingRecords.sortedByDescending { it.date }.forEach { record ->
                            Text(
                                "• ${dateFormat.format(Date(record.date))} — ${stringResource(Res.string.consolidated_hours_suffix, "%.2f".format(record.hoursConsumed))}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } },
    )
}
