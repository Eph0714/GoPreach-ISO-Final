package com.emfitsolutions.gopreach.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.emfitsolutions.gopreach.ui.screens.circuit.ReadReportPanel
import com.emfitsolutions.gopreach.ui.screens.circuit.ReportNarrator

/**
 * "Listen": reads [script] aloud with the phone's own offline voice - Play / Pause / Stop / Read Again, speed and "Show text". [language] is the
 * language of the text (e.g. "fil", "en") so a matching installed voice is used; null = the app language. The script is replaced, and whatever was
 * playing stops, when it changes. It pauses when the app leaves the screen and releases the speech engine when it leaves the composition.
 */
@Composable
fun ListenPanel(script: List<String>, label: String, language: String? = null) {
    val context = LocalContext.current
    val narrator = remember { ReportNarrator(context, emptyList()) }
    LaunchedEffect(script, language) { narrator.load(script, language) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) narrator.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); narrator.shutdown() }
    }
    ReadReportPanel(narrator, script, label)
}

/** Settings: the voice choices for the Listen (read aloud) buttons - Weather, My Planner, My Bible Text Record and the comparative reports. Per device. */
@Composable
fun ListenSettingsSection() {
    val pref = org.koin.compose.koinInject<com.emfitsolutions.gopreach.data.repository.ListenButtonPreference>()
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.Text("Listen Voice", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        ListenVoiceChoices(pref)
    }
}

/** The voice choices: a female or male voice, and the language the app's words are read in (Automatic, English, Tagalog or Iloko). Also offered inside the player. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ListenVoiceChoices(pref: com.emfitsolutions.gopreach.data.repository.ListenButtonPreference) {
    val gender by pref.gender.collectAsStateWithLifecycle()
    val language by pref.language.collectAsStateWithLifecycle()
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        androidx.compose.material3.Text("Voice", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
        ) {
            com.emfitsolutions.gopreach.domain.VoiceGender.entries.forEach { g ->
                androidx.compose.material3.FilterChip(selected = gender == g, onClick = { pref.setGender(g) }, label = { androidx.compose.material3.Text(g.label) })
            }
        }
        androidx.compose.material3.Text("Read in", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
        ) {
            com.emfitsolutions.gopreach.domain.ListenLanguageChoice.entries.forEach { c ->
                androidx.compose.material3.FilterChip(selected = language == c, onClick = { pref.setLanguage(c) }, label = { androidx.compose.material3.Text(c.label) })
            }
        }
        androidx.compose.material3.Text(
            "Automatic reads the app's words in English and Bible texts in their own language. Tagalog and Iloko read the app's words in that language; " +
                "your phone must have a matching voice installed (Settings > Text-to-speech) or another voice is used. Android does not say which voices are male or female, " +
                "so this is matched from the voice name when possible.",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The language the app's own words are read in right now (Settings or the player): the chosen one, or English for Automatic. Callers rebuild their script with it. */
@Composable
fun rememberNarrationLanguage(): com.emfitsolutions.gopreach.domain.NarrationLanguage {
    val pref = org.koin.compose.koinInject<com.emfitsolutions.gopreach.data.repository.ListenButtonPreference>()
    val choice by pref.language.collectAsStateWithLifecycle()
    return choice.narration ?: com.emfitsolutions.gopreach.domain.NarrationLanguage.ENGLISH
}
