package com.emfitsolutions.gopreach.ui.screens.reports

import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.print.ReportPrinter
import com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown
import com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.components.rememberCongregationContext

/** "Field Service Group Overseer"/"Servant"/"Assistant" already have a
 * shared label ([com.emfitsolutions.gopreach.ui.components.displayLabel] on
 * [com.emfitsolutions.gopreach.data.model.RegularElderRole]) — this is that
 * same "Regular Pioneer"/"Auxiliary Pioneer" formatting for [members]'
 * category, matching [ReportPrinter]-printed reports elsewhere in the app
 * that keep this as a private, per-screen extension rather than a shared one
 * (see ReportsScreen.kt's own copy's doc comment for why: it's a plain,
 * non-Composable label with no localization hook yet, deliberately deferred). */
private fun PublisherCategory.displayLabel(): String =
    name.split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }

/**
 * "Add a module for 'Field Service Group' Report. For Super admin, he can
 * see all congregation, other user can see only the record of their
 * congregation" — [congregationIds] `null` means Super-Admin (every
 * congregation); a real set means exactly the caller's own. One block per
 * Field Service Group: its three named roles, then every Publisher
 * currently assigned to it with their category, matching the spec's own
 * worked example exactly (including the "-Name (Category)" bullet shape).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldServiceGroupReportScreen(
    congregationIds: Set<String>?,
    onBack: () -> Unit,
    viewModel: FieldServiceGroupReportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    // "Add a filter for Congregation" (Super-Admin only) — a real scoped
    // role's own [congregationIds] is already a fixed one-element set, so
    // this stays unused for them.
    var congregationFilter by rememberCongregationContext("group_reports")
    val effectiveCongregationIds = congregationFilter?.let { setOf(it) } ?: congregationIds
    val needsCongregation = congregationIds == null && congregationFilter == null
    val rowsFlow = remember(effectiveCongregationIds, needsCongregation) { if (needsCongregation) kotlinx.coroutines.flow.flowOf(emptyList()) else viewModel.rowsFor(effectiveCongregationIds) }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // Only worth labeling each block with its own congregation when more
    // than one could actually appear — a role scoped to their own single
    // congregation would just see that same name repeated over every block.
    val showCongregationHeadings = rows.map { it.congregationId }.distinct().size > 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Field Service Group Report") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { ReportPrinter.printHtml(context, "Field Service Group Report", buildFieldServiceGroupReportHtml(rows, showCongregationHeadings)) }) {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Print / Export as PDF")
                    }
                },
            )
        },
    ) { padding ->
      Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (congregationIds == null) {
            CongregationFilterDropdown(
                congregations = congregations,
                selectedCongregationId = congregationFilter,
                onSelected = { congregationFilter = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (needsCongregation) {
            SelectCongregationPrompt()
        } else if (rows.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RecordFound(0)
                Text("No field service groups yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { RecordFound(rows.size) }
                items(rows, key = { it.group.id }) { row ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (showCongregationHeadings) {
                                Text(row.congregationName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(row.group.name, style = MaterialTheme.typography.titleMedium)
                            Text("Group Overseer: ${row.overseerName ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                            Text("Group Servant: ${row.servantName ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                            Text("Group Assistant: ${row.assistantName ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                            if (row.members.isEmpty()) {
                                Text("No publishers assigned to this group yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                row.members.forEach { member ->
                                    Text("-${member.fullName} (${member.category.displayLabel()})", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
      }
    }
}

/** Shared shape for both the on-screen cards above and Print/PDF — same
 * "-Name (Category)" bullet, same three named-role lines, so the printed
 * report can never drift from what's on screen (same discipline every other
 * report's own table-builder function follows in this app). */
private fun buildFieldServiceGroupReportHtml(rows: List<FieldServiceGroupReportRow>, showCongregationHeadings: Boolean): String = buildString {
    val e = ReportPrinter::escapeHtml
    append("<html><head><meta charset=\"utf-8\"><style>")
    append("body{font-family:sans-serif;font-size:12px;} h2{text-align:center;} ")
    append("h3.congregation{margin-top:18px;border-bottom:1px solid #999;padding-bottom:2px;} ")
    append("div.group{margin-top:14px;} p.group-name{font-weight:bold;margin:0 0 2px 0;} ")
    append("p.role{margin:0;} ul{margin:4px 0 0 0;padding-left:18px;} li{list-style:none;margin-left:-18px;}")
    append("</style></head><body>")
    append("<h2>Field Service Group Report</h2>")
    append("<p style=\"text-align:center;font-weight:bold;margin:0 0 6px 0\">Total Groups: ${rows.size}  •  Total Publishers: ${rows.sumOf { it.members.size }}</p>")
    var lastCongregationId: String? = null
    rows.forEach { row ->
        if (showCongregationHeadings && row.congregationId != lastCongregationId) {
            append("<h3 class=\"congregation\">").append(e(row.congregationName)).append("</h3>")
            lastCongregationId = row.congregationId
        }
        append("<div class=\"group\">")
        append("<p class=\"group-name\">").append(e(row.group.name)).append("</p>")
        append("<p class=\"role\">Group Overseer: ").append(e(row.overseerName ?: "—")).append("</p>")
        append("<p class=\"role\">Group Servant: ").append(e(row.servantName ?: "—")).append("</p>")
        append("<p class=\"role\">Group Assistant: ").append(e(row.assistantName ?: "—")).append("</p>")
        if (row.members.isEmpty()) {
            append("<ul><li>No publishers assigned to this group yet.</li></ul>")
        } else {
            append("<ul>")
            row.members.forEach { member ->
                append("<li>-").append(e(member.fullName)).append(" (").append(e(member.category.displayLabel())).append(")</li>")
            }
            append("</ul>")
        }
        append("</div>")
    }
    append("</body></html>")
}
