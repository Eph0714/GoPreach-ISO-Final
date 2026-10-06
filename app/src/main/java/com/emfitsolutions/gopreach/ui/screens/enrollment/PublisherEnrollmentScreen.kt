package com.emfitsolutions.gopreach.ui.screens.enrollment

import androidx.compose.foundation.verticalScroll
import com.emfitsolutions.gopreach.ui.components.PublisherFormFields
import com.emfitsolutions.gopreach.ui.components.PublisherFormState
import com.emfitsolutions.gopreach.data.model.displayName
import com.emfitsolutions.gopreach.ui.components.NameFieldsInOrder
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.ui.components.TempCredentialsResultCard
import com.emfitsolutions.gopreach.ui.components.rememberUnsavedChangesBackHandler

/**
 * "CREATING PUBLISHER" spec — Publisher enrollment.
 *
 * [visibleCongregationId] is the security boundary (resolved by the caller
 * from the enrolling session's own role, see GoPreachNavGraph): `null` means
 * Super-Admin, and only then does the Select Congregation field appear at
 * all — a non-null value keeps the Group-only screen for anyone already
 * scoped to one congregation (Admin/Coordinator Elder/Service Overseer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublisherEnrollmentScreen(
    currentPersonId: String,
    visibleCongregationId: String? = null,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: PublisherEnrollmentViewModel = koinViewModel(),
) {
    LaunchedEffect(visibleCongregationId) { viewModel.restrictTo(visibleCongregationId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()

    val hasUnsavedChanges = uiState.result == null && uiState.form != PublisherFormState()
    val guardedBack = rememberUnsavedChangesBackHandler(hasUnsavedChanges, onDiscard = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Enroll Publisher") },
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
                    val locationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    ) { granted -> if (granted) viewModel.captureLocation() }

                    // The same field set as the Edit Publisher dialog (see PublisherFormFields): names, middle
                    // initial, extension, gender, contacts, address, province/municipality/barangay,
                    // coordinates, email, category, field service group and status.
                    PublisherFormFields(
                        form = uiState.form,
                        onChange = viewModel::onFormChange,
                        groups = groups,
                        // A Super-Admin's new publisher starts in the congregation they are working in and they may
                        // change it; anyone else is fixed to their own congregation (shown, not editable).
                        congregations = congregations,
                        congregationEditable = visibleCongregationId == null,
                        groupEnabled = visibleCongregationId != null || uiState.selectedCongregationId != null,
                        capturingLocation = uiState.isCapturingLocation,
                        locationError = uiState.locationError,
                        onUseCurrentLocation = {
                            if (viewModel.hasLocationPermission()) viewModel.captureLocation()
                            else locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        },
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
                        Text("Create Publisher Account")
                    }
                }
            }
        }
    }
}

// CongregationDropdown (Super-Admin only) is already defined in
// AdminEnrollmentScreen.kt — same package, same exact shape needed here, so
// it's reused as-is rather than duplicated. RoleCheckboxRow is defined in
// CoordinatorElderEnrollmentScreen.kt, same package, reused the same way.
