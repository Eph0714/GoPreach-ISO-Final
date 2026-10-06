package com.emfitsolutions.gopreach.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.repository.QuickLoginMethod
import com.emfitsolutions.gopreach.domain.formatPersonName
import com.emfitsolutions.gopreach.ui.components.PublisherFormFields
import com.emfitsolutions.gopreach.ui.components.PublisherFormState
import com.emfitsolutions.gopreach.ui.screens.login.QuickLoginSetupDialog
import com.emfitsolutions.gopreach.ui.screens.login.launchBiometricPrompt

/**
 * First-time setup, shown after signing in with temporary credentials. No back navigation is offered on purpose —
 * none of this is optional except the extra login methods at the end. See [ForcedPasswordChangeViewModel].
 */
@Composable
fun ForcedPasswordChangeScreen(
    onCompleted: () -> Unit,
    viewModel: ForcedPasswordChangeViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state.step) {
            SetupStep.LOADING, SetupStep.FINISHING -> {
                Text(if (state.step == SetupStep.FINISHING) "Finishing your account setup…" else "Loading…", style = MaterialTheme.typography.titleMedium)
                CircularProgressIndicator()
            }
            SetupStep.CREDENTIALS -> CredentialsStep(state, viewModel)
            SetupStep.PROFILE_REVIEW -> ReviewStep(state, viewModel)
            SetupStep.PROFILE_EDIT -> EditStep(state, viewModel)
            SetupStep.METHODS -> MethodsStep(state, viewModel)
            SetupStep.NEEDS_ONLINE -> {
                Text("Connect to Finish Setup", style = MaterialTheme.typography.headlineSmall)
                Text(state.errorMessage ?: "Your account setup needs an internet connection to finish. Connect, then log in again with your new username and password.")
                Button(onClick = viewModel::signOutNow, modifier = Modifier.fillMaxWidth()) { Text("Back to Login") }
            }
        }
    }
}

// --- Step 1 ----------------------------------------------------------------------------------

@Composable
private fun CredentialsStep(state: FirstLoginUiState, viewModel: ForcedPasswordChangeViewModel) {
    Text("Set Your Permanent Credentials", style = MaterialTheme.typography.headlineMedium)
    Text(
        if (state.isPublisher) "You signed in with a temporary username and password. Choose your own. Next you'll check your personal details, and then log in again with your new credentials."
        else "You signed in with a temporary username and password. Choose your own before continuing — you'll need to log in again afterward.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = state.newUsername,
        onValueChange = viewModel::onUsernameChange,
        label = { Text("New Username") },
        singleLine = true,
        enabled = !state.isBusy,
        visualTransformation = VisualTransformation.None,
        // KeyboardType.Password (not .Text), despite showing plain text — the same "visible password" fix as the Login
        // screen's Username: suppresses "auto-space after a period" on every keyboard, which would silently corrupt a
        // dotted username like "user.test".
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    PasswordField(state.newPassword, viewModel::onPasswordChange, "New Password", !state.isBusy)
    PasswordField(state.confirmPassword, viewModel::onConfirmPasswordChange, "Confirm New Password", !state.isBusy)
    Text("At least 8 characters.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = viewModel::submitCredentials, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
        if (state.isBusy) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
        Text(if (state.isPublisher) "Save & Continue" else "Save & Log In Again")
    }
}

// --- Step 2: review ---------------------------------------------------------------------------

@Composable
private fun ReviewStep(state: FirstLoginUiState, viewModel: ForcedPasswordChangeViewModel) {
    val form = state.form
    Text("Check Your Information", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Please check your details carefully. Make sure all information below is correct before continuing.",
        style = MaterialTheme.typography.bodyMedium,
    )
    ReviewCard("Personal Information") {
        ReviewRow("Name", formatPersonName(form.firstName, form.middleInitial, form.lastName, form.extensionName))
        ReviewRow("First Name", form.firstName)
        ReviewRow("Middle Initial", form.middleInitial)
        ReviewRow("Last Name", form.lastName)
        ReviewRow("Extension Name", form.extensionName)
        ReviewRow("Contact", form.contact)
    }
    ReviewCard("Contact Person") {
        ReviewRow("Contact Person", form.contactPerson)
        ReviewRow("Contact Person Number", form.contactPersonNumber)
    }
    ReviewCard("Address & Location") {
        ReviewRow("Full Address", form.address)
        ReviewRow("Province", form.province.orEmpty())
        ReviewRow("Municipality", form.cityMunicipality.orEmpty())
        ReviewRow("Barangay", form.barangay.orEmpty())
        ReviewRow("Coordinates", if (form.latitudeText.isBlank() || form.longitudeText.isBlank()) "" else "${form.latitudeText}, ${form.longitudeText}")
    }
    ReviewCard("Contact Information") { ReviewRow("Email", form.email) }
    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    OutlinedButton(onClick = viewModel::startEditing, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) { Text("Edit Details") }
    Button(onClick = viewModel::askToConfirm, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
        if (state.isBusy) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
        Text("Confirm & Save")
    }

    if (state.showConfirmDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirm,
            title = { Text("Confirm Your Information") },
            text = { Text("Please make sure all your personal information is correct before continuing.") },
            confirmButton = { TextButton(onClick = viewModel::confirmAndSave) { Text("Confirm & Save") } },
            dismissButton = { TextButton(onClick = viewModel::dismissConfirm) { Text("Back / Edit") } },
        )
    }
}

@Composable
private fun ReviewCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
    }
}

// --- Step 2: edit -----------------------------------------------------------------------------

@Composable
private fun EditStep(state: FirstLoginUiState, viewModel: ForcedPasswordChangeViewModel) {
    val locationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.captureLocation() }

    Text("Edit Your Details", style = MaterialTheme.typography.headlineMedium)
    PublisherFormFields(
        form = state.form,
        onChange = viewModel::onFormChange,
        groups = emptyList(),
        profileOnly = true,
        capturingLocation = state.capturingLocation,
        locationError = state.locationError,
        onUseCurrentLocation = {
            if (viewModel.hasLocationPermission()) viewModel.captureLocation()
            else locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        },
    )
    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = viewModel::applyEdits, modifier = Modifier.fillMaxWidth()) { Text("Done Editing") }
    OutlinedButton(onClick = viewModel::cancelEditing, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

// --- Step 3: optional login methods -----------------------------------------------------------

@Composable
private fun MethodsStep(state: FirstLoginUiState, viewModel: ForcedPasswordChangeViewModel) {
    val activity = LocalContext.current as FragmentActivity
    var setting by remember { mutableStateOf<QuickLoginMethod?>(null) }

    Text("Set Up Additional Login Methods", style = MaterialTheme.typography.headlineMedium)
    Text(
        "You may choose additional secure ways to log in to GoPreach. This is optional and can be configured later from Account Management.",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (!state.canSetUpMethods) {
        Text(
            "You can turn these on later in Account Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            MethodRow(
                title = "Login with Biometrics",
                detail = if (state.deviceHasBiometrics) "Use your fingerprint or face. Nothing about it is stored in GoPreach." else "No fingerprint or face is set up on this device.",
                on = state.biometricOn,
                enabled = state.canSetUpMethods && state.deviceHasBiometrics,
                onEnable = {
                    launchBiometricPrompt(
                        activity = activity,
                        title = "Enable biometric login",
                        subtitle = "Confirm your fingerprint or face to turn it on for GoPreach",
                        negativeButtonText = "Cancel",
                        onSuccess = viewModel::enableBiometric,
                        onError = viewModel::onBiometricError,
                    )
                },
            )
            HorizontalDivider()
            MethodRow("Login with PIN", "A 6-digit PIN for this device.", state.pinOn, state.canSetUpMethods) { setting = QuickLoginMethod.PIN }
            HorizontalDivider()
            MethodRow("Login with Pattern", "An unlock pattern for this device.", state.patternOn, state.canSetUpMethods) { setting = QuickLoginMethod.PATTERN }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Login with Passkey") },
                supportingContent = { Text("Not available yet.") },
                trailingContent = { Text("Not Set Up", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
    }
    state.methodMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Button(onClick = viewModel::finishAndSignOut, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.biometricOn || state.pinOn || state.patternOn) "Continue" else "Skip for Now")
    }
    Text(
        "Next you'll be logged out and asked to log in again with your new username and password.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    setting?.let { method ->
        QuickLoginSetupDialog(
            method = method,
            onConfirmed = { secret -> viewModel.enrollQuickLogin(method, secret); setting = null },
            onDismiss = { setting = null },
        )
    }
}

@Composable
private fun MethodRow(title: String, detail: String, on: Boolean, enabled: Boolean, onEnable: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = {
            if (on) Text("Enabled", color = MaterialTheme.colorScheme.primary)
            else TextButton(onClick = onEnable, enabled = enabled) { Text("Enable") }
        },
    )
}

// ----------------------------------------------------------------------------------------------

/** Show/hide toggle: a permanently masked field with no way to check what you typed caused "password is correct but it says incorrect" reports elsewhere; this screen sets a first real password. */
@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean = true) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
