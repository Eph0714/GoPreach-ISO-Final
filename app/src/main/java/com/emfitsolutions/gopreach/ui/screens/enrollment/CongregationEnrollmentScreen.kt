package com.emfitsolutions.gopreach.ui.screens.enrollment

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.ui.components.LanguagesTagInput
import com.emfitsolutions.gopreach.ui.components.PhilippineAddressPicker
import com.emfitsolutions.gopreach.ui.components.rememberUnsavedChangesBackHandler

/** Spec §4.1 — Congregation Master File, Super-Admin only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CongregationEnrollmentScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: CongregationEnrollmentViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onSaved()
    }

    val hasUnsavedChanges = !uiState.saved && (
        uiState.name.isNotBlank() || !uiState.province.isNullOrBlank() || !uiState.cityMunicipality.isNullOrBlank() ||
            !uiState.barangay.isNullOrBlank() || uiState.code.isNotBlank() || uiState.languages.isNotEmpty()
        )
    val guardedBack = rememberUnsavedChangesBackHandler(
        hasUnsavedChanges,
        onDiscard = onBack,
        onSave = { viewModel.save(createdByPersonId = currentPersonId) },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Congregation") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = uiState.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Congregation Name") },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
            // "The Address must be replaced with (Province/City,
            // Municipality, Barangay)" — manual browsing via the dropdowns,
            // or tap "Use Current Location" to fill them automatically
            // (best-effort; still editable after).
            PhilippineAddressPicker(
                province = uiState.province,
                cityMunicipality = uiState.cityMunicipality,
                barangay = uiState.barangay,
                onChanged = viewModel::onAddressLevelsChanged,
                modifier = Modifier.fillMaxWidth(),
            )
            val locationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted -> if (granted) viewModel.captureLocation() }
            OutlinedButton(
                onClick = {
                    if (viewModel.hasLocationPermission()) viewModel.captureLocation()
                    else locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                },
                enabled = !uiState.isCapturingLocation,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isCapturingLocation) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("Getting current location…")
                } else {
                    Icon(Icons.Rounded.LocationOn, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Use Current Location")
                }
            }
            if (uiState.locationError != null) {
                Text(uiState.locationError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(
                value = uiState.code,
                onValueChange = viewModel::onCodeChange,
                label = { Text("Congregation Code (unique)") },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
            LanguagesTagInput(
                languages = uiState.languages,
                onAdd = viewModel::onAddLanguage,
                onRemove = viewModel::onRemoveLanguage,
                modifier = Modifier.fillMaxWidth(),
            )

            if (uiState.errorMessage != null) {
                Text(text = uiState.errorMessage!!, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = { viewModel.save(createdByPersonId = currentPersonId) },
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text("Save Congregation")
            }
        }
    }
}
