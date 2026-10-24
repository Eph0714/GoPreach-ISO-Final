package com.emfitsolutions.gopreach.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.repository.PendingLoginRequest
import com.emfitsolutions.gopreach.domain.UserSession
import com.emfitsolutions.gopreach.ui.components.AlertDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * The signed-in phone is the one that decides about a sign-in attempt from another device. While the app is open and the person is signed in, this asks
 * the server every few seconds for pending attempts and shows a centred "Is this you?" box for the newest one. Nothing is decided without a tap, and the
 * server expires a request nobody answers (it is refused, never approved). A real push notification (for when the app is closed) needs Firebase Cloud
 * Messaging, which this app does not have configured: see docs/LOGIN_SECURITY.md.
 */
class LoginRequestViewModel(
    private val authRepository: AuthRepository,
    userSession: UserSession,
) : ViewModel() {
    val isSignedIn: StateFlow<Boolean> = userSession.state.map { it.person != null }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    private val _pending = MutableStateFlow<PendingLoginRequest?>(null)
    val pending: StateFlow<PendingLoginRequest?> = _pending.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private var handled = emptySet<String>()

    suspend fun refresh() {
        val now = System.currentTimeMillis()
        _pending.value = authRepository.pendingLoginRequests()
            .filter { it.expiresAtMillis > now && it.id !in handled }
            .maxByOrNull { it.createdAtMillis }
    }

    fun decide(request: PendingLoginRequest, approve: Boolean) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            authRepository.decideLoginRequest(request.id, approve)
            handled = handled + request.id
            _pending.value = null
            _busy.value = false
        }
    }
}

@Composable
fun LoginRequestHost(viewModel: LoginRequestViewModel = koinViewModel()) {
    val signedIn by viewModel.isSignedIn.collectAsStateWithLifecycle()
    val request by viewModel.pending.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    LaunchedEffect(signedIn) {
        if (!signedIn) return@LaunchedEffect
        while (true) {
            runCatching { viewModel.refresh() }
            delay(5_000)
        }
    }
    val r = request ?: return
    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
        onDismissRequest = {},
        title = { Text("Security check", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Someone is trying to access your GoPreach account from another device. Is this you?",
                    textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                )
                r.device?.let { Text("Device: $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp)) }
                r.ip?.let { Text("Network: $it", style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Time: " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(r.createdAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "If you approve, this phone will be signed out and the other device will be signed in. If you do not recognise it, deny it: your session stays as it is.",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp),
                )
                Button(enabled = !busy, onClick = { viewModel.decide(r, true) }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) { Text("Yes, It's Me") }
                OutlinedButton(enabled = !busy, onClick = { viewModel.decide(r, false) }, modifier = Modifier.fillMaxWidth()) { Text("No, Deny Access") }
            }
        },
        confirmButton = {},
    )
}
