package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.ui.components.Scaffold
import com.emfitsolutions.gopreach.ui.screens.pipeline.HouseHolderRecordsScreen
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Circuit Overseer's House Holder Records: nothing is listed until ONE congregation is chosen (never "all"), and the choice is asked again every
 * time the module is opened from the side panel. The records shown are then those of that congregation only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircuitHouseHolderRecordsScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    people: CircuitPeopleViewModel = koinViewModel(),
) {
    // Entering the module always starts at "Select congregation" (the choice made in another module is not carried over).
    rememberSaveable { CircuitScopeStore.selectCongregation(null); true }
    val congregations by remember(currentPersonId) { people.congregations(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val selected = validCongregation(congregations, CircuitScopeStore.congregation)
    if (selected == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("House Holder Records") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                )
            },
        ) { padding ->
            SelectCongregationPrompt(congregations, onSelect = { CircuitScopeStore.selectCongregation(it) }, modifier = Modifier.padding(padding))
        }
        return
    }
    HouseHolderRecordsScreen(
        congregationIds = setOf(selected.id),
        groupId = null,
        onBack = onBack,
        onChangeCongregation = { CircuitScopeStore.selectCongregation(null) },
    )
}
