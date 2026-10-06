package com.emfitsolutions.gopreach.ui.screens.controlpanel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.emfitsolutions.gopreach.data.repository.ThemePreference
import com.emfitsolutions.gopreach.ui.components.ThemeOptionRow
import com.emfitsolutions.gopreach.ui.components.rememberActionToast

/**
 * Control Panel — Super-Admin only (spec §5.1). Logo upload/replace lands here
 * first; other system settings join it as later phases need a home for them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlPanelScreen(
    currentPersonId: String,
    /** Spec §1: the logo specifically is Super-Admin-only, even though the wider
     * Control Panel module is also reachable (read-only here) by an Admin scoped
     * to their own congregation (spec §3 permission matrix). */
    canManageLogo: Boolean,
    onBack: () -> Unit,
    viewModel: ControlPanelViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.uploadLogo(uri, currentPersonId)
    }

    val showToast = rememberActionToast()
    var wasUploading by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isUploading, uiState.errorMessage) {
        if (wasUploading && !uiState.isUploading && uiState.errorMessage == null) showToast("Logo updated.")
        wasUploading = uiState.isUploading
    }
    LaunchedEffect(uiState.orphanCleanupResult) {
        val count = uiState.orphanCleanupResult ?: return@LaunchedEffect
        showToast(if (count == 0) "No orphaned records found." else "Removed $count orphaned record${if (count == 1) "" else "s"}.")
        viewModel.orphanCleanupResultShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Control Panel") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("App Logo", style = MaterialTheme.typography.titleMedium)
            Text(
                "Shown on the login screen and every dashboard banner across the app.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (settings.logoUrl != null) {
                AsyncImage(
                    model = settings.logoUrl,
                    contentDescription = "Current app logo",
                    modifier = Modifier.size(96.dp).align(Alignment.CenterHorizontally),
                )
            } else {
                Text("No custom logo uploaded yet — using the default mark.", style = MaterialTheme.typography.bodySmall)
            }

            if (uiState.errorMessage != null) {
                Text(text = uiState.errorMessage!!, color = MaterialTheme.colorScheme.error)
            }

            if (canManageLogo) {
                Button(
                    onClick = { pickImage.launch("image/*") },
                    enabled = !uiState.isUploading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (uiState.isUploading) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(if (settings.logoUrl != null) "Replace Logo" else "Upload Logo")
                }
            } else {
                Text(
                    "Only a Super-Admin can change the app logo.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your own display preference — applies instantly, only on this device.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    ThemeOptionRow("System default", ThemePreference.SYSTEM, theme, viewModel::setTheme)
                    ThemeOptionRow("Light", ThemePreference.LIGHT, theme, viewModel::setTheme)
                    ThemeOptionRow("Dark", ThemePreference.DARK, theme, viewModel::setTheme)
                }
            }

            if (canManageLogo) {
                Text("Data Maintenance", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Permanently deletes any Elder/Publisher/Ministerial role record left over from a " +
                        "deleted person — these no longer count toward Total Elders or any other dashboard " +
                        "card, but removing them here clears them out for good.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { viewModel.cleanUpOrphanedRoleAssignments(currentPersonId) },
                    enabled = !uiState.isCleaningUpOrphans,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (uiState.isCleaningUpOrphans) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(20.dp))
                    }
                    Text("Clean Up Orphaned Role Records")
                }
            }
        }
    }
}
