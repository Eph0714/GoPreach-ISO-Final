package com.emfitsolutions.gopreach.ui.screens.reports

import com.emfitsolutions.gopreach.platform.rememberPlatformActions
import com.emfitsolutions.gopreach.platform.SimpleDateFormat
import com.emfitsolutions.gopreach.platform.Locale
import com.emfitsolutions.gopreach.platform.Date
import androidx.activity.compose.rememberLauncherForActivityResult
import com.emfitsolutions.gopreach.ui.components.RecordFound
import com.emfitsolutions.gopreach.data.model.displayName
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Card
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.print.ReportTable
import com.emfitsolutions.gopreach.ui.components.DateRange
import com.emfitsolutions.gopreach.ui.components.DateRangeFilterBar
import com.emfitsolutions.gopreach.ui.components.QuickDateRange
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.platform.Calendar

/** "For Admin, Elders and Service overseer select [All Group, Per Group]
 * filter in report" — [PER_GROUP] additionally needs one Group actually
 * picked (see [ReportsScreen]'s own `selectedGroupId`); [ALL_GROUPS] shows
 * every Group's section combined, same as before this filter existed. */
private enum class GroupFilterMode { ALL_GROUPS, PER_GROUP }

private fun PublisherCategory.displayLabel(): String = displayName
    .lowercase().split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

/** Spec §5.1 — Total Bible Studies / Interested People / preaching hours, per
 * publisher, grouped by Group, with an "All Publishers" summary at the top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    visibleCongregationId: String?,
    visibleGroupId: String? = null,
    /** Spec §5.2: only a Coordinator/Regular Elder may edit a publisher's
     * *submitted* monthly report; false hides the per-row edit affordance
     * entirely (e.g. for Super-Admin/Admin, who only view/export/print, spec §3). */
    canEditReports: Boolean = false,
    onEditPublisher: (personId: String) -> Unit = {},
    onBack: () -> Unit,
    viewModel: ReportsViewModel = koinViewModel(),
) {
    // "Select a congregation for Super Admin" — visibleCongregationId is
    // only ever null for Super-Admin (every other role is already fixed to
    // their own congregation upstream in GoPreachNavGraph, the actual
    // security boundary); this screen-local pick just narrows Super-Admin's
    // own already-unrestricted view, the same convention ManagePublishers
    // Screen's own CongregationFilterDropdown already uses. `null` here
    // means "All Congregations," same default as before this filter
    // existed.
    var congregationFilter by com.emfitsolutions.gopreach.ui.components.rememberCongregationContext("reports")
    val needsCongregation = visibleCongregationId == null && congregationFilter == null
    val effectiveCongregationId = visibleCongregationId ?: congregationFilter
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()

    // "For Admin, Elders and Service overseer select [All Group, Per Group]
    // filter" — generalizes the old Regular-Elder-only "My Group/My
    // Congregation" toggle into a picker every role scoped to one
    // congregation can use, not just Regular Elder. Defaults to PER_GROUP
    // pre-selected to [visibleGroupId] when the caller provided one (a
    // Regular Elder's own group — the same default the old toggle already
    // gave them), ALL_GROUPS otherwise.
    var groupFilterMode by remember { mutableStateOf(if (visibleGroupId != null) GroupFilterMode.PER_GROUP else GroupFilterMode.ALL_GROUPS) }
    var selectedGroupId by remember { mutableStateOf(visibleGroupId) }
    val groupsFlow = remember(effectiveCongregationId) { viewModel.groupsFor(effectiveCongregationId) }
    val groups by groupsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // "All Congregations" (no single congregation chosen yet) has no single
    // Group list to filter by — Per Group only makes sense once exactly one
    // congregation is in view.
    val canFilterByGroup = effectiveCongregationId != null
    val effectiveGroupId = if (canFilterByGroup && groupFilterMode == GroupFilterMode.PER_GROUP) selectedGroupId else null
    // Falling back to "All Congregations" (Super-Admin) drops any Per Group
    // pick along with it — there's no single Group list to have kept it
    // pointed at.
    LaunchedEffect(canFilterByGroup) {
        if (!canFilterByGroup) groupFilterMode = GroupFilterMode.ALL_GROUPS
    }

    // "Main Form Date Range Filtering" spec §7/§4/§8 — This Month by default
    // (calculated live from the current date), shared with the Dashboard's
    // own Reports screen via DateRangeStore rather than screen-local state,
    // so navigating between the two never resets the selection. Today/This
    // Week/This Month/This Year/Custom (From, To) are all offered by
    // [DateRangeFilterBar] below — nothing extra needed here for that.
    val dateRange by viewModel.dateRange.collectAsStateWithLifecycle()

    val rowsFlow = remember(effectiveCongregationId, effectiveGroupId, dateRange, needsCongregation) {
        if (needsCongregation) return@remember kotlinx.coroutines.flow.flowOf(emptyList())
        viewModel.rowsFor(effectiveCongregationId, effectiveGroupId, dateRange)
    }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // "Group the record by Group" — the same rows above, bucketed into
    // sections with their own Overseer/Servant/Assistant header and a
    // per-group summary total.
    val sectionsFlow = remember(effectiveCongregationId, effectiveGroupId, dateRange) {
        viewModel.groupedRowsFor(effectiveCongregationId, effectiveGroupId, dateRange)
    }
    val sections by sectionsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // "Make all reports have a print preview [and] export as pdf or excel,
    // put a heading" — safe to always offer: it's the exact same rows
    // already visible on screen, not a new data exposure, so there's no
    // separate Permission gate the way Add/Edit/Delete have via [readOnly]
    // elsewhere.
    val actions = rememberPlatformActions()
    val showToast = rememberActionToast()
    // "Change the Header text of the Publishers Report" — the printed/
    // exported report's own heading names the actual congregation being
    // reported on, not a fixed "GoPreach" — "All Congregations" is the one
    // case with no single name to use (Super-Admin viewing every
    // congregation at once, [effectiveCongregationId] null).
    val reportCongregationName = congregations.firstOrNull { it.id == effectiveCongregationId }?.name
        ?: "All Congregations"
    val reportTable = remember(sections, dateRange, reportCongregationName) {
        reportsTableFor(sections, dateRange, reportCongregationName)
    }
    // Bug fix ("I cannot see any PDF or Excel inside the Reports Summary"):
    // the Storage Access Framework picker just saves and closes — nothing
    // about that flow shows the result inside the app on its own. Now it
    // confirms the export succeeded and immediately opens the file, instead
    // of leaving the user to go find it in a file manager (or wonder whether
    // it ever actually happened).
    val exportCsvSuccess = stringResource(R.string.reports_export_csv_success)
    val exportFailedWrite = stringResource(R.string.reports_export_failed_write)
    val exportFailedUnknown = stringResource(R.string.reports_export_failed_unknown)
    val exportFailedGenericTemplate = stringResource(R.string.reports_export_failed_generic)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
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
    val exportFileName = "gopreach-reports-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.csv"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reports_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Bug fix ("I cannot see export to excel or pdf in
                    // Reports Summary"): these were `enabled = rows.isNotEmpty()`
                    // — a disabled IconButton's icon renders at reduced alpha,
                    // and on this TopAppBar's colors that read as "not there
                    // at all" rather than "there but dimmed," especially for
                    // whichever congregation/date range/scope happened to
                    // have zero submitted reports (the default landing state
                    // for plenty of sessions). Always enabled now — printing
                    // or exporting with nothing to show just produces a
                    // heading-only PDF/CSV, which is a harmless, valid
                    // result, not something worth hiding the buttons over.
                    // Dedicated PDF/Excel icons — distinct from the generic
                    // Print/Share glyphs, so the two actions read at a glance
                    // as "export as PDF" and "export as Excel" specifically.
                    IconButton(onClick = { actions.print(reportTable) }) {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = stringResource(R.string.reports_export_pdf_cd))
                    }
                    IconButton(onClick = { exportLauncher.launch(exportFileName) }) {
                        Icon(Icons.Rounded.TableChart, contentDescription = stringResource(R.string.reports_export_excel_cd))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        // "Select a congregation for Super Admin" — the only role that ever
        // reaches this screen with visibleCongregationId == null.
        if (visibleCongregationId == null) {
            ReportsCongregationDropdown(
                congregations = congregations,
                selectedId = congregationFilter,
                onSelected = { newCongregationId ->
                    congregationFilter = newCongregationId
                    // A Group picked under the previous congregation almost
                    // never belongs to the new one — reset rather than leave
                    // Per Group silently pointed at a stale/foreign Group.
                    selectedGroupId = null
                    groupFilterMode = GroupFilterMode.ALL_GROUPS
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        // "For Admin, Elders and Service overseer select [All Group, Per
        // Group] filter in report" — Super-Admin gets it too, once they've
        // narrowed to one congregation above (canFilterByGroup); there's
        // nothing role-specific about which of the four groups within a
        // congregation someone may look at once they can already see that
        // whole congregation's combined report.
        if (canFilterByGroup) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                SegmentedButton(
                    selected = groupFilterMode == GroupFilterMode.ALL_GROUPS,
                    onClick = { groupFilterMode = GroupFilterMode.ALL_GROUPS },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.reports_all_groups)) }
                SegmentedButton(
                    selected = groupFilterMode == GroupFilterMode.PER_GROUP,
                    onClick = { groupFilterMode = GroupFilterMode.PER_GROUP },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.reports_per_group)) }
            }
            if (groupFilterMode == GroupFilterMode.PER_GROUP) {
                ReportsGroupDropdown(
                    groups = groups,
                    selectedGroupId = selectedGroupId,
                    onSelected = { selectedGroupId = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        DateRangeFilterBar(
            range = dateRange,
            onRangeChange = viewModel::setDateRange,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (needsCongregation) {
            com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt()
        } else if (rows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.reports_no_submitted_yet),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    // "Separate the Total report of Regular Pioneers and
                    // Auxiliary Pioneer" / "Separate the Auxiliary and
                    // Regular Pioneer Bible Study and Total Report" — the
                    // combined Bible Studies/Hours lines above used to fold
                    // every category into one figure; these extra lines call
                    // out each Pioneer category's own publisher count, Bible
                    // Study total, and Hours total explicitly, at the
                    // congregation-wide "General Summary" level, the same
                    // way each Group's own section already breaks them out
                    // (see GroupReportCard).
                    val countsByCategory = rows.categoryCounts()
                    val bibleStudiesByCategory = rows.categoryBibleStudies()
                    val hoursByCategory = rows.categoryHours()
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.reports_all_publishers), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.reports_bible_studies_count, rows.sumOf { it.totalBibleStudies }), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.reports_hours_count, "%.1f".format(rows.sumOf { it.totalHours })), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.reports_interested_people_count, rows.sumOf { it.totalInterestedPeople }),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                stringResource(R.string.reports_total_regular_pioneer, countsByCategory[PublisherCategory.REGULAR_PIONEER] ?: 0),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            // "Add Special Pioneer publisher status category"
                            // — its own breakdown line, same "keep display
                            // distinct" treatment Regular/Auxiliary Pioneer
                            // already get here.
                            Text(
                                stringResource(R.string.reports_total_special_pioneer, countsByCategory[PublisherCategory.SPECIAL_PIONEER] ?: 0),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.reports_total_auxiliary_pioneer, countsByCategory[PublisherCategory.AUXILIARY_PIONEER] ?: 0),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            // "Include the total number of Regular
                            // Publisher, Unbaptized Publisher to every group
                            // summary and grand total summary" — each
                            // Group's own section already listed these (its
                            // categoryCounts loop covers every category
                            // present, not just Pioneers); this grand total
                            // card only had the two Pioneer counts explicitly
                            // called out, so these two were missing here.
                            Text(
                                stringResource(R.string.reports_total_regular_publisher, countsByCategory[PublisherCategory.REGULAR_PUBLISHER] ?: 0),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.reports_total_unbaptized_publisher, countsByCategory[PublisherCategory.UNBAPTIZED_PUBLISHER] ?: 0),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.reports_total_bible_studies_for, PublisherCategory.REGULAR_PIONEER.displayLabel(), bibleStudiesByCategory[PublisherCategory.REGULAR_PIONEER] ?: 0),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.reports_total_bible_studies_for, PublisherCategory.SPECIAL_PIONEER.displayLabel(), bibleStudiesByCategory[PublisherCategory.SPECIAL_PIONEER] ?: 0),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.reports_total_bible_studies_for, PublisherCategory.AUXILIARY_PIONEER.displayLabel(), bibleStudiesByCategory[PublisherCategory.AUXILIARY_PIONEER] ?: 0),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.reports_total_hours_for, PublisherCategory.REGULAR_PIONEER.displayLabel(), "%.1f".format(hoursByCategory[PublisherCategory.REGULAR_PIONEER] ?: 0.0)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.reports_total_hours_for, PublisherCategory.SPECIAL_PIONEER.displayLabel(), "%.1f".format(hoursByCategory[PublisherCategory.SPECIAL_PIONEER] ?: 0.0)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.reports_total_hours_for, PublisherCategory.AUXILIARY_PIONEER.displayLabel(), "%.1f".format(hoursByCategory[PublisherCategory.AUXILIARY_PIONEER] ?: 0.0)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                item { RecordFound(sections.sumOf { it.rows.size }) }
                items(sections, key = { it.groupId ?: "unassigned" }) { section ->
                    GroupReportCard(section, dateRange, canEditReports, onEditPublisher)
                }
            }
        }
        }
    }
}

/** "Select a congregation for Super Admin" — same shape
 * ManagePublishersScreen's own CongregationFilterDropdown already uses;
 * `null` selection means "All Congregations" (this screen's original,
 * unfiltered default). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportsCongregationDropdown(
    congregations: List<Congregation>,
    selectedId: String?,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = congregations.firstOrNull { it.id == selectedId }?.name ?: "Select Congregation"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.reports_congregation_group_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            congregations.forEach { c ->
                DropdownMenuItem(text = { Text(c.name) }, onClick = { onSelected(c.id); expanded = false })
            }
        }
    }
}

/** "Per Group" — which Group, within whichever congregation is currently in
 * view, to narrow the report down to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportsGroupDropdown(
    groups: List<Group>,
    selectedGroupId: String?,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = groups.firstOrNull { it.id == selectedGroupId }?.name ?: stringResource(R.string.reports_select_a_group)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.reports_group_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (groups.isEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.reports_no_groups_yet)) }, onClick = {}, enabled = false)
            }
            groups.forEach { g ->
                DropdownMenuItem(text = { Text(g.name) }, onClick = { onSelected(g.id); expanded = false })
            }
        }
    }
}

@Composable
private fun GroupReportCard(
    section: GroupReportSection,
    dateRange: DateRange,
    canEditReports: Boolean,
    onEditPublisher: (personId: String) -> Unit,
) {
    val yes = stringResource(R.string.home_yes)
    val no = stringResource(R.string.home_no)
    val na = stringResource(R.string.reports_na)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.reports_group_name, section.groupName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.reports_group_overseer, section.overseerName ?: "—"), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.reports_group_servant, section.servantName ?: "—"), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.reports_group_assistant, section.assistantName ?: "—"), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            section.rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.person.fullName, style = MaterialTheme.typography.titleSmall)
                        Text(row.category.displayLabel(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.reports_row_summary, "%.1f".format(row.totalHours), row.totalBibleStudies, row.attendedPreaching.toYesNoNa(yes, no, na)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (canEditReports) {
                        IconButton(onClick = { onEditPublisher(row.person.id) }) {
                            Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.reports_edit_this_month_cd))
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                stringResource(R.string.reports_summary_total_for, dateRange.periodLabel()),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            section.categoryCounts.entries.sortedBy { it.key.displayLabel() }.forEach { (category, count) ->
                Text(stringResource(R.string.reports_total_category, category.displayLabel(), count), style = MaterialTheme.typography.bodySmall)
            }
            // "Separate the Auxiliary and Regular Pioneer Bible Study" — same
            // per-category breakdown Hours already had, now for Bible
            // Studies too.
            section.categoryBibleStudies.entries.sortedBy { it.key.displayLabel() }.forEach { (category, count) ->
                Text(stringResource(R.string.reports_total_bible_studies_for, category.displayLabel(), count), style = MaterialTheme.typography.bodySmall)
            }
            // "Total of hours per group... Regular Pioneer, Auxiliary
            // Pioneer and Special Pioneer" — all three always listed (0.0
            // when none), then the group's overall total across every
            // category.
            section.pioneerHours.forEach { (category, hours) ->
                Text(stringResource(R.string.reports_total_hours_for, category.displayLabel(), "%.1f".format(hours)), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                stringResource(R.string.reports_group_total_hours, "%.1f".format(section.totalHours)),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun Boolean?.toYesNoNa(yes: String, no: String, na: String): String = when (this) {
    true -> yes
    false -> no
    null -> na
}

private fun DateRange.periodLabel(): String {
    val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
    return "${dateFormat.format(Date(startMillis))} - ${dateFormat.format(Date(endMillis))}"
}

/** "Sept," not "Sep" — the exact abbreviation asked for; every other month
 * keeps the standard three-letter form. Used only by [reportHeaderFor]'s own
 * date-range wording, never [periodLabel] above (the per-group "SUMMARY
 * TOTAL FOR THE PERIOD OF ..." line inside the report body) — the header
 * text is the only thing this request asked to change. */
private val REPORT_HEADER_SHORT_MONTHS = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sept", "Oct", "Nov", "Dec",
)

private fun reportHeaderDate(millis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    return "${REPORT_HEADER_SHORT_MONTHS[cal.get(Calendar.MONTH)]} ${cal.get(Calendar.DAY_OF_MONTH)}, ${cal.get(Calendar.YEAR)}"
}

/** "GoPreach App — Change Publisher Report Header": the congregation-scoped
 * heading for the printed/exported Publisher Report — "{Congregation}
 * Publisher Report of {period}", where {period} depends on exactly how the
 * period was picked, never a fixed "GoPreach Publisher Report(s) (...)" for
 * every case the way this used to read:
 * - [QuickDateRange.THIS_MONTH] -> "September 2026" (full month name + year)
 * - [QuickDateRange.THIS_YEAR] -> "2026" (year alone)
 * - anything else (Today/This Week/a custom Start-End pick) -> "Sept 1, 2026
 *   to Sept 30, 2026" — "to" between the two dates, never parentheses or a
 *   hyphen, per this request's own worked examples.
 */
private fun reportHeaderFor(congregationName: String, dateRange: DateRange): String {
    val period = when (dateRange.option) {
        QuickDateRange.THIS_MONTH -> SimpleDateFormat("MMMM yyyy", Locale.US).format(Date(dateRange.startMillis))
        QuickDateRange.THIS_YEAR -> Calendar.getInstance().apply { timeInMillis = dateRange.startMillis }.get(Calendar.YEAR).toString()
        QuickDateRange.TODAY, QuickDateRange.THIS_WEEK, QuickDateRange.CUSTOM ->
            "${reportHeaderDate(dateRange.startMillis)} to ${reportHeaderDate(dateRange.endMillis)}"
    }
    return "$congregationName Publisher Report of $period"
}

/** Shared shape for both Print and CSV export — "put a heading" (the title
 * plus the period line) is baked in here, same as before, now walking every
 * [GroupReportSection] in turn: a group header pseudo-row, that group's
 * publisher rows, then its own "SUMMARY TOTAL" pseudo-rows, before moving to
 * the next group — matches the on-screen grouping exactly so the export
 * never shows a different shape than what's on screen. */
private fun reportsTableFor(sections: List<GroupReportSection>, dateRange: DateRange, congregationName: String): ReportTable {
    val periodLabel = dateRange.periodLabel()
    val columns = listOf("Status", "Publisher Name", "Hours", "Bible Studies", "Attended Preaching")
    val rows = mutableListOf<List<String>>()
    sections.forEach { section ->
        rows += listOf(
            "GROUP: ${section.groupName}",
            "Overseer: ${section.overseerName ?: "—"} | Servant: ${section.servantName ?: "—"} | Assistant: ${section.assistantName ?: "—"}",
            "", "", "",
        )
        section.rows.forEach { row ->
            rows += listOf(
                row.category.displayLabel(),
                row.person.fullName,
                "%.1f".format(row.totalHours),
                row.totalBibleStudies.toString(),
                row.attendedPreaching.toYesNoNa("Yes", "No", "N/A"),
            )
        }
        rows += listOf("SUMMARY TOTAL FOR THE PERIOD OF $periodLabel", "", "", "", "")
        section.categoryCounts.entries.sortedBy { it.key.displayLabel() }.forEach { (category, count) ->
            rows += listOf("Total ${category.displayLabel()}", count.toString(), "", "", "")
        }
        section.categoryBibleStudies.entries.sortedBy { it.key.displayLabel() }.forEach { (category, count) ->
            rows += listOf("Total Bible Studies for ${category.displayLabel()}", count.toString(), "", "", "")
        }
        section.pioneerHours.forEach { (category, hours) ->
            rows += listOf("Total Hours for ${category.displayLabel()}", "%.1f".format(hours), "", "", "")
        }
        rows += listOf("Total Hours for this Group (All Categories)", "%.1f".format(section.totalHours), "", "", "")
        rows += listOf("", "", "", "", "")
    }
    val allRows = sections.flatMap { it.rows }
    // "Separate the Total report of Regular Pioneers and Auxiliary
    // Pioneer"/"...Bible Study and Total Report" — called out as their own
    // totals lines, same as the on-screen "All Publishers" card, instead of
    // folding into the single combined Bible Studies/Hours totals below.
    val countsByCategory = allRows.categoryCounts()
    val bibleStudiesByCategory = allRows.categoryBibleStudies()
    val hoursByCategory = allRows.categoryHours()
    return ReportTable(
        title = reportHeaderFor(congregationName, dateRange),
        count = allRows.size,
        countLabel = "Total Publishers",
        columns = columns,
        rows = rows,
        totals = listOf(
            "Bible Studies" to allRows.sumOf { it.totalBibleStudies }.toString(),
            "Hours" to "%.1f".format(allRows.sumOf { it.totalHours }),
            "Total Regular Pioneer" to (countsByCategory[PublisherCategory.REGULAR_PIONEER] ?: 0).toString(),
            "Total Special Pioneer" to (countsByCategory[PublisherCategory.SPECIAL_PIONEER] ?: 0).toString(),
            "Total Auxiliary Pioneer" to (countsByCategory[PublisherCategory.AUXILIARY_PIONEER] ?: 0).toString(),
            // "Include the total number of Regular Publisher, Unbaptized
            // Publisher to every group summary and grand total summary" —
            // each Group's own SUMMARY TOTAL block above already listed
            // these via categoryCounts; this overall totals row only had
            // the two Pioneer counts explicitly called out.
            "Total Publisher" to (countsByCategory[PublisherCategory.REGULAR_PUBLISHER] ?: 0).toString(),
            "Total Unbaptized Publisher" to (countsByCategory[PublisherCategory.UNBAPTIZED_PUBLISHER] ?: 0).toString(),
            "Total Bible Studies for Regular Pioneer" to (bibleStudiesByCategory[PublisherCategory.REGULAR_PIONEER] ?: 0).toString(),
            "Total Bible Studies for Special Pioneer" to (bibleStudiesByCategory[PublisherCategory.SPECIAL_PIONEER] ?: 0).toString(),
            "Total Bible Studies for Auxiliary Pioneer" to (bibleStudiesByCategory[PublisherCategory.AUXILIARY_PIONEER] ?: 0).toString(),
            "Total Hours for Regular Pioneer" to "%.1f".format(hoursByCategory[PublisherCategory.REGULAR_PIONEER] ?: 0.0),
            "Total Hours for Special Pioneer" to "%.1f".format(hoursByCategory[PublisherCategory.SPECIAL_PIONEER] ?: 0.0),
            "Total Hours for Auxiliary Pioneer" to "%.1f".format(hoursByCategory[PublisherCategory.AUXILIARY_PIONEER] ?: 0.0),
        ),
    )
}
