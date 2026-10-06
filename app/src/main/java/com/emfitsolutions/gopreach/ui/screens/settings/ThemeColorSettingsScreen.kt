package com.emfitsolutions.gopreach.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.ui.components.ColorWheelPicker
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.theme.ThemeColorOption

/**
 * "Theme Color Settings — Simplified User Experience" (spec §16-25) — one
 * screen: pick a color (wheel, eyedropper, or one of the six presets),
 * preview it, press Save. Nothing here is applied to the live app theme
 * until Save is pressed (spec §18/§20) — [pendingOption]/[pendingCustom] are
 * plain Compose state, never written to [com.emfitsolutions.gopreach.data
 * .repository.ThemePreferenceRepository] until then, so leaving this screen
 * (Back, process death, anything) without saving silently discards them and
 * the previously-saved color is exactly what reopening this screen shows
 * (spec §20/§21) — there is nothing to explicitly "cancel".
 *
 * Reuses [SettingsViewModel] (already the sole owner of this repository's
 * StateFlows) rather than a second ViewModel — spec §33: "Do not duplicate
 * ViewModels/repositories." No congregation scoping and no extra permission
 * check: this was, and remains, a per-device preference every signed-in
 * role already has (spec §24) — moving its *entry point* into the Control
 * Panel drawer section doesn't narrow or widen who can reach it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeColorSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val savedOption by viewModel.colorOption.collectAsStateWithLifecycle()
    val savedCustomColor by viewModel.customColor.collectAsStateWithLifecycle()
    val showToast = rememberActionToast()

    // Local, unsaved selection — seeded from whatever is currently saved so
    // reopening this screen previews the last-saved color, not a blank one
    // (spec §25 test 10/43).
    var pendingOption by remember(savedOption) { mutableStateOf(savedOption) }
    var pendingCustomColor by remember(savedCustomColor) { mutableStateOf(savedCustomColor) }
    val previewColor = if (pendingOption == ThemeColorOption.CUSTOM) pendingCustomColor else (pendingOption.swatch?.light ?: pendingCustomColor)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.theme_color_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.dashboard_back_cd))
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(R.string.settings_theme_color_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Selected-color preview (spec §17) — updates instantly on every
            // pick, still nothing but local state until Save.
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(previewColor, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
                Text(
                    stringResource(R.string.theme_color_settings_preview_label),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // Color wheel — the primary "select a color directly" flow
            // (spec §16). Picking a point switches the pending selection to
            // Custom.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ColorWheelPicker(
                        color = pendingCustomColor,
                        onColorChanged = { picked ->
                            pendingCustomColor = picked
                            pendingOption = ThemeColorOption.CUSTOM
                        },
                    )
                }
            }

            // Existing preset swatches (spec §6: don't remove existing
            // theme-color functionality) — an optional shortcut alongside
            // the wheel, not a required extra step.
            Text(stringResource(R.string.theme_color_settings_presets_label), style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeColorOption.entries.filter { it != ThemeColorOption.CUSTOM }.chunked(3).forEach { rowOptions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        rowOptions.forEach { option ->
                            PresetSwatch(
                                option = option,
                                selected = pendingOption == option,
                                onClick = { pendingOption = option },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - rowOptions.size) { Box(modifier = Modifier.weight(1f)) }
                    }
                }
            }

            // The one required action (spec §17/§19) — validates (a color is
            // always selected here, defaulted from the saved one), saves via
            // the existing persistence mechanism, and the live theme applies
            // itself the moment ThemePreferenceRepository's StateFlow changes
            // (see ui/theme/Theme.kt) — nothing else to wire up here.
            val savedMessage = stringResource(R.string.theme_color_settings_saved_message)
            Button(
                onClick = {
                    // Save-only-once (spec §18: "do not write repeated
                    // database updates for every movement around the color
                    // wheel") — exactly one write, whichever of the two the
                    // pending selection currently is.
                    if (pendingOption == ThemeColorOption.CUSTOM) {
                        viewModel.setCustomColor(pendingCustomColor)
                    } else {
                        viewModel.setColorOption(pendingOption)
                    }
                    showToast(savedMessage)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

@Composable
private fun PresetSwatch(
    option: ThemeColorOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(option.swatch?.light ?: Color.Gray, CircleShape)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                val tint = if ((option.swatch?.light ?: Color.Gray).luminance() < 0.5f) Color.White else MaterialTheme.colorScheme.onSurface
                Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = tint)
            }
        }
        Text(option.label, style = MaterialTheme.typography.labelSmall)
    }
}
