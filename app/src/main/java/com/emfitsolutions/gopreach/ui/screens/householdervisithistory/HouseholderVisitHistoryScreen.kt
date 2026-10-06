package com.emfitsolutions.gopreach.ui.screens.householdervisithistory

import com.emfitsolutions.gopreach.platform.rememberFileCreator
import com.emfitsolutions.gopreach.data.print.escapeHtml
import com.emfitsolutions.gopreach.platform.rememberPlatformActions
import com.emfitsolutions.gopreach.platform.SimpleDateFormat
import com.emfitsolutions.gopreach.platform.Locale
import com.emfitsolutions.gopreach.platform.Date
import com.emfitsolutions.gopreach.ui.components.RecordFound
import com.emfitsolutions.gopreach.data.print.OrientationMode
import com.emfitsolutions.gopreach.data.print.PrintOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.Visit
import com.emfitsolutions.gopreach.data.print.ReportTable
import com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.screens.pipeline.PipelinePersonDetailScreen
import com.emfitsolutions.gopreach.ui.screens.pipeline.PipelineViewModel

private fun PipelineStage.statusLabel(): String = when (this) {
    PipelineStage.SEARCHING -> "Found Interested"
    PipelineStage.RETURN_VISIT -> "Return Visit"
    PipelineStage.BIBLE_STUDY -> "Bible Study"
}

/** "STATUS VISUAL DISTINCTION" — spec's exact fixed colors, deliberately
 * literal hex rather than a theme role (this is a specific recognition
 * system the user asked for by name, not a generic "make it stand out"), the
 * one exception being [PipelineStage.SEARCHING]'s "Black": a real black
 * would be unreadable on this app's dark theme, so it resolves to
 * [Color.Unspecified] instead — [androidx.compose.material3.Text] then
 * falls back to its own current content color, which already renders as a
 * near-black on a light background and a near-white on a dark one. That's
 * the same "regular, undistinguished" result the spec's own "Found
 * Interested = BLACK + REGULAR" row is asking for (no special color at
 * all), just theme-safe. This is a *display-only* mapping — see this
 * function's own callers, never anything that reads [PipelineStage] itself
 * — so it can never be mistaken for changing what stage a record is at
 * (spec §6). */
private fun PipelineStage.statusColor(): Color = when (this) {
    PipelineStage.BIBLE_STUDY -> Color(0xFF1565C0)
    PipelineStage.RETURN_VISIT -> Color(0xFF2E7D32)
    PipelineStage.SEARCHING -> Color.Unspecified
}

/** "Bible Study = ... BOLD; Return Visit/Found Interested = ... REGULAR" —
 * applied to both the Householder Name and the Status text together (the
 * spec's own Bible Study worked example bolds both lines, not just one). */
private fun PipelineStage.statusFontWeight(): FontWeight =
    if (this == PipelineStage.BIBLE_STUDY) FontWeight.Bold else FontWeight.Normal

/** Same three colors as [statusColor], expressed as a CSS color for the
 * print/PDF HTML output (spec §4's own "...Record details where the status
 * is displayed" — the printed report is exactly that). `null` (Found
 * Interested) means "don't override the print stylesheet's own text color"
 * — the same theme-safety reasoning [statusColor] uses, just expressed the
 * way an inline `style` attribute needs it. */
private fun PipelineStage.statusCssColor(): String? = when (this) {
    PipelineStage.BIBLE_STUDY -> "#1565C0"
    PipelineStage.RETURN_VISIT -> "#2E7D32"
    PipelineStage.SEARCHING -> null
}

private fun Visit.statusLabel(): String = outcome.name.replace('_', ' ')

private fun formatGpsDecimal(lat: Double, lng: Double): String = "%.4f, %.4f".format(lat, lng)

/**
 * "House Holder Visit History" — a consolidated, search/filter/list layer
 * over the *existing* Searching/Return Visit/Bible Study records and their
 * Visit history, for Super-Admin (every authorized congregation) and
 * Publisher (their own congregation) accounts. Opening a record hands off to
 * the existing [PipelinePersonDetailScreen] unchanged (spec §8-§14's Add/
 * Edit/Delete-own-visit-only rules, and firestore.rules' matching `visits`
 * enforcement, already live there — see [HouseholderVisitHistoryViewModel]'s
 * own doc comment for why this screen never reimplements any of that).
 * [congregationId] is the actual security boundary — `null` (Super-Admin)
 * sees every congregation, a real id sees exactly that one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholderVisitHistoryScreen(
    congregationId: String?,
    currentPersonId: String,
    onBack: () -> Unit,
    viewModel: HouseholderVisitHistoryViewModel = koinViewModel(),
    pipelineViewModel: PipelineViewModel = koinViewModel(),
) {
    LaunchedEffect(congregationId) { viewModel.restrictTo(congregationId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val personNames by viewModel.personNames.collectAsStateWithLifecycle()
    val actions = rememberPlatformActions()
    val showToast = rememberActionToast()
    var selectedRow by remember { mutableStateOf<HouseholderRow?>(null) }

    val current = selectedRow
    if (current != null) {
        PipelinePersonDetailScreen(
            person = current.person,
            currentPersonId = currentPersonId,
            congregationName = current.congregationName,
            stage = current.person.pipelineStage,
            // Same "Super-Admin/explicitly-authorized-role override" every
            // other caller of this shared detail screen already uses —
            // `congregationId == null` is this app's established convention
            // for "this session is Super-Admin, unscoped" (see this
            // screen's own doc comment).
            canManageAllVisitHistory = congregationId == null,
            onBack = { selectedRow = null },
            viewModel = pipelineViewModel,
        )
        return
    }

    // "Add an export to Excel or PDF feature" (spec §21/§22) — one row per
    // Visit across every matching householder, retrieving ALL of that
    // person's history regardless of what's scrolled into view on screen
    // (rows/visits here are already the complete, unpaginated lists
    // [HouseholderVisitHistoryViewModel] builds). Coordinates as plain
    // decimal text, never a hyperlink/formula (spec §17/§21).
    val exportTable = remember(uiState.rows, personNames) {
        ReportTable(
            title = "House Holder Visit History",
            count = uiState.rows.size,
            countLabel = "Total House Holders",
            columns = listOf(
                "House Holder", "Status", "Assigned Publisher", "Congregation", "Place of Origin",
                "Province", "Municipality/City", "Barangay", "Contact", "House Holder GPS",
                "Visit Date", "Visit Status", "Remarks", "Visit Coordinates", "Recorded By",
            ),
            rows = uiState.rows.flatMap { row ->
                val common = listOf(
                    row.person.name,
                    row.person.pipelineStage.statusLabel(),
                    row.publisherName ?: "Unassigned",
                    row.congregationName,
                    row.person.address,
                    row.person.province.orEmpty(),
                    row.person.cityMunicipality.orEmpty(),
                    row.person.barangay.orEmpty(),
                    row.person.contact.orEmpty(),
                    if (row.person.hasGpsLocation) formatGpsDecimal(row.person.gpsLat!!, row.person.gpsLng!!) else "",
                )
                val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                if (row.visits.isEmpty()) {
                    listOf(common + listOf("", "", "", "", ""))
                } else {
                    row.visits.map { visit ->
                        common + listOf(
                            dateFormat.format(Date(visit.visitDate)),
                            visit.statusLabel(),
                            visit.topicDiscussed.orEmpty(),
                            if (visit.hasVisitLocation) formatGpsDecimal(visit.visitLat!!, visit.visitLng!!) else "Not available",
                            personNames[visit.createdByPersonId] ?: "—",
                        )
                    }
                }
            },
            totals = listOf("Total House Holders" to uiState.rows.size.toString()),
        )
    }
    val exportLauncher = rememberFileCreator("text/csv") { uri ->
        if (uri != null) {
            try {
                val wrote = actions.writeCsv(uri.toString(), exportTable)
                if (wrote) {
                    showToast("Exported to Excel (CSV).")
                    actions.openFile(uri.toString(), "text/csv")
                } else {
                    showToast("Couldn't write the file.")
                }
            } catch (e: Exception) {
                showToast("Export failed: ${e.localizedMessage ?: "unknown error"}")
            }
        }
    }
    val exportFileName = "gopreach-householder-visit-history-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.csv"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("House Holder Visit History") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(
                        onClick = { actions.printHtml("House Holder Visit History", buildHouseholderVisitHistoryPrintHtml(uiState.rows, personNames), PrintOptions(OrientationMode.LANDSCAPE)) },
                        enabled = uiState.rows.isNotEmpty(),
                    ) {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Print / Export as PDF")
                    }
                    IconButton(onClick = { exportLauncher.launch(exportFileName) }, enabled = uiState.rows.isNotEmpty()) {
                        Icon(Icons.Rounded.TableChart, contentDescription = "Export as Excel")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxWidth().padding(padding)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // "Super Admin – Congregation Filter" — only ever shown for
                // the actual unscoped (Super-Admin) session; [congregationId]
                // being non-null here already means a real congregation
                // boundary the UI never exposes a way around (see
                // HouseholderVisitHistoryViewModel.setCongregationFilter's
                // own doc comment for the matching ViewModel-side guard).
                if (congregationId == null) {
                    CongregationFilterDropdown(
                        congregations = uiState.congregations,
                        selectedCongregationId = uiState.congregationFilter,
                        onSelected = viewModel::setCongregationFilter,
                        label = "Search By Congregation",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                RecordTypeDropdown(selected = uiState.recordType, onSelected = viewModel::setRecordType)
                SearchByDropdown(selected = uiState.searchByField, onSelected = viewModel::setSearchByField)
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    label = { Text("Search") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider()
            if (congregationId == null && uiState.congregationFilter == null) {
                com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt()
            } else if (uiState.rows.isEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                    RecordFound(0)
                    Text("No house holder records found.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { RecordFound(uiState.rows.size) }
                    items(uiState.rows, key = { it.person.id }) { row ->
                        HouseholderCard(row, onClick = { selectedRow = row })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordTypeDropdown(selected: RecordTypeFilter, onSelected: (RecordTypeFilter) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Record Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RecordTypeFilter.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { onSelected(option); expanded = false })
            }
        }
    }
}

/** "Simplify the Filter... ONE search/filter dropdown" — replaces the old
 * three cascading Province/Municipality/Barangay dropdowns. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchByDropdown(selected: SearchByField, onSelected: (SearchByField) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Search By") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SearchByField.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { onSelected(option); expanded = false })
            }
        }
    }
}

@Composable
private fun HouseholderCard(row: HouseholderRow, onClick: () -> Unit) {
    // "STATUS VISUAL DISTINCTION" — Bible Study blue+bold, Return Visit
    // green+regular, Found Interested black(-safe)+regular; applied to both
    // the Householder Name and the Status line together, and recalculated
    // fresh from [row.person.pipelineStage] on every recomposition, so a
    // legitimate status change (spec §6/§7 — Add/Edit/status-move, then a
    // live Firestore update flows back into this same [row]) repaints the
    // color/weight automatically rather than needing any special refresh
    // logic of its own.
    val stage = row.person.pipelineStage
    val statusColor = stage.statusColor()
    val statusWeight = stage.statusFontWeight()
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.person.name, style = MaterialTheme.typography.titleMedium, fontWeight = statusWeight, color = statusColor)
            Text(stage.statusLabel(), style = MaterialTheme.typography.bodyMedium, fontWeight = statusWeight, color = statusColor)
            Text(
                "Assigned Publisher: ${row.publisherName ?: "Unassigned"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val location = listOfNotNull(row.person.barangay, row.person.cityMunicipality, row.person.province).joinToString(", ")
            if (location.isNotBlank()) {
                Text(location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Unused placeholder to keep the [InterestedPerson] import meaningful for
 * IDE navigation from this file's own doc comments; the type itself is only
 * ever referenced through [HouseholderRow.person] above. */
private fun unusedTypeAnchor(person: InterestedPerson) = person

/** "Printing / Print Report Format" — one grouped block per house holder
 * (parent details, then every one of their own Visit History entries with
 * its own real, existing fields — never a "Visit History 1/2/3" placeholder,
 * never split across unrelated pages/sections), instead of the flat
 * one-row-per-visit table [ReportTable] renders. [rows] is already exactly
 * what's on screen — same congregation scope/filter, same Record Type/
 * Search filters — so printing can never show more than the signed-in
 * session is actually looking at (spec §9's "never allow the print function
 * to bypass the user's congregation restriction"). No "Contact" field is
 * printed: [InterestedPerson] has no such field today, and inventing one
 * isn't this change's job (see this screen's own module doc comment on
 * building nothing new). */
private fun buildHouseholderVisitHistoryPrintHtml(rows: List<HouseholderRow>, personNames: Map<String, String>): String {
    val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    fun esc(text: String) = escapeHtml(text)
    return buildString {
        append("<html><head><meta charset=\"utf-8\"><style>")
        append("body{font-family:sans-serif;font-size:12px;} h2{text-align:center;} ")
        append("div.household{margin-top:18px;padding-top:10px;border-top:2px solid #333;} ")
        append("div.household:first-of-type{border-top:none;} ")
        append("p.field{margin:2px 0;} p.field b{display:inline-block;min-width:110px;} ")
        append("div.visit{margin:6px 0 6px 24px;padding:6px 10px;border-left:3px solid #999;} ")
        append("div.visit p{margin:1px 0;} p.visitTitle{font-weight:bold;margin:0;}")
        append("</style></head><body>")
        append("<h2>").append(esc("House Holder Visit History")).append("</h2>")
        rows.forEach { row ->
            val person = row.person
            // "STATUS VISUAL DISTINCTION... Record details where the status
            // is displayed" — the printed/PDF report is exactly that; same
            // three colors [HouseholderCard] uses on-screen (see
            // [PipelineStage.statusCssColor]'s own doc comment for why
            // Found Interested has no color override here either).
            val statusStyle = buildString {
                person.pipelineStage.statusCssColor()?.let { append("color:$it;") }
                if (person.pipelineStage == PipelineStage.BIBLE_STUDY) append("font-weight:bold;")
            }
            append("<div class=\"household\">")
            append("<p class=\"field\" style=\"$statusStyle\"><b>House holder:</b> ").append(esc(person.name)).append("</p>")
            append("<p class=\"field\" style=\"$statusStyle\"><b>Status:</b> ").append(esc(person.pipelineStage.statusLabel())).append("</p>")
            append("<p class=\"field\"><b>Assigned to:</b> ").append(esc(row.publisherName ?: "Unassigned")).append("</p>")
            append("<p class=\"field\"><b>Coordinates:</b> ")
                .append(esc(if (person.hasGpsLocation) formatGpsDecimal(person.gpsLat!!, person.gpsLng!!) else "Not available"))
                .append("</p>")
            val currentAddress = listOfNotNull(person.barangay, person.cityMunicipality, person.province).filter { it.isNotBlank() }.joinToString(", ")
            append("<p class=\"field\"><b>Place of Origin:</b> ").append(esc(person.address.ifBlank { "—" })).append("</p>")
            append("<p class=\"field\"><b>Contact:</b> ").append(esc(person.contact?.takeIf { it.isNotBlank() } ?: "—")).append("</p>")
            append("<p class=\"field\"><b>Current Address:</b> ").append(esc(currentAddress.ifBlank { "—" })).append("</p>")
            if (row.visits.isEmpty()) {
                append("<div class=\"visit\"><p>No visit history recorded.</p></div>")
            } else {
                row.visits.forEachIndexed { index, visit ->
                    append("<div class=\"visit\">")
                    append("<p class=\"visitTitle\">Visit History ").append(index + 1).append("</p>")
                    append("<p><b>Date:</b> ").append(esc(dateFormat.format(Date(visit.visitDate)))).append("</p>")
                    append("<p><b>Publisher:</b> ").append(esc(personNames[visit.publisherPersonId] ?: "—")).append("</p>")
                    append("<p><b>Status:</b> ").append(esc(visit.statusLabel())).append("</p>")
                    append("<p><b>Remarks:</b> ").append(esc(visit.topicDiscussed?.takeIf { it.isNotBlank() } ?: "—")).append("</p>")
                    append("<p><b>Recorded by:</b> ").append(esc(personNames[visit.createdByPersonId] ?: "—")).append("</p>")
                    append("<p><b>Visit Coordinates:</b> ")
                        .append(esc(if (visit.hasVisitLocation) formatGpsDecimal(visit.visitLat!!, visit.visitLng!!) else "Not available"))
                        .append("</p>")
                    append("</div>")
                }
            }
            append("</div>")
        }
        append("</body></html>")
    }
}
