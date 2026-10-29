package com.emfitsolutions.gopreach.ui.screens.congregations

import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.ui.components.RecordViewToggle
import com.emfitsolutions.gopreach.ui.components.UniversalColumn
import com.emfitsolutions.gopreach.ui.components.recordItems
import com.emfitsolutions.gopreach.ui.components.rememberRecordViewMode
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RestoreFromTrash
import com.emfitsolutions.gopreach.ui.components.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.emfitsolutions.gopreach.ui.components.Scaffold
import com.emfitsolutions.gopreach.ui.components.HideInFullScreen
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.repository.OverseerOption
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import com.emfitsolutions.gopreach.ui.components.PhilippineAddressPicker
import com.emfitsolutions.gopreach.ui.components.DeleteChoiceDialog
import com.emfitsolutions.gopreach.ui.components.EditSectionHeader
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.LanguagesTagInput
import com.emfitsolutions.gopreach.ui.components.ReadOnlyField
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage
import kotlinx.coroutines.launch

/** Spec §3/§5.1 — Manage Congregation Master File, Super-Admin only.
 * [canPermanentlyDelete] is Super-Admin-only across every record type in
 * this app's "Admin Record Deletion" pass (see BUILD_PLAN.md).
 * [readOnly] — a restricted user (e.g. Circuit Overseer/custom) whose
 * [com.emfitsolutions.gopreach.data.model.UserAccessGrant] has
 * `VIEW_CONGREGATIONS` but not `ADD_CONGREGATIONS`/`EDIT_CONGREGATIONS`/
 * `DELETE_CONGREGATIONS` still needs somewhere to actually see this data —
 * this hides Add/Edit/Delete/Reactivate without duplicating the whole
 * screen (backlog: "read-only variants of the management screens"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageCongregationsScreen(
    currentPersonId: String,
    canPermanentlyDelete: Boolean,
    readOnly: Boolean = false,
    onBack: () -> Unit,
    onAddNew: () -> Unit,
    viewModel: ManageCongregationsViewModel = koinViewModel(),
) {
    val allCongregations by viewModel.congregations.collectAsStateWithLifecycle()
    var showInactive by remember { mutableStateOf(false) }
    val congregations = allCongregations.filter { showInactive || it.status == RecordStatus.ACTIVE }
    var pendingDelete by remember { mutableStateOf<Congregation?>(null) }
    var pendingEdit by remember { mutableStateOf<Congregation?>(null) }
    var editError by remember { mutableStateOf<String?>(null) }
    val circuitLinks by viewModel.circuitLinks.collectAsStateWithLifecycle()
    val overseers by viewModel.overseers.collectAsStateWithLifecycle()
    val overseerNames by viewModel.overseerNames.collectAsStateWithLifecycle()
    val circuitMandatory by viewModel.circuitAssignmentMandatory.collectAsStateWithLifecycle()
    var permanentDeleteImpactSummary by remember { mutableStateOf<String?>(null) }
    var permanentDeleteChecked by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val showToast = rememberActionToast()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Congregations") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!readOnly) {
                androidx.compose.material3.ExtendedFloatingActionButton(onClick = onAddNew, icon = { Icon(Icons.Rounded.Add, contentDescription = null) }, text = { Text("New Congregation") })
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            HideInFullScreen(true) {
            androidx.compose.material3.FilterChip(selected = showInactive, onClick = { showInactive = !showInactive }, label = { Text("Include inactive") }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (congregations.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RecordFound(0)
                    Text("No congregations yet. Tap + to add one.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                val viewMode = rememberRecordViewMode("congregations")
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            "${congregations.size} " + if (congregations.size == 1) "congregation" else "congregations",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item { RecordViewToggle(viewMode) }
                    recordItems(
                        viewMode, congregations, { it.id },
                        listOf(
                                UniversalColumn<com.emfitsolutions.gopreach.data.model.Congregation>("Congregation", 200.dp) { it.name },
                                UniversalColumn("Code", 90.dp) { it.code },
                                UniversalColumn("Address", 240.dp) { it.address },
                                UniversalColumn("Circuit Overseer", 170.dp) { circuitLinks[it.id]?.let { l -> overseerNames[l.circuitOverseerPersonId] } ?: "Not assigned" },
                                UniversalColumn("Enrolled by", 150.dp) { it.enrolledByPersonId?.let { id -> overseerNames[id] ?: "Circuit Overseer" } ?: "-" },
                                UniversalColumn("Status", 90.dp) { if (it.status == RecordStatus.ACTIVE) "Active" else "Inactive" },
                        ),
                        onRowClick = { pendingEdit = it },
                    ) { congregation ->
                        val link = circuitLinks[congregation.id]
                        com.emfitsolutions.gopreach.ui.screens.elders.PersonRecordCard(
                            name = congregation.name,
                            detail = listOf(congregation.address, "Code: ${congregation.code}").filter { it.isNotBlank() }.joinToString(" · "),
                            active = congregation.status == RecordStatus.ACTIVE,
                            readOnly = readOnly,
                            hasTemporarySignIn = false,
                            chips = listOfNotNull(
                                link?.let { "Circuit Overseer: ${overseerNames[it.circuitOverseerPersonId] ?: "-"}" },
                                link?.let { "Circuit Code: ${it.circuitCode}" },
                                congregation.enrolledByPersonId?.let { "Enrolled by Circuit Overseer: ${overseerNames[it] ?: "-"}" },
                            ) + congregation.languages,
                            note = if (link == null) "Circuit Overseer: not assigned" else null,
                            onEdit = { pendingEdit = congregation },
                            onDelete = { pendingDelete = congregation },
                            onRestore = { viewModel.setStatus(congregation, RecordStatus.ACTIVE, currentPersonId); showToast("\"${congregation.name}\" reactivated.") },
                            onShowSignIn = {},
                        )
                    }
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        LaunchedEffect(toDelete.id) {
            permanentDeleteImpactSummary = viewModel.permanentDeleteImpactSummary(toDelete.id)
            permanentDeleteChecked = true
        }
        if (permanentDeleteChecked) {
            DeleteChoiceDialog(
                recordLabel = toDelete.name,
                canPermanentlyDelete = canPermanentlyDelete,
                permanentDeleteImpactSummary = permanentDeleteImpactSummary,
                onDismiss = { pendingDelete = null; permanentDeleteChecked = false },
                onMoveToInactive = { viewModel.setStatus(toDelete, RecordStatus.INACTIVE, currentPersonId) },
                onDeletePermanently = { viewModel.permanentlyDelete(toDelete.id, currentPersonId) },
            )
        }
    }

    val toEdit = pendingEdit
    if (toEdit != null) {
        val link = circuitLinks[toEdit.id]
        EditCongregationDialog(
            congregation = toEdit,
            overseers = overseers,
            currentOwnerId = link?.circuitOverseerPersonId,
            currentOwnerName = link?.circuitOverseerPersonId?.let { overseerNames[it] },
            currentCode = link?.circuitCode,
            circuitMandatory = circuitMandatory,
            externalError = editError,
            onSave = { updated, overseerId ->
                editError = null
                viewModel.update(updated, overseerId, currentPersonId) { problem ->
                    if (problem == null) {
                        showToast("\"${updated.name}\" saved.")
                        pendingEdit = null
                    } else {
                        editError = problem
                    }
                }
            },
            onDismiss = { pendingEdit = null; editError = null },
        )
    }
}

/** Congregation's editable surface (Name/Address/Code) is already the whole
 * record's editable content — this adds the previously-missing read-only
 * System Information (record ID, status, date added) so the full stored
 * record is visible, not just what happens to be editable. */
@Composable
private fun EditCongregationDialog(
    congregation: Congregation,
    overseers: List<OverseerOption>,
    currentOwnerId: String?,
    currentOwnerName: String?,
    currentCode: String?,
    circuitMandatory: Boolean,
    externalError: String?,
    onSave: (Congregation, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var pickedOverseerId by remember { mutableStateOf(currentOwnerId) }
    var name by remember { mutableStateOf(congregation.name) }
    var province by remember { mutableStateOf(congregation.province) }
    var cityMunicipality by remember { mutableStateOf(congregation.cityMunicipality) }
    var barangay by remember { mutableStateOf(congregation.barangay) }
    var code by remember { mutableStateOf(congregation.code) }
    var languages by remember { mutableStateOf(congregation.languages) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val message = requiredFieldsMessage(
            "Congregation Name" to name.isNotBlank(),
            "Province" to !province.isNullOrBlank(),
            "Municipality / City" to !cityMunicipality.isNullOrBlank(),
            "Barangay" to !barangay.isNullOrBlank(),
            "Congregation Code" to code.isNotBlank(),
        )
        if (message != null) {
            errorMessage = message
            return
        }
        onSave(
            congregation.copy(
                name = name.trim(),
                // Derived, human-readable fallback — see Congregation
                // .address's own doc comment.
                address = listOfNotNull(barangay, cityMunicipality, province).joinToString(", "),
                province = province,
                cityMunicipality = cityMunicipality,
                barangay = barangay,
                code = code.trim(),
                languages = languages,
            ),
            pickedOverseerId,
        )
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = "Edit Congregation",
        onConfirm = ::submit,
        confirmLabel = "Save Changes",
        errorMessage = errorMessage ?: externalError,
        maxContentHeight = 480.dp,
        hasUnsavedChanges = pickedOverseerId != currentOwnerId || name != congregation.name || province != congregation.province ||
            cityMunicipality != congregation.cityMunicipality || barangay != congregation.barangay ||
            code != congregation.code || languages != congregation.languages,
    ) {
                EditSectionHeader("Congregation Information")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.uppercase() },
                    label = { Text("Congregation Name") },
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
                // "The Address must be replaced with (Province/City,
                // Municipality, Barangay)" — no free-text address field
                // here anymore.
                PhilippineAddressPicker(
                    province = province,
                    cityMunicipality = cityMunicipality,
                    barangay = barangay,
                    onChanged = { p, c, b -> province = p; cityMunicipality = c; barangay = b },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase() },
                    label = { Text("Congregation Code") },
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
                LanguagesTagInput(
                    languages = languages,
                    onAdd = { languages = languages + it },
                    onRemove = { languages = languages - it },
                    modifier = Modifier.fillMaxWidth(),
                )

                EditSectionHeader("Circuit")
                // Editable at any time: pick a different Circuit Overseer (the congregation moves from the old overseer's
                // list to the new one's in a single server step) or, while not yet mandatory, clear it.
                val options = buildList {
                    if (!circuitMandatory) add("" to "— None —")
                    // Keep the current overseer selectable even if they are no longer assignable (inactive account / code).
                    if (currentOwnerId != null && overseers.none { it.personId == currentOwnerId }) add(currentOwnerId to (currentOwnerName ?: "Current overseer"))
                    addAll(overseers.map { it.personId to it.name })
                }
                val picked = overseers.firstOrNull { it.personId == pickedOverseerId }
                SimpleDropdown(
                    label = if (circuitMandatory) "Circuit Overseer Assigned *" else "Circuit Overseer Assigned",
                    selectedLabel = picked?.name ?: if (pickedOverseerId == currentOwnerId) currentOwnerName.orEmpty() else if (pickedOverseerId == null) "— None —" else "",
                    options = options,
                    onSelected = { pickedOverseerId = it.ifEmpty { null } },
                )
                ReadOnlyField(
                    "Circuit Code",
                    picked?.circuitCode ?: if (pickedOverseerId != null && pickedOverseerId == currentOwnerId) currentCode ?: "—" else "Select a Circuit Overseer",
                )
                if (pickedOverseerId != currentOwnerId && currentOwnerId != null) {
                    Text(
                        "On save, this congregation moves from ${currentOwnerName ?: "its current Circuit Overseer"} to ${picked?.name ?: "no one"}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                EditSectionHeader("System Information")
                ReadOnlyField("Record ID", congregation.id)
                ReadOnlyField("Status", congregation.status.name)
                ReadOnlyField("Date Added", formatRecordTimestamp(congregation.createdAt))
    }
}
