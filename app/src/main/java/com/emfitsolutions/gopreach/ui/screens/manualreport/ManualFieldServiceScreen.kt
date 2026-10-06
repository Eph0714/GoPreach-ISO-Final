package com.emfitsolutions.gopreach.ui.screens.manualreport

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.data.model.displayName
import com.emfitsolutions.gopreach.domain.MonthBounds
import com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown
import com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.components.pickableMonths
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.rememberCongregationContext
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manual Field Service Record: an authorized admin-track user enters a month's field service for a publisher or
 * pioneer who cannot use GoPreach. The record is saved as an ordinary monthly report (marked Source: Manual Entry,
 * with who entered it and when), so the Field Service Report, Comparative Report/graph, totals and exports count it
 * exactly like a record the publisher entered. [fixedCongregationId] is the boundary; a Super-Admin (null) must pick a
 * congregation first. The ViewModel re-checks role and congregation on save.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ManualFieldServiceScreen(
    fixedCongregationId: String?,
    currentPersonId: String,
    currentRole: AdminRole?,
    onBack: () -> Unit,
    viewModel: ManualFieldServiceViewModel = koinViewModel(),
) {
    val showToast = rememberActionToast()
    val scope = rememberCoroutineScope()
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    var congregationFilter by rememberCongregationContext("manual_field_service")
    val congregationId = fixedCongregationId ?: congregationFilter
    // The chosen publisher lives up here so Back (system or the arrow) first returns to the publisher list, and only then leaves the screen.
    var selectedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(congregationId) { selectedId = null }
    androidx.activity.compose.BackHandler(enabled = selectedId != null) { selectedId = null }
    val allowed = currentRole != null && currentRole in ManualEntryRoles
    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    val dateTimeFormat = remember { SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manual Field Service Record") },
                navigationIcon = { IconButton(onClick = { if (selectedId != null) selectedId = null else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!allowed) {
                Text("You do not have access to manual field service records.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                return@Column
            }
            if (fixedCongregationId == null) {
                CongregationFilterDropdown(
                    congregations = congregations,
                    selectedCongregationId = congregationFilter,
                    onSelected = { congregationFilter = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (congregationId == null) {
                SelectCongregationPrompt()
                return@Column
            }

            val publishers by remember(congregationId) { viewModel.publishersIn(congregationId) }.collectAsStateWithLifecycle(initialValue = emptyList())
            val reports by remember(congregationId) { viewModel.reportsIn(congregationId) }.collectAsStateWithLifecycle(initialValue = emptyList())
            val names by viewModel.names.collectAsStateWithLifecycle(initialValue = emptyMap())
            val months = remember { pickableMonths() }

            var query by remember(congregationId) { mutableStateOf("") }
            var month by remember(congregationId) { mutableStateOf(months.first()) }
            var hoursText by remember { mutableStateOf("") }
            var minutesText by remember { mutableStateOf("") }
            var participated by remember { mutableStateOf<Boolean?>(null) }
            var studiesText by remember { mutableStateOf("") }
            var remarks by remember { mutableStateOf("") }
            var error by remember { mutableStateOf<String?>(null) }
            var confirmUpdate by remember { mutableStateOf(false) }
            var deleting by remember { mutableStateOf<MonthlyReport?>(null) }

            val selected = publishers.firstOrNull { it.person.id == selectedId }
            val existing = selected?.let { p ->
                reports.firstOrNull { it.publisherPersonId == p.person.id && MonthBounds.of(it.periodMonth).startInclusive == MonthBounds.of(month).startInclusive }
            }

            // Selecting a publisher/month that already has a record loads it, so Save becomes an update.
            LaunchedEffect(selectedId, month, existing?.id, existing?.lastEditedAt) {
                if (existing != null) {
                    val minutes = Math.round((existing.hoursRendered ?: 0.0) * 60).toInt()
                    hoursText = if (selected?.isPioneer == true) (minutes / 60).toString() else ""
                    minutesText = if (selected?.isPioneer == true) (minutes % 60).toString() else ""
                    participated = existing.participatedInPreaching
                    studiesText = existing.bibleStudiesCount.toString()
                    remarks = existing.remarks.orEmpty()
                } else {
                    hoursText = ""; minutesText = ""; participated = null; studiesText = ""; remarks = ""
                }
                error = null
            }

            fun buildInput() = ManualRecordInput(
                publisherPersonId = selectedId.orEmpty(),
                month = month,
                hours = hoursText.toIntOrNull() ?: 0,
                minutes = minutesText.toIntOrNull() ?: 0,
                participated = participated,
                bibleStudies = studiesText.toIntOrNull() ?: 0,
                remarks = remarks,
            )

            fun doSave() {
                scope.launch {
                    val message = viewModel.save(buildInput(), congregationId, currentRole, currentPersonId)
                    if (message != null) error = message else {
                        error = null
                        showToast(if (existing != null) "Record updated." else "Record saved.")
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // --- Publisher search and selection ---
                Text("Publisher", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                if (selected == null) {
                    // The month is chosen first so the list can show which publishers already have a record for it.
                    Text("Month", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    MonthDropdown(selected = month, months = months, format = monthFormat) { month = it }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search Publisher...") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        singleLine = true,
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val monthStart = MonthBounds.of(month).startInclusive
                    fun recordOf(publisherId: String) =
                        reports.firstOrNull { it.publisherPersonId == publisherId && MonthBounds.of(it.periodMonth).startInclusive == monthStart }
                    var recordFilter by remember { mutableStateOf(0) } // 0 all, 1 with a record, 2 without
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("All", "With record", "No record").forEachIndexed { i, label ->
                            androidx.compose.material3.FilterChip(selected = recordFilter == i, onClick = { recordFilter = i }, label = { Text(label, fontSize = 12.sp) })
                        }
                    }
                    val q = query.trim()
                    val matches = publishers.filter { p ->
                        (q.isEmpty() || p.person.fullName.contains(q, ignoreCase = true) || p.person.firstName.contains(q, ignoreCase = true) ||
                            p.person.lastName.contains(q, ignoreCase = true) || p.person.id.contains(q, ignoreCase = true)) &&
                            when (recordFilter) { 1 -> recordOf(p.person.id) != null; 2 -> recordOf(p.person.id) == null; else -> true }
                    }
                    val withRecord = publishers.count { recordOf(it.person.id) != null }
                    if (matches.isEmpty()) {
                        Text("No publishers found.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "${matches.size} publisher${if (matches.size == 1) "" else "s"} · $withRecord of ${publishers.size} have a record for ${monthFormat.format(Date(month))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Every match is listed and the list uses the rest of the screen height; it scrolls inside this box rather than being cut off.
                    Column(modifier = Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp - 400).dp.coerceAtLeast(220.dp)).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        matches.forEach { p ->
                            val record = recordOf(p.person.id)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                tonalElevation = 1.dp,
                                modifier = Modifier.fillMaxWidth().clickable { selectedId = p.person.id },
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(p.person.fullName, style = MaterialTheme.typography.bodyMedium)
                                        Text(p.category.displayName + if (p.isPioneer) " · Pioneer" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                    if (record != null) {
                                        // Already reported for this month: by the publisher or entered manually.
                                        Surface(shape = RoundedCornerShape(6.dp), color = if (record.isManualEntry) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer) {
                                            Text(
                                                if (record.isManualEntry) "Manual record" else "Reported",
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            )
                                        }
                                    } else {
                                        Text("No record", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    return@Column
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(selected.person.fullName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "Status: ${selected.category.displayName} (${if (selected.isPioneer) "Pioneer" else "Non-Pioneer"})",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { selectedId = null; query = "" }) { Text("Change") }
                    }
                }

                // --- Month ---
                Text("Month", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                MonthDropdown(selected = month, months = months, format = monthFormat) { month = it }

                if (existing != null) {
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "A field service record already exists for this month. Saving will update the existing record.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                // --- Fields: pioneers enter hours, everyone else participation. Stack on phones, side by side when wide. ---
                val wide = LocalConfiguration.current.screenWidthDp >= 600
                if (selected.isPioneer) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        NumberField("Hours", hoursText, { hoursText = it }, Modifier.width(if (wide) 160.dp else 140.dp))
                        NumberField("Minutes", minutesText, { minutesText = it }, Modifier.width(if (wide) 160.dp else 140.dp))
                    }
                } else {
                    Text("Participation", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = participated == true, onClick = { participated = true })
                        Text("Yes", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(16.dp))
                        RadioButton(selected = participated == false, onClick = { participated = false })
                        Text("No", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (participated == false && (studiesText.toIntOrNull() ?: 0) > 0) {
                        Text(
                            "Participation is No, but Bible Studies is above 0. Set Participation to Yes, or Bible Studies to 0.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                NumberField("Bible Studies", studiesText, { studiesText = it }, Modifier.width(160.dp))
                OutlinedTextField(
                    value = remarks,
                    onValueChange = { remarks = it },
                    label = { Text("Remarks") },
                    minLines = 2,
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (existing != null) confirmUpdate = true else doSave() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.height(38.dp),
                    ) { Text(if (existing != null) "Update Record" else "Save Record", fontSize = 13.sp) }
                    OutlinedButton(
                        onClick = { selectedId = null; query = "" },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.height(38.dp),
                    ) { Text("Cancel", fontSize = 13.sp) }
                }

                // --- Manual records already entered in this congregation ---
                val manual = reports.filter { it.isManualEntry }.sortedWith(compareByDescending<MonthlyReport> { it.periodMonth }.thenBy { names[it.publisherPersonId].orEmpty() })
                Text("Manual records (${manual.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                if (manual.isEmpty()) Text("None entered yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                manual.take(50).forEach { r ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text("${names[r.publisherPersonId] ?: "—"} – ${monthFormat.format(Date(r.periodMonth))}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                val minutes = Math.round((r.hoursRendered ?: 0.0) * 60).toInt()
                                Text(
                                    buildString {
                                        if (r.hoursRendered != null) append("${minutes / 60}h ${minutes % 60}m · ") else append("Participated: ${if (r.participatedInPreaching == true) "Yes" else "No"} · ")
                                        append("Bible Studies ${r.bibleStudiesCount}")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                // Administrative detail: where it came from and who entered / last changed it.
                                Text(
                                    "Source: Manual Entry · Entered by ${r.createdByPersonId?.let { names[it] } ?: "—"}" + (r.createdAt?.let { " · ${dateTimeFormat.format(Date(it))}" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (r.lastEditedByPersonId != null && r.lastEditedAt != null && r.lastEditedAt != r.createdAt) {
                                    Text(
                                        "Last modified by ${names[r.lastEditedByPersonId] ?: "—"} · ${dateTimeFormat.format(Date(r.lastEditedAt!!))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                r.remarks?.takeIf { it.isNotBlank() }?.let { Text("Remarks: $it", style = MaterialTheme.typography.bodySmall) }
                            }
                            IconButton(onClick = {
                                selectedId = r.publisherPersonId
                                month = MonthBounds.of(r.periodMonth).startInclusive
                            }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp)) }
                            IconButton(onClick = { deleting = r }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
            }

            if (confirmUpdate) {
                AlertDialog(
                    onDismissRequest = { confirmUpdate = false },
                    title = { Text("Update existing record?") },
                    text = { Text("A field service record already exists for this month. Do you want to update the existing record?") },
                    confirmButton = { TextButton(onClick = { confirmUpdate = false; doSave() }) { Text("Update") } },
                    dismissButton = { TextButton(onClick = { confirmUpdate = false }) { Text("Cancel") } },
                )
            }
            deleting?.let { r ->
                val label = "${names[r.publisherPersonId] ?: "—"} – ${monthFormat.format(Date(r.periodMonth))}"
                AlertDialog(
                    onDismissRequest = { deleting = null },
                    title = { Text("Delete manual record?") },
                    text = { Text("Delete the manual field service record for $label? The publisher's account is not affected.") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.delete(r, congregationId, currentRole, currentPersonId)
                            showToast("Record deleted.")
                            deleting = null
                        }) { Text("Delete") }
                    },
                    dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
                )
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(4)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        visualTransformation = VisualTransformation.None,
        modifier = modifier,
    )
}

@Composable
private fun MonthDropdown(selected: Long, months: List<Long>, format: SimpleDateFormat, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        onClick = { expanded = true },
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.height(36.dp),
    ) {
        Row(modifier = Modifier.padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(format.format(Date(selected)), fontSize = 14.sp)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose month", modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(modifier = Modifier.height(280.dp).width(190.dp).verticalScroll(rememberScrollState())) {
                months.forEach { m -> DropdownMenuItem(text = { Text(format.format(Date(m)), fontSize = 14.sp) }, onClick = { onSelect(m); expanded = false }) }
            }
        }
    }
}
