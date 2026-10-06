package com.emfitsolutions.gopreach.ui.screens.territoryassignments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.koin.compose.viewmodel.koinViewModel
import com.emfitsolutions.gopreach.ui.components.map.NamedBoundary
import com.emfitsolutions.gopreach.ui.components.map.HideSystemBarsEffect
import com.emfitsolutions.gopreach.ui.components.map.OsmBoundaryMap

/** One barangay to look up a boundary for — [province]/[municipality] are
 * needed alongside [barangayName] since [TerritoryAssignmentsViewModel
 * .boundaryGeometry] looks a barangay up by all three together, the same
 * bundled-data key [BarangayBoundaryDialog] already uses for a single one. */
data class GroupTerritoryBarangay(val province: String, val municipality: String, val barangayName: String)

/**
 * "Show all Territory" next to a Field Service Group's name — every barangay
 * currently assigned to that Group, all drawn on one map in the Group's own
 * color (same [com.emfitsolutions.gopreach.ui.components.GroupColorPalette]
 * color every other Group-colored screen already uses), so a Secretary can
 * see the Group's whole territory at a glance instead of opening one
 * barangay at a time. Reuses [OsmBoundaryMap] exactly as [BarangayBoundaryDialog]
 * does, just with every barangay's boundary passed in at once instead of one —
 * no landmarks/live-location/pick-a-point here, since this view's whole
 * purpose is the overview, not planning a single visit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupTerritoryMapDialog(
    groupName: String,
    boundaryColorHex: String?,
    barangays: List<GroupTerritoryBarangay>,
    onDismiss: () -> Unit,
    viewModel: TerritoryAssignmentsViewModel = koinViewModel(),
) {
    var boundaries by remember(barangays) { mutableStateOf<List<NamedBoundary>?>(null) }
    // "If a boundary is clicked, show the single barangay view" — which
    // barangay (by name, matched back against [barangays]) the user tapped
    // on this overview map, null when none/dismissed.
    var drillDownBarangay by remember(barangays) { mutableStateOf<GroupTerritoryBarangay?>(null) }

    LaunchedEffect(barangays) {
        boundaries = barangays.mapNotNull { b ->
            viewModel.boundaryGeometry(b.province, b.municipality, b.barangayName)
                ?.let { geometryJson -> NamedBoundary(b.barangayName, geometryJson) }
        }
    }

    var fullScreen by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { if (fullScreen) fullScreen = false else onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        HideSystemBarsEffect(fullScreen)
        Scaffold(
            topBar = {
                if (!fullScreen) TopAppBar(
                    title = {
                        Column {
                            Text(groupName)
                            Text(
                                "${barangays.size} Barangay${if (barangays.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close")
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                val resolved = boundaries
                when {
                    resolved == null -> Box(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    resolved.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No boundary map available for this Group's territory yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> OsmBoundaryMap(
                        boundaries = resolved,
                        boundaryColorHex = boundaryColorHex,
                        onBoundaryClick = { name -> drillDownBarangay = barangays.find { it.barangayName == name } },
                        exportTitle = groupName,
                        fullScreen = fullScreen,
                        onFullScreenChange = { fullScreen = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    drillDownBarangay?.let { b ->
        BarangayBoundaryDialog(
            province = b.province,
            municipality = b.municipality,
            barangayName = b.barangayName,
            boundaryColorHex = boundaryColorHex,
            onDismiss = { drillDownBarangay = null },
        )
    }
}
