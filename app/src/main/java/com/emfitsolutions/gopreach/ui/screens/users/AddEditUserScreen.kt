package com.emfitsolutions.gopreach.ui.screens.users

import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.ui.components.NameFieldsInOrder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.Permission
import com.emfitsolutions.gopreach.data.model.ScopeType
import com.emfitsolutions.gopreach.data.model.UserAccessGrant
import com.emfitsolutions.gopreach.data.repository.TempCredentials
import com.emfitsolutions.gopreach.ui.components.ReadOnlyField
import com.emfitsolutions.gopreach.ui.components.ShareableSetupLink
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import com.emfitsolutions.gopreach.ui.components.rememberActionToast

/**
 * One form for both [ADD] and [Edit] (spec §4/§8) — Role+Permission+Scope, not
 * a role dropdown (spec's explicit "Final Requirement"). [targetPersonId] null
 * means "creating a new user"; non-null loads that user's current grant for
 * editing. Scope can be All Congregations, a set of whole Congregations, or a
 * set of individual Groups — all three are backed by [ScopeType] and enforced
 * by [com.emfitsolutions.gopreach.domain.PermissionChecker]/[UserAccessGrant.allows].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditUserScreen(
    targetPersonId: String?,
    currentPersonId: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: AddEditUserViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()

    LaunchedEffect(targetPersonId) {
        if (targetPersonId != null) viewModel.loadForEdit(targetPersonId)
    }
    val showToast = rememberActionToast()
    LaunchedEffect(uiState.saveCompleted) {
        if (uiState.saveCompleted && uiState.isEditMode) {
            showToast("User saved.")
            onDone()
        }
    }

    if (uiState.savedResult != null) {
        NewUserCredentialsCard(uiState.savedResult!!, onDone = onDone)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isEditMode) "Edit User Access" else "Add User") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!uiState.isEditMode) {
                Text("Full Name", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = uiState.fullName,
                    onValueChange = viewModel::onFullNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("First Last") },
                )
                Text(
                    "A username and temporary password are generated automatically — you'll get a shareable link once this is saved.",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
            } else {
                // Show the complete stored Person record when editing, not just
                // permissions/scope/status — Personal Information is editable;
                // Username/Date Added are system-generated and shown read-only.
                Text("Personal Information", style = MaterialTheme.typography.titleMedium)
                NameFieldsInOrder(
                    first = {
                        OutlinedTextField(
                        value = uiState.firstName,
                        onValueChange = viewModel::onFirstNameChange,
                        label = { Text("First Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    },
                    last = {
                        OutlinedTextField(
                        value = uiState.lastName,
                        onValueChange = viewModel::onLastNameChange,
                        label = { Text("Last Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    },
                )
                OutlinedTextField(
                    value = uiState.address,
                    onValueChange = viewModel::onAddressChange,
                    label = { Text("Address") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = uiState.contact,
                    onValueChange = viewModel::onContactChange,
                    label = { Text("Contact") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = uiState.email,
                    onValueChange = viewModel::onEmailChange,
                    label = { Text("Email (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider()
                Text("System Information", style = MaterialTheme.typography.titleMedium)
                ReadOnlyField("Username", uiState.username)
                ReadOnlyField("Date Added", formatRecordTimestamp(uiState.createdAt))
                HorizontalDivider()
            }

            Text("Role", style = MaterialTheme.typography.titleSmall)
            Text(
                "Circuit Overseer / Custom User — every capability below is opt-in; nothing is granted automatically (spec §5).",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            Permission.entries.forEach { permission ->
                PermissionRow(
                    label = permission.displayLabel(),
                    checked = permission in uiState.selectedPermissions,
                    onCheckedChange = { viewModel.onPermissionToggled(permission, it) },
                )
            }

            HorizontalDivider()
            Text("Access Scope", style = MaterialTheme.typography.titleMedium)
            ScopeRadioRow(
                label = "All Congregations",
                selected = uiState.scopeType == ScopeType.ALL_CONGREGATIONS,
                onClick = { viewModel.onScopeTypeChange(ScopeType.ALL_CONGREGATIONS) },
            )
            ScopeRadioRow(
                label = "Selected Congregations",
                selected = uiState.scopeType == ScopeType.SELECTED_CONGREGATIONS,
                onClick = { viewModel.onScopeTypeChange(ScopeType.SELECTED_CONGREGATIONS) },
            )
            if (uiState.scopeType == ScopeType.SELECTED_CONGREGATIONS) {
                Column(modifier = Modifier.padding(start = 24.dp)) {
                    congregations.forEach { congregation ->
                        PermissionRow(
                            label = congregation.name,
                            checked = congregation.id in uiState.selectedCongregationIds,
                            onCheckedChange = { viewModel.onCongregationToggled(congregation.id, it) },
                        )
                    }
                }
            }
            ScopeRadioRow(
                label = "Selected Field Service Groups",
                selected = uiState.scopeType == ScopeType.SELECTED_GROUPS,
                onClick = { viewModel.onScopeTypeChange(ScopeType.SELECTED_GROUPS) },
            )
            if (uiState.scopeType == ScopeType.SELECTED_GROUPS) {
                Column(modifier = Modifier.padding(start = 24.dp)) {
                    if (groups.isEmpty()) {
                        Text(
                            "No field service groups exist yet — create one under Enrollment > Field Service Groups first.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // Grouped under their own congregation's name so a Super-Admin
                    // picking across many congregations can tell them apart —
                    // Group.name alone isn't unique app-wide.
                    congregations.forEach { congregation ->
                        val congregationGroups = groups.filter { it.congregationId == congregation.id }
                        if (congregationGroups.isNotEmpty()) {
                            Text(
                                congregation.name,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            congregationGroups.forEach { group ->
                                PermissionRow(
                                    label = group.name,
                                    checked = group.id in uiState.selectedGroupIds,
                                    onCheckedChange = { viewModel.onGroupToggled(group.id, it) },
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.isEditMode) {
                HorizontalDivider()
                Text("Account Status", style = MaterialTheme.typography.titleMedium)
                AccountStatus.entries.forEach { status ->
                    ScopeRadioRow(
                        label = status.name,
                        selected = uiState.status == status,
                        onClick = { viewModel.onStatusChange(status) },
                    )
                }
            }

            if (uiState.errorMessage != null) {
                Text(uiState.errorMessage!!, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = { if (uiState.isEditMode) viewModel.saveEdit(currentPersonId) else viewModel.createUser(currentPersonId) },
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isSaving) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text(if (uiState.isEditMode) "Save Changes" else "Create User")
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label)
    }
}

@Composable
private fun ScopeRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun NewUserCredentialsCard(credentials: TempCredentials, onDone: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("User created", style = MaterialTheme.typography.headlineSmall)
            Text("Share these credentials — the user must change the password on first login.")
            Text("Username: ${credentials.username}", fontWeight = FontWeight.Bold)
            Text("Temporary password: ${credentials.temporaryPassword}", fontWeight = FontWeight.Bold)
            ShareableSetupLink(credentials.username, credentials.temporaryPassword, credentials.shareableLink)
            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

private fun Permission.displayLabel(): String = when (this) {
    Permission.VIEW_CONGREGATIONS -> "View Congregations"
    Permission.ADD_CONGREGATIONS -> "Add Congregations"
    Permission.EDIT_CONGREGATIONS -> "Edit Congregations"
    Permission.DELETE_CONGREGATIONS -> "Delete Congregations"
    Permission.VIEW_ELDERS -> "View Elders"
    Permission.MANAGE_ELDERS -> "Manage Elders"
    Permission.VIEW_GROUPS -> "View Field Service Groups"
    Permission.MANAGE_GROUPS -> "Manage Field Service Groups"
    Permission.VIEW_TERRITORY_ASSIGNMENTS -> "View Territory Assignments"
    Permission.MANAGE_TERRITORY_ASSIGNMENTS -> "Manage Territory Assignments"
    Permission.VIEW_PUBLISHERS -> "View Publishers"
    Permission.MANAGE_PUBLISHERS -> "Manage Publishers"
    Permission.VIEW_PUBLISHER_REPORTS -> "View Publisher Reports"
    Permission.VIEW_GROUP_REPORTS -> "View Group Reports"
    Permission.VIEW_CONGREGATION_REPORTS -> "View Congregation Reports"
    Permission.PRINT_REPORTS -> "Print Reports"
    Permission.EXPORT_REPORTS -> "Export Reports"
    Permission.MANAGE_USERS -> "Manage Users"
}
