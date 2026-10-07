package com.emfitsolutions.gopreach.ui.screens.elders

import androidx.compose.foundation.clickable
import com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.components.RecordFound
import com.emfitsolutions.gopreach.ui.components.NameFieldsInOrder
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.RegularElderRole
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.ui.components.DeleteChoiceDialog
import com.emfitsolutions.gopreach.ui.components.EditSectionHeader
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.ReadOnlyField
import com.emfitsolutions.gopreach.ui.components.TempCredentialLookupDialog
import com.emfitsolutions.gopreach.ui.components.displayLabel
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp

/** One row backed by a single admin-track [RoleAssignment] — the shape
 * [ManageMinisterialServantsScreen]/ViewModel still uses (Ministerial
 * Servant stays its own separate module, one role per person, unlike the
 * consolidated "Elders" module's own [EldersRow], which aggregates every
 * role a person holds into one row instead). Relocated here from the
 * now-removed ManageCoordinatorEldersViewModel.kt, this file's other
 * long-standing consumer alongside Ministerial Servant before the
 * "Consolidate Elder, Coordinator Elder, Service Overseer and Secretary
 * Enrollment" update. */
data class ElderRow(
    val person: Person,
    val assignment: RoleAssignment,
    val scopeName: String,
    val isActive: Boolean,
    /** Only meaningful for a Regular-Elder-shaped row — their Group Overseer/
     * Servant/Assistant assignment (null otherwise, or not yet placed in a
     * Group role). */
    val regularElderRole: RegularElderRole? = null,
)

/** Shared list UI for [ManageMinisterialServantsScreen] —
 * same card layout as Manage Admins (name/scope/contact/edit/delete(deactivate)/
 * temp-credential lookup), just parameterized by title and what "scope" means
 * for that role. [canPermanentlyDelete] is Super-Admin-only, per the "Admin
 * Record Deletion" spec's scoping decision (see BUILD_PLAN.md). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElderListScreen(
    title: String,
    scopeLabel: String,
    rows: List<ElderRow>,
    canPermanentlyDelete: Boolean,
    /** A restricted user with `VIEW_ELDERS` but not `MANAGE_ELDERS` — hides
     * Add/Edit/Delete/Restore. */
    readOnly: Boolean = false,
    onBack: () -> Unit,
    onAddNew: () -> Unit,
    onSetActive: (ElderRow, Boolean) -> Unit,
    onEdit: (ElderRow, Person) -> Unit,
    onPermanentlyDelete: (ElderRow) -> Unit,
    /** Overrides the default Personal-Information-only [EditElderDialog]
     * with a richer one (e.g. Service Overseer's roles-and-congregation
     * editor) — null (the default) keeps every other caller's existing
     * edit dialog exactly as it was. */
    editDialogContent: (@Composable (row: ElderRow, onDismiss: () -> Unit) -> Unit)? = null,
    /** "Add a filter for Congregation" (Super-Admin only) — rendered right
     * below "Show Inactive" when non-null; each caller supplies its own
     * [com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown]
     * only when it's operating unscoped (fixedCongregationId == null), and
     * [rows] has already been filtered by the caller before it ever reaches
     * this composable — this slot only renders the control, it doesn't
     * filter anything itself. */
    congregationFilterContent: (@Composable () -> Unit)? = null,
    /** Super-Admin has not yet selected a Congregation: show the prompt instead of records. */
    needsCongregation: Boolean = false,
) {
    var showInactive by remember { mutableStateOf(false) }
    val visibleRows = rows.filter { showInactive || it.isActive }
    var lookupTarget by remember { mutableStateOf<Person?>(null) }
    var pendingEdit by remember { mutableStateOf<ElderRow?>(null) }
    var pendingDeactivate by remember { mutableStateOf<ElderRow?>(null) }
    val showToast = rememberActionToast()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
                    Icon(Icons.Rounded.Add, contentDescription = "Enroll $title")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = showInactive, onCheckedChange = { showInactive = it })
                Text("Show Inactive")
            }
            if (congregationFilterContent != null) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    congregationFilterContent()
                }
            }
        if (needsCongregation) {
            SelectCongregationPrompt()
        } else if (visibleRows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RecordFound(0)
                Text("None enrolled yet. Tap + to enroll one.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            com.emfitsolutions.gopreach.ui.components.UniversalReport(
                title = title,
                details = listOf("Scope" to scopeLabel),
                items = visibleRows,
                key = { it.person.id },
                columns = listOf(
                    com.emfitsolutions.gopreach.ui.components.UniversalColumn<ElderRow>("Name", 200.dp) { it.person.fullName },
                    com.emfitsolutions.gopreach.ui.components.UniversalColumn<ElderRow>(scopeLabel, 170.dp) { it.scopeName },
                    com.emfitsolutions.gopreach.ui.components.UniversalColumn<ElderRow>("Contact", 130.dp) { it.person.contact },
                    com.emfitsolutions.gopreach.ui.components.UniversalColumn<ElderRow>("Status", 90.dp) { if (it.isActive) "Active" else "Inactive" },
                ),
                searchText = { listOf(it.person.fullName, it.scopeName, it.person.contact, it.person.email.orEmpty()) },
                filters = listOf(
                    com.emfitsolutions.gopreach.ui.components.UniversalFilter<ElderRow>("scope", scopeLabel, visibleRows.map { it.scopeName }.distinct().sorted().map { it to it }) { r, v -> r.scopeName == v },
                ),
                sorts = listOf(
                    com.emfitsolutions.gopreach.ui.components.UniversalSort<ElderRow>("az", "Name A–Z", compareBy { it.person.fullName.lowercase() }),
                    com.emfitsolutions.gopreach.ui.components.UniversalSort<ElderRow>("za", "Name Z–A", compareByDescending { it.person.fullName.lowercase() }),
                ),
                summary = { shown ->
                    listOf(
                        "Total Records" to shown.size.toString(),
                        "Active" to shown.count { it.isActive }.toString(),
                        "Inactive" to shown.count { !it.isActive }.toString(),
                    ) + shown.groupingBy { it.scopeName }.eachCount().entries.sortedBy { it.key }.map { (scope, n) -> scope to n.toString() }
                },
                generatedBy = "Administrator",
                card = { row ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = row.person.isTemporaryCredential) { lookupTarget = row.person },
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.person.fullName, style = MaterialTheme.typography.titleMedium)
                                if (row.regularElderRole != null) {
                                    Text("Role: ${row.regularElderRole.displayLabel()}", style = MaterialTheme.typography.bodySmall)
                                }
                                Text("$scopeLabel: ${row.scopeName}", style = MaterialTheme.typography.bodySmall)
                                Text("Contact: ${row.person.contact}", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    if (row.isActive) "Active" else "Inactive",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (row.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                                if (row.person.isTemporaryCredential) {
                                    Text(
                                        "Tap to view temporary sign-in",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                            }
                            if (!readOnly) {
                                IconButton(onClick = { pendingEdit = row }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "Edit")
                                }
                                if (row.isActive) {
                                    IconButton(onClick = { pendingDeactivate = row }) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                                    }
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

    lookupTarget?.let { person ->
        TempCredentialLookupDialog(person = person, onDismiss = { lookupTarget = null })
    }

    val toEdit = pendingEdit
    if (toEdit != null) {
        if (editDialogContent != null) {
            editDialogContent(toEdit) { pendingEdit = null }
        } else {
            EditElderDialog(
                row = toEdit,
                scopeLabel = scopeLabel,
                onSave = { updated ->
                    onEdit(toEdit, updated)
                    showToast("\"${updated.fullName}\" saved.")
                    pendingEdit = null
                },
                onDismiss = { pendingEdit = null },
            )
        }
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

/** Shows the complete stored Elder record when editing, not just Address/
 * Contact — Personal Information is editable; Assignment and System
 * Information are read-only (Group role/reassignment and active/inactive
 * status already have their own dedicated controls elsewhere). */
@Composable
private fun EditElderDialog(
    row: ElderRow,
    scopeLabel: String,
    onSave: (Person) -> Unit,
    onDismiss: () -> Unit,
) {
    var firstName by remember { mutableStateOf(row.person.firstName) }
    var lastName by remember { mutableStateOf(row.person.lastName) }
    var address by remember { mutableStateOf(row.person.address) }
    var contact by remember { mutableStateOf(row.person.contact) }
    var email by remember { mutableStateOf(row.person.email ?: "") }
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
        onSave(
            row.person.copy(
                firstName = firstName.trim(),
                lastName = lastName.trim(),
                address = address.trim(),
                contact = contact.trim(),
                email = email.trim().ifBlank { null },
            ),
        )
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = "Edit ${row.person.fullName}",
        onConfirm = ::submit,
        confirmLabel = "Save Changes",
        errorMessage = errorMessage,
        hasUnsavedChanges = firstName != row.person.firstName || lastName != row.person.lastName ||
            address != row.person.address || contact != row.person.contact || email != (row.person.email ?: ""),
        maxContentHeight = 520.dp,
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
                ReadOnlyField(scopeLabel, row.scopeName)
                if (row.regularElderRole != null) {
                    ReadOnlyField("Group Role", row.regularElderRole.displayLabel())
                }

                EditSectionHeader("System Information")
                ReadOnlyField("Username", row.person.username)
                ReadOnlyField("Status", if (row.isActive) "Active" else "Inactive")
                ReadOnlyField("Date Added", formatRecordTimestamp(row.person.createdAt))
    }
}
