package com.emfitsolutions.gopreach.ui.screens.enrollment

import androidx.compose.foundation.verticalScroll
import com.emfitsolutions.gopreach.ui.components.NameFieldsInOrder
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RegularElderRole
import com.emfitsolutions.gopreach.ui.components.TempCredentialsResultCard
import com.emfitsolutions.gopreach.ui.components.rememberUnsavedChangesBackHandler

/**
 * "MINISTERIAL ACCOUNT" spec. Reachable by Super-Admin, Admin (own
 * congregation), and Coordinator Elder (own congregation) — same access set
 * as Service Overseer enrollment. Unlike Service Overseer, there's no
 * per-congregation cap here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinisterialServantEnrollmentScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: MinisterialServantEnrollmentViewModel = hiltViewModel(),
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
                title = { Text("Enroll Ministerial Servant") },
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
                        "Group Servant and Group Assistant are mutually exclusive with each other, and independently, only one publisher category applies at a time.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    RoleCheckboxRow(
                        label = "Group Servant",
                        checked = uiState.groupRole == RegularElderRole.GROUP_SERVANT,
                        enabled = uiState.groupRole == null || uiState.groupRole == RegularElderRole.GROUP_SERVANT,
                        onCheckedChange = { viewModel.onGroupRoleToggled(RegularElderRole.GROUP_SERVANT, it) },
                    )
                    RoleCheckboxRow(
                        label = "Group Assistant",
                        checked = uiState.groupRole == RegularElderRole.GROUP_ASSISTANT,
                        enabled = uiState.groupRole == null || uiState.groupRole == RegularElderRole.GROUP_ASSISTANT,
                        onCheckedChange = { viewModel.onGroupRoleToggled(RegularElderRole.GROUP_ASSISTANT, it) },
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
                        Text("Create Ministerial Servant Account")
                    }
                }
            }
        }
    }
}
