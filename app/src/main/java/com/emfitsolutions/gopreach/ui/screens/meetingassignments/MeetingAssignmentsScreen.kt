package com.emfitsolutions.gopreach.ui.screens.meetingassignments

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.CartAssignmentRow
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.MidweekAssignmentItem
import com.emfitsolutions.gopreach.data.model.MidweekMeetingSchedule
import com.emfitsolutions.gopreach.data.model.MidweekSection
import com.emfitsolutions.gopreach.data.model.PublicTalkScheduleRow
import com.emfitsolutions.gopreach.data.print.ReportPrinter
import com.emfitsolutions.gopreach.data.print.ReportTable
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.ui.window.DialogProperties

private enum class MeetingAssignmentCategory(val label: String) {
    MIDWEEK("Midweek Meeting Schedule"),
    PUBLIC_TALK("Public Talk and Watchtower Study Schedule"),
    CART_ASSIGNMENT("Cart Assignment"),
}

/**
 * "Meeting and Cart Assignment" module (renamed from "Meeting Assignments"
 * when Cart Assignment was added) — Coordinator Elder/Regular Elder/Service
 * Overseer/Admin (own congregation, via [fixedCongregationId])/Super-Admin
 * (every congregation, picks one) enroll the Midweek Meeting Schedule, the
 * Public Talk and Watchtower Study Schedule, and Cart Assignment, all under
 * the same role/congregation restriction; every Publisher sees their own
 * congregation's copy, [readOnly] — no Add/Edit/Delete for them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingAssignmentsScreen(
    currentPersonId: String,
    fixedCongregationId: String?,
    readOnly: Boolean,
    onBack: () -> Unit,
    viewModel: MeetingAssignmentsViewModel = koinViewModel(),
) {
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    // Bug fix — same class of issue as ManageGroupsScreen's GroupDialog
    // ("Congregation/Group is required" even after picking one): `remember`
    // keyed on the live `congregations` list gets a new List instance on
    // every Firestore snapshot re-emission, silently resetting the
    // Super-Admin's own pick back to `congregations.firstOrNull()` — here
    // that doesn't surface as a validation error (there's no Save button on
    // this screen), but it does mean the visibly selected congregation could
    // silently jump to a different one mid-session. `rememberSaveable`,
    // pre-filled once, isn't keyed on the list at all and survives
    // configuration changes too.
    var pickedCongregationId by rememberSaveable { mutableStateOf(fixedCongregationId) }
    var congregationPreselected by rememberSaveable { mutableStateOf(fixedCongregationId != null) }
    if (!congregationPreselected && congregations.isNotEmpty()) {
        pickedCongregationId = congregations.firstOrNull()?.id
        congregationPreselected = true
    }
    val congregationId = fixedCongregationId ?: pickedCongregationId
    var category by remember { mutableStateOf(MeetingAssignmentCategory.MIDWEEK) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meeting and Cart Assignment") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (fixedCongregationId == null) {
                CongregationDropdown(
                    congregations = congregations,
                    selectedId = pickedCongregationId,
                    onSelected = { pickedCongregationId = it },
                )
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                MeetingAssignmentCategory.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = category == entry,
                        onClick = { category = entry },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = MeetingAssignmentCategory.entries.size),
                    ) { Text(entry.label, maxLines = 2) }
                }
            }

            if (congregationId == null) {
                Text(
                    "No congregation to show yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else when (category) {
                MeetingAssignmentCategory.MIDWEEK -> MidweekMeetingScheduleTab(
                    congregationId = congregationId,
                    congregationName = congregations.firstOrNull { it.id == congregationId }?.name ?: "—",
                    readOnly = readOnly,
                    currentPersonId = currentPersonId,
                    viewModel = viewModel,
                )
                MeetingAssignmentCategory.PUBLIC_TALK -> PublicTalkScheduleTab(
                    congregationId = congregationId,
                    congregationName = congregations.firstOrNull { it.id == congregationId }?.name ?: "—",
                    readOnly = readOnly,
                    currentPersonId = currentPersonId,
                    viewModel = viewModel,
                )
                MeetingAssignmentCategory.CART_ASSIGNMENT -> CartAssignmentTab(
                    congregationId = congregationId,
                    congregationName = congregations.firstOrNull { it.id == congregationId }?.name ?: "—",
                    readOnly = readOnly,
                    currentPersonId = currentPersonId,
                    viewModel = viewModel,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CongregationDropdown(congregations: List<Congregation>, selectedId: String?, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = congregations.firstOrNull { it.id == selectedId }?.name ?: ""
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Congregation") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            congregations.forEach { congregation ->
                DropdownMenuItem(text = { Text(congregation.name) }, onClick = { onSelected(congregation.id); expanded = false })
            }
        }
    }
}

// ---------------------------------------------------------------------
// Midweek Meeting Schedule
// ---------------------------------------------------------------------

@Composable
private fun MidweekMeetingScheduleTab(
    congregationId: String,
    congregationName: String,
    readOnly: Boolean,
    currentPersonId: String,
    viewModel: MeetingAssignmentsViewModel,
) {
    val context = LocalContext.current
    var weekStart by remember { mutableStateOf(mondayOfWeek(System.currentTimeMillis())) }
    val scheduleFlow = remember(congregationId, weekStart) { viewModel.scheduleFor(congregationId, weekStart) }
    val schedule by scheduleFlow.collectAsStateWithLifecycle(initialValue = null)
    var editingItem by remember { mutableStateOf<Triple<MidweekSection, Int?, MidweekAssignmentItem?>?>(null) }
    var pendingDelete by remember { mutableStateOf<Pair<MidweekSection, Int>?>(null) }

    // "Make the week selectable, e.g. <August 31-September 6 2026>
    // <September 7 2026-September 14 2026>" — a dropdown of actual
    // Monday-Sunday weeks (spanning well before/after today), rather than a
    // raw date picker that just happened to snap to a Monday.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.weight(1f)) {
            WeekPickerDropdown(weekStart = weekStart, onSelected = { weekStart = it })
        }
        IconButton(onClick = { ReportPrinter.printHtml(context, "Midweek Meeting Schedule — $congregationName", buildMidweekPrintHtml(congregationName, weekStart, schedule)) }) {
            Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Print Midweek Meeting Schedule")
        }
    }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MidweekSection.entries.forEach { section ->
            val items = schedule?.itemsFor(section).orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    section.displayLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = section.textColor(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(section.backgroundColor(), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                if (items.isEmpty()) {
                    Text(
                        if (readOnly) "No assignments yet for this section." else "No assignments yet — tap + to add one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    items.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.particular + formatDurationSuffix(item.durationMinutes),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "Assigned to: ${item.assignedTo}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!readOnly) {
                                IconButton(onClick = { editingItem = Triple(section, index, item) }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "Edit assignment")
                                }
                                IconButton(onClick = { pendingDelete = section to index }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete assignment")
                                }
                            }
                        }
                    }
                }
                if (!readOnly) {
                    TextButton(onClick = { editingItem = Triple(section, null, null) }) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text("Add Assignment")
                    }
                }
            }
        }
    }

    val toEdit = editingItem
    if (toEdit != null) {
        val (section, index, item) = toEdit
        MidweekItemDialog(
            existing = item,
            congregationId = congregationId,
            viewModel = viewModel,
            onSave = { newItem ->
                val current = (schedule?.itemsFor(section) ?: emptyList()).toMutableList()
                if (index != null) current[index] = newItem else current.add(newItem)
                viewModel.saveSectionItems(schedule, congregationId, weekStart, section, current, currentPersonId)
                editingItem = null
            },
            onDismiss = { editingItem = null },
        )
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        val (section, index) = toDelete
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete Assignment?") },
            text = { Text("This removes it from the schedule for this week.") },
            confirmButton = {
                TextButton(onClick = {
                    val current = (schedule?.itemsFor(section) ?: emptyList()).toMutableList()
                    if (index in current.indices) current.removeAt(index)
                    viewModel.saveSectionItems(schedule, congregationId, weekStart, section, current, currentPersonId)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MidweekItemDialog(
    existing: MidweekAssignmentItem?,
    congregationId: String,
    viewModel: MeetingAssignmentsViewModel,
    onSave: (MidweekAssignmentItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val namesFlow = remember(congregationId) { viewModel.rosterNamesFor(congregationId) }
    val names by namesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var particular by remember { mutableStateOf(existing?.particular ?: "") }
    var duration by remember { mutableStateOf(existing?.durationMinutes ?: "") }
    var assignedTo by remember { mutableStateOf(existing?.assignedTo ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val message = requiredFieldsMessage(
            "Particular" to particular.isNotBlank(),
            "Assigned To" to assignedTo.isNotBlank(),
        )
        if (message != null) {
            errorMessage = message
            return
        }
        onSave(MidweekAssignmentItem(particular = particular.trim(), durationMinutes = duration.trim(), assignedTo = assignedTo.trim()))
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = if (existing == null) "Add Assignment" else "Edit Assignment",
        onConfirm = ::submit,
        confirmLabel = if (existing == null) "Add" else "Save",
        errorMessage = errorMessage,
        maxContentHeight = 400.dp,
        hasUnsavedChanges = particular != (existing?.particular ?: "") || duration != (existing?.durationMinutes ?: "") ||
            assignedTo != (existing?.assignedTo ?: ""),
    ) {
        OutlinedTextField(
            value = particular,
            onValueChange = { particular = it },
            label = { Text("Particular") },
            placeholder = { Text("e.g. Bible Reading") },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = duration,
            onValueChange = { duration = it.filter { c -> c.isDigit() } },
            label = { Text("Duration (minutes, optional)") },
            placeholder = { Text("e.g. 5") },
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        // "In assigning publishers, browse it from the publishers record...
        // however can be entered manually if not available" — suggestions
        // from this congregation's own roster, but never blocked to just
        // that list (a visiting speaker, or "Evarose and Jovy" naming two
        // people at once, still just types normally).
        PublisherAutocompleteField(
            label = "Assigned To",
            value = assignedTo,
            onValueChange = { assignedTo = it },
            suggestions = names,
            placeholderText = "e.g. John Funtallera",
        )
    }
}

private fun MidweekSection.backgroundColor(): Color = when (this) {
    MidweekSection.TREASURES -> Color(0xFF616161)
    MidweekSection.FIELD_MINISTRY -> Color(0xFFFFD54F)
    MidweekSection.LIVING_AS_CHRISTIANS -> Color(0xFF8B0000)
}

private fun MidweekSection.textColor(): Color = when (this) {
    MidweekSection.TREASURES -> Color.White
    MidweekSection.FIELD_MINISTRY -> Color(0xFF3E2E00)
    MidweekSection.LIVING_AS_CHRISTIANS -> Color.White
}

internal fun formatWeekRange(weekStart: Long): String {
    val startCal = Calendar.getInstance().apply { timeInMillis = weekStart }
    val endCal = Calendar.getInstance().apply { timeInMillis = weekStart; add(Calendar.DAY_OF_MONTH, 6) }
    val sameMonth = startCal.get(Calendar.MONTH) == endCal.get(Calendar.MONTH) && startCal.get(Calendar.YEAR) == endCal.get(Calendar.YEAR)
    return if (sameMonth) {
        "${SimpleDateFormat("MMMM d", Locale.getDefault()).format(startCal.time)} - ${SimpleDateFormat("d, yyyy", Locale.getDefault()).format(endCal.time)}"
    } else {
        "${SimpleDateFormat("MMMM d", Locale.getDefault()).format(startCal.time)} - ${SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(endCal.time)}"
    }
}

/** "Make the week selectable, e.g. <August 31-September 6 2026>
 * <September 7 2026-September 14 2026>" — every Monday-Sunday week from 8
 * weeks ago through a year ahead, each shown as its own formatted range
 * (see [formatWeekRange]) rather than a raw calendar date picker. Widest
 * reasonable window for scheduling meetings ahead of time; still a closed
 * list (like [MonthPickerField] in Monthly Report), so there's no way to
 * land on a non-Monday week start by construction. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekPickerDropdown(weekStart: Long, onSelected: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val weeks = remember {
        val thisWeek = mondayOfWeek(System.currentTimeMillis())
        (-8..52).map { offset -> Calendar.getInstance().apply { timeInMillis = thisWeek; add(Calendar.WEEK_OF_YEAR, offset) }.timeInMillis }
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = formatWeekRange(weekStart),
            onValueChange = {},
            readOnly = true,
            label = { Text("Select Week") },
            leadingIcon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            weeks.forEach { week ->
                DropdownMenuItem(text = { Text(formatWeekRange(week)) }, onClick = { onSelected(week); expanded = false })
            }
        }
    }
}

// ---------------------------------------------------------------------
// Public Talk and Watchtower Study Schedule
// ---------------------------------------------------------------------

@Composable
private fun PublicTalkScheduleTab(
    congregationId: String,
    congregationName: String,
    readOnly: Boolean,
    currentPersonId: String,
    viewModel: MeetingAssignmentsViewModel,
) {
    val context = LocalContext.current
    val rowsFlow = remember(congregationId) { viewModel.rowsFor(congregationId) }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var pendingEdit by remember { mutableStateOf<PublicTalkScheduleRow?>(null) }
    var pendingDelete by remember { mutableStateOf<PublicTalkScheduleRow?>(null) }
    val showToast = rememberActionToast()
    val dateFormat = remember { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!readOnly) {
                OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Add Schedule")
                }
            }
            IconButton(onClick = { ReportPrinter.print(context, publicTalkReportTable(congregationName, rows, dateFormat)) }) {
                Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Print Public Talk and Watchtower Study Schedule")
            }
        }
        if (rows.isEmpty()) {
            Text(
                "No Public Talk / Watchtower Study schedule yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(dateFormat.format(Date(row.date)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                if (!readOnly) {
                                    Row {
                                        IconButton(onClick = { pendingEdit = row }) {
                                            Icon(Icons.Rounded.Edit, contentDescription = "Edit schedule")
                                        }
                                        IconButton(onClick = { pendingDelete = row }) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Delete schedule")
                                        }
                                    }
                                }
                            }
                            Text("Theme: ${row.theme}", style = MaterialTheme.typography.bodyMedium)
                            Text("Speaker: ${row.speaker}", style = MaterialTheme.typography.bodySmall)
                            Text("Chairman: ${row.chairman}", style = MaterialTheme.typography.bodySmall)
                            Text("Watchtower Conductor: ${row.watchtowerConductor}", style = MaterialTheme.typography.bodySmall)
                            Text("Watchtower Reader: ${row.watchtowerReader}", style = MaterialTheme.typography.bodySmall)
                            Text("Mic Servers: ${row.micServers}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        PublicTalkScheduleDialog(
            existing = null,
            congregationId = congregationId,
            existingRows = rows,
            currentPersonId = currentPersonId,
            viewModel = viewModel,
            onSaved = { showToast("Schedule added.") },
            onDismiss = { showAdd = false },
        )
    }
    val toEdit = pendingEdit
    if (toEdit != null) {
        PublicTalkScheduleDialog(
            existing = toEdit,
            congregationId = congregationId,
            existingRows = rows,
            currentPersonId = currentPersonId,
            viewModel = viewModel,
            onSaved = { showToast("Schedule saved.") },
            onDismiss = { pendingEdit = null },
        )
    }
    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this schedule?") },
            text = { Text("This removes the ${dateFormat.format(Date(toDelete.date))} row.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePublicTalkRow(toDelete, currentPersonId)
                    showToast("Schedule deleted.")
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PublicTalkScheduleDialog(
    existing: PublicTalkScheduleRow?,
    congregationId: String,
    existingRows: List<PublicTalkScheduleRow>,
    currentPersonId: String,
    viewModel: MeetingAssignmentsViewModel,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val namesFlow = remember(congregationId) { viewModel.rosterNamesFor(congregationId) }
    val names by namesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var date by remember { mutableStateOf(existing?.date) }
    var theme by remember { mutableStateOf(existing?.theme ?: "") }
    var speaker by remember { mutableStateOf(existing?.speaker ?: "") }
    var chairman by remember { mutableStateOf(existing?.chairman ?: "") }
    var watchtowerConductor by remember { mutableStateOf(existing?.watchtowerConductor ?: "") }
    var watchtowerReader by remember { mutableStateOf(existing?.watchtowerReader ?: "") }
    var micServers by remember { mutableStateOf(existing?.micServers ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()) }

    fun submit() {
        val message = requiredFieldsMessage(
            "Date" to (date != null),
            "Theme" to theme.isNotBlank(),
            "Speaker" to speaker.isNotBlank(),
            "Chairman" to chairman.isNotBlank(),
            "Watchtower Conductor" to watchtowerConductor.isNotBlank(),
            "Watchtower Reader" to watchtowerReader.isNotBlank(),
            "Mic Servers" to micServers.isNotBlank(),
        )
        if (message != null) {
            errorMessage = message
            return
        }
        isSaving = true
        coroutineScope.launch {
            val row = PublicTalkScheduleRow(
                id = existing?.id ?: "",
                congregationId = congregationId,
                date = date!!,
                theme = theme.trim(),
                speaker = speaker.trim(),
                chairman = chairman.trim(),
                watchtowerConductor = watchtowerConductor.trim(),
                watchtowerReader = watchtowerReader.trim(),
                micServers = micServers.trim(),
                createdByPersonId = existing?.createdByPersonId ?: "",
                createdAt = existing?.createdAt ?: 0L,
            )
            val duplicateError = viewModel.savePublicTalkRow(existingRows, row, currentPersonId)
            isSaving = false
            if (duplicateError != null) {
                errorMessage = duplicateError
            } else {
                onSaved()
                onDismiss()
            }
        }
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = if (existing == null) "Add Public Talk / Watchtower Schedule" else "Edit Public Talk / Watchtower Schedule",
        onConfirm = ::submit,
        confirmLabel = if (isSaving) "Saving…" else if (existing == null) "Add" else "Save",
        confirmEnabled = !isSaving,
        errorMessage = errorMessage,
        maxContentHeight = 480.dp,
        hasUnsavedChanges = date != existing?.date || theme != (existing?.theme ?: "") || speaker != (existing?.speaker ?: "") ||
            chairman != (existing?.chairman ?: "") || watchtowerConductor != (existing?.watchtowerConductor ?: "") ||
            watchtowerReader != (existing?.watchtowerReader ?: "") || micServers != (existing?.micServers ?: ""),
    ) {
        OutlinedButton(
            onClick = {
                val calendar = Calendar.getInstance().apply { date?.let { timeInMillis = it } }
                DatePickerDialog(
                    context,
                    { _, year, month, day ->
                        date = Calendar.getInstance().apply {
                            set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day)
                            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        errorMessage = null
                    },
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH),
                ).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(date?.let { dateFormat.format(Date(it)) } ?: "Set Date")
        }
        OutlinedTextField(
            value = theme,
            onValueChange = { theme = it },
            label = { Text("Theme") },
            placeholder = { Text("e.g. Who Is Jehovah?") },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        // "In assigning publishers, browse it from the publishers record...
        // however can be entered manually if not available" — every field
        // below suggests from this congregation's own roster but never
        // blocks free text (a visiting speaker, or "Ephraim / Josue" naming
        // two Mic Servers at once, still just types normally).
        PublisherAutocompleteField(label = "Speaker", value = speaker, onValueChange = { speaker = it }, suggestions = names)
        PublisherAutocompleteField(label = "Chairman", value = chairman, onValueChange = { chairman = it }, suggestions = names)
        PublisherAutocompleteField(label = "Watchtower Conductor", value = watchtowerConductor, onValueChange = { watchtowerConductor = it }, suggestions = names)
        PublisherAutocompleteField(label = "Watchtower Reader", value = watchtowerReader, onValueChange = { watchtowerReader = it }, suggestions = names)
        PublisherAutocompleteField(label = "Mic Servers", value = micServers, onValueChange = { micServers = it }, suggestions = names, placeholderText = "e.g. Ephraim / Josue")
    }
}

// ---------------------------------------------------------------------
// Cart Assignment
// ---------------------------------------------------------------------

/** "Add a 'Cart Assignment' next to [the] 'Public Talk and Watchtower
 * Study' button... Entities (Date, Location, Publishers)... can also be
 * add, edit and delete permanently, use the same restriction for users in
 * Midweek and Public [T]alk" — same shape/role gating as [PublicTalkScheduleTab]
 * ([readOnly] passed down from the exact same caller), except multiple rows
 * may share one date (spec's own two-location example), so there is no
 * duplicate-date guard here. */
@Composable
private fun CartAssignmentTab(
    congregationId: String,
    congregationName: String,
    readOnly: Boolean,
    currentPersonId: String,
    viewModel: MeetingAssignmentsViewModel,
) {
    val context = LocalContext.current
    val rowsFlow = remember(congregationId) { viewModel.cartAssignmentsFor(congregationId) }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var pendingEdit by remember { mutableStateOf<CartAssignmentRow?>(null) }
    var pendingDelete by remember { mutableStateOf<CartAssignmentRow?>(null) }
    val showToast = rememberActionToast()
    val dateFormat = remember { SimpleDateFormat("MM-dd-yyyy", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!readOnly) {
                OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Add Cart Assignment")
                }
            }
            IconButton(onClick = { ReportPrinter.print(context, cartAssignmentReportTable(congregationName, rows, dateFormat)) }) {
                Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Print Cart Assignment")
            }
        }
        if (rows.isEmpty()) {
            Text(
                "No Cart Assignments yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(dateFormat.format(Date(row.date)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                if (!readOnly) {
                                    Row {
                                        IconButton(onClick = { pendingEdit = row }) {
                                            Icon(Icons.Rounded.Edit, contentDescription = "Edit cart assignment")
                                        }
                                        IconButton(onClick = { pendingDelete = row }) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Delete cart assignment")
                                        }
                                    }
                                }
                            }
                            Text("Location: ${row.location}", style = MaterialTheme.typography.bodyMedium)
                            Text("Publishers: ${row.publishers}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        CartAssignmentDialog(
            existing = null,
            congregationId = congregationId,
            currentPersonId = currentPersonId,
            viewModel = viewModel,
            onSaved = { showToast("Cart assignment added.") },
            onDismiss = { showAdd = false },
        )
    }
    val toEdit = pendingEdit
    if (toEdit != null) {
        CartAssignmentDialog(
            existing = toEdit,
            congregationId = congregationId,
            currentPersonId = currentPersonId,
            viewModel = viewModel,
            onSaved = { showToast("Cart assignment saved.") },
            onDismiss = { pendingEdit = null },
        )
    }
    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { pendingDelete = null },
            title = { Text("Permanently Delete Cart Assignment?") },
            text = { Text("This permanently removes the ${dateFormat.format(Date(toDelete.date))} — ${toDelete.location} row. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCartAssignment(toDelete, currentPersonId)
                    showToast("Cart assignment deleted.")
                    pendingDelete = null
                }) { Text("Delete Permanently") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CartAssignmentDialog(
    existing: CartAssignmentRow?,
    congregationId: String,
    currentPersonId: String,
    viewModel: MeetingAssignmentsViewModel,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val namesFlow = remember(congregationId) { viewModel.rosterNamesFor(congregationId) }
    val names by namesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var date by remember { mutableStateOf(existing?.date) }
    var location by remember { mutableStateOf(existing?.location ?: "") }
    var publishers by remember { mutableStateOf(existing?.publishers ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val dateFormat = remember { SimpleDateFormat("MM-dd-yyyy", Locale.getDefault()) }

    fun submit() {
        val message = requiredFieldsMessage(
            "Date" to (date != null),
            "Location" to location.isNotBlank(),
            "Publishers" to publishers.isNotBlank(),
        )
        if (message != null) {
            errorMessage = message
            return
        }
        val row = CartAssignmentRow(
            id = existing?.id ?: "",
            congregationId = congregationId,
            date = date!!,
            location = location.trim(),
            publishers = publishers.trim(),
            createdByPersonId = existing?.createdByPersonId ?: "",
            createdAt = existing?.createdAt ?: 0L,
        )
        viewModel.saveCartAssignment(row, currentPersonId)
        onSaved()
        onDismiss()
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = if (existing == null) "Add Cart Assignment" else "Edit Cart Assignment",
        onConfirm = ::submit,
        confirmLabel = if (existing == null) "Add" else "Save",
        errorMessage = errorMessage,
        maxContentHeight = 360.dp,
        hasUnsavedChanges = date != existing?.date || location != (existing?.location ?: "") || publishers != (existing?.publishers ?: ""),
    ) {
        OutlinedButton(
            onClick = {
                val calendar = Calendar.getInstance().apply { date?.let { timeInMillis = it } }
                DatePickerDialog(
                    context,
                    { _, year, month, day ->
                        date = Calendar.getInstance().apply {
                            set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day)
                            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        errorMessage = null
                    },
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH),
                ).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(date?.let { dateFormat.format(Date(it)) } ?: "Set Date")
        }
        OutlinedTextField(
            value = location,
            onValueChange = { location = it },
            label = { Text("Location") },
            placeholder = { Text("e.g. Market Place Solano") },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        // "In assigning publishers, browse it from the publishers record...
        // however can be entered manually if not available."
        PublisherAutocompleteField(
            label = "Publishers",
            value = publishers,
            onValueChange = { publishers = it },
            suggestions = names,
            placeholderText = "e.g. Eva and Lita",
        )
    }
}

/** The same section colors [MidweekMeetingScheduleTab]'s own on-screen UI
 * uses ([MidweekSection.backgroundColor]/[textColor]), duplicated here as
 * plain hex — the print HTML has no Compose [androidx.compose.ui.graphics
 * .Color] to reuse directly, and this keeps the printed sheet visually
 * consistent with what's on screen. */
private fun MidweekSection.printBarColor(): String = when (this) {
    MidweekSection.TREASURES -> "#616161"
    MidweekSection.FIELD_MINISTRY -> "#FFD54F"
    MidweekSection.LIVING_AS_CHRISTIANS -> "#8B0000"
}

private fun MidweekSection.printTextColor(): String = when (this) {
    MidweekSection.TREASURES, MidweekSection.LIVING_AS_CHRISTIANS -> "#FFFFFF"
    MidweekSection.FIELD_MINISTRY -> "#3E2E00"
}

/** "Redesign the midweek meeting print report" (reference: a real
 * congregation's own printed Midweek Meeting Schedule sheet — congregation
 * name + big title up top, week range in italic caps, each of the three
 * program sections as its own colored bar, particulars numbered
 * continuously straight through all three sections rather than restarting
 * per section, duration inline with the particular, assignee on the same
 * line). Built and printed as bespoke HTML via [ReportPrinter.printHtml]
 * directly — this layout has nothing in common with [ReportTable]'s plain
 * columns-and-rows shape, so it bypasses that generic path entirely instead
 * of contorting it to fit. */
private fun buildMidweekPrintHtml(congregationName: String, weekStart: Long, schedule: MidweekMeetingSchedule?): String {
    fun esc(s: String) = ReportPrinter.escapeHtml(s)
    var number = 0
    val sectionsHtml = buildString {
        MidweekSection.entries.forEach { section ->
            append("<div class=\"section-bar\" style=\"background:${section.printBarColor()};color:${section.printTextColor()};\">")
            append(esc(section.displayLabel))
            append("</div>")
            val items = schedule?.itemsFor(section).orEmpty()
            if (items.isEmpty()) {
                append("<p class=\"empty\">No assignments yet for this section.</p>")
            } else {
                append("<table class=\"items\">")
                items.forEach { item ->
                    number++
                    append("<tr>")
                    append("<td class=\"num\">").append(number).append(".</td>")
                    append("<td class=\"particular\">").append(esc(item.particular)).append(esc(formatDurationSuffix(item.durationMinutes))).append("</td>")
                    append("<td class=\"assigned\">")
                    if (item.assignedTo.isNotBlank()) append("Assigned to: ").append(esc(item.assignedTo))
                    append("</td>")
                    append("</tr>")
                }
                append("</table>")
            }
        }
    }
    return """
        <html><head><meta charset="utf-8"><style>
        body{font-family:sans-serif;font-size:13px;color:#222;margin:24px;}
        .header{display:flex;justify-content:space-between;align-items:flex-start;border-bottom:2px solid #444;padding-bottom:8px;}
        .congregation{font-style:italic;font-weight:bold;font-size:20px;color:#2b4a6f;}
        .title{font-size:26px;font-weight:bold;text-align:right;white-space:nowrap;}
        .week{font-style:italic;font-weight:bold;font-size:13px;letter-spacing:0.5px;margin:8px 0 16px;}
        .section-bar{font-weight:bold;text-transform:uppercase;padding:6px 10px;margin-top:14px;margin-bottom:4px;font-size:13px;}
        table.items{width:100%;border-collapse:collapse;margin-bottom:4px;}
        table.items td{padding:4px 6px;vertical-align:top;font-size:13px;}
        td.num{width:26px;font-weight:bold;}
        td.particular{width:65%;}
        td.assigned{text-align:right;color:#333;white-space:nowrap;}
        p.empty{font-style:italic;color:#777;margin:4px 0 12px 6px;}
        </style></head><body>
        <div class="header">
        <div class="congregation">${esc(congregationName)}</div>
        <div class="title">Midweek Meeting Schedule</div>
        </div>
        <div class="week">${esc(formatWeekRange(weekStart).uppercase())}</div>
        $sectionsHtml
        </body></html>
    """.trimIndent()
}

/** "...'Public Talk and Watchtower Study'" — one row per [PublicTalkScheduleRow]. */
private fun publicTalkReportTable(congregationName: String, rows: List<PublicTalkScheduleRow>, dateFormat: SimpleDateFormat): ReportTable {
    val tableRows = rows.map { row ->
        listOf(
            dateFormat.format(Date(row.date)),
            row.theme,
            row.speaker,
            row.chairman,
            row.watchtowerConductor,
            row.watchtowerReader,
            row.micServers,
        )
    }
    return ReportTable(
        title = "Public Talk and Watchtower Study Schedule — $congregationName",
        count = rows.size,
        countLabel = "Total Schedules",
        columns = listOf("Date", "Theme", "Speaker", "Chairman", "Watchtower Conductor", "Watchtower Reader", "Mic Servers"),
        rows = tableRows,
    )
}

/** "...'Cart Assignment'" — one row per [CartAssignmentRow]; unlike Public
 * Talk, more than one row can share the same date (see [CartAssignmentRow]'s
 * own doc comment), which is exactly why this prints as a plain table
 * (spec's own layout example — repeated Date/Location/Publishers blocks)
 * rather than one row per date. */
private fun cartAssignmentReportTable(congregationName: String, rows: List<CartAssignmentRow>, dateFormat: SimpleDateFormat): ReportTable {
    val tableRows = rows.map { row -> listOf(dateFormat.format(Date(row.date)), row.location, row.publishers) }
    return ReportTable(
        title = "Cart Assignment — $congregationName",
        count = rows.size,
        countLabel = "Total Assignments",
        columns = listOf("Date", "Location", "Publishers"),
        rows = tableRows,
    )
}

/** "Show the word 'minutes' in view form" — [raw] is stored as a plain
 * digits-only number (see [MidweekItemDialog]'s Duration field); the view
 * row is the only place the unit is spelled out, so a value of "5" always
 * reads as "(5 minutes)" instead of a bare, ambiguous number. Blank stays
 * blank — a particular with no duration set shows no parenthetical at all. */
internal fun formatDurationSuffix(raw: String): String = if (raw.isBlank()) "" else " ($raw minutes)"

/**
 * "In assigning publishers... allow multiple publisher[s] in every
 * assignment" — every assignee field in this module (Midweek's Assigned To,
 * Public Talk's Speaker/Chairman/Watchtower Conductor/Watchtower Reader/Mic
 * Servers, Cart Assignment's Publishers) is built on this one multi-select
 * picker: a dropdown of this congregation's own roster (from
 * [MeetingAssignmentsViewModel.rosterNamesFor]) that ADDS a removable chip
 * per tap instead of replacing the whole field, plus a manual-entry row for
 * anyone not in the roster yet — "however can be entered manually if not
 * available" still holds, just per-name now instead of as one free-typed
 * string. [value] is stored exactly like before (a single comma-separated
 * string — no model/Firestore-schema change needed), so a pre-existing
 * "Eva and Lita"-style value with no comma just shows up as one chip,
 * removable or left alone, same as any other name here. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PublisherAutocompleteField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    placeholderText: String? = null,
) {
    val selectedNames = remember(value) { value.split(",").map { it.trim() }.filter { it.isNotBlank() } }
    var expanded by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }

    fun addName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank() || selectedNames.any { it.equals(trimmed, ignoreCase = true) }) return
        onValueChange((selectedNames + trimmed).joinToString(", "))
    }
    fun removeName(name: String) {
        onValueChange(selectedNames.filterNot { it == name }.joinToString(", "))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val available = remember(suggestions, selectedNames) {
            suggestions.filter { s -> selectedNames.none { it.equals(s, ignoreCase = true) } }
        }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = "",
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                placeholder = { Text(placeholderText ?: if (selectedNames.isEmpty()) "Select from roster" else "Add another") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (available.isEmpty()) {
                    DropdownMenuItem(text = { Text("No more publishers in this congregation.") }, onClick = {}, enabled = false)
                } else {
                    available.forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { addName(name); expanded = false })
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = manualText,
                onValueChange = { manualText = it },
                label = { Text("Add manually (not in roster)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addName(manualText); manualText = "" }),
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { addName(manualText); manualText = "" }, enabled = manualText.isNotBlank()) {
                Icon(Icons.Rounded.Add, contentDescription = "Add $label")
            }
        }
        if (selectedNames.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                selectedNames.forEach { name ->
                    AssistChip(
                        onClick = { removeName(name) },
                        label = { Text(name) },
                        trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove $name") },
                    )
                }
            }
        }
    }
}
