package com.emfitsolutions.gopreach.ui.screens.enrollment

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RegularElderRole
import com.emfitsolutions.gopreach.data.model.displayLabel
import com.emfitsolutions.gopreach.ui.components.TempCredentialsResultCard
import com.emfitsolutions.gopreach.ui.components.rememberUnsavedChangesBackHandler

/**
 * "Consolidate Elder, Coordinator Elder, Service Overseer and Secretary
 * Enrollment" — the single Add form for Regular Elder, Coordinator Elder,
 * Service Overseer, and Secretary. Reachable by Super-Admin, Admin (own
 * congregation), and Coordinator Elder (own congregation) — same access set
 * every one of the three enrollment screens this replaces already shared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EldersEnrollmentScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: EldersEnrollmentViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    var isSuperAdmin by remember { mutableStateOf(false) }

    LaunchedEffect(currentPersonId) {
        isSuperAdmin = viewModel.isEnrollerSuperAdmin(currentPersonId)
    }

    val hasUnsavedChanges = uiState.result == null && (
        uiState.firstName.isNotBlank() || uiState.lastName.isNotBlank() || uiState.address.isNotBlank() ||
            uiState.email.isNotBlank() || uiState.contact.isNotBlank()
        )
    val guardedBack = rememberUnsavedChangesBackHandler(hasUnsavedChanges, onDiscard = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Enroll Elder") },
                navigationIcon = {
                    IconButton(onClick = guardedBack.onBackPressed) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            if (uiState.result != null) {
                TempCredentialsResultCard(credentials = uiState.result!!, onDone = onDone)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    NameFieldsInOrder(
                        first = {
                            OutlinedTextField(
                            value = uiState.firstName,
                            onValueChange = viewModel::onFirstNameChange,
                            label = { Text("First Name") },
                            singleLine = true,
                            visualTransformation = VisualTransformation.None,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        },
                        last = {
                            OutlinedTextField(
                            value = uiState.lastName,
                            onValueChange = viewModel::onLastNameChange,
                            label = { Text("Last Name") },
                            singleLine = true,
                            visualTransformation = VisualTransformation.None,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        },
                    )
                    OutlinedTextField(
                        value = uiState.address,
                        onValueChange = viewModel::onAddressChange,
                        label = { Text("Address") },
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = uiState.email,
                        onValueChange = viewModel::onEmailChange,
                        label = { Text("Email (optional)") },
                        singleLine = true,
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = uiState.contact,
                        onValueChange = viewModel::onContactChange,
                        label = { Text("Contact") },
                        singleLine = true,
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (isSuperAdmin) {
                        CongregationDropdown(
                            congregations = congregations,
                            selectedId = uiState.selectedCongregationId,
                            onSelected = viewModel::onCongregationSelected,
                        )
                    } else {
                        Text(
                            "This person will be assigned to your congregation.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    HorizontalDivider()
                    Text("Select Role", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "One or more roles may apply to the same person. Group Overseer/Assistant Group Overseer are mutually exclusive with each other, as are the three Publisher categories.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    listOf(AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY, AdminRole.REGULAR_ELDER).forEach { role ->
                        RoleCheckboxRow(
                            label = role.displayLabel(),
                            checked = role in uiState.selectedAdminRoles,
                            onCheckedChange = { viewModel.onAdminRoleToggled(role, it) },
                        )
                    }
                    RoleCheckboxRow(
                        label = "Group Overseer",
                        checked = uiState.regularElderRole == RegularElderRole.GROUP_OVERSEER,
                        onCheckedChange = { viewModel.onRegularElderRoleToggled(RegularElderRole.GROUP_OVERSEER, it) },
                    )
                    RoleCheckboxRow(
                        label = "Assistant Group Overseer",
                        checked = uiState.regularElderRole == RegularElderRole.GROUP_ASSISTANT,
                        onCheckedChange = { viewModel.onRegularElderRoleToggled(RegularElderRole.GROUP_ASSISTANT, it) },
                    )
                    RoleCheckboxRow(
                        label = "Regular Pioneer",
                        checked = uiState.publisherCategory == PublisherCategory.REGULAR_PIONEER,
                        enabled = uiState.publisherCategory == null || uiState.publisherCategory == PublisherCategory.REGULAR_PIONEER,
                        onCheckedChange = { viewModel.onPublisherCategoryToggled(PublisherCategory.REGULAR_PIONEER, it) },
                    )
                    RoleCheckboxRow(
                        label = "Special Pioneer",
                        checked = uiState.publisherCategory == PublisherCategory.SPECIAL_PIONEER,
                        enabled = uiState.publisherCategory == null || uiState.publisherCategory == PublisherCategory.SPECIAL_PIONEER,
                        onCheckedChange = { viewModel.onPublisherCategoryToggled(PublisherCategory.SPECIAL_PIONEER, it) },
                    )
                    RoleCheckboxRow(
                        label = "Auxiliary Pioneer",
                        checked = uiState.publisherCategory == PublisherCategory.AUXILIARY_PIONEER,
                        enabled = uiState.publisherCategory == null || uiState.publisherCategory == PublisherCategory.AUXILIARY_PIONEER,
                        onCheckedChange = { viewModel.onPublisherCategoryToggled(PublisherCategory.AUXILIARY_PIONEER, it) },
                    )
                    RoleCheckboxRow(
                        label = "Publisher",
                        checked = uiState.publisherCategory == PublisherCategory.REGULAR_PUBLISHER,
                        enabled = uiState.publisherCategory == null || uiState.publisherCategory == PublisherCategory.REGULAR_PUBLISHER,
                        onCheckedChange = { viewModel.onPublisherCategoryToggled(PublisherCategory.REGULAR_PUBLISHER, it) },
                    )

                    if (uiState.errorMessage != null) {
                        Text(text = uiState.errorMessage!!, color = MaterialTheme.colorScheme.error)
                    }

                    Button(
                        onClick = { viewModel.save(enrollingPersonId = currentPersonId) },
                        enabled = !uiState.isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        }
                        Text("Create Elder Account")
                    }
                }
            }
        }
    }
}

/** One "Select Role" checkbox — [enabled] false renders it visibly disabled
 * (used for the three mutually-exclusive Publisher categories: checking one
 * disables the other two, not just leaves them checkable-but-ignored).
 * Internal, not private — reused as-is by MinisterialServantEnrollmentScreen
 * (same package, identical "Select Role" section); relocated here from the
 * now-removed CoordinatorElderEnrollmentScreen. */
@Composable
internal fun RoleCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
