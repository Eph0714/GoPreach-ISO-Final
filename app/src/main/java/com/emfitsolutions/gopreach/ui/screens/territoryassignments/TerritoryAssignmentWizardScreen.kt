package com.emfitsolutions.gopreach.ui.screens.territoryassignments

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.repository.MunicipalitySelection
import com.emfitsolutions.gopreach.data.repository.PsgcOption
import com.emfitsolutions.gopreach.data.repository.TerritoryAssignmentResult
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf

private const val STEP_GROUP = 0
private const val STEP_MUNICIPALITIES = 1
private const val STEP_BARANGAYS = 2
private const val STEP_CONFIRM = 3
private const val STEP_COUNT = 4

/**
 * Add/Edit Territory Assignment — a dedicated full-screen wizard rather than
 * [com.emfitsolutions.gopreach.ui.components.FormDialog] (checked that
 * component's own sizing — a fixed-height scrollable dialog — against what
 * this flow needs: a Group pick, a Province + multi-Municipality pick, a
 * Barangay checklist per municipality that can run into the hundreds with
 * per-row explanations, then a confirmation summary; four genuinely
 * sequential concerns is a wizard shape, not a compact form).
 *
 * "A single Field Service Group may cover multiple municipalities" — Add and
 * Edit are the same flow here: [groupIdArg]/[provinceIdArg] non-null means
 * this Group already has territory in that province, which is preloaded
 * (municipalities + their barangays) so the user adds to/edits it rather
 * than creating a disconnected duplicate; the exact same preload also fires
 * from a fresh "Add" session the moment the user picks a Group+Province
 * combination that already has existing territory. [congregationIdArg] is
 * passed explicitly rather than re-derived, since the dashboard already
 * knows it for the Edit route; congregationId becomes immutable once a Group
 * is picked (matching [com.emfitsolutions.gopreach.data.repository
 * .TerritoryAssignmentRepository.saveGroupTerritoryForProvince]'s own
 * single-group-per-call contract).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerritoryAssignmentWizardScreen(
    congregationIdArg: String?,
    groupIdArg: String?,
    provinceIdArg: Int?,
    fixedCongregationId: String?,
    currentPersonId: String,
    onDone: () -> Unit,
    viewModel: TerritoryAssignmentWizardViewModel = hiltViewModel(),
) {
    val isEditing = groupIdArg != null && provinceIdArg != null
    var step by rememberSaveable { mutableStateOf(STEP_GROUP) }
    // Reset the instant the step changes — advancing to a new step should
    // never carry over "you tried to skip this" styling from the step before.
    var nextTappedWhileInvalid by remember(step) { mutableStateOf(false) }
    var isLoadingExisting by remember { mutableStateOf(isEditing) }

    // Super-Admin only (fixedCongregationId == null) and only for a brand-new
    // session — editing always keeps the loaded territory's own
    // congregationId, never offers a picker (congregationId is immutable
    // once a Group is picked).
    var pickedCongregationId by rememberSaveable { mutableStateOf(fixedCongregationId ?: congregationIdArg) }
    val congregationId = fixedCongregationId ?: pickedCongregationId
    val congregations by viewModel.congregations.collectAsStateWithLifecycle(initialValue = emptyList())

    var selectedGroupId by rememberSaveable { mutableStateOf(groupIdArg) }
    var provinceOption by remember { mutableStateOf<PsgcOption?>(null) }
    var selectedMunicipalities by remember { mutableStateOf<List<PsgcOption>>(emptyList()) }
    var barangaysByMuncity by remember { mutableStateOf<Map<Int, List<PsgcOption>>>(emptyMap()) }

    val groups by (congregationId?.let { viewModel.groupsFor(it) } ?: flowOf(emptyList()))
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedGroup = groups.firstOrNull { it.id == selectedGroupId }

    // Edit pre-fill — loads once, before the user can interact with any step.
    LaunchedEffect(Unit) {
        if (groupIdArg != null && provinceIdArg != null && congregationId != null) {
            val existing = viewModel.existingMunicipalitiesFor(congregationId, groupIdArg, provinceIdArg)
            if (existing.isNotEmpty()) {
                val first = existing.first()
                provinceOption = PsgcOption(first.provinceId, first.provinceName)
                selectedMunicipalities = existing.map { PsgcOption(it.muncityId, it.muncityName) }
                barangaysByMuncity = existing.associate { it.muncityId to it.barangays }
            }
        }
        isLoadingExisting = false
    }

    // "Display its current territory assignments to prevent accidental
    // duplication" — the same preload, triggered again whenever the Group or
    // Province changes mid-session (covers both a fresh "Add" session where
    // the user picks a Group+Province that already has territory, and
    // switching Province while already on this screen — see this screen's
    // own file-level doc comment). Skipped while the initial edit pre-fill
    // above is still running so the two don't race each other.
    LaunchedEffect(selectedGroupId, provinceOption?.id) {
        if (isLoadingExisting) return@LaunchedEffect
        val cid = congregationId
        val gid = selectedGroupId
        val pid = provinceOption?.id
        if (cid == null || gid == null || pid == null) return@LaunchedEffect
        val existing = viewModel.existingMunicipalitiesFor(cid, gid, pid)
        selectedMunicipalities = existing.map { PsgcOption(it.muncityId, it.muncityName) }
        barangaysByMuncity = existing.associate { it.muncityId to it.barangays }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val showToast = rememberActionToast()
    var hasConfirmed by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.saveResult) {
        when (val result = uiState.saveResult) {
            is TerritoryAssignmentResult.Success -> {
                showToast(if (isEditing) "Territory assignment saved." else "Territory assignment added.")
                viewModel.consumeSaveResult()
                onDone()
            }
            is TerritoryAssignmentResult.Conflict, is TerritoryAssignmentResult.Offline, is TerritoryAssignmentResult.Error -> {
                hasConfirmed = false
            }
            null -> Unit
        }
    }

    fun submit() {
        if (hasConfirmed) return
        hasConfirmed = true
        val cid = congregationId ?: return
        val group = selectedGroup ?: return
        val province = provinceOption ?: return
        val selections = selectedMunicipalities.map { m ->
            MunicipalitySelection(
                provinceId = province.id,
                provinceName = province.name,
                muncityId = m.id,
                muncityName = m.name,
                barangays = barangaysByMuncity[m.id] ?: emptyList(),
            )
        }
        viewModel.save(cid, group.id, group.name, province.id, province.name, selections, currentPersonId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Territory Assignment" else "Add Territory Assignment") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (isLoadingExisting) {
            Column(modifier = Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(modifier = Modifier.padding(32.dp))
            }
            return@Scaffold
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LinearProgressIndicator(
                progress = { (step + 1f) / STEP_COUNT },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Step ${step + 1} of $STEP_COUNT",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )

            // Bug fix — "app closes after clicking Next after selecting
            // municipality": a scrollable-in-scrollable (a step's own
            // LazyColumn sitting inside this shared container's
            // Modifier.verticalScroll()) is explicitly disallowed by Compose
            // and crashes the instant that step renders (see
            // CheckScrollableContainerConstraints). Steps 1/4 are short forms
            // that still want to scroll as a plain Column; Municipalities/
            // Barangays get the bounded, unscrolled container their own
            // LazyColumn needs (those steps scroll themselves).
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                when (step) {
                    STEP_GROUP -> Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        GroupStep(
                            congregationId = congregationId,
                            fixedCongregationId = fixedCongregationId,
                            isEditing = isEditing,
                            congregations = congregations,
                            onCongregationSelected = { pickedCongregationId = it; selectedGroupId = null },
                            groups = groups,
                            selectedGroupId = selectedGroupId,
                            onGroupSelected = { selectedGroupId = it },
                        )
                    }
                    STEP_MUNICIPALITIES -> MunicipalitiesStep(
                        viewModel = viewModel,
                        province = provinceOption,
                        onProvinceSelected = { provinceOption = it; selectedMunicipalities = emptyList(); barangaysByMuncity = emptyMap() },
                        selectedMunicipalities = selectedMunicipalities,
                        onSelectedMunicipalitiesChange = { selectedMunicipalities = it },
                    )
                    STEP_BARANGAYS -> BarangaysStep(
                        viewModel = viewModel,
                        congregationId = congregationId,
                        excludeGroupId = selectedGroupId,
                        municipalities = selectedMunicipalities,
                        barangaysByMuncity = barangaysByMuncity,
                        onBarangaysChange = { muncityId, barangays -> barangaysByMuncity = barangaysByMuncity + (muncityId to barangays) },
                    )
                    STEP_CONFIRM -> Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        ConfirmStep(
                            group = selectedGroup,
                            province = provinceOption,
                            municipalities = selectedMunicipalities,
                            barangaysByMuncity = barangaysByMuncity,
                            saveResult = uiState.saveResult,
                        )
                    }
                }
            }

            val totalSelectedBarangays = selectedMunicipalities.sumOf { (barangaysByMuncity[it.id] ?: emptyList()).size }
            val nextEnabled = when (step) {
                STEP_GROUP -> congregationId != null && selectedGroupId != null
                STEP_MUNICIPALITIES -> provinceOption != null && selectedMunicipalities.isNotEmpty()
                STEP_BARANGAYS -> totalSelectedBarangays > 0
                else -> true
            }
            val stepErrorMessage = when (step) {
                STEP_GROUP -> requiredFieldsMessage(
                    "Congregation" to (congregationId != null),
                    "Field Service Group" to (selectedGroupId != null),
                )
                STEP_MUNICIPALITIES -> requiredFieldsMessage(
                    "Province" to (provinceOption != null),
                    "At least one municipality" to selectedMunicipalities.isNotEmpty(),
                )
                STEP_BARANGAYS -> requiredFieldsMessage("At least one barangay" to (totalSelectedBarangays > 0))
                else -> null
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (step > STEP_GROUP) {
                    TextButton(onClick = { step-- }, enabled = !uiState.isSaving) { Text("Back") }
                } else {
                    TextButton(onClick = onDone, enabled = !uiState.isSaving) { Text("Cancel") }
                }
                if (step < STEP_CONFIRM) {
                    TextButton(onClick = { if (nextEnabled) step++ else nextTappedWhileInvalid = true }) { Text("Next") }
                } else {
                    TextButton(onClick = ::submit, enabled = !hasConfirmed && !uiState.isSaving) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        }
                        Text(if (isEditing) "Save Changes" else "Create Assignment")
                    }
                }
            }
            if (step < STEP_CONFIRM && stepErrorMessage != null && !nextEnabled && nextTappedWhileInvalid) {
                // Only surfaced once Next has actually been tapped while
                // incomplete (nextTappedWhileInvalid) — not live on every
                // recomposition, which would show a scary red error the
                // instant this step is even opened, before the user has had
                // a chance to fill anything in.
                Text(
                    stepErrorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupStep(
    congregationId: String?,
    fixedCongregationId: String?,
    isEditing: Boolean,
    congregations: List<Congregation>,
    onCongregationSelected: (String) -> Unit,
    groups: List<Group>,
    selectedGroupId: String?,
    onGroupSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Field Service Group", style = MaterialTheme.typography.titleMedium)
        if (fixedCongregationId == null && !isEditing) {
            SimpleDropdown(
                label = "Congregation",
                selectedLabel = congregations.firstOrNull { it.id == congregationId }?.name ?: "",
                options = congregations.map { it.id to it.name },
                onSelected = onCongregationSelected,
            )
        }
        if (congregationId == null) {
            Text("Select a congregation first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (groups.isEmpty()) {
            Text("This congregation has no active Field Service Groups yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            SimpleDropdown(
                label = "Field Service Group",
                selectedLabel = groups.firstOrNull { it.id == selectedGroupId }?.name ?: "",
                options = groups.map { it.id to it.name },
                onSelected = onGroupSelected,
            )
        }
    }
}

/** Step 2 — single Province, then a multi-select Municipality checklist
 * scoped to it ("A single Field Service Group may cover multiple
 * municipalities ... including barangays from different municipalities
 * within the same province"). Changing Province clears the municipality (and
 * therefore barangay) selection — a save session is scoped to one province,
 * see this screen's own file-level doc comment. */
@Composable
private fun MunicipalitiesStep(
    viewModel: TerritoryAssignmentWizardViewModel,
    province: PsgcOption?,
    onProvinceSelected: (PsgcOption) -> Unit,
    selectedMunicipalities: List<PsgcOption>,
    onSelectedMunicipalitiesChange: (List<PsgcOption>) -> Unit,
) {
    var allMuncities by remember(province?.id) { mutableStateOf<List<PsgcOption>>(emptyList()) }
    var isLoading by remember(province?.id) { mutableStateOf(false) }
    var search by remember(province?.id) { mutableStateOf("") }

    LaunchedEffect(province?.id) {
        val p = province ?: return@LaunchedEffect
        isLoading = true
        allMuncities = viewModel.searchMunicipalities(p.id, "")
        isLoading = false
    }

    val selectedIds = selectedMunicipalities.map { it.id }.toSet()
    val visible = allMuncities.filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }

    // fillMaxSize, not wrap-content — this sits directly in the caller's
    // bounded (non-scrolling) container; the LazyColumn below needs a real
    // bounded max height via weight(1f), same reasoning the Barangays step
    // already documents on this screen's own file-level doc comment.
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Province & Municipalities", style = MaterialTheme.typography.titleMedium)
        PsgcSearchField(
            label = "Province",
            selected = province,
            enabled = true,
            search = { query -> viewModel.searchProvinces(query) },
            onSelected = onProvinceSelected,
        )
        if (province == null) {
            Text(
                "Select a province to browse its municipalities.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search municipality / city") },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    onSelectedMunicipalitiesChange((selectedMunicipalities + visible).distinctBy { it.id })
                }) { Text("Select All") }
                OutlinedButton(onClick = {
                    val visibleIds = visible.map { it.id }.toSet()
                    onSelectedMunicipalitiesChange(selectedMunicipalities.filterNot { it.id in visibleIds })
                }) { Text("Clear Selection") }
            }
            Text(
                "${selectedMunicipalities.size} municipalit${if (selectedMunicipalities.size == 1) "y" else "ies"} selected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            } else if (visible.isEmpty()) {
                Text("No municipalities match this search.", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(visible, key = { it.id }) { muncity ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = muncity.id in selectedIds,
                                onCheckedChange = { checked ->
                                    onSelectedMunicipalitiesChange(
                                        if (checked) (selectedMunicipalities + muncity).distinctBy { it.id }
                                        else selectedMunicipalities.filterNot { it.id == muncity.id },
                                    )
                                },
                            )
                            Text(muncity.name, modifier = Modifier.padding(top = 14.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Step 3 — one expandable section per selected municipality, each with its
 * own search + Select All/Clear + checklist, all inside **one** LazyColumn
 * (a section per municipality via repeated `item`/`items` calls) rather than
 * nested LazyColumns — same scrollable-in-scrollable constraint this
 * screen's own file-level doc comment already explains for why Steps 2/3
 * don't share the other steps' Modifier.verticalScroll(Column). */
@Composable
private fun BarangaysStep(
    viewModel: TerritoryAssignmentWizardViewModel,
    congregationId: String?,
    excludeGroupId: String?,
    municipalities: List<PsgcOption>,
    barangaysByMuncity: Map<Int, List<PsgcOption>>,
    onBarangaysChange: (muncityId: Int, barangays: List<PsgcOption>) -> Unit,
) {
    var allBarangaysByMuncity by remember(municipalities) { mutableStateOf<Map<Int, List<PsgcOption>>>(emptyMap()) }
    var taken by remember(municipalities) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var isLoading by remember(municipalities) { mutableStateOf(true) }
    var searchByMuncity by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }

    LaunchedEffect(municipalities, congregationId) {
        if (municipalities.isEmpty()) {
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        allBarangaysByMuncity = municipalities.associate { it.id to viewModel.searchBarangays(it.id, "") }
        taken = congregationId?.let { viewModel.takenBarangays(it, excludeGroupId) } ?: emptyMap()
        isLoading = false
    }

    if (municipalities.isEmpty()) {
        Text(
            "Select at least one municipality first.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        return
    }
    if (isLoading) {
        CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            municipalities.forEach { muncity ->
            val allBarangays = allBarangaysByMuncity[muncity.id] ?: emptyList()
            val selected = barangaysByMuncity[muncity.id] ?: emptyList()
            val selectedIds = selected.map { it.id }.toSet()
            val search = searchByMuncity[muncity.id] ?: ""
            val visible = allBarangays.filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }
            val selectableVisible = visible.filter { it.id !in taken }

            item(key = "header_${muncity.id}") {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Text(muncity.name.uppercase(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = search,
                        onValueChange = { searchByMuncity = searchByMuncity + (muncity.id to it) },
                        label = { Text("Search barangay in ${muncity.name}") },
                        singleLine = true,
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        OutlinedButton(onClick = {
                            onBarangaysChange(muncity.id, (selected + selectableVisible).distinctBy { it.id })
                        }) { Text("Select All") }
                        OutlinedButton(onClick = {
                            val visibleIds = visible.map { it.id }.toSet()
                            onBarangaysChange(muncity.id, selected.filterNot { it.id in visibleIds })
                        }) { Text("Clear Selection") }
                    }
                    Text(
                        "${selected.size} barangay${if (selected.size == 1) "" else "s"} selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (visible.isEmpty()) {
                        Text("No barangays match this search.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(visible, key = { "b_${muncity.id}_${it.id}" }) { barangay ->
                val takenBy = taken[barangay.id]
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = barangay.id in selectedIds,
                            enabled = takenBy == null,
                            onCheckedChange = { checked ->
                                onBarangaysChange(
                                    muncity.id,
                                    if (checked) (selected + barangay).distinctBy { it.id } else selected.filterNot { it.id == barangay.id },
                                )
                            },
                        )
                        Text(
                            barangay.name,
                            modifier = Modifier.padding(top = 14.dp),
                            color = if (takenBy != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (takenBy != null) {
                        Text(
                            "Already assigned to $takenBy",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 48.dp),
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun ConfirmStep(
    group: Group?,
    province: PsgcOption?,
    municipalities: List<PsgcOption>,
    barangaysByMuncity: Map<Int, List<PsgcOption>>,
    saveResult: TerritoryAssignmentResult?,
) {
    val totalBarangays = municipalities.sumOf { (barangaysByMuncity[it.id] ?: emptyList()).size }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Confirm", style = MaterialTheme.typography.titleMedium)
        Text("Field Service Group: ${group?.name ?: "—"}", style = MaterialTheme.typography.bodyMedium)
        Text("Province: ${province?.name ?: "—"}", style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        municipalities.forEach { muncity ->
            val barangays = (barangaysByMuncity[muncity.id] ?: emptyList()).sortedBy { it.name }
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                Text(muncity.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${barangays.size} barangay${if (barangays.size == 1) "" else "s"}: ${barangays.joinToString(", ") { it.name }}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(
            "${municipalities.size} municipalit${if (municipalities.size == 1) "y" else "ies"} · " +
                "$totalBarangays barangay${if (totalBarangays == 1) "" else "s"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        when (saveResult) {
            is TerritoryAssignmentResult.Conflict ->
                Text(
                    "${saveResult.barangayName} is already assigned to ${saveResult.takenByGroupName} in this congregation.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            is TerritoryAssignmentResult.Offline ->
                Text(saveResult.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            is TerritoryAssignmentResult.Error ->
                Text(saveResult.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else -> Unit
        }
    }
}

/** Minimal search-as-you-type PSGC field — debounced local search over
 * [viewModel]'s suspend lookups, no persistent dropdown state machine (this
 * module's own, simpler counterpart to [com.emfitsolutions.gopreach.ui
 * .components.PhilippineAddressPicker]'s private SearchableDropdown, which
 * isn't reusable here since it only ever surfaces plain names — see this
 * screen's own file-level doc comment). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PsgcSearchField(
    label: String,
    selected: PsgcOption?,
    enabled: Boolean,
    search: suspend (String) -> List<PsgcOption>,
    onSelected: (PsgcOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember(selected?.id) { mutableStateOf(selected?.name ?: "") }
    var options by remember { mutableStateOf<List<PsgcOption>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(query, expanded, enabled) {
        if (!expanded || !enabled) return@LaunchedEffect
        isLoading = true
        delay(250)
        options = runCatching { search(query) }.getOrDefault(emptyList())
        isLoading = false
    }

    val showMenu = expanded && enabled
    ExposedDropdownMenuBox(expanded = showMenu, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; expanded = true },
            label = { Text(label) },
            enabled = enabled,
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showMenu) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = showMenu, onDismissRequest = { expanded = false }) {
            when {
                isLoading -> DropdownMenuItem(text = { Text("Searching…") }, onClick = {})
                options.isEmpty() -> DropdownMenuItem(text = { Text("No matches") }, onClick = {})
                else -> options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name) },
                        onClick = {
                            query = option.name
                            expanded = false
                            onSelected(option)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SimpleDropdown(
    label: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelected(id); expanded = false })
            }
        }
    }
}
