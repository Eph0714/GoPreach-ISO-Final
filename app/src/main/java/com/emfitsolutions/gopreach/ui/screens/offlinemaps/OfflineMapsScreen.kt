package com.emfitsolutions.gopreach.ui.screens.offlinemaps

import com.emfitsolutions.gopreach.ui.components.ActionKind
import com.emfitsolutions.gopreach.ui.components.ActionButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.BuildConfig
import com.emfitsolutions.gopreach.data.offlinemap.OfflineMaps
import com.emfitsolutions.gopreach.platform.rememberFilePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private fun mb(bytes: Long) = "%.0f MB".format(bytes / 1_048_576.0)

/**
 * "Offline Maps" - manage the Philippines street map used when there is no internet. Open to every signed-in role, since
 * every role can open a map. See [OfflineMaps] for how the pack is stored and why it is OpenStreetMap-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(OfflineMaps.isInstalled()) }
    var size by remember { mutableStateOf(OfflineMaps.packSizeBytes()) }
    var forced by remember { mutableStateOf(OfflineMaps.forceOffline) }
    var busy by remember { mutableStateOf<Job?>(null) }
    var done by remember { mutableStateOf(0L) }
    var total by remember { mutableStateOf(-1L) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() { installed = OfflineMaps.isInstalled(); size = OfflineMaps.packSizeBytes() }
    fun run(block: suspend () -> Unit) {
        message = null; done = 0; total = -1
        busy = scope.launch {
            try {
                block()
                message = "Offline map is ready."
            } catch (e: CancellationException) {
                message = "Stopped. Start again to continue."
            } catch (e: Exception) {
                message = "Failed: ${e.message ?: "unknown error"}"
            } finally {
                busy = null
                refresh()
            }
        }
    }
    val picker = rememberFilePicker { uri ->
        if (uri != null) run { OfflineMaps.importFrom(uri) { d, t -> done = d; total = t } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline Maps") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Philippines street map", style = MaterialTheme.typography.titleMedium)
            Text(
                if (installed) "Installed (${mb(size)}). Maps switch to it automatically when the phone has no internet."
                else "Not installed. Download it once on Wi-Fi to open the Territory Map and capture GPS without internet.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Offline maps are the OpenStreetMap street map without satellite photos. GPS works without internet either way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val job = busy
            if (job != null) {
                if (total > 0) {
                    LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(if (total > 0) "${mb(done)} of ${mb(total)}" else mb(done), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { job.cancel() }) { Text("Stop") }
            } else {
                val url = BuildConfig.OFFLINE_MAP_URL
                if (url.isNotBlank()) {
                    val partial = OfflineMaps.partialBytes()
                    Button(onClick = { run { OfflineMaps.download(url) { d, t -> done = d; total = t } } }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (installed) "Download again (update)" else if (partial > 0) "Continue download (${mb(partial)} done)" else "Download")
                    }
                }
                OutlinedButton(onClick = { picker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text("Choose a .pmtiles file from this phone") }
                if (installed) {
                    ActionButton(ActionKind.Delete, onClick = { OfflineMaps.delete(); refresh() }, label = "Delete offline map", modifier = Modifier.fillMaxWidth())
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            if (installed) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Always use the offline map", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Even when the phone is online. Useful on a weak signal.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = forced, onCheckedChange = { forced = it; OfflineMaps.forceOffline = it })
                }
            }
            Text(
                "Maps already open keep their current look; reopen the map after downloading or changing this.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
