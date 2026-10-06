package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.domain.UserSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OfflineSessionBannerViewModel @Inject constructor(
    userSession: UserSession,
    connectivityObserver: ConnectivityObserver,
    private val authRepository: AuthRepository,
) : ViewModel() {

    /** Only worth showing once there's actually a connection to act on — an
     * offline-only session working offline is completely normal and
     * expected; the problem this banner exists for is specifically that
     * reconnecting never fixes it on its own (see [UserSession.SessionState
     * .isOfflineOnlySession]'s own doc comment for why). */
    val shouldShow: StateFlow<Boolean> = combine(
        userSession.state,
        connectivityObserver.observe(),
    ) { state, online -> state.isOfflineOnlySession && online }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }
}

/**
 * "I cannot see the same data on other phone" — for the specific case where
 * that device's session was ever established via [AuthRepository
 * .offlineSignIn] (signed in with no internet at the time): nothing in the
 * app can silently upgrade that into a real, syncable Firebase session later
 * — only a genuine sign-in with the actual password can, and this app never
 * stores that. Shown at the top of every role's Main Form, right alongside
 * [AlarmRingingBanner], for as long as that gap exists and the device now
 * has a real connection to actually fix it — giving the Publisher a clear,
 * actionable way out instead of a permanently, silently broken sync with no
 * visible explanation (the header's own Online/Offline badge only reflects
 * *network* connectivity, which stays "Online" through this entire state).
 */
@Composable
fun OfflineSessionBanner(viewModel: OfflineSessionBannerViewModel = hiltViewModel()) {
    val shouldShow by viewModel.shouldShow.collectAsStateWithLifecycle()
    if (!shouldShow) return

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    "Sync is unavailable on this device",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                "You signed in without an internet connection, so this device can't sync new or existing records with the server or other devices. Sign out and sign back in now that you're online to fix this.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = viewModel::signOut,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Sign Out") }
            }
        }
    }
}
