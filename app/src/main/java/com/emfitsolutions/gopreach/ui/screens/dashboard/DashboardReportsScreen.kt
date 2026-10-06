package com.emfitsolutions.gopreach.ui.screens.dashboard

import com.emfitsolutions.gopreach.platform.rememberFileCreator
import com.emfitsolutions.gopreach.platform.rememberPlatformActions
import com.emfitsolutions.gopreach.platform.SimpleDateFormat
import com.emfitsolutions.gopreach.platform.Locale
import com.emfitsolutions.gopreach.platform.Date
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.data.print.ReportTable
import com.emfitsolutions.gopreach.ui.components.DateRange
import com.emfitsolutions.gopreach.ui.components.DateRangeFilterBar
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.charts.StatCard
import androidx.compose.ui.window.DialogProperties

// A fixed color code shared between the KPI cards and the donut chart, so
// "Regular Pioneers" (say) always reads as the same color everywhere on this
// dashboard — the explicit "add a color code" request.
private val COLOR_HOURS: Color get() = Color(0xFFE0A526)

/** What the details dialog shows for one tapped [StatCard] — spec: "make the
 * button clickable... show the details inside when clicked." [breakdown] is
 * whatever sub-figures actually compose that headline number, when there
 * are any (e.g. Total Publishers breaks down into its categories); it's
 * empty for a figure with no further breakdown in this app's data model,
 * in which case the dialog just confirms the value, congregation, and
 * period it's for. */
private data class StatDetail(
    val label: String,
    val value: String,
    val breakdown: List<Pair<String, String>>,
)

private val ELDER_STATUSES = setOf("Coordinator Elder", "Regular Elder", "Service Overseer", "Secretary")

/** Which of [StatMember.statuses] are actually relevant to the card whose
 * dialog is currently showing them — a person often holds roles that span
 * both an Elder title *and* a Publisher category at once (e.g. a Regular
 * Elder who's also a Regular Pioneer), but "Total Elders"' own list should
 * only ever show their elder title(s), not their unrelated publisher
 * category, and vice versa. [StatDetail.label] is either an elder-track
 * label ("Total Elders"/"Total Ministerial") or a publisher-track one
 * (everything else this dialog is ever opened from) — never both — so a
 * simple either/or split is enough here. */
private fun relevantStatuses(detailLabel: String, member: StatMember): Set<String> =
    if (detailLabel == "Total Elders" || detailLabel == "Total Ministerial") {
        member.statuses.filter { it in ELDER_STATUSES || it == "Ministerial Servant" }.toSet()
    } else {
        member.statuses - ELDER_STATUSES - "Ministerial Servant"
    }

/** "add also status, example 'EVAROSE FERNANDEZ (REGULAR PIONEER)'" — shared
 * between the on-screen dialog list and its PDF/Excel export so both always
 * show the same thing. Multiple statuses (a person holding more than one
 * Elder-title role at once) are joined with "/"; no parentheses at all when
 * this dashboard has no specific status resolved for that role. */
private fun memberDisplayName(detailLabel: String, member: StatMember): String {
    val statuses = relevantStatuses(detailLabel, member)
    if (statuses.isEmpty()) return member.fullName
    return "${member.fullName} (${statuses.sorted().joinToString(" / ") { it.uppercase() }})"
}

/** Shared by [DashboardStatsContent] (the on-screen cards) and
 * [DashboardReportsScreen] (its PDF/Excel export) so the two can never list
 * a different set of figures — same source, one place this list is defined. */
private fun buildStatCards(displayed: CongregationStats): List<StatDetail> {
    // "Add Special Pioneer publisher status category" — its own figures
    // throughout, folded in alongside Regular Pioneer's wherever this
    // dashboard already breaks Regular Pioneer out as its own line (never
    // silently absorbed into it — see PublisherCategory.SPECIAL_PIONEER's
    // own doc comment).
    val totalHours = displayed.regularPioneerHours + displayed.specialPioneerHours + displayed.auxiliaryPioneerHours
    return listOf(
        StatDetail(
            "Total Publishers", displayed.totalPublishers.toString(),
            breakdown = listOf(
                "Publishers" to displayed.regularPublishers.toString(),
                "Regular Pioneers" to displayed.regularPioneers.toString(),
                "Special Pioneers" to displayed.specialPioneers.toString(),
                "Auxiliary Pioneers" to displayed.auxiliaryPioneers.toString(),
                "Unbaptized Publishers" to displayed.unbaptizedPublishers.toString(),
                "Inactive Publishers" to displayed.inactivePublishers.toString(),
            ),
        ),
        StatDetail("Total Elders", displayed.totalElders.toString(), emptyList()),
        StatDetail("Total Ministerial", displayed.totalMinisterial.toString(), emptyList()),
        StatDetail("Regular Pioneers", displayed.regularPioneers.toString(), emptyList()),
        StatDetail("Special Pioneers", displayed.specialPioneers.toString(), emptyList()),
        StatDetail("Auxiliary Pioneers", displayed.auxiliaryPioneers.toString(), emptyList()),
        StatDetail("Unbaptized Publishers", displayed.unbaptizedPublishers.toString(), emptyList()),
        StatDetail("Inactive Publishers", displayed.inactivePublishers.toString(), emptyList()),
        StatDetail("Removed Publishers", displayed.removedPublishers.toString(), emptyList()),
        StatDetail("Bible Studies", displayed.totalBibleStudies.toString(), emptyList()),
        StatDetail(
            "Total Preaching Hours", "%.1f".format(totalHours),
            breakdown = listOf(
                "Regular Pioneer Hours" to "%.1f".format(displayed.regularPioneerHours),
                "Special Pioneer Hours" to "%.1f".format(displayed.specialPioneerHours),
                "Auxiliary Pioneer Hours" to "%.1f".format(displayed.auxiliaryPioneerHours),
            ),
        ),
    )
}

/**
 * "Role-Based Dashboard... Graphical Reports" spec §3-§5,§7,§8,§15 — KPI
 * cards + charts, with no `Scaffold`/`TopAppBar` of its own so it can be
 * embedded directly on a role's main dashboard body (see [AdminHomeScreen]'s
 * "graphical Summary in the main form" requirement), not just reached as a
 * separate screen. [DashboardReportsScreen] below wraps this exact same
 * content for the cases (Coordinator Elder, Regular Elder, ...) that still
 * navigate to it as its own destination.
 *
 * Scoped automatically to whatever congregation(s) the signed-in session is
 * authorized to see (enforced upstream, before this composable ever runs —
 * see [DashboardStatsViewModel]'s doc comment). A Super-Admin sees every
 * congregation plus a comparison bar chart; anyone else sees exactly their
 * own congregation's numbers, full stop — there is no congregation picker to
 * escape that scope with.
 *
 * The KPI row is a wrapping [FlowRow], not a horizontally-scrolling list —
 * every card is visible on the main form at once, per explicit request.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DashboardStatsContent(
    visibleCongregationIds: Set<String>?,
    /** Non-null replaces the Overview stat cards with this Quick Access content for the displayed stats. */
    quickAccess: (@Composable (CongregationStats) -> Unit)? = null,
    /** Rendered where the removed charts used to be: the Recently Visited section for the displayed stats. */
    recentlyVisited: (@Composable (CongregationStats) -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: DashboardStatsViewModel = koinViewModel(),
) {
    LaunchedEffect(visibleCongregationIds) { viewModel.restrictTo(visibleCongregationIds) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isMultiCongregation = uiState.all.size > 1

    // "Add an export to Excel or PDF feature under this module" — each stat
    // card's own tap-to-see-names dialog (below) gets its own PDF/Excel
    // export of exactly the list it shows, independent of the whole-
    // dashboard export [DashboardReportsScreen] already offers. Declared
    // here (unconditionally, before this composable's own early returns for
    // isLoading/error) since `rememberLauncherForActivityResult` must be
    // called on every composition of this composable, not only while a
    // dialog happens to be open; [pendingExportTable] is what the launcher's
    // callback actually writes, set right before each `launch()` call.
    val actions = rememberPlatformActions()
    val showToast = rememberActionToast()
    var pendingExportTable by remember { mutableStateOf<ReportTable?>(null) }
    val exportCsvSuccess = stringResource(R.string.reports_export_csv_success)
    val exportFailedWrite = stringResource(R.string.reports_export_failed_write)
    val exportFailedUnknown = stringResource(R.string.reports_export_failed_unknown)
    val exportFailedGenericTemplate = stringResource(R.string.reports_export_failed_generic)
    val statExportLauncher = rememberFileCreator("text/csv") { uri ->
        val table = pendingExportTable
        if (uri != null && table != null) {
            try {
                val wrote = actions.writeCsv(uri.toString(), table)
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

    if (uiState.isLoading) {
        Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp))
        }
        return
    }

    if (uiState.error != null) {
        Column(modifier = modifier.fillMaxWidth().padding(24.dp)) {
            Text(stringResource(R.string.dashboard_stats_unavailable_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.dashboard_stats_unavailable_detail, uiState.error ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val displayed = uiState.selectedCongregationId?.let { id -> uiState.all.firstOrNull { it.congregationId == id } }
        ?: uiState.overallTotal ?: uiState.all.firstOrNull()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (isMultiCongregation) {
            Text(stringResource(R.string.reports_congregation_group_label), style = MaterialTheme.typography.titleSmall)
            var expanded by remember { mutableStateOf(false) }
            val allCongregationsLabel = stringResource(R.string.dashboard_all_congregations)
            val selectedLabel = uiState.selectedCongregationId
                ?.let { id -> uiState.all.firstOrNull { it.congregationId == id }?.congregationName }
                ?: allCongregationsLabel
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(allCongregationsLabel) },
                        onClick = { viewModel.selectCongregation(null); expanded = false },
                    )
                    uiState.all.forEach { stats ->
                        DropdownMenuItem(
                            text = { Text(stats.congregationName) },
                            onClick = { viewModel.selectCongregation(stats.congregationId); expanded = false },
                        )
                    }
                }
            }
        }

        DateRangeFilterBar(
            range = uiState.dateRange,
            onRangeChange = viewModel::setDateRange,
        )
        Text(
            stringResource(R.string.dashboard_description),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (displayed == null) {
            Text(stringResource(R.string.dashboard_no_congregation_data), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        Column {
            Text(displayed.congregationName, style = MaterialTheme.typography.titleLarge)
            Text(
                "${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(uiState.dateRange.startMillis))} – " +
                    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(uiState.dateRange.endMillis)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(if (quickAccess != null) "Quick Access" else stringResource(R.string.dashboard_overview), style = MaterialTheme.typography.titleMedium)
        // Per explicit request: no icons, no per-item color coding on these
        // cards — Total Publishers and Total Elders are also their own
        // separate cards here now, not one combined "Publishers vs Elders"
        // card with a shared proportion bar. Each is clickable and opens a
        // details dialog (spec: "show the details inside when clicked").
        val statCards = buildStatCards(displayed)
        var selectedDetail by remember { mutableStateOf<StatDetail?>(null) }
        // A fixed 2-column grid (not a wrapping FlowRow) — matches the
        // reference's "Accounts" section exactly: two equal-width cards per
        // row, regardless of screen width, rather than reflowing to 3+ on a
        // wider phone/tablet. Chunked manually (9 cards is small enough that
        // a LazyVerticalGrid would be more machinery than this needs).
        if (quickAccess != null) quickAccess(displayed) else statCards.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems.forEach { detail ->
                    StatCard(detail.label, detail.value, onClick = { selectedDetail = detail }, modifier = Modifier.weight(1f))
                }
                if (rowItems.size == 1) Box(modifier = Modifier.weight(1f))
            }
        }

        selectedDetail?.let { detail ->
            // Spec: tapping a card shows who actually makes up that number, not
            // just the number — e.g. "Total Elders 3 / Henry Canales (Solano
            // Tagalog Congregation), ...". Scoped to whichever congregation
            // [displayed] currently represents — a blank congregationId means
            // the "All Congregations" total, so every member counts there;
            // otherwise only that one congregation's members do. Same
            // deduplicated-by-person source [CongregationStats.compute] uses
            // for the headline number itself, so the list and the count can
            // never silently disagree.
            val matchingMembers = uiState.members
                .filter { detail.label in it.statLabels }
                .filter { displayed.congregationId.isBlank() || it.congregationId == displayed.congregationId }
                .sortedBy { it.fullName }
            // "Add an export to Excel or PDF feature under this module" —
            // exactly the list this dialog shows, one row per name plus the
            // headline total as its own summary line (same [ReportTable]
            // shape every other PDF/Excel export in this app already uses).
            val memberReportTable = remember(detail, matchingMembers) {
                ReportTable(
                    title = detail.label,
                    count = matchingMembers.size,
                    countLabel = "Total Members",
                    // "add also status" — its own column here (rather than
                    // folded into Name, as the on-screen dialog shows it),
                    // since a spreadsheet/PDF export reads better as
                    // structured columns than a parenthesized suffix.
                    columns = listOf("#", "Name", "Status"),
                    rows = matchingMembers.mapIndexed { index, member ->
                        listOf((index + 1).toString(), member.fullName, relevantStatuses(detail.label, member).sorted().joinToString(" / ") { it.uppercase() })
                    },
                    totals = listOf(detail.label to detail.value),
                )
            }
            AlertDialog(
                properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
                onDismissRequest = { selectedDetail = null },
                title = { Text(detail.label) },
                text = {
                    Column(
                        modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).imePadding(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(detail.value, style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "${displayed.congregationName} · " +
                                "${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(uiState.dateRange.startMillis))} – " +
                                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(uiState.dateRange.endMillis)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (detail.breakdown.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            detail.breakdown.forEach { (subLabel, subValue) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(subLabel, style = MaterialTheme.typography.bodyMedium)
                                    Text(subValue, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        if (matchingMembers.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            // "Do not include the congregation name next to
                            // the publisher name. Put a separator in each
                            // name" — just the name now (every name here is
                            // already scoped to one congregation, either
                            // [displayed] itself or, for "All Congregations",
                            // a plain member list where the name alone is
                            // what was asked for), with a divider line
                            // between each row rather than one divider above
                            // the whole list. "add also status, example
                            // 'EVAROSE FERNANDEZ (REGULAR PIONEER)'" — each
                            // person's own specific role/category, not the
                            // umbrella card label; joined with "/" on the
                            // rare person holding more than one at once
                            // (e.g. Coordinator Elder/Regular Elder), and
                            // omitted entirely for a role with no specific
                            // status resolved.
                            matchingMembers.forEachIndexed { index, member ->
                                Text(memberDisplayName(detail.label, member), style = MaterialTheme.typography.bodyMedium)
                                if (index != matchingMembers.lastIndex) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        // Same "print preview always offers Save as PDF" +
                        // plain-CSV-for-Excel pair every other export in this
                        // app already uses — disabled rather than hidden
                        // when there's nothing in this particular list to
                        // export (e.g. a figure with zero members).
                        IconButton(
                            onClick = { actions.print(memberReportTable) },
                            enabled = matchingMembers.isNotEmpty(),
                        ) {
                            Icon(Icons.Rounded.PictureAsPdf, contentDescription = stringResource(R.string.reports_export_pdf_cd))
                        }
                        IconButton(
                            onClick = {
                                pendingExportTable = memberReportTable
                                statExportLauncher.launch("gopreach-${detail.label.lowercase().replace(' ', '-')}-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.csv")
                            },
                            enabled = matchingMembers.isNotEmpty(),
                        ) {
                            Icon(Icons.Rounded.TableChart, contentDescription = stringResource(R.string.reports_export_excel_cd))
                        }
                        TextButton(onClick = { selectedDetail = null }) { Text(stringResource(R.string.action_close)) }
                    }
                },
            )
        }

        // The Preaching Hours / Publishers per Congregation / Elders per Congregation charts are gone; recent Return
        // Visit and Bible Study activity takes their place.
        recentlyVisited?.invoke(displayed)
    }
}

/** Standalone screen wrapper around [DashboardStatsContent] — used by roles
 * (Coordinator Elder, Regular Elder, ...) that still reach this as its own
 * destination rather than having it embedded on their main dashboard body. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardReportsScreen(
    visibleCongregationIds: Set<String>?,
    /** "Allow the admin, super admin, coordinator elder, service overseer to
     * export the report to PDF or Excel" — Regular Elder/Ministerial Servant
     * can still view this screen (see GoPreachNavGraph's own drawer gating)
     * but don't get the export actions; wired from the nav graph based on
     * role, same pattern [ReportsScreen]'s own `canEditReports` uses. */
    canExport: Boolean = false,
    onBack: () -> Unit,
    viewModel: DashboardStatsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = rememberPlatformActions()
    val showToast = rememberActionToast()

    // Same [displayed] derivation [DashboardStatsContent] uses internally —
    // duplicated here (not exposed from that composable) purely so the
    // export table always matches whatever congregation/period the cards on
    // screen are currently showing.
    val displayed = uiState.selectedCongregationId?.let { id -> uiState.all.firstOrNull { it.congregationId == id } }
        ?: uiState.overallTotal ?: uiState.all.firstOrNull()
    val reportTable = remember(displayed, uiState.dateRange) {
        displayed?.let { dashboardTableFor(it, uiState.dateRange) }
    }
    // Bug fix ("I cannot see any PDF or Excel"): see ReportsScreen's matching
    // fix — the Storage Access Framework picker just saves and closes with
    // no feedback of its own; now it confirms and opens the file immediately.
    val exportCsvSuccess = stringResource(R.string.reports_export_csv_success)
    val exportFailedWrite = stringResource(R.string.reports_export_failed_write)
    val exportFailedUnknown = stringResource(R.string.reports_export_failed_unknown)
    val exportFailedGenericTemplate = stringResource(R.string.reports_export_failed_generic)
    val exportLauncher = rememberFileCreator("text/csv") { uri ->
        val table = reportTable
        if (uri != null && table != null) {
            try {
                val wrote = actions.writeCsv(uri.toString(), table)
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
    val exportFileName = "gopreach-dashboard-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.csv"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dashboard_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.dashboard_back_cd)) }
                },
                actions = {
                    // Data is already live (every source Flow updates this screen
                    // automatically — spec §15), so this is a reassurance affordance
                    // more than a functional necessity; kept per spec §3's explicit
                    // "refresh" requirement.
                    IconButton(onClick = { viewModel.selectCongregation(uiState.selectedCongregationId) }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.dashboard_refresh_cd))
                    }
                    if (canExport) {
                        // Same "print preview always offers Save as PDF" +
                        // plain-CSV-for-Excel pair [ReportsScreen] already
                        // uses — no new export mechanism to maintain.
                        IconButton(
                            onClick = { reportTable?.let { actions.print(it) } },
                            enabled = reportTable != null,
                        ) {
                            Icon(Icons.Rounded.PictureAsPdf, contentDescription = stringResource(R.string.reports_export_pdf_cd))
                        }
                        IconButton(
                            onClick = { exportLauncher.launch(exportFileName) },
                            enabled = reportTable != null,
                        ) {
                            Icon(Icons.Rounded.TableChart, contentDescription = stringResource(R.string.reports_export_excel_cd))
                        }
                    }
                },
            )
        },
    ) { padding ->
        DashboardStatsContent(
            visibleCongregationIds = visibleCongregationIds,
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            viewModel = viewModel,
        )
    }
}

/** Shared shape for both Print/PDF and CSV/Excel export of the dashboard's
 * KPI cards — same [buildStatCards] list the on-screen cards themselves are
 * built from, so the exported report can never show different figures than
 * what's on screen. */
private fun dashboardTableFor(displayed: CongregationStats, dateRange: DateRange): ReportTable {
    val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
    val periodLabel = "${dateFormat.format(Date(dateRange.startMillis))} - ${dateFormat.format(Date(dateRange.endMillis))}"
    val cards = buildStatCards(displayed)
    return ReportTable(
        title = "GoPreach Dashboard Report — ${displayed.congregationName} ($periodLabel)",
        columns = listOf("Figure", "Value"),
        rows = cards.map { listOf(it.label, it.value) } +
            cards.flatMap { card -> card.breakdown.map { (label, value) -> listOf("  ${card.label} — $label", value) } },
    )
}
