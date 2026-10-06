package com.emfitsolutions.gopreach.ui.screens.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.repository.QuickLoginMethod
import com.emfitsolutions.gopreach.data.repository.QuickLoginStore
import com.emfitsolutions.gopreach.domain.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Setting up, changing or removing the on-device PIN and Pattern. Every set-up or change first
 * re-checks the account's current password with the server, so a stolen unlocked phone can't quietly
 * add a PIN of its own.
 */
class LoginMethodsViewModel(
    private val authRepository: AuthRepository,
    private val quickLoginStore: QuickLoginStore,
    private val userSession: UserSession,
) : ViewModel() {

    private val _enrolled = MutableStateFlow(readEnrolled())
    val enrolled: StateFlow<Set<QuickLoginMethod>> = _enrolled.asStateFlow()

    private fun readEnrolled(): Set<QuickLoginMethod> =
        QuickLoginMethod.entries.filter { runCatching { quickLoginStore.isEnrolled(it) }.getOrDefault(false) }.toSet()

    fun accountUsername(): String? = userSession.state.value.person?.username?.takeIf { it.isNotBlank() }

    /** Returns an error message, or null when [password] is this account's current password. */
    suspend fun verifyPassword(password: String): String? = authRepository.verifyCurrentPassword(password).exceptionOrNull()?.message

    fun enroll(method: QuickLoginMethod, secret: String, password: String): Boolean {
        val username = accountUsername() ?: return false
        val ok = runCatching { quickLoginStore.enroll(method, secret, username, password) }.isSuccess
        _enrolled.value = readEnrolled()
        return ok
    }

    fun disable(method: QuickLoginMethod) {
        runCatching { quickLoginStore.disable(method) }
        _enrolled.value = readEnrolled()
    }
}

/** Asks for the current password and checks it with the server before [onVerified]. */
@Composable
fun PasswordConfirmDialog(
    message: String,
    verify: suspend (String) -> String?,
    onVerified: (password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text("Confirm your password") },
        text = {
            Column {
                Text(message)
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
                        val failure = verify(password)
                        checking = false
                        if (failure != null) error = failure else onVerified(password)
                    }
                },
            ) {
                if (checking) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).padding(2.dp), strokeWidth = 2.dp)
                Text("Continue")
            }
        },
        dismissButton = { TextButton(enabled = !checking, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Account Settings → Security: every way to sign in and whether it is set up on this device. */
@Composable
fun LoginMethodsSection(viewModel: LoginMethodsViewModel = koinViewModel()) {
    val enrolled by viewModel.enrolled.collectAsStateWithLifecycle()

    // Which method is going through password check -> create -> confirm, and what's been verified so far.
    var pendingMethod by remember { mutableStateOf<QuickLoginMethod?>(null) }
    var verifiedPassword by remember { mutableStateOf<String?>(null) }
    var disabling by remember { mutableStateOf<QuickLoginMethod?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    Text("Security — Login Methods", style = MaterialTheme.typography.titleMedium)
    Text(
        "These are extra ways to sign in on this device. Each one still signs you in to your own GoPreach account through the server. Turn on only what you want; none are on until you do.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text("Username & Password") },
                supportingContent = { Text("Always on. Needed to set up, change or recover the others.") },
                trailingContent = { Text("Enabled", color = MaterialTheme.colorScheme.primary) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Passkey") },
                supportingContent = { Text("Not available yet. It needs a server component GoPreach doesn't have.") },
                trailingContent = { Text("Not Set Up", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
            QuickLoginMethod.entries.forEach { method ->
                HorizontalDivider()
                val on = method in enrolled
                ListItem(
                    headlineContent = { Text(method.label) },
                    supportingContent = {
                        Column {
                            Text(
                                if (on) "On for this device. It doesn't move to a new phone." else "Off. Sign in faster with a ${method.label.lowercase()} on this device.",
                            )
                            if (on) Text(
                                "Too many wrong tries lock it for a while, then turn it off.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingContent = {
                        Text(if (on) "Enabled" else "Not Set Up", color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                )
                Column(modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                    androidx.compose.foundation.layout.Row {
                        TextButton(onClick = {
                            if (viewModel.accountUsername() == null) message = "Your username couldn't be read. Sign out and sign in again, then try again."
                            else { pendingMethod = method; verifiedPassword = null }
                        }) { Text(if (on) "Change ${method.label}" else "Set up ${method.label}") }
                        if (on) TextButton(onClick = { disabling = method }) { Text("Disable") }
                    }
                }
            }
        }
    }

    pendingMethod?.let { method ->
        val password = verifiedPassword
        if (password == null) {
            PasswordConfirmDialog(
                message = "Enter your current password to ${if (method in enrolled) "change" else "set up"} your ${method.label}.",
                verify = viewModel::verifyPassword,
                onVerified = { verifiedPassword = it },
                onDismiss = { pendingMethod = null },
            )
        } else {
            QuickLoginSetupDialog(
                method = method,
                onConfirmed = { secret ->
                    val ok = viewModel.enroll(method, secret, password)
                    pendingMethod = null
                    verifiedPassword = null
                    message = if (ok) "Your ${method.label} is set up for this device." else "Couldn't save your ${method.label}. Please try again."
                },
                onDismiss = { pendingMethod = null; verifiedPassword = null },
            )
        }
    }

    disabling?.let { method ->
        AlertDialog(
            onDismissRequest = { disabling = null },
            title = { Text("Disable ${method.label}?") },
            text = { Text("You'll sign in with your password (or another method) instead. You can set it up again any time.") },
            confirmButton = { TextButton(onClick = { viewModel.disable(method); disabling = null }) { Text("Disable") } },
            dismissButton = { TextButton(onClick = { disabling = null }) { Text("Cancel") } },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Login Methods") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    BiometricLoginSettingsSection(showTitle = false)
}
