package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * The frame the Account and Preferences pages share: a normal screen with its own top bar, or — when they sit as tabs inside the
 * combined Settings screen ([embedded]) — just their content, with the hub's own top bar above.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsFrame(title: String, onBack: () -> Unit, embedded: Boolean, content: @Composable (PaddingValues) -> Unit) {
    if (embedded) {
        content(PaddingValues(0.dp))
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                )
            },
        ) { padding -> content(padding) }
    }
}
