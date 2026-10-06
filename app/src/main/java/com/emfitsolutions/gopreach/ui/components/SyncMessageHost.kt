package com.emfitsolutions.gopreach.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.sync.SyncStatusCenter
import kotlinx.coroutines.flow.SharedFlow

class SyncMessageHostViewModel(
    syncStatusCenter: SyncStatusCenter,
) : ViewModel() {
    val messages: SharedFlow<String> = syncStatusCenter.messages
}

/**
 * Mounted once, app-wide (see `MainActivity`), so [SyncStatusCenter]'s system
 * notifications — "Changes saved locally...", "Synchronization completed
 * successfully.", etc. — reach the user as a plain Toast regardless of which
 * screen they're currently on, the same reasoning [rememberActionToast] uses:
 * this app has dozens of independent Scaffolds, most without a snackbarHost.
 */
@Composable
fun SyncMessageHost(viewModel: SyncMessageHostViewModel = koinViewModel()) {
    val showToast = rememberActionToast()
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> showToast(message) }
    }
}
