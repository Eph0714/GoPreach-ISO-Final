package com.emfitsolutions.gopreach.ui.screens.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private const val PROMPT_TITLE = "Enable biometric login"
private const val PROMPT_SUBTITLE = "Confirm your fingerprint or face to turn it on for GoPreach"

/**
 * Right after a password sign-in, asks whether to enable biometric login — an explicit opt-in;
 * nothing is registered just because the device has a fingerprint or face. Mounted once at the
 * nav graph root, because the login screen is already gone by the time the session is signed in.
 */
@Composable
fun BiometricEnrollmentOfferHost(signedIn: Boolean) {
    val viewModel: BiometricOfferViewModel = hiltViewModel()
    val pending by viewModel.offer.pending.collectAsStateWithLifecycle()
    val activity = LocalContext.current as FragmentActivity

    // Never keep the password around once the session is gone.
    LaunchedEffect(signedIn) { if (!signedIn) viewModel.offer.clear() }

    if (signedIn && pending != null) {
        AlertDialog(
            onDismissRequest = viewModel.offer::clear,
            title = { Text("Enable Biometric Login?") },
            text = { Text("Sign in next time with your fingerprint or face instead of typing your password. You can turn it off later in Settings.") },
            confirmButton = {
                TextButton(onClick = {
                    launchBiometricPrompt(
                        activity = activity,
                        title = PROMPT_TITLE,
                        subtitle = PROMPT_SUBTITLE,
                        negativeButtonText = "Cancel",
                        onSuccess = viewModel.offer::confirm,
                        onError = { viewModel.offer.clear() },
                    )
                }) { Text("Enable") }
            },
            dismissButton = { TextButton(onClick = viewModel.offer::decline) { Text("Not now") } },
        )
    }
}

/** Settings → Security: turn biometric login on (password, then biometric) or off for this device. */
@Composable
fun BiometricLoginSettingsSection(showTitle: Boolean = true, viewModel: BiometricSetupViewModel = hiltViewModel()) {
    val enrolled by viewModel.enrolled.collectAsStateWithLifecycle()
    val activity = LocalContext.current as FragmentActivity
    val scope = rememberCoroutineScope()

    var showPasswordDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    if (showTitle) Text("Security", style = MaterialTheme.typography.titleMedium)
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text("Login with Biometrics") },
            supportingContent = {
                Text(
                    if (enrolled) "On for this device. You can sign in with your fingerprint or face."
                    else "Off. Turn on to sign in with your fingerprint or face on this device.",
                )
            },
            trailingContent = {
                Switch(
                    checked = enrolled,
                    onCheckedChange = { turnOn ->
                        when {
                            !turnOn -> viewModel.disable()
                            !viewModel.deviceSupportsBiometrics -> message = "This device has no fingerprint or face set up. Add one in your device settings first."
                            viewModel.accountUsername() == null -> message = "Your username couldn't be read. Sign out and sign in again, then try again."
                            else -> showPasswordDialog = true
                        }
                    },
                )
            },
        )
    }

    if (showPasswordDialog) {
        var password by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var checking by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!checking) showPasswordDialog = false },
            title = { Text("Confirm your password") },
            text = {
                Column {
                    Text("Enter your current password to turn on biometric login.")
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = !checking,
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotEmpty() && !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            val failure = viewModel.verifyPassword(password)
                            checking = false
                            if (failure != null) {
                                error = failure
                            } else {
                                val verified = password
                                showPasswordDialog = false
                                launchBiometricPrompt(
                                    activity = activity,
                                    title = PROMPT_TITLE,
                                    subtitle = PROMPT_SUBTITLE,
                                    negativeButtonText = "Cancel",
                                    onSuccess = {
                                        if (!viewModel.completeEnrollment(verified)) message = "Couldn't turn on biometric login. Please try again."
                                    },
                                    onError = { message = it },
                                )
                            }
                        }
                    },
                ) {
                    if (checking) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).padding(2.dp), strokeWidth = 2.dp)
                    Text("Continue")
                }
            },
            dismissButton = { TextButton(enabled = !checking, onClick = { showPasswordDialog = false }) { Text("Cancel") } },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Biometric Login") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
}
