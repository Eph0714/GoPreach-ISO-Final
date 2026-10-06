package com.emfitsolutions.gopreach.ui.screens.elders

import androidx.compose.foundation.layout.Row
import com.emfitsolutions.gopreach.ui.components.NameFieldsInOrder
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RegularElderRole
import com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown
import com.emfitsolutions.gopreach.ui.components.rememberCongregationContext
import com.emfitsolutions.gopreach.ui.components.EditSectionHeader
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.ReadOnlyField
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage
import kotlinx.coroutines.flow.first

@Composable
fun ManageMinisterialServantsScreen(
    fixedCongregationId: String?,
    currentPersonId: String,
    canPermanentlyDelete: Boolean,
    readOnly: Boolean = false,
    onBack: () -> Unit,
    onAddNew: () -> Unit,
    viewModel: ManageMinisterialServantsViewModel = koinViewModel(),
) {
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    var congregationFilter by rememberCongregationContext("ministerial_servants")
    val needsCongregation = fixedCongregationId == null && congregationFilter == null
    val effectiveCongregationId = fixedCongregationId ?: congregationFilter
    val rowsFlow = remember(effectiveCongregationId, needsCongregation) { if (needsCongregation) kotlinx.coroutines.flow.flowOf(emptyList()) else viewModel.rowsFor(effectiveCongregationId) }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    ElderListScreen(
        title = "Ministerial Servants",
        scopeLabel = "Congregation",
        rows = rows,
        canPermanentlyDelete = canPermanentlyDelete,
        readOnly = readOnly,
        onBack = onBack,
        onAddNew = onAddNew,
        onSetActive = { row, active -> viewModel.setActive(row.assignment, active, currentPersonId) },
        onEdit = { _, updated -> viewModel.updatePerson(updated) },
        onPermanentlyDelete = { row -> viewModel.permanentlyDelete(row, currentPersonId) },
        needsCongregation = needsCongregation,
        congregationFilterContent = if (fixedCongregationId == null) {
            {
                CongregationFilterDropdown(
                    congregations = congregations,
                    selectedCongregationId = congregationFilter,
                    onSelected = { congregationFilter = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else null,
        editDialogContent = { row, onDismiss ->
            // "In Ministerial Servant Module, allow the user to edit also the
            // user role, show the checkbox to edit the user roles" — a
            // dedicated editor (Congregation + Select Role, on top of
            // Personal Information), same pattern Coordinator Elder/Service
            // Overseer's own edit dialogs already use, not the generic
            // Personal-Information-only EditElderDialog this screen used to
            // fall back on.
            MinisterialServantEditDialog(
                row = row,
                fixedCongregationId = fixedCongregationId,
                congregations = congregations,
                currentPersonId = currentPersonId,
                viewModel = viewModel,
                onDismiss = onDismiss,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MinisterialServantEditDialog(
    row: ElderRow,
    fixedCongregationId: String?,
    congregations: List<Congregation>,
    currentPersonId: String,
    viewModel: ManageMinisterialServantsViewModel,
    onDismiss: () -> Unit,
) {
    var firstName by remember { mutableStateOf(row.person.firstName) }
    var lastName by remember { mutableStateOf(row.person.lastName) }
    var address by remember { mutableStateOf(row.person.address) }
    var contact by remember { mutableStateOf(row.person.contact) }
    var email by remember { mutableStateOf(row.person.email ?: "") }
    // Bug fix — same "Congregation/Group is required" even after picking one,
    // confirmed root-caused (and fixed) in ManageGroupsScreen's GroupDialog:
    // `rememberSaveable` (immune to a config change wiping a plain `remember`)
    // and re-deriving the resolved id fresh inside submit() rather than
    // trusting a val computed here — a real, confirmed-on-device Compose
    // staleness where that kind of pre-computed val can go stale inside a
    // local closure even though the live picked-id state stays correct.
    var pickedCongregationId by rememberSaveable { mutableStateOf(row.assignment.congregationId) }
    val congregationId = fixedCongregationId ?: pickedCongregationId

    // Same bug fix as Coordinator Elder/Service Overseer/Regular Elder's own
    // edit dialogs ("the checkbox for user role is not checked for the
    // current state when Editing"): `LaunchedEffect` + `.first()` suspends
    // for the flow's genuine first emission before ever assigning anything,
    // rather than locking in a synchronous placeholder before Firestore's
    // real RoleAssignment data has arrived.
    val additionalRolesFlow = remember(row.person.id) { viewModel.additionalRolesFor(row.person.id) }
    var groupRole by remember { mutableStateOf<RegularElderRole?>(null) }
    var publisherCategory by remember { mutableStateOf<PublisherCategory?>(null) }
    var initialGroupRole by remember { mutableStateOf<RegularElderRole?>(null) }
    var initialPublisherCategory by remember { mutableStateOf<PublisherCategory?>(null) }
    LaunchedEffect(additionalRolesFlow) {
        val (loadedGroupRole, loadedPublisherCategory) = additionalRolesFlow.first()
        groupRole = loadedGroupRole
        publisherCategory = loadedPublisherCategory
        initialGroupRole = loadedGroupRole
        initialPublisherCategory = loadedPublisherCategory
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val resolvedCongregationId = fixedCongregationId ?: pickedCongregationId
        val message = requiredFieldsMessage(
            "First Name" to firstName.isNotBlank(),
            "Last Name" to lastName.isNotBlank(),
            "Address" to address.isNotBlank(),
            "Contact" to contact.isNotBlank(),
            "Congregation" to (resolvedCongregationId != null),
        )
        if (message != null) {
            errorMessage = message
            return
        }
        viewModel.updateRolesAndPerson(
            row = row,
            updatedPerson = row.person.copy(
                firstName = firstName.trim(),
                lastName = lastName.trim(),
                address = address.trim(),
                contact = contact.trim(),
                email = email.trim().ifBlank { null },
            ),
            newCongregationId = resolvedCongregationId!!,
            groupRole = groupRole,
            publisherCategory = publisherCategory,
            actorPersonId = currentPersonId,
        )
        onDismiss()
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = "Edit ${row.person.fullName}",
        onConfirm = ::submit,
        confirmLabel = "Save Changes",
        errorMessage = errorMessage,
        maxContentHeight = 560.dp,
        hasUnsavedChanges = firstName != row.person.firstName || lastName != row.person.lastName ||
            address != row.person.address || contact != row.person.contact || email != (row.person.email ?: "") ||
            pickedCongregationId != row.assignment.congregationId ||
            groupRole != initialGroupRole || publisherCategory != initialPublisherCategory,
    ) {
                EditSectionHeader("Personal Information")
                NameFieldsInOrder(
                    first = {
                        OutlinedTextField(
                        value = firstName,
                        onValueChange = { firstName = it.uppercase() },
                        label = { Text("First Name") },
                        singleLine = true,
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    },
                    last = {
                        OutlinedTextField(
                        value = lastName,
                        onValueChange = { lastName = it.uppercase() },
                        label = { Text("Last Name") },
                        singleLine = true,
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    },
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.uppercase() },
                    label = { Text("Address") },
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it.uppercase() },
                    label = { Text("Contact") },
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email (optional)") },
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )

                EditSectionHeader("Assignment")
                if (fixedCongregationId == null) {
                    MinisterialServantCongregationDropdown(
                        congregations = congregations,
                        selectedId = pickedCongregationId,
                        onSelected = { pickedCongregationId = it },
                    )
                } else {
                    ReadOnlyField("Congregation", row.scopeName)
                }

                HorizontalDivider()
                Text("Select Role", style = MaterialTheme.typography.titleSmall)
                Text(
                    "In addition to Ministerial Servant — Group Servant and Group Assistant are mutually exclusive; a publisher category can apply at the same time as either.",
                    style = MaterialTheme.typography.bodySmall,
                )
                MinisterialServantRoleCheckboxRow(
                    label = "Group Servant",
                    checked = groupRole == RegularElderRole.GROUP_SERVANT,
                    enabled = groupRole == null || groupRole == RegularElderRole.GROUP_SERVANT,
                    onCheckedChange = { checked -> groupRole = if (checked) RegularElderRole.GROUP_SERVANT else null },
                )
                MinisterialServantRoleCheckboxRow(
                    label = "Group Assistant",
                    checked = groupRole == RegularElderRole.GROUP_ASSISTANT,
                    enabled = groupRole == null || groupRole == RegularElderRole.GROUP_ASSISTANT,
                    onCheckedChange = { checked -> groupRole = if (checked) RegularElderRole.GROUP_ASSISTANT else null },
                )
                MinisterialServantRoleCheckboxRow(
                    label = "Regular Pioneer",
                    checked = publisherCategory == PublisherCategory.REGULAR_PIONEER,
                    enabled = publisherCategory == null || publisherCategory == PublisherCategory.REGULAR_PIONEER,
                    onCheckedChange = { checked -> publisherCategory = if (checked) PublisherCategory.REGULAR_PIONEER else null },
                )
                MinisterialServantRoleCheckboxRow(
                    label = "Special Pioneer",
                    checked = publisherCategory == PublisherCategory.SPECIAL_PIONEER,
                    enabled = publisherCategory == null || publisherCategory == PublisherCategory.SPECIAL_PIONEER,
                    onCheckedChange = { checked -> publisherCategory = if (checked) PublisherCategory.SPECIAL_PIONEER else null },
                )
                MinisterialServantRoleCheckboxRow(
                    label = "Auxiliary Pioneer",
                    checked = publisherCategory == PublisherCategory.AUXILIARY_PIONEER,
                    enabled = publisherCategory == null || publisherCategory == PublisherCategory.AUXILIARY_PIONEER,
                    onCheckedChange = { checked -> publisherCategory = if (checked) PublisherCategory.AUXILIARY_PIONEER else null },
                )
                MinisterialServantRoleCheckboxRow(
                    label = "Publisher",
                    checked = publisherCategory == PublisherCategory.REGULAR_PUBLISHER,
                    enabled = publisherCategory == null || publisherCategory == PublisherCategory.REGULAR_PUBLISHER,
                    onCheckedChange = { checked -> publisherCategory = if (checked) PublisherCategory.REGULAR_PUBLISHER else null },
                )

                EditSectionHeader("System Information")
                ReadOnlyField("Username", row.person.username)
                ReadOnlyField("Status", if (row.isActive) "Active" else "Inactive")
                ReadOnlyField("Date Added", formatRecordTimestamp(row.person.createdAt))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MinisterialServantCongregationDropdown(congregations: List<Congregation>, selectedId: String?, onSelected: (String) -> Unit) {
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

/** Same "checking one disables/unchecks the others" checkbox row shape
 * ManageServiceOverseersScreen's own local copy uses — not shared directly
 * since each Manage screen keeps its edit dialog self-contained. */
@Composable
private fun MinisterialServantRoleCheckboxRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
