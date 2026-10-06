package com.emfitsolutions.gopreach.ui.screens.account

import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.ui.components.PublisherFormFields
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.PreachingDay

/** A password field with a show/hide toggle — without this, a typo the user
 * can't see is indistinguishable from a genuinely wrong password, which is
 * exactly what was causing "password is correct but it says incorrect" here:
 * the field looked wrong when it wasn't, or vice versa, because it was always
 * masked with no way to check. */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password",
                )
            }
        },
        modifier = modifier,
    )
}

/** Spec §1 — "Edit Account / Account Settings" screen: change username (with
 * uniqueness + current-password checks) and change password (current/new/
 * confirm + requirement validation), both hashed/stored by Firebase Auth
 * itself, never in plaintext by this app. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AccountSettingsScreen(
    onBack: () -> Unit,
    onSignedOutForPasswordChange: () -> Unit,
    // "Add a module to the Publisher Account/Profile" — only a Publisher
    // sees the Preaching Availability section below; every other role
    // reaches this exact same screen for name/username/password unchanged.
    isPublisher: Boolean = false,
    // "This will be visible in other publisher account" — opens
    // PublisherSchedulesScreen; null (default) hides the link entirely,
    // same pattern as isPublisher gating the section above it.
    onViewPublisherSchedules: (() -> Unit)? = null,
    viewModel: AccountSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.passwordChanged) {
        if (uiState.passwordChanged) onSignedOutForPasswordChange()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account Settings") },
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
            Text("My Information", style = MaterialTheme.typography.titleMedium)
            Text(
                "Keep your details up to date. Your role, category, group and status are managed by your administrators.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val locationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) { granted -> if (granted) viewModel.captureLocation() }
            PublisherFormFields(
                form = uiState.profile,
                onChange = viewModel::onProfileChange,
                groups = emptyList(),
                profileOnly = true,
                capturingLocation = uiState.capturingLocation,
                locationError = uiState.locationError,
                onUseCurrentLocation = {
                    if (viewModel.hasLocationPermission()) viewModel.captureLocation()
                    else locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                },
            )
            if (uiState.profileError != null) Text(uiState.profileError!!, color = MaterialTheme.colorScheme.error)
            if (uiState.profileMessage != null) Text(uiState.profileMessage!!, color = MaterialTheme.colorScheme.primary)
            Button(onClick = viewModel::saveProfile, enabled = !uiState.isSavingProfile, modifier = Modifier.fillMaxWidth()) {
                if (uiState.isSavingProfile) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text("Save My Information")
            }

            // "Add Module: Preaching Availability" — Publisher-only. Saved
            // straight onto this account's own shared Person record (see
            // that field's own doc comment on why that already satisfies
            // "This will be visible in other publisher account"), and read
            // by AssignPublisherDialog when a Service Overseer/Admin/
            // Super-Admin is choosing who to send a House Holder Assignment
            // to.
            if (isPublisher) {
                HorizontalDivider()
                Text("Preaching Availability", style = MaterialTheme.typography.titleMedium)
                Text("Available Days for Preaching", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreachingDay.entries.forEach { day ->
                        FilterChip(
                            selected = day in uiState.availableDays,
                            onClick = { viewModel.toggleAvailableDay(day) },
                            label = { Text(day.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = uiState.availabilityRemarks,
                    onValueChange = viewModel::onAvailabilityRemarksChange,
                    label = { Text("Remarks") },
                    placeholder = { Text("Preferred schedule, limitations, or other notes") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (uiState.availabilityError != null) Text(uiState.availabilityError!!, color = MaterialTheme.colorScheme.error)
                if (uiState.availabilityMessage != null) Text(uiState.availabilityMessage!!, color = MaterialTheme.colorScheme.primary)
                Button(onClick = viewModel::saveAvailability, enabled = !uiState.isSavingAvailability, modifier = Modifier.fillMaxWidth()) {
                    if (uiState.isSavingAvailability) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("Save Availability")
                }
                if (onViewPublisherSchedules != null) {
                    OutlinedButton(onClick = onViewPublisherSchedules, modifier = Modifier.fillMaxWidth()) {
                        Text("View Other Publishers' Schedules")
                    }
                }
            }

            HorizontalDivider()

            Text("Change Username", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = uiState.newUsername,
                onValueChange = viewModel::onNewUsernameChange,
                label = { Text("New Username") },
                singleLine = true,
                // Plain text, never masked — same "visible password" trick
                // LoginScreen's Username field uses: KeyboardType.Password
                // is the only reliable way to stop every IME's "auto-space
                // after a period" behavior (autoCorrectEnabled = false alone
                // doesn't cover it on several keyboards), so a dotted
                // username like "user.test" can never silently become
                // "user. test" while typing it here either.
                visualTransformation = VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(
                value = uiState.currentPasswordForUsername,
                onValueChange = viewModel::onCurrentPasswordForUsernameChange,
                label = "Current Password",
                modifier = Modifier.fillMaxWidth(),
            )
            if (uiState.usernameError != null) Text(uiState.usernameError!!, color = MaterialTheme.colorScheme.error)
            if (uiState.usernameMessage != null) Text(uiState.usernameMessage!!, color = MaterialTheme.colorScheme.primary)
            Button(onClick = viewModel::saveUsername, enabled = !uiState.isSavingUsername, modifier = Modifier.fillMaxWidth()) {
                if (uiState.isSavingUsername) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text("Save Username")
            }

            HorizontalDivider()

            com.emfitsolutions.gopreach.ui.screens.login.LoginMethodsSection()

            HorizontalDivider()

            Text("Change Password", style = MaterialTheme.typography.titleMedium)
            PasswordField(
                value = uiState.currentPassword,
                onValueChange = viewModel::onCurrentPasswordChange,
                label = "Current Password",
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(
                value = uiState.newPassword,
                onValueChange = viewModel::onNewPasswordChange,
                label = "New Password",
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(
                value = uiState.confirmNewPassword,
                onValueChange = viewModel::onConfirmNewPasswordChange,
                label = "Confirm New Password",
                modifier = Modifier.fillMaxWidth(),
            )
            Text("At least 6 characters.", style = MaterialTheme.typography.bodySmall)
            if (uiState.passwordError != null) Text(uiState.passwordError!!, color = MaterialTheme.colorScheme.error)
            Button(onClick = viewModel::savePassword, enabled = !uiState.isSavingPassword, modifier = Modifier.fillMaxWidth()) {
                if (uiState.isSavingPassword) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text("Save Password")
            }
            Text(
                "Changing your password signs you out — log back in with the new one.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
