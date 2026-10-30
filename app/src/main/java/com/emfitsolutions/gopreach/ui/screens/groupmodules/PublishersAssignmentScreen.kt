package com.emfitsolutions.gopreach.ui.screens.groupmodules

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.AssignmentStatus
import com.emfitsolutions.gopreach.data.model.AssignmentTypes
import com.emfitsolutions.gopreach.data.model.PublisherAssignment
import com.emfitsolutions.gopreach.ui.components.Scaffold
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.koin.compose.viewmodel.koinViewModel

private val dayFormat get() = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
private val stampFormat get() = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())

/** A status as the small coloured label both apps and the website use. */
@Composable
fun AssignmentStatusPill(status: String) {
    val s = AssignmentStatus.of(status)
    Text(
        s.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(s.textColor),
        modifier = Modifier.background(Color(s.backColor), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/**
 * Publishers Assignment: the Overseer, Servant and Assistant of an FS Group give, edit, follow and remove the assignments of the publishers of THEIR group. The group is the
 * person's own slot (never a choice); the server checks every save again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublishersAssignmentScreen(
    currentPersonId: String,
    currentPersonName: String,
    congregationId: String?,
    onBack: () -> Unit,
    viewModel: PublishersAssignmentViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.startSync().collect { } }
    val flow = remember(currentPersonId, congregationId) { viewModel.state(currentPersonId, congregationId) }
    val state by flow.collectAsStateWithLifecycle(initialValue = AssignmentsState())
    var query by rememberSaveable { mutableStateOf("") }
    var statusFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var typeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<PublisherAssignment?>(null) }
    var creating by remember { mutableStateOf(false) }
    var historyOf by remember { mutableStateOf<PublisherAssignment?>(null) }
    var deleting by remember { mutableStateOf<PublisherAssignment?>(null) }
    val slot = state.slot

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Publishers Assignment") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (slot == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(
                    if (state.loaded) "Publishers Assignment is kept by the Overseer, Servant and Assistant of an FS Group, for their own group only." else "Loading...",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        val shown = filterAssignments(state.assignments, { state.members[it].orEmpty() }, query, statusFilter, typeFilter)
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(slot.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(slot.group.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text(
                            "${state.members.size} ${if (state.members.size == 1) "publisher" else "publishers"} in this group · ${state.assignments.size} ${if (state.assignments.size == 1) "assignment" else "assignments"}. " +
                                "You manage the assignments of your own FS Group only.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        androidx.compose.material3.Button(onClick = { creating = true }, modifier = Modifier.padding(top = 6.dp)) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Text("New Assignment", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search publisher or assignment") },
                )
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = statusFilter == null, onClick = { statusFilter = null }, label = { Text("All statuses") })
                    AssignmentStatus.entries.forEach { s ->
                        FilterChip(selected = statusFilter == s.name, onClick = { statusFilter = if (statusFilter == s.name) null else s.name }, label = { Text(s.label) })
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = typeFilter == null, onClick = { typeFilter = null }, label = { Text("All types") })
                    AssignmentTypes.all.forEach { (key, label) ->
                        FilterChip(selected = typeFilter == key, onClick = { typeFilter = if (typeFilter == key) null else key }, label = { Text(label) })
                    }
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (state.assignments.isEmpty()) "No assignments yet. Tap New Assignment to give the first one." else "No assignment matches the search.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(shown, key = { it.id }) { a ->
                AssignmentCard(
                    a, publisherName = state.members[a.publisherPersonId] ?: a.publisherName, stillInGroup = a.publisherPersonId in state.members,
                    onStatus = { viewModel.setStatus(a, it, currentPersonId, currentPersonName, System.currentTimeMillis()) },
                    onEdit = { editing = a }, onHistory = { historyOf = a }, onDelete = { deleting = a },
                )
            }
        }
    }

    if (creating && slot != null) {
        AssignmentFormDialog(
            title = "New assignment for ${slot.group.name}", members = state.members, initial = null,
            onDismiss = { creating = false },
            onSave = { draft ->
                viewModel.create(slot, draft, state.members[draft.publisherPersonId].orEmpty(), currentPersonId, currentPersonName, System.currentTimeMillis())
                creating = false
            },
        )
    }
    editing?.let { a ->
        AssignmentFormDialog(
            title = "Edit assignment", members = state.members, initial = a, onDismiss = { editing = null },
            onSave = { draft ->
                viewModel.update(a, draft, currentPersonId, currentPersonName, System.currentTimeMillis())
                editing = null
            },
        )
    }
    historyOf?.let { a ->
        AlertDialog(
            onDismissRequest = { historyOf = null },
            title = { Text("Assignment history") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.members[a.publisherPersonId] ?: a.publisherName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    a.history.reversed().forEach { h ->
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(10.dp)) {
                            Text(h.note.ifBlank { AssignmentStatus.of(h.status).label }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text("${stampFormat.format(Date(h.at))} · ${h.byName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { historyOf = null }) { Text("Close") } },
        )
    }
    deleting?.let { a ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this assignment?") },
            text = { Text("\"${a.title}\" and its history are removed.") },
            confirmButton = { TextButton(onClick = { viewModel.delete(a.id); deleting = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AssignmentCard(
    a: PublisherAssignment,
    publisherName: String,
    stillInGroup: Boolean,
    onStatus: (AssignmentStatus) -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onDelete: () -> Unit,
) {
    var statusMenu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(a.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                AssignmentStatusPill(a.status)
            }
            Text(publisherName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (!stillInGroup) Text("No longer in this group", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Text(
                AssignmentTypes.label(a.type) + (a.date?.let { " · " + dayFormat.format(Date(it)) } ?: "") + " · by ${a.assignedByName}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (a.description.isNotBlank()) Text(a.description, style = MaterialTheme.typography.bodySmall)
            if (a.remarks.isNotBlank()) Text("Remarks: ${a.remarks}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Updated ${stampFormat.format(Date(a.updatedAt))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 2.dp)) {
                Box {
                    TextButton(onClick = { statusMenu = true }) { Text("Status"); Icon(Icons.Rounded.ArrowDropDown, contentDescription = null) }
                    DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                        AssignmentStatus.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { statusMenu = false; onStatus(s) }) }
                    }
                }
                if (stillInGroup) TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onHistory) { Text("History") }
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun <T> PickerField(label: String, value: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value.ifBlank { "Choose..." }, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(key) }) }
        }
    }
}

/** The form for one assignment. The publisher can only be one of the group's own members; the group itself is not a field. */
@Composable
private fun AssignmentFormDialog(
    title: String,
    members: Map<String, String>,
    initial: PublisherAssignment?,
    onDismiss: () -> Unit,
    onSave: (PublisherAssignment) -> Unit,
) {
    val context = LocalContext.current
    var publisher by remember { mutableStateOf(initial?.publisherPersonId.orEmpty()) }
    var type by remember { mutableStateOf(initial?.type ?: "FIELD_SERVICE") }
    var heading by remember { mutableStateOf(initial?.title.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var remarks by remember { mutableStateOf(initial?.remarks.orEmpty()) }
    var status by remember { mutableStateOf(initial?.status ?: AssignmentStatus.ASSIGNED.name) }
    var date by remember { mutableStateOf(initial?.date) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initial == null) {
                    PickerField("Publisher", members[publisher].orEmpty(), members.entries.map { it.key to it.value }) { publisher = it }
                } else {
                    Text(members[publisher] ?: initial.publisherName, fontWeight = FontWeight.SemiBold)
                }
                PickerField("Type", AssignmentTypes.label(type), AssignmentTypes.all.entries.map { it.key to it.value }) { type = it }
                OutlinedTextField(value = heading, onValueChange = { heading = it.take(120) }, label = { Text("Assignment title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(
                    onClick = {
                        val cal = Calendar.getInstance().apply { date?.let { timeInMillis = it } }
                        DatePickerDialog(context, { _, y, m, d ->
                            date = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(date?.let { "Date: " + dayFormat.format(Date(it)) } ?: "Assignment date (optional)") }
                OutlinedTextField(value = description, onValueChange = { description = it.take(1000) }, label = { Text("Description") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                PickerField("Status", AssignmentStatus.of(status).label, AssignmentStatus.entries.map { it.name to it.label }) { status = it }
                OutlinedTextField(value = remarks, onValueChange = { remarks = it.take(500) }, label = { Text("Remarks") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = publisher.isNotBlank() && heading.isNotBlank(),
                onClick = {
                    onSave(
                        (initial ?: PublisherAssignment()).copy(
                            publisherPersonId = publisher, type = type, title = heading.trim(), description = description.trim(), remarks = remarks.trim(), status = status, date = date,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What the publisher sees of their FS Group's assignments: only their own, read-only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyGroupAssignmentsScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    viewModel: PublishersAssignmentViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.startSync().collect { } }
    val flow = remember(currentPersonId) { viewModel.mine(currentPersonId) }
    val rows by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My FS Group Assignments") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    "The assignments your FS Group's Overseer, Servant or Assistant gave you. Only you and the officers of your group can see them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (rows.isEmpty()) item { Text("You have no assignments from your FS Group.", style = MaterialTheme.typography.bodyMedium) }
            items(rows, key = { it.id }) { a ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(a.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            AssignmentStatusPill(a.status)
                        }
                        Text(
                            AssignmentTypes.label(a.type) + (a.date?.let { " · " + dayFormat.format(Date(it)) } ?: "") + " · ${a.groupName} · by ${a.assignedByName}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (a.description.isNotBlank()) Text(a.description, style = MaterialTheme.typography.bodySmall)
                        if (a.remarks.isNotBlank()) Text("Remarks: ${a.remarks}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
