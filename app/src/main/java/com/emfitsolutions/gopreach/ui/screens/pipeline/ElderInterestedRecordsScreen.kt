package com.emfitsolutions.gopreach.ui.screens.pipeline

import androidx.compose.foundation.clickable
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp

private fun PipelineStage.label(): String = when (this) {
    PipelineStage.SEARCHING -> "Searching"
    PipelineStage.RETURN_VISIT -> "Return Visit"
    PipelineStage.BIBLE_STUDY -> "Bible Study"
}

/**
 * Spec §15 — "Elders should be able to see Interested Person information
 * according to their existing Congregation/Group access scope." Read-only
 * browse-and-view: an Elder/Admin sees every Interested Person within
 * [congregationId] (narrowed further to [groupId]'s own members for a
 * Regular Elder — see [PipelineViewModel.peopleForScope]), can search/filter
 * and open a record's full detail, but cannot add/edit/delete or advance a
 * pipeline stage from here — this screen only ever satisfies "see," not the
 * write authority [PipelineScreen] already gives a publisher over their own
 * records or [SuperAdminInterestedRecordsScreen] gives a Super-Admin over
 * every record. Kept deliberately read-only rather than reusing
 * [PipelinePersonDetailScreen]'s full action set: that screen's Visit/GPS/
 * stage-advance actions attribute writes to the viewing session
 * ([currentPersonId]), which would be wrong here since the viewer is
 * typically not this record's own publisher.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElderInterestedRecordsScreen(
    congregationId: String?,
    groupId: String?,
    onBack: () -> Unit,
    viewModel: PipelineViewModel = hiltViewModel(),
) {
    val congregationName by remember(congregationId) {
        if (congregationId != null) viewModel.congregationName(congregationId) else kotlinx.coroutines.flow.flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val scopeLabel = (congregationName ?: "—") + if (groupId != null) " (own Group only)" else ""

    var stage by remember { mutableStateOf(PipelineStage.SEARCHING) }
    val peopleFlow = remember(stage, congregationId, groupId) { viewModel.peopleForScope(stage, congregationId, groupId) }
    val allPeople by peopleFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var showInactive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectedPerson by remember { mutableStateOf<InterestedPerson?>(null) }

    val people = remember(allPeople, showInactive, query) {
        allPeople
            .filter { showInactive || it.status == RecordStatus.ACTIVE }
            .filter { p -> query.isBlank() || p.name.contains(query, ignoreCase = true) || p.address.contains(query, ignoreCase = true) }
            .sortedBy { it.name }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Interested People") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                )
                ScrollableTabRow(selectedTabIndex = PipelineStage.entries.indexOf(stage)) {
                    PipelineStage.entries.forEach { s ->
                        Tab(selected = stage == s, onClick = { stage = s }, text = { Text(s.label()) })
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Scope: $scopeLabel",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search by name or place of origin") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showInactive, onCheckedChange = { showInactive = it })
                Text("Show Inactive")
            }
            if (people.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    RecordFound(0)
                    Text("No ${stage.label()} records found in this scope.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { RecordFound(people.size) }
                    items(people, key = { it.id }) { person ->
                        val publisherName by remember(person.publisherPersonId) { viewModel.personName(person.publisherPersonId) }.collectAsStateWithLifecycle(initialValue = null)
                        Card(modifier = Modifier.fillMaxWidth().clickable { selectedPerson = person }) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(person.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    publisherName?.let { "Publisher: $it" } ?: "Unassigned",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(person.address, style = MaterialTheme.typography.bodySmall)
                                if (person.status == RecordStatus.INACTIVE) {
                                    Text("Inactive", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val current = selectedPerson
    if (current != null) {
        val publisherName by remember(current.publisherPersonId) { viewModel.personName(current.publisherPersonId) }.collectAsStateWithLifecycle(initialValue = null)
        InterestedPersonReadOnlyDialog(person = current, publisherName = publisherName, onDismiss = { selectedPerson = null })
    }
}

/** Every field [InterestedPerson] carries, plain read-only text — no action
 * buttons, per this screen's own "see, don't manage" scope note above. */
@Composable
private fun InterestedPersonReadOnlyDialog(person: InterestedPerson, publisherName: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(person.name) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ReadOnlyRow("Publisher", publisherName ?: "Unassigned")
                ReadOnlyRow("Place of Origin", person.address)
                if (!person.province.isNullOrBlank() || !person.cityMunicipality.isNullOrBlank() || !person.barangay.isNullOrBlank()) {
                    ReadOnlyRow("Current Address", listOfNotNull(person.barangay, person.cityMunicipality, person.province).joinToString(", "))
                }
                person.gender?.let { ReadOnlyRow("Gender", it.name) }
                person.ageYears?.let { ReadOnlyRow("Age", it.toString()) }
                person.spouse?.let { if (it.isNotBlank()) ReadOnlyRow("Spouse", it) }
                person.children?.let { if (it.isNotBlank()) ReadOnlyRow("Children", it) }
                person.placeOrigin?.let { if (it.isNotBlank() && it != person.address) ReadOnlyRow("Previously Recorded Place Origin", it) }
                person.language?.let { if (it.isNotBlank()) ReadOnlyRow("Language", it) }
                person.religion?.let { if (it.isNotBlank()) ReadOnlyRow("Religion", it) }
                person.literaturePlace?.let { if (it.isNotBlank()) ReadOnlyRow("Literature Left At", it) }
                person.contact?.let { if (it.isNotBlank()) ReadOnlyRow("Contact", it) }
                if (person.hasGpsLocation) {
                    ReadOnlyRow("GPS", "${person.gpsLat}, ${person.gpsLng}")
                }
                ReadOnlyRow("Stage", person.pipelineStage.label())
                ReadOnlyRow("Status", person.status.name)
                ReadOnlyRow("Date Added", formatRecordTimestamp(person.createdAt))
                person.remarks?.let { if (it.isNotBlank()) ReadOnlyRow("Remarks", it) }
                person.notes?.let { if (it.isNotBlank()) ReadOnlyRow("Notes", it) }
            }
        },
    )
}

@Composable
private fun ReadOnlyRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
