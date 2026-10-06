package com.emfitsolutions.gopreach.ui.screens.admins

import androidx.compose.foundation.clickable
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
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown
import com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.components.rememberCongregationContext
import com.emfitsolutions.gopreach.ui.components.DeleteChoiceDialog
import com.emfitsolutions.gopreach.ui.components.EditSectionHeader
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.ReadOnlyField
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import com.emfitsolutions.gopreach.ui.components.TempCredentialLookupDialog
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage

/** Spec §3/§5.1 — Manage Admins, Super-Admin only. "Move to Inactive" deactivates
 * rather than erases the record — an inactive Admin can be restored, and their
 * history/audit trail stays intact. [canPermanentlyDelete] is Super-Admin-only
 * per the "Admin Record Deletion" spec's scoping decision (see BUILD_PLAN.md). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageAdminsScreen(
    currentPersonId: String,
    canPermanentlyDelete: Boolean,
    onBack: () -> Unit,
    onAddNew: () -> Unit,
    viewModel: ManageAdminsViewModel = koinViewModel(),
) {
    val allAdmins by viewModel.admins.collectAsStateWithLifecycle()
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    var showInactive by remember { mutableStateOf(false) }
    // "Add a filter for Congregation" — this screen has no fixed congregation
    // scope at all (Manage Admins is Super-Admin only, always every
    // congregation), so this is purely a display filter, not a security
    // boundary; `null` (the default) means "All Congregations."
    var congregationFilter by rememberCongregationContext("manage_admins")
    val needsCongregation = congregationFilter == null
    val admins = allAdmins
        .filter { showInactive || it.isActive }
        .filter { it.assignment.congregationId == congregationFilter }
    var lookupTarget by remember { mutableStateOf<Person?>(null) }
    var pendingEdit by remember { mutableStateOf<AdminRow?>(null) }
    var pendingDeactivate by remember { mutableStateOf<AdminRow?>(null) }
    val showToast = rememberActionToast()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Admins") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddNew) {
                Icon(Icons.Rounded.Add, contentDescription = "Enroll Admin")
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
            CongregationFilterDropdown(
                congregations = congregations,
                selectedCongregationId = congregationFilter,
                onSelected = { congregationFilter = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        if (needsCongregation) {
            SelectCongregationPrompt()
        } else if (admins.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RecordFound(0)
                Text("No admins enrolled yet. Tap + to enroll one.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { RecordFound(admins.size) }
                items(admins, key = { it.person.id }) { row ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = row.person.isTemporaryCredential) { lookupTarget = row.person },
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.person.fullName, style = MaterialTheme.typography.titleMedium)
                                Text("Congregation: ${row.congregationName}", style = MaterialTheme.typography.bodySmall)
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
                            IconButton(onClick = { pendingEdit = row }) {
                                Icon(Icons.Rounded.Edit, contentDescription = "Edit")
                            }
                            if (row.isActive) {
                                IconButton(onClick = { pendingDeactivate = row }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                                }
                            } else {
                                IconButton(onClick = { viewModel.setActive(row.assignment, true, currentPersonId) }) {
                                    Icon(Icons.Rounded.RestoreFromTrash, contentDescription = "Restore")
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    lookupTarget?.let { person ->
        TempCredentialLookupDialog(person = person, onDismiss = { lookupTarget = null })
    }

    val toEdit = pendingEdit
    if (toEdit != null) {
        EditAdminDialog(
            row = toEdit,
            onSave = { updated ->
                viewModel.updatePerson(updated)
                showToast("\"${updated.fullName}\" saved.")
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
            onMoveToInactive = { viewModel.setActive(toDeactivate.assignment, false, currentPersonId) },
            onDeletePermanently = { viewModel.permanentlyDelete(toDeactivate, currentPersonId) },
        )
    }
}

/** Shows the complete stored Admin record when editing, not just Address/
 * Contact/Email — Personal Information is editable; Assignment and System
 * Information are read-only (congregation reassignment and active/inactive
 * status already have their own dedicated flows on this screen). */
@Composable
private fun EditAdminDialog(
    row: AdminRow,
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
        maxContentHeight = 520.dp,
        hasUnsavedChanges = firstName != row.person.firstName || lastName != row.person.lastName ||
            address != row.person.address || contact != row.person.contact || email != (row.person.email ?: ""),
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
                ReadOnlyField("Congregation", row.congregationName)
                ReadOnlyField("Role", "Admin")

                EditSectionHeader("System Information")
                ReadOnlyField("Username", row.person.username)
                ReadOnlyField("Status", if (row.isActive) "Active" else "Inactive")
                ReadOnlyField("Date Added", formatRecordTimestamp(row.person.createdAt))
    }
}
