package com.emfitsolutions.gopreach.ui.screens.elders

import androidx.compose.foundation.clickable
import com.emfitsolutions.gopreach.ui.components.RecordFound
import com.emfitsolutions.gopreach.data.model.displayName
import com.emfitsolutions.gopreach.ui.components.NameFieldsInOrder
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
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RegularElderRole
import com.emfitsolutions.gopreach.data.model.displayLabel
import com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown
import com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.components.rememberCongregationContext
import com.emfitsolutions.gopreach.ui.components.DeleteChoiceDialog
import com.emfitsolutions.gopreach.ui.components.EditSectionHeader
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.ReadOnlyField
import com.emfitsolutions.gopreach.ui.components.TempCredentialLookupDialog
import com.emfitsolutions.gopreach.ui.components.displayLabel as regularElderRoleDisplayLabel
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage

/** Every checkbox the unified Elders form offers (spec §7/§18) — in display
 * order. */
private val ADMIN_ROLE_CHECKBOXES = listOf(AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY, AdminRole.REGULAR_ELDER)

/**
 * "Consolidate Elder, Coordinator Elder, Service Overseer and Secretary
 * Enrollment" — the single Manage screen for Regular Elder, Coordinator
 * Elder, Service Overseer, and Secretary, replacing the three separate
 * Manage screens those used to be (spec §1/§5/§43). Congregation filter
 * first (Super-Admin: a real dropdown; every other role: fixed, per
 * [fixedCongregationId]), then Role, then Search — spec §44's exact order.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ManageEldersScreen(
    fixedCongregationId: String?,
    currentPersonId: String,
    canPermanentlyDelete: Boolean,
    readOnly: Boolean = false,
    onBack: () -> Unit,
    onAddNew: () -> Unit,
    viewModel: ManageEldersViewModel = koinViewModel(),
) {
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    // "For Super Admin: Congregation: [All Congregations]" — a scoped role
    // never sees this control at all (fixedCongregationId != null), so this
    // local pick can only ever matter for Super-Admin, same convention every
    // other Manage screen here already uses.
    var congregationFilter by rememberCongregationContext("elders")
    val effectiveCongregationId = fixedCongregationId ?: congregationFilter
    val needsCongregation = fixedCongregationId == null && congregationFilter == null
    val rowsFlow = remember(effectiveCongregationId, needsCongregation) { if (needsCongregation) kotlinx.coroutines.flow.flowOf(emptyList()) else viewModel.rowsFor(effectiveCongregationId) }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    ManageEldersContent(
        fixedCongregationId = fixedCongregationId,
        canPermanentlyDelete = canPermanentlyDelete,
        readOnly = readOnly,
        onBack = onBack,
        onAddNew = onAddNew,
        rows = rows,
        congregations = congregations,
        selectedCongregationId = congregationFilter,
        onCongregationSelected = { congregationFilter = it },
        onSetActive = { row, active -> viewModel.setActive(row, active, currentPersonId) },
        onEdit = { row, updatedPerson, congregationId, adminRoles, regularElderRole, publisherCategory ->
            viewModel.updateRolesAndPerson(row, updatedPerson, congregationId, adminRoles, regularElderRole, publisherCategory, currentPersonId)
        },
        onPermanentlyDelete = { row -> viewModel.permanentlyDelete(row, currentPersonId) },
    )
}

/** The stateless list/filter/edit UI itself, kept separate from
 * [ManageEldersScreen]'s ViewModel wiring above purely so its layout can be
 * read/reasoned about (and previewed) independent of Hilt. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ManageEldersContent(
    fixedCongregationId: String?,
    canPermanentlyDelete: Boolean,
    readOnly: Boolean = false,
    onBack: () -> Unit,
    onAddNew: () -> Unit,
    rows: List<EldersRow>,
    congregations: List<Congregation>,
    /** Non-null (Super-Admin only, per [fixedCongregationId] == null) shows
     * the Congregation filter dropdown; the caller already filters [rows]
     * before they ever reach this composable, same convention as
     * [ElderListScreen]. */
    selectedCongregationId: String?,
    onCongregationSelected: (String?) -> Unit,
    onSetActive: (EldersRow, Boolean) -> Unit,
    onEdit: (EldersRow, Person, String, Set<AdminRole>, RegularElderRole?, PublisherCategory?) -> Unit,
    onPermanentlyDelete: (EldersRow) -> Unit,
) {
    var showInactive by remember { mutableStateOf(false) }
    var roleFilter by remember { mutableStateOf<AdminRole?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var lookupTarget by remember { mutableStateOf<Person?>(null) }
    var pendingEdit by remember { mutableStateOf<EldersRow?>(null) }
    var pendingDeactivate by remember { mutableStateOf<EldersRow?>(null) }
    val showToast = rememberActionToast()

    // "The filter must operate against the actual assigned roles" (spec §16)
    // — a person with more than one role still shows up for either.
    val roleFiltered = if (roleFilter == null) rows else rows.filter { roleFilter in it.adminRoles }
    val query = searchQuery.trim()
    val searched = if (query.isBlank()) {
        roleFiltered
    } else {
        roleFiltered.filter { row ->
            row.person.fullName.contains(query, ignoreCase = true) ||
                row.person.contact.contains(query, ignoreCase = true) ||
                (row.person.email?.contains(query, ignoreCase = true) == true) ||
                row.congregationName.contains(query, ignoreCase = true) ||
                row.adminRoles.any { it.displayLabel().contains(query, ignoreCase = true) }
        }
    }
    val visibleRows = searched.filter { showInactive || it.isActive }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Elders") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!readOnly) {
                FloatingActionButton(onClick = onAddNew) {
                    Icon(Icons.Rounded.Add, contentDescription = "Enroll Elder")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Spec §44 order: Congregation, Role, Search.
                if (selectedCongregationId != null || fixedCongregationId == null) {
                    CongregationFilterDropdown(
                        congregations = congregations,
                        selectedCongregationId = selectedCongregationId,
                        onSelected = onCongregationSelected,
                    )
                }
                RoleFilterDropdown(selected = roleFilter, onSelected = { roleFilter = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showInactive, onCheckedChange = { showInactive = it })
                    Text("Show Inactive")
                }
            }
            if (fixedCongregationId == null && selectedCongregationId == null) {
                SelectCongregationPrompt()
            } else if (visibleRows.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    RecordFound(0)
                    Text("None enrolled yet. Tap + to enroll one.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                com.emfitsolutions.gopreach.ui.components.UniversalReport(
                    title = "Elders",
                    details = emptyList(),
                    items = visibleRows,
                    key = { it.person.id },
                    columns = listOf(
                        com.emfitsolutions.gopreach.ui.components.UniversalColumn<EldersRow>("Name", 200.dp) { it.person.fullName },
                        com.emfitsolutions.gopreach.ui.components.UniversalColumn<EldersRow>("Congregation", 170.dp) { it.congregationName },
                        com.emfitsolutions.gopreach.ui.components.UniversalColumn<EldersRow>("Roles", 240.dp) { r -> r.adminRoles.joinToString(", ") { it.displayLabel() } },
                        com.emfitsolutions.gopreach.ui.components.UniversalColumn<EldersRow>("Contact", 130.dp) { it.person.contact },
                        com.emfitsolutions.gopreach.ui.components.UniversalColumn<EldersRow>("Status", 90.dp) { if (it.isActive) "Active" else "Inactive" },
                    ),
                    searchText = { r -> listOf(r.person.fullName, r.person.contact, r.person.email.orEmpty(), r.congregationName) + r.adminRoles.map { it.displayLabel() } },
                    filters = listOf(
                        com.emfitsolutions.gopreach.ui.components.UniversalFilter<EldersRow>("congregation", "Congregations", visibleRows.map { it.congregationName }.distinct().sorted().map { it to it }) { r, v -> r.congregationName == v },
                    ),
                    sorts = listOf(
                        com.emfitsolutions.gopreach.ui.components.UniversalSort<EldersRow>("az", "Name A–Z", compareBy { it.person.fullName.lowercase() }),
                        com.emfitsolutions.gopreach.ui.components.UniversalSort<EldersRow>("za", "Name Z–A", compareByDescending { it.person.fullName.lowercase() }),
                    ),
                    summary = { shown ->
                        listOf(
                            "Total Elders" to shown.size.toString(),
                            "Active" to shown.count { it.isActive }.toString(),
                            "Inactive" to shown.count { !it.isActive }.toString(),
                        ) + shown.flatMap { it.adminRoles }.groupingBy { it }.eachCount().entries.sortedBy { it.key.displayLabel() }.map { (role, n) -> role.displayLabel() to n.toString() }
                    },
                    generatedBy = "Administrator",
                    card = { row ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = row.person.isTemporaryCredential) { lookupTarget = row.person },
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(row.person.fullName, style = MaterialTheme.typography.titleMedium)
                                    Text("Congregation: ${row.congregationName}", style = MaterialTheme.typography.bodySmall)
                                    // "If the list becomes too wide on mobile,
                                    // display roles as chips" (spec §28).
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        row.adminRoles.forEach { role ->
                                            AssistChip(onClick = {}, label = { Text(role.displayLabel(), style = MaterialTheme.typography.labelSmall) }, colors = AssistChipDefaults.assistChipColors())
                                        }
                                        row.regularElderRole?.let { groupRole ->
                                            AssistChip(onClick = {}, label = { Text(groupRole.regularElderRoleDisplayLabel(), style = MaterialTheme.typography.labelSmall) })
                                        }
                                        row.publisherCategory?.let { category ->
                                            AssistChip(onClick = {}, label = { Text(category.displayName, style = MaterialTheme.typography.labelSmall) })
                                        }
                                    }
                                    Text("Contact: ${row.person.contact}", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        if (row.isActive) "Active" else "Inactive",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (row.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    )
                                    if (row.person.isTemporaryCredential) {
                                        Text("Tap to view temporary sign-in", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                                if (!readOnly) {
                                    IconButton(onClick = { pendingEdit = row }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit") }
                                    if (row.isActive) {
                                        IconButton(onClick = { pendingDeactivate = row }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete") }
                                    } else {
                                        IconButton(onClick = { onSetActive(row, true); showToast("\"${row.person.fullName}\" reactivated.") }) {
                                            Icon(Icons.Rounded.RestoreFromTrash, contentDescription = "Restore")
                                        }
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    lookupTarget?.let { person -> TempCredentialLookupDialog(person = person, onDismiss = { lookupTarget = null }) }

    val toEdit = pendingEdit
    if (toEdit != null) {
        EditElderDialog(
            row = toEdit,
            congregations = congregations,
            isCongregationEditable = selectedCongregationId != null || fixedCongregationId == null,
            onSave = { updatedPerson, congregationId, adminRoles, regularElderRole, publisherCategory ->
                onEdit(toEdit, updatedPerson, congregationId, adminRoles, regularElderRole, publisherCategory)
                showToast("\"${updatedPerson.fullName}\" saved.")
                pendingEdit = null
            },
            onDismiss = { pendingEdit = null },
        )
    }

    val toDeactivate = pendingDeactivate
    if (toDeactivate != null) {
        DeleteChoiceDialog(
            recordLabel = toDeactivate.person.fullName,
            canPermanentlyDelete = canPermanentlyDelete,
            onDismiss = { pendingDeactivate = null },
            onMoveToInactive = { onSetActive(toDeactivate, false) },
            onDeletePermanently = { onPermanentlyDelete(toDeactivate) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleFilterDropdown(selected: AdminRole?, onSelected: (AdminRole?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = selected?.displayLabel() ?: "All Roles"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Role") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("All Roles") }, onClick = { onSelected(null); expanded = false })
            ADMIN_ROLE_CHECKBOXES.forEach { role ->
                DropdownMenuItem(text = { Text(role.displayLabel()) }, onClick = { onSelected(role); expanded = false })
            }
        }
    }
}

/** The full Edit form — Personal Information, Congregation (editable only
 * when [isCongregationEditable]), and every "Select Role" checkbox
 * (spec §19: "The Edit screen must allow changing... Assigned roles"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditElderDialog(
    row: EldersRow,
    congregations: List<Congregation>,
    isCongregationEditable: Boolean,
    onSave: (Person, String, Set<AdminRole>, RegularElderRole?, PublisherCategory?) -> Unit,
    onDismiss: () -> Unit,
) {
    var firstName by remember { mutableStateOf(row.person.firstName) }
    var lastName by remember { mutableStateOf(row.person.lastName) }
    var address by remember { mutableStateOf(row.person.address) }
    var contact by remember { mutableStateOf(row.person.contact) }
    var email by remember { mutableStateOf(row.person.email ?: "") }
    var congregationId by remember { mutableStateOf(row.congregationId) }
    var selectedAdminRoles by remember { mutableStateOf(row.adminRoles) }
    var regularElderRole by remember { mutableStateOf(row.regularElderRole) }
    var publisherCategory by remember { mutableStateOf(row.publisherCategory) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val message = requiredFieldsMessage(
            "First Name" to firstName.isNotBlank(),
            "Last Name" to lastName.isNotBlank(),
            "Address" to address.isNotBlank(),
            "Contact" to contact.isNotBlank(),
        )
        if (message != null) {
            errorMessage = message
            return
        }
        if (selectedAdminRoles.isEmpty() && regularElderRole == null && publisherCategory == null) {
            errorMessage = "Select at least one role."
            return
        }
        onSave(
            row.person.copy(firstName = firstName.trim(), lastName = lastName.trim(), address = address.trim(), contact = contact.trim(), email = email.trim().ifBlank { null }),
            congregationId,
            selectedAdminRoles,
            regularElderRole,
            publisherCategory,
        )
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = "Edit ${row.person.fullName}",
        onConfirm = ::submit,
        confirmLabel = "Save Changes",
        errorMessage = errorMessage,
        hasUnsavedChanges = firstName != row.person.firstName || lastName != row.person.lastName || address != row.person.address ||
            contact != row.person.contact || email != (row.person.email ?: "") || congregationId != row.congregationId ||
            selectedAdminRoles != row.adminRoles || regularElderRole != row.regularElderRole || publisherCategory != row.publisherCategory,
        maxContentHeight = 620.dp,
    ) {
        EditSectionHeader("Personal Information")
        NameFieldsInOrder(
            first = {
                OutlinedTextField(
                value = firstName, onValueChange = { firstName = it.uppercase() }, label = { Text("First Name") },
                singleLine = true, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth(),
            )
            },
            last = {
                OutlinedTextField(
                value = lastName, onValueChange = { lastName = it.uppercase() }, label = { Text("Last Name") },
                singleLine = true, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth(),
            )
            },
        )
        OutlinedTextField(
            value = address, onValueChange = { address = it.uppercase() }, label = { Text("Address") },
            visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = contact, onValueChange = { contact = it.uppercase() }, label = { Text("Contact") },
            singleLine = true, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("Email (optional)") },
            singleLine = true, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth(),
        )

        EditSectionHeader("Congregation")
        if (isCongregationEditable) {
            var expanded by remember { mutableStateOf(false) }
            val selectedName = congregations.firstOrNull { it.id == congregationId }?.name ?: ""
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selectedName, onValueChange = {}, readOnly = true, label = { Text("Congregation") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    congregations.forEach { congregation ->
                        DropdownMenuItem(text = { Text(congregation.name) }, onClick = { congregationId = congregation.id; expanded = false })
                    }
                }
            }
        } else {
            ReadOnlyField("Congregation", row.congregationName)
        }

        EditSectionHeader("Select Role")
        ADMIN_ROLE_CHECKBOXES.forEach { role ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = role in selectedAdminRoles,
                    onCheckedChange = { checked -> selectedAdminRoles = if (checked) selectedAdminRoles + role else selectedAdminRoles - role },
                )
                Text(role.displayLabel())
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = regularElderRole == RegularElderRole.GROUP_OVERSEER,
                onCheckedChange = { checked -> regularElderRole = if (checked) RegularElderRole.GROUP_OVERSEER else if (regularElderRole == RegularElderRole.GROUP_OVERSEER) null else regularElderRole },
            )
            Text("Group Overseer")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = regularElderRole == RegularElderRole.GROUP_ASSISTANT,
                onCheckedChange = { checked -> regularElderRole = if (checked) RegularElderRole.GROUP_ASSISTANT else if (regularElderRole == RegularElderRole.GROUP_ASSISTANT) null else regularElderRole },
            )
            Text("Assistant Group Overseer")
        }
        listOf(
            PublisherCategory.REGULAR_PIONEER to "Regular Pioneer",
            PublisherCategory.SPECIAL_PIONEER to "Special Pioneer",
            PublisherCategory.AUXILIARY_PIONEER to "Auxiliary Pioneer",
            PublisherCategory.REGULAR_PUBLISHER to "Publisher",
        ).forEach { (category, label) ->
            val enabled = publisherCategory == null || publisherCategory == category
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = publisherCategory == category,
                    enabled = enabled,
                    onCheckedChange = { checked -> publisherCategory = if (checked) category else if (publisherCategory == category) null else publisherCategory },
                )
                Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        EditSectionHeader("System Information")
        ReadOnlyField("Username", row.person.username)
        ReadOnlyField("Status", if (row.isActive) "Active" else "Inactive")
        ReadOnlyField("Date Added", formatRecordTimestamp(row.person.createdAt))
    }
}
