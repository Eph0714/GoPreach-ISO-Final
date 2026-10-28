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
class SecurityNoticeViewModel(private val security: SecurityService, userSession: UserSession) : ViewModel() {
    val isSignedIn: StateFlow<Boolean> = userSession.state.map { it.person != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _notices = MutableStateFlow<List<SecurityEvent>>(emptyList())
    val notices: StateFlow<List<SecurityEvent>> = _notices.asStateFlow()

    /** Cheap: a session another device logged out answers 401 and the app signs itself out by itself. */
    suspend fun checkSession() = security.checkSession()

    suspend fun refresh() {
        val unseen = security.notices()
        // The server marks them seen only when the person has read them; until then the same ones come back.
        _notices.value = unseen
    }

    fun dismiss() {
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
    if (notices.isEmpty()) return
    AlertDialog(
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
