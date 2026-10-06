package com.emfitsolutions.gopreach.ui.screens.settings

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.AppSettings
import com.emfitsolutions.gopreach.data.repository.AppSettingsRepository
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.domain.PermissionChecker
import com.emfitsolutions.gopreach.domain.UserSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** When the user last touched the app, on the monotonic clock (which keeps
 * counting while the device sleeps, unlike uptime). Fed by
 * `MainActivity.onUserInteraction()`. */
@Singleton
class InactivityTracker @Inject constructor() {
    @Volatile
    private var lastInteraction: Long = SystemClock.elapsedRealtime()

    fun touch() {
        lastInteraction = SystemClock.elapsedRealtime()
    }

    fun idleMillis(): Long = SystemClock.elapsedRealtime() - lastInteraction
}

/** Roles that may open Session Timeout Setting — Super-Admin, every Admin and
 * every Elder. Mirrors the `appSettings` write rule in firestore.rules. */
val SESSION_TIMEOUT_MANAGER_ROLES: Set<AdminRole> = setOf(
    AdminRole.SUPER_ADMIN,
    AdminRole.ADMIN_PER_CONGREGATION,
    AdminRole.COORDINATOR_ELDER,
    AdminRole.REGULAR_ELDER,
    AdminRole.SERVICE_OVERSEER,
    AdminRole.SECRETARY,
)

@HiltViewModel
class SessionTimeoutViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val authRepository: AuthRepository,
    private val inactivityTracker: InactivityTracker,
    userSession: UserSession,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = appSettingsRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val isSignedIn: StateFlow<Boolean> = userSession.state.map { it.person != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun idleMillis(): Long = inactivityTracker.idleMillis()
    fun resetIdle() = inactivityTracker.touch()
    fun signOut() = authRepository.signOut()

    /** [onResult] gets null on success, or a message to show the user. */
    fun save(enabled: Boolean, minutes: Int, actorPersonId: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val assignments = roleAssignmentRepository.observeForPerson(actorPersonId).first()
            val role = PermissionChecker.highestAdminRole(assignments)
            if (role == null || role !in SESSION_TIMEOUT_MANAGER_ROLES) {
                onResult(PermissionChecker.NO_ACCESS_MESSAGE)
                return@launch
            }
            appSettingsRepository.saveSessionTimeout(enabled, minutes, actorPersonId)
            onResult(null)
        }
    }
}

/** App-wide inactivity watcher: signs the user out once they've been idle for
 * the configured time. Placed once at the root, next to the other app-wide
 * hosts. The periodic check handles "left the app open"; the ON_START check
 * handles "left the app in the background" (a coroutine delay doesn't tick
 * while the device sleeps, the monotonic clock does). */
@Composable
fun SessionTimeoutHost(viewModel: SessionTimeoutViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val signedIn by viewModel.isSignedIn.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val limitMillis = settings.sessionTimeoutMinutes.coerceAtLeast(AppSettings.MIN_SESSION_TIMEOUT_MINUTES) * 60_000L
    val active = signedIn && settings.sessionTimeoutEnabled

    // A fresh sign-in (or a settings change) starts a fresh idle window.
    LaunchedEffect(signedIn) { if (signedIn) viewModel.resetIdle() }

    // Shown first; the sign-out (back to the login screen) only happens once the user acknowledges it.
    var showExpiredDialog by remember { mutableStateOf(false) }
    val expire by rememberUpdatedState {
        showExpiredDialog = true
    }
    if (showExpiredDialog) {
        androidx.compose.material3.AlertDialog(
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
            onDismissRequest = {},
            title = { Text("Session Expired") },
            text = { Text("Session Expired, Please re-login") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showExpiredDialog = false
                    viewModel.signOut()
                }) { Text("OK") }
            },
        )
    }

    LaunchedEffect(active, limitMillis) {
        if (!active) return@LaunchedEffect
        while (true) {
            val remaining = limitMillis - viewModel.idleMillis()
            if (remaining <= 0) {
                expire()
                return@LaunchedEffect
            }
            delay(remaining.coerceAtMost(30_000L).coerceAtLeast(1_000L))
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, active, limitMillis) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && active && viewModel.idleMillis() >= limitMillis) expire()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** "Session Timeout Setting" module. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionTimeoutSettingScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    viewModel: SessionTimeoutViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var enabled by remember(settings.sessionTimeoutEnabled) { mutableStateOf(settings.sessionTimeoutEnabled) }
    var minutesText by remember(settings.sessionTimeoutMinutes) { mutableStateOf(settings.sessionTimeoutMinutes.toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val minutes = minutesText.toIntOrNull()
    val minutesValid = minutes != null &&
        minutes in AppSettings.MIN_SESSION_TIMEOUT_MINUTES..AppSettings.MAX_SESSION_TIMEOUT_MINUTES

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session Timeout Setting") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Automatically log users out after a period of no activity. They will have to log in again. " +
                    "This applies to everyone using the app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Enable session timeout", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it; message = null })
            }
            OutlinedTextField(
                value = minutesText,
                onValueChange = { value -> minutesText = value.filter { it.isDigit() }.take(3); message = null },
                label = { Text("Minutes of inactivity before logout") },
                enabled = enabled,
                singleLine = true,
                isError = enabled && !minutesValid,
                supportingText = {
                    Text("Between ${AppSettings.MIN_SESSION_TIMEOUT_MINUTES} and ${AppSettings.MAX_SESSION_TIMEOUT_MINUTES} minutes (default ${AppSettings.DEFAULT_SESSION_TIMEOUT_MINUTES}).")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = {
                    // A disabled timer's minutes field is greyed out, so keep whatever is stored.
                    val toSave = if (enabled) minutes else settings.sessionTimeoutMinutes
                    if (toSave == null || (enabled && !minutesValid)) {
                        message = "Enter a whole number of minutes from ${AppSettings.MIN_SESSION_TIMEOUT_MINUTES} to ${AppSettings.MAX_SESSION_TIMEOUT_MINUTES}."
                        return@Button
                    }
                    viewModel.save(enabled, toSave, currentPersonId) { error ->
                        if (error == null) Toast.makeText(context, "Session timeout setting saved.", Toast.LENGTH_SHORT).show()
                        else message = error
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
