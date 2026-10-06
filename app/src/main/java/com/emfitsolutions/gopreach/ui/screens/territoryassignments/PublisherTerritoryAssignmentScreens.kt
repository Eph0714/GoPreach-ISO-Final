package com.emfitsolutions.gopreach.ui.screens.territoryassignments

import androidx.compose.foundation.clickable
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Print
import com.emfitsolutions.gopreach.data.print.ReportPrinter
import com.emfitsolutions.gopreach.data.print.ReportTable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.PublisherTerritoryAssignment
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.PhilippineLocationRepository
import com.emfitsolutions.gopreach.data.repository.PsgcOption
import com.emfitsolutions.gopreach.data.repository.PublisherTerritoryAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.domain.PermissionChecker
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PublisherOption(val personId: String, val name: String)

data class PublisherAssignmentFormState(
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class PublisherTerritoryAssignmentViewModel @Inject constructor(
    private val repository: PublisherTerritoryAssignmentRepository,
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val philippineLocationRepository: PhilippineLocationRepository,
    private val recycleBinRepository: com.emfitsolutions.gopreach.data.repository.RecycleBinRepository,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    val congregations: Flow<List<Congregation>> = congregationRepository.observeAll()
        .map { list -> list.filter { it.status == RecordStatus.ACTIVE }.sortedBy { it.name } }

    val assignments: Flow<List<PublisherTerritoryAssignment>> = repository.observeAll()
        .map { list -> list.sortedWith(compareBy({ it.publisherName }, { it.muncityName }, { it.barangayName })) }

    private val _formState = MutableStateFlow(PublisherAssignmentFormState())
    val formState: StateFlow<PublisherAssignmentFormState> = _formState

    /** Active Publishers enrolled in [congregationId] (any category except
     * Removed). */
    fun publishersFor(congregationId: String): Flow<List<PublisherOption>> =
        combine(personRepository.observeAll(), roleAssignmentRepository.observeAll()) { people, assignments ->
            assignments
                .filter { it.congregationId == congregationId && it.status == RoleAssignmentStatus.ACTIVE }
                .filter { a ->
                    val type = a.resolvedRoleTypeOrNull()
                    type is RoleType.Publisher &&
                        type.category != com.emfitsolutions.gopreach.data.model.PublisherCategory.REMOVED_PUBLISHER
                }
                .mapNotNull { a -> people.firstOrNull { it.id == a.personId } }
                .distinctBy { it.id }
                .map { PublisherOption(it.id, it.fullName) }
                .sortedBy { it.name }
        }

    /** The selected Publisher's own enrolled Return Visit records. */
    fun returnVisitsFor(publisherPersonId: String): Flow<List<InterestedPerson>> =
        interestedPersonRepository.observeAll().map { list ->
            list.filter { it.publisherPersonId == publisherPersonId && it.pipelineStage == PipelineStage.RETURN_VISIT }
                .sortedBy { it.name }
        }

    suspend fun searchProvinces(query: String): List<PsgcOption> = philippineLocationRepository.searchProvinces(query)
    suspend fun searchMunicipalities(provinceId: Int): List<PsgcOption> =
        philippineLocationRepository.searchCitiesMunicipalities(provinceId, "")
    suspend fun searchBarangays(muncityId: Int): List<PsgcOption> = philippineLocationRepository.searchBarangays(muncityId, "")

    fun consumeFormState() {
        _formState.value = PublisherAssignmentFormState()
    }

    fun save(assignment: PublisherTerritoryAssignment, actorPersonId: String) {
        _formState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            val actorAssignments = roleAssignmentRepository.observeForPerson(actorPersonId).first()
            if (PermissionChecker.fullCrudAssignment(actorAssignments) == null) {
                _formState.update { it.copy(isSaving = false, errorMessage = PermissionChecker.NO_ACCESS_MESSAGE) }
                return@launch
            }
            try {
                repository.save(assignment.copy(createdByPersonId = actorPersonId))
                _formState.update { it.copy(isSaving = false, saved = true) }
            } catch (e: Exception) {
                _formState.update { it.copy(isSaving = false, errorMessage = e.message ?: "Could not save the assignment.") }
            }
        }
    }

    fun delete(assignmentId: String, actorPersonId: String, onNoAccess: () -> Unit) {
        viewModelScope.launch {
            val actorAssignments = roleAssignmentRepository.observeForPerson(actorPersonId).first()
            if (PermissionChecker.fullCrudAssignment(actorAssignments) == null) {
                onNoAccess()
                return@launch
            }
            // Deleting moves it to Deleted Records (kept whole, restorable); it only disappears for good from there.
            val assignment = repository.observeAll().first().firstOrNull { it.id == assignmentId }
            if (assignment != null) {
                recycleBinRepository.moveToTrash(
                    recordType = "Per Publisher Territory Assignment",
                    module = "Territory Assignment",
                    label = "${assignment.publisherName} — ${assignment.barangayName}, ${assignment.muncityName}",
                    congregationId = assignment.congregationId,
                    originalCreatedAt = assignment.createdAt,
                    originalModifiedAt = assignment.updatedAt,
                    deletedByPersonId = actorPersonId,
                    items = listOf(recycleBinRepository.item("publisherTerritoryAssignments", assignment.id, assignment)),
                )
            }
            repository.delete(assignmentId)
        }
    }
}

/** Territory Assignment landing page — the two modules ("FS Group Assignment",
 * "Per Publisher Assignment") the module now offers before opening either. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerritoryAssignmentHomeScreen(
    onBack: () -> Unit,
    onOpenFsGroup: () -> Unit,
    onOpenPerPublisher: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Territory Assignment") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ModuleCard("FS Group Assignment", "Assign territory (municipalities and barangays) to a Field Service Group.", onOpenFsGroup)
            ModuleCard("Per Publisher Assignment", "Assign a barangay, and optionally Return Visits, to one specific Publisher.", onOpenPerPublisher)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModuleCard(title: String, description: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Per Publisher Assignment — list of existing assignments (scoped to
 * [fixedCongregationId] unless Super-Admin, who sees every congregation). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublisherTerritoryAssignmentsScreen(
    fixedCongregationId: String?,
    currentPersonId: String,
    onBack: () -> Unit,
    onAddNew: () -> Unit,
    viewModel: PublisherTerritoryAssignmentViewModel = hiltViewModel(),
) {
    val all by viewModel.assignments.collectAsStateWithLifecycle(initialValue = emptyList())
    val congregations by viewModel.congregations.collectAsStateWithLifecycle(initialValue = emptyList())
    val rows = all.filter { fixedCongregationId == null || it.congregationId == fixedCongregationId }
    var pendingDelete by remember { mutableStateOf<PublisherTerritoryAssignment?>(null) }
    val showToast = rememberActionToast()
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Per Publisher Assignment") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    // "Print / PDF" of the list as shown (system print dialog, Save as PDF).
                    IconButton(onClick = {
                        ReportPrinter.print(
                            context,
                            ReportTable(
                                title = "Per Publisher Territory Assignments",
                                count = rows.size,
                                countLabel = "Total Assignments",
                                columns = listOf("Publisher", "Barangay", "Municipality", "Province", "Return Visits"),
                                rows = rows.map { listOf(it.publisherName, it.barangayName, it.muncityName, it.provinceName, it.returnVisitNames.joinToString(", ")) },
                                totals = listOf("Assignments" to rows.size.toString()),
                            ),
                        )
                    }) { Icon(Icons.Rounded.Print, contentDescription = "Print or save as PDF") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddNew,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Assign Territory") },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Column(modifier = Modifier.padding(padding).padding(16.dp)) {
                RecordFound(0)
                Text(
                    "No per-publisher territory assignments yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { RecordFound(rows.size) }
                items(rows, key = { it.id }) { row ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(row.publisherName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${row.barangayName}, ${row.muncityName}, ${row.provinceName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (fixedCongregationId == null) {
                                    Text(
                                        congregations.firstOrNull { it.id == row.congregationId }?.name ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (row.returnVisitNames.isNotEmpty()) {
                                    Text(
                                        "Return visits: ${row.returnVisitNames.joinToString(", ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = { pendingDelete = row }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete assignment")
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove assignment?") },
            text = { Text("${target.publisherName} will no longer be assigned to ${target.barangayName}, ${target.muncityName}.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id, currentPersonId) { showToast(PermissionChecker.NO_ACCESS_MESSAGE) }
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

/** Per Publisher Assignment form — Congregation (Super-Admin only), Publisher,
 * Province → Municipality → Barangay (PSGC dropdowns), optional Return
 * Visits drawn from that Publisher's own enrolled records. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublisherTerritoryAssignmentFormScreen(
    fixedCongregationId: String?,
    currentPersonId: String,
    onDone: () -> Unit,
    viewModel: PublisherTerritoryAssignmentViewModel = hiltViewModel(),
) {
    val congregations by viewModel.congregations.collectAsStateWithLifecycle(initialValue = emptyList())
    var pickedCongregationId by remember { mutableStateOf<String?>(null) }
    val congregationId = fixedCongregationId ?: pickedCongregationId

    val publishers by (congregationId?.let { viewModel.publishersFor(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var publisherId by remember { mutableStateOf<String?>(null) }

    var province by remember { mutableStateOf<PsgcOption?>(null) }
    var municipalities by remember { mutableStateOf<List<PsgcOption>>(emptyList()) }
    var municipality by remember { mutableStateOf<PsgcOption?>(null) }
    var barangays by remember { mutableStateOf<List<PsgcOption>>(emptyList()) }
    var barangay by remember { mutableStateOf<PsgcOption?>(null) }
    var selectedReturnVisitIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val returnVisits by (publisherId?.let { viewModel.returnVisitsFor(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(province?.id) {
        municipality = null
        barangay = null
        barangays = emptyList()
        municipalities = province?.let { runCatching { viewModel.searchMunicipalities(it.id) }.getOrDefault(emptyList()) } ?: emptyList()
    }
    LaunchedEffect(municipality?.id) {
        barangay = null
        barangays = municipality?.let { runCatching { viewModel.searchBarangays(it.id) }.getOrDefault(emptyList()) } ?: emptyList()
    }

    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val showToast = rememberActionToast()
    var submitTapped by remember { mutableStateOf(false) }

    LaunchedEffect(formState.saved) {
        if (formState.saved) {
            showToast("Territory assigned to publisher.")
            viewModel.consumeFormState()
            onDone()
        }
    }

    val missing = requiredFieldsMessage(
        "Congregation" to (congregationId != null),
        "Publisher" to (publisherId != null),
        "Province" to (province != null),
        "Municipality" to (municipality != null),
        "Barangay" to (barangay != null),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assign Territory to Publisher") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (fixedCongregationId == null) {
                SimpleDropdown(
                    label = "Congregation",
                    selectedLabel = congregations.firstOrNull { it.id == congregationId }?.name ?: "",
                    options = congregations.map { it.id to it.name },
                    onSelected = { pickedCongregationId = it; publisherId = null; selectedReturnVisitIds = emptySet() },
                )
            }
            if (congregationId == null) {
                Text("Select a congregation first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SimpleDropdown(
                    label = "Publisher",
                    selectedLabel = publishers.firstOrNull { it.personId == publisherId }?.name ?: "",
                    options = publishers.map { it.personId to it.name },
                    onSelected = { publisherId = it; selectedReturnVisitIds = emptySet() },
                )
                if (publishers.isEmpty()) {
                    Text("This congregation has no active publishers.", style = MaterialTheme.typography.bodySmall)
                }
            }

            Text("Territory", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            PsgcSearchField(
                label = "Province",
                selected = province,
                enabled = true,
                search = { query -> viewModel.searchProvinces(query) },
                onSelected = { province = it },
            )
            SimpleDropdown(
                label = "Municipality / City",
                selectedLabel = municipality?.name ?: "",
                options = municipalities.map { it.id.toString() to it.name },
                onSelected = { id -> municipality = municipalities.firstOrNull { it.id.toString() == id } },
            )
            SimpleDropdown(
                label = "Barangay",
                selectedLabel = barangay?.name ?: "",
                options = barangays.map { it.id.toString() to it.name },
                onSelected = { id -> barangay = barangays.firstOrNull { it.id.toString() == id } },
            )

            if (publisherId != null) {
                Text("Return Visits (optional)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                if (returnVisits.isEmpty()) {
                    Text(
                        "This publisher has no enrolled return visit records.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    returnVisits.forEach { rv ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedReturnVisitIds = if (rv.id in selectedReturnVisitIds) selectedReturnVisitIds - rv.id else selectedReturnVisitIds + rv.id
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = rv.id in selectedReturnVisitIds,
                                onCheckedChange = { checked ->
                                    selectedReturnVisitIds = if (checked) selectedReturnVisitIds + rv.id else selectedReturnVisitIds - rv.id
                                },
                            )
                            Column {
                                Text(rv.name)
                                val where = listOfNotNull(rv.barangay, rv.cityMunicipality).joinToString(", ")
                                if (where.isNotBlank()) {
                                    Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            val error = formState.errorMessage ?: if (submitTapped) missing else null
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDone, enabled = !formState.isSaving, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    enabled = !formState.isSaving,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        submitTapped = true
                        val publisher = publishers.firstOrNull { it.personId == publisherId }
                        val p = province
                        val m = municipality
                        val b = barangay
                        val cid = congregationId
                        if (cid == null || publisher == null || p == null || m == null || b == null) return@Button
                        val chosen = returnVisits.filter { it.id in selectedReturnVisitIds }
                        viewModel.save(
                            PublisherTerritoryAssignment(
                                congregationId = cid,
                                publisherPersonId = publisher.personId,
                                publisherName = publisher.name,
                                provinceId = p.id, provinceName = p.name,
                                muncityId = m.id, muncityName = m.name,
                                barangayId = b.id, barangayName = b.name,
                                returnVisitIds = chosen.map { it.id },
                                returnVisitNames = chosen.map { it.name },
                            ),
                            currentPersonId,
                        )
                    },
                ) {
                    if (formState.isSaving) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).then(Modifier))
                    Text("Assign")
                }
            }
        }
    }
}
