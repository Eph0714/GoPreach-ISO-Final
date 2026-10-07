package com.emfitsolutions.gopreach.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.emfitsolutions.gopreach.ui.screens.account.AccountSettingsScreen

/**
 * Settings and Account Settings in one place, for every user: an **Account** tab (my information, username, sign-in methods,
 * password — and, for a Publisher, preaching availability) and a **Preferences** tab (appearance, theme colour, names, printing,
 * notification sound, data management, app version). The two used to be separate screens with separate menu entries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(
    initialTab: Int,
    onBack: () -> Unit,
    onSignedOutForPasswordChange: () -> Unit,
    isPublisher: Boolean,
    onViewPublisherSchedules: (() -> Unit)?,
    onNavigateToThemeColorSettings: () -> Unit,
    currentPersonId: String,
    showDeletedRecordsSettings: Boolean,
    onOpenDeletedRecords: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Account") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Preferences") })
            }
            if (tab == 0) {
                AccountSettingsScreen(
                    onBack = onBack,
                    onSignedOutForPasswordChange = onSignedOutForPasswordChange,
                    isPublisher = isPublisher,
                    onViewPublisherSchedules = onViewPublisherSchedules,
                    embedded = true,
                )
            } else {
                SettingsScreen(
                    onBack = onBack,
                    onNavigateToThemeColorSettings = onNavigateToThemeColorSettings,
                    currentPersonId = currentPersonId,
                    showDeletedRecordsSettings = showDeletedRecordsSettings,
                    onOpenDeletedRecords = onOpenDeletedRecords,
                    embedded = true,
                )
            }
        }
    }
}
