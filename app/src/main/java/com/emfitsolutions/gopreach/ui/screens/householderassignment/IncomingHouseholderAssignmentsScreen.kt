package com.emfitsolutions.gopreach.ui.screens.householderassignment

import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.HouseholderAssignment
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import com.emfitsolutions.gopreach.ui.components.rememberActionToast

/** Spec's own preset options, "Other" always last with a free-text follow-up. */
private val REJECTION_REASONS = listOf(
    "Already handling this house holder",
    "Not available",
    "Location is inconvenient",
    "Unable to take this assignment",
    "Other",
)

private fun addressLine(person: InterestedPerson): String? {
    val parts = listOfNotNull(person.barangay, person.cityMunicipality, person.province).filter { it.isNotBlank() }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/**
 * "Add a New Module: House Holder Assignment" — the receiving Publisher's
 * own "Incoming Assignments" queue: Notification -> Incoming Assignments ->
 * Review Assignment -> Accept/Reject. Mirrors
 * [com.emfitsolutions.gopreach.ui.screens.pipeline
 * .PublisherForwardRequestsScreen] closely (same entities, same review-
 * dialog shape, same "auto-close if resolved elsewhere" guard) — a Publisher
 * never creates an assignment here, only reviews one already sent to them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingHouseholderAssignmentsScreen(
    currentPersonId: String,
    currentPersonName: String,
    onBack: () -> Unit,
    viewModel: HouseholderAssignmentViewModel = hiltViewModel(),
) {
    val assignmentsFlow = remember(currentPersonId) { viewModel.incomingAssignmentsFor(currentPersonId) }
    val assignments by assignmentsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by remember { mutableStateOf<HouseholderAssignment?>(null) }
    var confirmingReject by remember { mutableStateOf(false) }
    val showToast = rememberActionToast()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Incoming Assignments") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (assignments.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RecordFound(0)
                Text("No incoming House Holder Assignments.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { RecordFound(assignments.size) }
                items(assignments, key = { it.id }) { assignment ->
                    val personFlow = remember(assignment.interestedPersonId) { viewModel.personFor(assignment.interestedPersonId) }
                    val person by personFlow.collectAsStateWithLifecycle(initialValue = null)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(assignment.personNameSnapshot, style = MaterialTheme.typography.titleMedium)
                            Text("Record Type: ${assignment.recordType.assignmentLabel()}", style = MaterialTheme.typography.bodySmall)
                            (assignment.barangaySnapshot ?: person?.let { addressLine(it) })?.let {
                                Text("Barangay: $it", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("From: ${assignment.assignedByNameSnapshot}", style = MaterialTheme.typography.bodySmall)
                            Text("Assigned: ${formatRecordTimestamp(assignment.assignedAt)}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { selected = assignment }) { Text("Review Assignment") }
                        }
                    }
                }
            }
        }
    }

    // "If a forward request is cancelled, the accept/decline dialog open on
    // the receiving side must close automatically" — same guard
    // PublisherForwardRequestsScreen already uses, applied here for a
    // Cancelled-while-open assignment.
    LaunchedEffect(selected?.id, assignments) {
        val id = selected?.id
        if (id != null && assignments.none { it.id == id }) selected = null
    }

    selected?.let { assignment ->
        val personFlow = remember(assignment.interestedPersonId) { viewModel.personFor(assignment.interestedPersonId) }
        val person by personFlow.collectAsStateWithLifecycle(initialValue = null)
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { selected = null },
            title = { Text("New House Holder Assignment") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("You have received a new assignment from ${assignment.assignedByNameSnapshot}.", style = MaterialTheme.typography.bodySmall)
                    Text("—".repeat(20), style = MaterialTheme.typography.bodySmall)
                    Text("House Holder: ${assignment.personNameSnapshot}")
                    Text("Record Type: ${assignment.recordType.assignmentLabel()}")
                    (assignment.barangaySnapshot ?: person?.barangay)?.let { if (it.isNotBlank()) Text("Barangay: $it") }
                    (assignment.addressSnapshot ?: person?.address)?.let { if (it.isNotBlank()) Text("Place of Origin: $it") }
                    person?.let { p ->
                        p.gender?.let { Text("Gender: ${it.name.lowercase().replaceFirstChar(Char::uppercase)}") }
                        p.ageYears?.let { Text("Age: $it") }
                        p.contact?.let { if (it.isNotBlank()) Text("Contact: $it") }
                    }
                    (assignment.notesSnapshot ?: person?.notes)?.let { if (it.isNotBlank()) Text("Notes: $it") }
                    Text("Assigned By: ${assignment.assignedByNameSnapshot}")
                    Text("Congregation: ${assignment.congregationNameSnapshot}")
                    Text("Date/Time: ${formatRecordTimestamp(assignment.assignedAt)}")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.accept(assignment, currentPersonId, currentPersonName)
                        showToast("${assignment.personNameSnapshot} has been successfully accepted and assigned to $currentPersonName.")
                        selected = null
                    },
                ) { Text("Accept Assignment") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmingReject = true }) { Text("Reject Assignment") }
                    TextButton(onClick = { selected = null }) { Text("Close") }
                }
            },
        )
    }

    if (confirmingReject) {
        selected?.let { assignment ->
            RejectAssignmentDialog(
                onDismiss = { confirmingReject = false },
                onConfirm = { reason ->
                    viewModel.reject(assignment, currentPersonId, currentPersonName, reason)
                    showToast("This assignment has been rejected and the assigning user has been notified.")
                    confirmingReject = false
                    selected = null
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RejectAssignmentDialog(onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var selectedReason by remember { mutableStateOf<String?>(null) }
    var otherText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reject Assignment?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Are you sure you want to reject this assignment? You may optionally provide a reason.", style = MaterialTheme.typography.bodySmall)
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selectedReason ?: "(No reason)",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Reason (optional)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("(No reason)") }, onClick = { selectedReason = null; expanded = false })
                        REJECTION_REASONS.forEach { reason ->
                            DropdownMenuItem(text = { Text(reason) }, onClick = { selectedReason = reason; expanded = false })
                        }
                    }
                }
                if (selectedReason == "Other") {
                    OutlinedTextField(
                        value = otherText,
                        onValueChange = { otherText = it },
                        label = { Text("Please specify") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val reason = when {
                        selectedReason == "Other" -> otherText.trim().ifBlank { "Other" }
                        selectedReason != null -> selectedReason
                        else -> null
                    }
                    onConfirm(reason)
                },
            ) { Text("Confirm Reject") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
