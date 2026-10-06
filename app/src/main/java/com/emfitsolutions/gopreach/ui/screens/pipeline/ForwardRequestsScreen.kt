package com.emfitsolutions.gopreach.ui.screens.pipeline

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.ForwardRequest
import com.emfitsolutions.gopreach.data.model.ForwardRequestStatus
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.PublisherForwardRequest
import com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown
import com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.components.rememberCongregationContext
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import androidx.compose.ui.window.DialogProperties

/** "Forward to Other Congregation" spec flow — the receiving Service
 * Overseer's (also Coordinator Elder/Admin/Super-Admin) incoming review
 * queue: full record details, [ACCEPT] (then Assign to Publisher) / [DECLINE].
 * Also lists same-congregation "FORWARD TO OTHER PUBLISHER" requests
 * read-only below it — every role "can also see this," per spec, but only
 * the target publisher ever acts on that one.
 *
 * [readOnly] widens *visibility* of this whole screen to Regular Elder/
 * Ministerial Servant (the notification balloon's "Incoming approval request
 * for transfer [All]" item) without widening *approval authority* — they see
 * the same request details Service Overseer/Coordinator Elder/Admin/
 * Super-Admin do, just with [ACCEPT]/[DECLINE] replaced by a plain [CLOSE].
 *
 * "Consolidate 'Forward Request' Modules for Super Admin" — this one screen
 * is now also what used to be the separate "Forward Request Module": for
 * [isSuperAdmin], the same congregation-filtered list additionally shows
 * *every* status (not just PENDING — see [ForwardRequestsViewModel
 * .allRequestsFor]/[PublisherForwardRequestsViewModel.requestsFor], which
 * already return every status regardless), and gains a per-row Edit action
 * plus checkbox multi-select with Select All / Delete Selected. Every other
 * role keeps exactly the behavior this screen already had — PENDING-only,
 * no selection UI, no delete — since none of that is reachable unless
 * [isSuperAdmin] is true (and the nav graph only ever passes `true` for an
 * actual Super-Admin session). Deleting a request here only ever removes the
 * request document itself, never the underlying InterestedPerson record,
 * and never changes how forwarding, acceptance, notifications, or ownership
 * work for anyone else — see [ForwardRequestsViewModel.deleteRequest]/
 * [PublisherForwardRequestsViewModel.deleteRequest]'s own doc comments. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardRequestsScreen(
    congregationIds: Set<String>?,
    currentPersonId: String,
    readOnly: Boolean = false,
    isSuperAdmin: Boolean = false,
    onBack: () -> Unit,
    viewModel: ForwardRequestsViewModel = koinViewModel(),
    publisherForwardViewModel: PublisherForwardRequestsViewModel = koinViewModel(),
) {
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    // "Add a filter for Congregation" (Super-Admin only).
    var congregationFilter by rememberCongregationContext("forward_requests")
    val effectiveCongregationIds = congregationFilter?.let { setOf(it) } ?: congregationIds
    val needsCongregation = congregationIds == null && congregationFilter == null
    val requestsFlow = remember(effectiveCongregationIds, isSuperAdmin, needsCongregation) {
        if (needsCongregation) return@remember kotlinx.coroutines.flow.flowOf(emptyList())
        if (isSuperAdmin) viewModel.allRequestsFor(effectiveCongregationIds) else viewModel.pendingRequestsFor(effectiveCongregationIds)
    }
    val requests by requestsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val publisherRequestsFlow = remember(effectiveCongregationIds, needsCongregation) { if (needsCongregation) kotlinx.coroutines.flow.flowOf(emptyList()) else publisherForwardViewModel.requestsFor(effectiveCongregationIds) }
    val publisherRequests by publisherRequestsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by remember { mutableStateOf<ForwardRequest?>(null) }
    val showToast = rememberActionToast()

    // Super-Admin-only bulk selection/delete state — see this composable's
    // own doc comment. Two separate id sets since a congregation-forward and
    // a publisher-forward request are different document types/collections
    // ([ForwardRequestRepository]/[PublisherForwardRequestRepository]);
    // "Select All"/"Delete Selected" act on both together.
    var selectedCongRequestIds by remember { mutableStateOf(setOf<String>()) }
    var selectedPublisherRequestIds by remember { mutableStateOf(setOf<String>()) }
    var editingCongregationRequest by remember { mutableStateOf<ForwardRequest?>(null) }
    var editingPublisherRequest by remember { mutableStateOf<PublisherForwardRequest?>(null) }
    var confirmingBulkDelete by remember { mutableStateOf(false) }
    val totalSelected = selectedCongRequestIds.size + selectedPublisherRequestIds.size
    val allSelected = (requests.isNotEmpty() || publisherRequests.isNotEmpty()) &&
        selectedCongRequestIds.size == requests.size && selectedPublisherRequestIds.size == publisherRequests.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Forward Request") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
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
        if (isSuperAdmin && (requests.isNotEmpty() || publisherRequests.isNotEmpty())) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = { checked ->
                        if (checked) {
                            selectedCongRequestIds = requests.map { it.id }.toSet()
                            selectedPublisherRequestIds = publisherRequests.map { it.id }.toSet()
                        } else {
                            selectedCongRequestIds = emptySet()
                            selectedPublisherRequestIds = emptySet()
                        }
                    },
                )
                Text("Select All", modifier = Modifier.weight(1f))
                Text(
                    "${requests.size + publisherRequests.size} record(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (totalSelected > 0) {
                Button(
                    onClick = { confirmingBulkDelete = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Delete Selected ($totalSelected)")
                }
            }
        }
        if (needsCongregation) {
            SelectCongregationPrompt()
        } else if (requests.isEmpty() && publisherRequests.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RecordFound(0)
                Text("No forward requests.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { RecordFound(requests.size + publisherRequests.size) }
                if (requests.isNotEmpty()) {
                    item { Text("To Other Congregation", style = MaterialTheme.typography.titleSmall) }
                    items(requests, key = { it.id }) { request ->
                        val personFlow = remember(request.interestedPersonId) { viewModel.personFor(request.interestedPersonId) }
                        val person by personFlow.collectAsStateWithLifecycle(initialValue = null)
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(start = if (isSuperAdmin) 0.dp else 16.dp)) {
                                if (isSuperAdmin) {
                                    Checkbox(
                                        checked = request.id in selectedCongRequestIds,
                                        onCheckedChange = { checked ->
                                            selectedCongRequestIds = if (checked) selectedCongRequestIds + request.id else selectedCongRequestIds - request.id
                                        },
                                    )
                                }
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = if (isSuperAdmin) 0.dp else 0.dp).padding(end = 16.dp)) {
                                    Text(request.personNameSnapshot, style = MaterialTheme.typography.titleMedium)
                                    // "Include the basic details of the forwarded
                                    // record, not just the name" — stage + address,
                                    // live off the record itself (see
                                    // ForwardRequestsViewModel.personFor's own doc
                                    // comment).
                                    person?.let { p ->
                                        Text(stageLabel(p.pipelineStage), style = MaterialTheme.typography.bodySmall)
                                        addressLine(p)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    }
                                    Text("From: ${request.fromPublisherNameSnapshot} · ${request.fromCongregationNameSnapshot}", style = MaterialTheme.typography.bodySmall)
                                    Text("To: ${request.toCongregationNameSnapshot}", style = MaterialTheme.typography.bodySmall)
                                    Text("Requested: ${formatRecordTimestamp(request.requestedAt)}", style = MaterialTheme.typography.bodySmall)
                                    if (isSuperAdmin) Text("Status: ${statusLabel(request.status)}", style = MaterialTheme.typography.bodySmall, color = statusColor(request.status))
                                    Row {
                                        TextButton(onClick = { selected = request }) { Text(if (readOnly) "View" else "Review") }
                                        if (isSuperAdmin) {
                                            IconButton(onClick = { editingCongregationRequest = request }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (publisherRequests.isNotEmpty()) {
                    item { Text(if (isSuperAdmin) "To Other Publisher" else "To Other Publisher (view only)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                    items(publisherRequests, key = { it.id }) { request ->
                        PublisherForwardRequestRow(
                            request = request,
                            isSuperAdmin = isSuperAdmin,
                            selected = request.id in selectedPublisherRequestIds,
                            onSelectedChange = { checked ->
                                selectedPublisherRequestIds = if (checked) selectedPublisherRequestIds + request.id else selectedPublisherRequestIds - request.id
                            },
                            onEdit = { editingPublisherRequest = request },
                        )
                    }
                }
            }
        }
      }
    }

    AutoCloseOnNoLongerPending(selected?.id, requests.map { it.id }) { selected = null }

    selected?.let { request ->
        ReviewForwardRequestDialog(
            request = request,
            currentPersonId = currentPersonId,
            readOnly = readOnly,
            onDismiss = { selected = null },
            onDecline = {
                viewModel.decline(request, currentPersonId)
                showToast("Forward request declined.")
                selected = null
            },
            onAccepted = { assignedTo -> showToast("Accepted — assigned to ${assignedTo.fullName}.") },
            viewModel = viewModel,
        )
    }

    editingCongregationRequest?.let { request ->
        EditCongregationRequestDialog(
            request = request,
            onDismiss = { editingCongregationRequest = null },
            onSave = { updated ->
                viewModel.updateRequest(updated, currentPersonId)
                showToast("Forward request updated.")
                editingCongregationRequest = null
            },
        )
    }
    editingPublisherRequest?.let { request ->
        EditPublisherRequestDialog(
            request = request,
            onDismiss = { editingPublisherRequest = null },
            onSave = { updated ->
                publisherForwardViewModel.updateRequest(updated, currentPersonId)
                showToast("Forward request updated.")
                editingPublisherRequest = null
            },
        )
    }
    if (confirmingBulkDelete) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { confirmingBulkDelete = false },
            title = { Text("Delete Forward Request Record(s)?") },
            text = { Text("Are you sure you want to delete the selected Forward Request record(s)? This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    requests.filter { it.id in selectedCongRequestIds }.forEach { viewModel.deleteRequest(it, currentPersonId) }
                    publisherRequests.filter { it.id in selectedPublisherRequestIds }.forEach { publisherForwardViewModel.deleteRequest(it, currentPersonId) }
                    showToast("Forward Request record(s) successfully deleted.")
                    selectedCongRequestIds = emptySet()
                    selectedPublisherRequestIds = emptySet()
                    confirmingBulkDelete = false
                }) { Text("Yes, Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingBulkDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun statusLabel(status: ForwardRequestStatus): String = when (status) {
    ForwardRequestStatus.PENDING -> "Pending"
    ForwardRequestStatus.ACCEPTED -> "Accepted"
    ForwardRequestStatus.DECLINED -> "Declined"
    ForwardRequestStatus.CANCELLED -> "Cancelled"
}

@Composable
private fun statusColor(status: ForwardRequestStatus) = when (status) {
    ForwardRequestStatus.PENDING -> MaterialTheme.colorScheme.tertiary
    ForwardRequestStatus.ACCEPTED -> MaterialTheme.colorScheme.primary
    ForwardRequestStatus.DECLINED -> MaterialTheme.colorScheme.error
    ForwardRequestStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun PublisherForwardRequestRow(
    request: PublisherForwardRequest,
    isSuperAdmin: Boolean,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            if (isSuperAdmin) Checkbox(checked = selected, onCheckedChange = onSelectedChange)
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).padding(end = 16.dp)) {
                Text(request.personNameSnapshot, style = MaterialTheme.typography.titleMedium)
                Text("From: ${request.fromPublisherNameSnapshot} → ${request.toPublisherNameSnapshot}", style = MaterialTheme.typography.bodySmall)
                Text("Requested: ${formatRecordTimestamp(request.requestedAt)}", style = MaterialTheme.typography.bodySmall)
                Text("Status: ${statusLabel(request.status)}", style = MaterialTheme.typography.bodySmall, color = statusColor(request.status))
                if (isSuperAdmin) {
                    IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = "Edit") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewForwardRequestDialog(
    request: ForwardRequest,
    currentPersonId: String,
    readOnly: Boolean,
    onDismiss: () -> Unit,
    onDecline: () -> Unit,
    onAccepted: (Person) -> Unit,
    viewModel: ForwardRequestsViewModel,
) {
    val publishersFlow = remember(request.toCongregationId) { viewModel.assignablePublishers(request.toCongregationId) }
    val publishers by publishersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val personFlow = remember(request.interestedPersonId) { viewModel.personFor(request.interestedPersonId) }
    val person by personFlow.collectAsStateWithLifecycle(initialValue = null)
    var assigning by remember { mutableStateOf(false) }
    var selectedPublisher by remember { mutableStateOf<Person?>(null) }
    // "Prevent Double Submission" — this dialog predates FormDialog's own
    // built-in guard and isn't built on it (a custom two-step Accept/Assign
    // flow), so it needs its own; a fresh instance of this composable is
    // created each time a request is opened, so this always starts unarmed.
    var hasActed by remember { mutableStateOf(false) }

    if (readOnly) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = onDismiss,
            title = { Text("Forward Request") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("FORWARD REQUEST FROM:", style = MaterialTheme.typography.labelLarge)
                    Text("Publisher Name: ${request.fromPublisherNameSnapshot}")
                    Text("Congregation: ${request.fromCongregationNameSnapshot}")
                    Text("—".repeat(20), style = MaterialTheme.typography.bodySmall)
                    Text("Name: ${request.personNameSnapshot}")
                    person?.let { p ->
                        Text("Record status: ${stageLabel(p.pipelineStage)}")
                        addressLine(p)?.let { Text("Address: $it") }
                    }
                    Text("Status: Pending", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        return
    }

    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        onDismissRequest = onDismiss,
        title = { Text("Forward Request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("FORWARD REQUEST FROM:", style = MaterialTheme.typography.labelLarge)
                Text("Publisher Name: ${request.fromPublisherNameSnapshot}")
                Text("Congregation: ${request.fromCongregationNameSnapshot}")
                Text("—".repeat(20), style = MaterialTheme.typography.bodySmall)
                Text("Name: ${request.personNameSnapshot}")
                person?.let { p ->
                    Text("Record status: ${stageLabel(p.pipelineStage)}")
                    addressLine(p)?.let { Text("Address: $it") }
                }
                if (!assigning) {
                    Text("To assign this record to a publisher in your congregation, tap Accept.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Assign to:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = selectedPublisher?.fullName ?: "Select a publisher",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            visualTransformation = VisualTransformation.None,
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            if (publishers.isEmpty()) {
                                DropdownMenuItem(text = { Text("No publishers available") }, onClick = {}, enabled = false)
                            }
                            publishers.forEach { p ->
                                DropdownMenuItem(text = { Text(p.fullName) }, onClick = { selectedPublisher = p; expanded = false })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!assigning) {
                TextButton(onClick = { assigning = true }, enabled = !hasActed) { Text("Accept") }
            } else {
                TextButton(
                    onClick = {
                        val publisher = selectedPublisher
                        if (publisher != null && !hasActed) {
                            hasActed = true
                            viewModel.accept(request, publisher, currentPersonId)
                            onAccepted(publisher)
                            onDismiss()
                        }
                    },
                    enabled = selectedPublisher != null && !hasActed,
                ) { Text("Confirm") }
            }
        },
        dismissButton = {
            if (!assigning) {
                TextButton(
                    onClick = { if (!hasActed) { hasActed = true; onDecline() } },
                    enabled = !hasActed,
                ) { Text("Decline") }
            } else {
                TextButton(onClick = { assigning = false; selectedPublisher = null }, enabled = !hasActed) { Text("Back") }
            }
        },
    )
}

/** "If a forward request is cancelled, the accept/decline dialog open on the
 * receiving side must close automatically" — [selected] holds a static
 * snapshot of the request from when the dialog opened, so it never sees the
 * sender's later cancel on its own; this watches the *live*, reactively
 * filtered [requests] list instead and clears [selected] the moment the open
 * request's id drops out of it (cancelled, accepted, or declined by this
 * same screen, or the record's own PENDING status otherwise changing
 * server-side) — a real, no-manual-refresh close, not just "next open will be
 * stale-free". */
@Composable
private fun AutoCloseOnNoLongerPending(selectedId: String?, pendingIds: List<String>, onAutoClose: () -> Unit) {
    LaunchedEffect(selectedId, pendingIds) {
        if (selectedId != null && selectedId !in pendingIds) onAutoClose()
    }
}

/** "Include the basic details of the forwarded record, not just the name" —
 * a plain, human-readable stage name (matching the label this same stage
 * shows as everywhere else in the pipeline UI — see PipelineScreen's own,
 * screen-private equivalent). */
private fun stageLabel(stage: PipelineStage): String = when (stage) {
    PipelineStage.SEARCHING -> "Searching"
    PipelineStage.RETURN_VISIT -> "Return Visit"
    PipelineStage.BIBLE_STUDY -> "Bible Study"
}

/** Barangay/City-Municipality/Province, comma-joined, skipping whichever of
 * the three weren't filled in — `null` (not an empty string) when none of
 * them were, so callers can cleanly skip the line entirely instead of
 * showing an empty one. */
private fun addressLine(person: InterestedPerson): String? {
    val parts = listOfNotNull(person.barangay, person.cityMunicipality, person.province).filter { it.isNotBlank() }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/** Super-Admin-only correction tool (see this file's top-of-file doc
 * comment on consolidating the former "Forward Request Module" in here) —
 * edits a congregation-forward request's own snapshot fields/status
 * directly. Never touches the underlying InterestedPerson record. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditCongregationRequestDialog(
    request: ForwardRequest,
    onDismiss: () -> Unit,
    onSave: (ForwardRequest) -> Unit,
) {
    var name by remember { mutableStateOf(request.personNameSnapshot) }
    var status by remember { mutableStateOf(request.status) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        onDismissRequest = onDismiss,
        title = { Text("Edit Forward Request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = status.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Status") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        ForwardRequestStatus.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.name) }, onClick = { status = s; expanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(request.copy(personNameSnapshot = name, status = status)) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Same Super-Admin correction tool as [EditCongregationRequestDialog], for
 * a same-congregation "Forward to Other Publisher" request instead. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditPublisherRequestDialog(
    request: PublisherForwardRequest,
    onDismiss: () -> Unit,
    onSave: (PublisherForwardRequest) -> Unit,
) {
    var name by remember { mutableStateOf(request.personNameSnapshot) }
    var status by remember { mutableStateOf(request.status) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        onDismissRequest = onDismiss,
        title = { Text("Edit Forward Request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = status.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Status") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        ForwardRequestStatus.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.name) }, onClick = { status = s; expanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(request.copy(personNameSnapshot = name, status = status)) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
