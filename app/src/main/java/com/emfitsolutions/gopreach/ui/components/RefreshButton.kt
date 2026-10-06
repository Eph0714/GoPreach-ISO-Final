package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.data.sync.DataRefresher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RefreshButtonViewModel @Inject constructor(
    private val dataRefresher: DataRefresher,
    private val connectivityObserver: ConnectivityObserver,
) : ViewModel() {
    fun refresh(onDone: (success: Boolean, offline: Boolean) -> Unit) {
        if (!connectivityObserver.isOnline()) {
            onDone(false, true)
            return
        }
        viewModelScope.launch {
            val success = dataRefresher.refreshNow()
            onDone(success, false)
        }
    }
}

/**
 * "Add a refresh button only not sync" — a plain re-fetch from the server,
 * deliberately separate from [SyncToServerButton]: this never touches the
 * local pending-upload queue, it only pulls (see [DataRefresher.refreshNow]),
 * so a Publisher who just wants to see whether anything changed elsewhere
 * doesn't also risk pushing a stale/incomplete local edit up in the same tap.
 */
@Composable
fun RefreshButton(
    modifier: Modifier = Modifier,
    viewModel: RefreshButtonViewModel = hiltViewModel(),
) {
    var isRefreshing by remember { mutableStateOf(false) }
    val showToast = rememberActionToast()

    IconButton(
        onClick = {
            if (isRefreshing) return@IconButton
            isRefreshing = true
            viewModel.refresh { success, offline ->
                isRefreshing = false
                showToast(
                    when {
                        offline -> "No internet connection"
                        success -> "Refreshed"
                        else -> "Couldn't refresh everything — check your connection"
                    },
                )
            }
        },
        modifier = modifier,
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
        }
    }
}
