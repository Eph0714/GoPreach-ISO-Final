package com.emfitsolutions.gopreach.ui.screens.calendar

import androidx.compose.foundation.verticalScroll
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.Schedule
import com.emfitsolutions.gopreach.data.model.ScheduleKind
import com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown
import com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.components.rememberCongregationContext
import com.emfitsolutions.gopreach.ui.components.DateTimeField
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.window.DialogProperties

/**
 * Spec §6.2 — Calendar. A single chronological list rather than a month grid
 * for this first pass — a dedicated month/week grid widget is a natural visual
 * upgrade later, but every view/add/edit/delete rule from the spec's role table
 * (via [CalendarScope]) is already enforced here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    currentPersonId: String,
    scope: CalendarScope,
    onBack: () -> Unit,
    viewModel: CalendarViewModel = koinViewModel(),
) {
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    // "Add a filter for Congregation" (Super-Admin only) — only offered when
    // [scope] is already unscoped (AdminTrack with a null congregationId);
    // every other scope is already fixed to one congregation, so there's
    // nothing for them to filter.
    var congregationFilter by rememberCongregationContext("calendar")
    val isSuperAdminScope = scope is CalendarScope.AdminTrack && scope.congregationId == null
    val effectiveScope = if (isSuperAdminScope) (scope as CalendarScope.AdminTrack).copy(congregationId = congregationFilter) else scope
    val needsCongregation = isSuperAdminScope && congregationFilter == null
    val eventsFlow = remember(effectiveScope, needsCongregation) { if (needsCongregation) kotlinx.coroutines.flow.flowOf(emptyList()) else viewModel.eventsFor(effectiveScope, currentPersonId) }
    val events by eventsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingEdit by remember { mutableStateOf<Schedule?>(null) }
    var pendingDelete by remember { mutableStateOf<Schedule?>(null) }
    val showToast = rememberActionToast()
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    val isPublisherScope = scope is CalendarScope.Publisher

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Rounded.Add, contentDescription = if (isPublisherScope) "New Note" else "New Event")
            }
        },
    ) { padding ->
      Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (isSuperAdminScope) {
            CongregationFilterDropdown(
                congregations = congregations,
                selectedCongregationId = congregationFilter,
                onSelected = { congregationFilter = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (needsCongregation) {
            SelectCongregationPrompt()
        } else if (events.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RecordFound(0)
                Text("Nothing on the calendar yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { RecordFound(events.size) }
                items(events, key = { it.id }) { event ->
                    val editable = viewModel.canEdit(scope, event, currentPersonId)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(event.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${dateFormat.format(Date(event.startTime))} – ${dateFormat.format(Date(event.endTime))}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (event.kind == ScheduleKind.PERSONAL_NOTE) {
                                    Text("Personal note", style = MaterialTheme.typography.labelSmall)
                                }
                                if (event.description != null) {
                                    Text(event.description!!, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (editable) {
                                Row {
                                    IconButton(onClick = { pendingEdit = event }) {
                                        Icon(Icons.Rounded.Edit, contentDescription = "Edit")
                                    }
                                    IconButton(onClick = { pendingDelete = event }) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "Delete")
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

    if (showCreateDialog) {
        EventDialog(
            currentPersonId = currentPersonId,
            scope = scope,
            existingEvent = null,
            onSave = { viewModel.save(it); showToast("Event added.") },
            onDismiss = { showCreateDialog = false },
        )
    }

    val toEditEvent = pendingEdit
    if (toEditEvent != null) {
        EventDialog(
            currentPersonId = currentPersonId,
            scope = scope,
            existingEvent = toEditEvent,
            onSave = { viewModel.save(it); showToast("Event saved.") },
            onDismiss = { pendingEdit = null },
        )
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${toDelete.title}\"?") },
            text = { Text("This removes the calendar entry.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(toDelete, currentPersonId)
                    showToast("\"${toDelete.title}\" moved to Deleted Records.")
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EventDialog(
    currentPersonId: String,
    scope: CalendarScope,
    existingEvent: Schedule?,
    onSave: (Schedule) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(existingEvent?.title ?: "") }
    var description by remember { mutableStateOf(existingEvent?.description ?: "") }
    var startTime by remember { mutableStateOf(existingEvent?.startTime) }
    var endTime by remember { mutableStateOf(existingEvent?.endTime) }
    val isNew = existingEvent == null
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val start = startTime
        val end = endTime
        val message = requiredFieldsMessage(
            "Event Title" to title.isNotBlank(),
            "Start" to (start != null),
            "End" to (end != null),
        )
        if (message != null) {
            errorMessage = message
            return
        }
        onSave(
            when (scope) {
                is CalendarScope.Publisher -> Schedule(
                    id = existingEvent?.id ?: "",
                    kind = ScheduleKind.PERSONAL_NOTE,
                    title = title.trim(),
                    description = description.trim().ifBlank { null },
                    startTime = start!!,
                    endTime = end!!,
                    ownerPersonId = currentPersonId,
                    createdByPersonId = existingEvent?.createdByPersonId ?: currentPersonId,
                    createdAt = existingEvent?.createdAt ?: System.currentTimeMillis(),
                )
                is CalendarScope.AdminTrack -> Schedule(
                    id = existingEvent?.id ?: "",
                    kind = ScheduleKind.CALENDAR_EVENT,
                    title = title.trim(),
                    description = description.trim().ifBlank { null },
                    startTime = start!!,
                    endTime = end!!,
                    congregationId = existingEvent?.congregationId ?: scope.congregationId,
                    groupId = existingEvent?.groupId ?: (if (!scope.canEditAll) scope.editableGroupId else null),
                    createdByPersonId = existingEvent?.createdByPersonId ?: currentPersonId,
                    createdAt = existingEvent?.createdAt ?: System.currentTimeMillis(),
                )
            }
        )
        onDismiss()
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = when {
            !isNew -> "Edit ${if (scope is CalendarScope.Publisher) "Note" else "Event"}"
            scope is CalendarScope.Publisher -> "New Personal Note"
            else -> "New Calendar Event"
        },
        onConfirm = ::submit,
        confirmLabel = "Save",
        errorMessage = errorMessage,
        maxContentHeight = 420.dp,
        hasUnsavedChanges = title != (existingEvent?.title ?: "") || description != (existingEvent?.description ?: "") ||
            startTime != existingEvent?.startTime || endTime != existingEvent?.endTime,
    ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.uppercase() },
                    label = { Text("Event Title") },
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.uppercase() },
                    label = { Text("Description (optional)") },
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
                DateTimeField(label = "Start", valueMillis = startTime, onValueChange = { startTime = it })
                DateTimeField(label = "End", valueMillis = endTime, onValueChange = { endTime = it })
    }
}
