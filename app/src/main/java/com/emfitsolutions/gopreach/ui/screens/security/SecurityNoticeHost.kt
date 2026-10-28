package com.emfitsolutions.gopreach.ui.screens.security

import com.emfitsolutions.gopreach.ui.components.ActionKind
import com.emfitsolutions.gopreach.ui.components.ActionButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.repository.PendingLoginRequest
import com.emfitsolutions.gopreach.data.repository.SecurityEvent
import com.emfitsolutions.gopreach.data.repository.SecurityService
import com.emfitsolutions.gopreach.domain.UserSession
import com.emfitsolutions.gopreach.ui.components.AlertDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * Tells the signed-in person about security events of their account while the app is open: a new device signed in, a session was ended by an administrator, two-factor was
 * turned on or off, all devices were logged out. Each is shown once; "Review devices" opens Security & Device Management. A push notification for when the app is closed
 * needs Firebase Cloud Messaging, which this app does not have configured.
 */
class SecurityNoticeViewModel(private val security: SecurityService, userSession: UserSession, private val authRepository: AuthRepository) : ViewModel() {
    val isSignedIn: StateFlow<Boolean> = userSession.state.map { it.person != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _notices = MutableStateFlow<List<SecurityEvent>>(emptyList())
    val notices: StateFlow<List<SecurityEvent>> = _notices.asStateFlow()

    // ---- "Someone is trying to sign in from a new device": the signed-in device approves or denies it (the new device waits; nothing opens until an Approve) ----
    private val _requests = MutableStateFlow<List<PendingLoginRequest>>(emptyList())
    val requests: StateFlow<List<PendingLoginRequest>> = _requests.asStateFlow()
    private val decided = mutableSetOf<String>()

    suspend fun refreshRequests() {
        val now = System.currentTimeMillis()
        _requests.value = authRepository.pendingLoginRequests().filter { it.expiresAtMillis > now && it.id !in decided }.sortedByDescending { it.createdAtMillis }
    }

    fun decide(request: PendingLoginRequest, approve: Boolean) {
        decided += request.id
        _requests.value = _requests.value.filter { it.id != request.id }
        viewModelScope.launch { runCatching { authRepository.decideLoginRequest(request.id, approve) } }
    }

    /** Cheap: a session another device logged out answers 401 and the app signs itself out by itself. */
    suspend fun checkSession() = security.checkSession()

    /** Notices the person closed in this session: they never come back, even if the server is slow to mark them seen. */
    private val dismissed = mutableSetOf<String>()

    suspend fun refresh() {
        val unseen = security.notices().filter { it.id !in dismissed }
        // The server marks them seen only when the person has read them; until then the same ones come back.
        _notices.value = unseen
    }

    fun dismiss() {
        dismissed += _notices.value.map { it.id }
        _notices.value = emptyList()
        viewModelScope.launch { runCatching { security.markNoticesSeen() } }
    }
}

/** Set by the navigation graph: opens Security & Device Management from a notice. */
object SecurityNav {
    var open: (() -> Unit)? = null
}

@Composable
fun SecurityNoticeHost(onReview: () -> Unit = { SecurityNav.open?.invoke() }, viewModel: SecurityNoticeViewModel = koinViewModel()) {
    val signedIn by viewModel.isSignedIn.collectAsStateWithLifecycle()
    val notices by viewModel.notices.collectAsStateWithLifecycle()
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    LaunchedEffect(signedIn) {
        if (!signedIn) return@LaunchedEffect
        // Every 30 seconds the phone asks whether its session is still alive (a logout from another device ends it at once); the notices come every other time.
        var round = 0
        while (true) {
            runCatching { viewModel.checkSession() }
            if (round % 2 == 0) runCatching { viewModel.refresh() }
            round++
            delay(30_000)
        }
    }
    // A new device is waiting: ask first, before anything else (it expires in a few minutes).
    LaunchedEffect(signedIn) {
        if (!signedIn) return@LaunchedEffect
        while (true) {
            runCatching { viewModel.refreshRequests() }
            delay(8_000)
        }
    }
    requests.firstOrNull()?.let { r ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Is this you signing in?") },
            text = {
                Column {
                    Text("A new device entered your password and is waiting for you to approve it. Nothing opens on it until you say yes.", modifier = Modifier.padding(bottom = 8.dp))
                    r.device?.let { Text("Device: $it", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                    Text("Time: ${whenText(r.createdAtMillis)}" + (r.ip?.let { "  -  Network: $it" } ?: ""))
                    Text("If you do not recognise it, deny it and change your password.", modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.decide(r, true) }) { Text("Yes, it's me") } },
            dismissButton = { TextButton(onClick = { viewModel.decide(r, false) }) { Text("No, deny it") } },
        )
        return
    }
    if (notices.isEmpty()) return    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text("Security notice") },
        text = {
            Column {
                notices.take(5).forEach { n ->
                    Text(
                        "${whenText(n.at)} - ${SecurityEventNames[n.type] ?: n.type}" + (n.device?.let { ": $it" } ?: ""),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text("If this was not you, open Security & Device Management, log the device out and change your password.")
            }
        },
        confirmButton = { TextButton(onClick = { viewModel.dismiss(); onReview() }) { Text("Review devices") } },
        dismissButton = { ActionButton(ActionKind.Cancel, onClick = viewModel::dismiss, label = "Dismiss") },
    )
}
