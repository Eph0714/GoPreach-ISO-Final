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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.PublisherForwardRequest
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import androidx.compose.ui.window.DialogProperties

/** "FORWARD TO OTHER PUBLISHER" spec flow — the *receiving* publisher's
 * "Forwarded to Me" queue: full record details, [ACCEPT]/[DECLINE] directly
 * (no assignment step — the sender already targeted this exact publisher). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublisherForwardRequestsScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    viewModel: PublisherForwardRequestsViewModel = koinViewModel(),
) {
    val requestsFlow = remember(currentPersonId) { viewModel.incomingRequestsFor(currentPersonId) }
    val requests by requestsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by remember { mutableStateOf<PublisherForwardRequest?>(null) }
    val showToast = rememberActionToast()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Forwarded to Me") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (requests.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RecordFound(0)
                Text("No pending records forwarded to you.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { RecordFound(requests.size) }
                items(requests, key = { it.id }) { request ->
                    val personFlow = remember(request.interestedPersonId) { viewModel.personFor(request.interestedPersonId) }
                    val person by personFlow.collectAsStateWithLifecycle(initialValue = null)
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(request.personNameSnapshot, style = MaterialTheme.typography.titleMedium)
                            // "Include the basic details of the forwarded record,
                            // not just the name" — stage + address, live off the
                            // record itself (see PublisherForwardRequestsViewModel
                            // .personFor's own doc comment).
                            person?.let { p ->
                                Text(stageLabel(p.pipelineStage), style = MaterialTheme.typography.bodySmall)
                                addressLine(p)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                            Text("From: ${request.fromPublisherNameSnapshot}", style = MaterialTheme.typography.bodySmall)
                            Text("Requested: ${formatRecordTimestamp(request.requestedAt)}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { selected = request }) { Text("Review") }
                        }
                    }
                }
            }
        }
    }

    // "If a forward request is cancelled, the accept/decline dialog open on
    // the receiving side must close automatically" — [requests] is the live,
    // reactively-filtered PENDING queue; the moment the open request's id
    // drops out of it (the sender cancelled it, or it was actioned from
    // elsewhere), this closes the dialog on its own — no manual refresh
    // needed to discover the stale state.
    LaunchedEffect(selected?.id, requests) {
        val id = selected?.id
        if (id != null && requests.none { it.id == id }) selected = null
    }

    selected?.let { request ->
        val personFlow = remember(request.interestedPersonId) { viewModel.personFor(request.interestedPersonId) }
        val person by personFlow.collectAsStateWithLifecycle(initialValue = null)
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { selected = null },
            title = { Text("Forwarded Record") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("FORWARDED BY:", style = MaterialTheme.typography.labelLarge)
                    Text("Publisher Name: ${request.fromPublisherNameSnapshot}")
                    Text("—".repeat(20), style = MaterialTheme.typography.bodySmall)
                    Text("Name: ${request.personNameSnapshot}")
                    // "Include the basic details of the forwarded record, not
                    // just the name" — same live lookup as the list card.
                    person?.let { p ->
                        Text("Status: ${stageLabel(p.pipelineStage)}")
                        p.gender?.let { Text("Gender: ${it.name.lowercase().replaceFirstChar(Char::uppercase)}") }
                        addressLine(p)?.let { Text("Address: $it") }
                    }
                    Text(
                        "Accepting adds this record to your own Bible Study/Return Visit record and removes it from ${request.fromPublisherNameSnapshot}'s.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.accept(request, currentPersonId)
                        showToast("Accepted — added to your own record.")
                        selected = null
                    },
                ) { Text("Accept") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            viewModel.decline(request, currentPersonId)
                            showToast("Forward request declined.")
                            selected = null
                        },
                    ) { Text("Decline") }
                    TextButton(onClick = { selected = null }) { Text("Close") }
                }
            },
        )
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
