package com.emfitsolutions.gopreach.ui.screens.voicenotes

import com.emfitsolutions.gopreach.ui.components.ActionButton
import com.emfitsolutions.gopreach.ui.components.StandardSearchField
import com.emfitsolutions.gopreach.ui.components.ActionKind
import com.emfitsolutions.gopreach.ui.components.ActionIconButton
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.VoiceNote
import com.emfitsolutions.gopreach.data.model.VoiceNoteStatus
import com.emfitsolutions.gopreach.data.model.VoiceStep
import com.emfitsolutions.gopreach.domain.VoiceNoteLogic
import com.emfitsolutions.gopreach.ui.components.AlertDialog
import com.emfitsolutions.gopreach.ui.components.ListenPanel
import com.emfitsolutions.gopreach.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Yellow = Color(0xFFF2B705)
private val stamp = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())

private fun statusColor(s: VoiceNoteStatus): Color = when (s) {
    VoiceNoteStatus.LOCAL_DRAFT -> Color(0xFF9E9E9E)
    VoiceNoteStatus.WAITING -> Color(0xFFEF6C00)
    VoiceNoteStatus.TRANSCRIBING, VoiceNoteStatus.GENERATING -> Color(0xFF1E88E5)
    VoiceNoteStatus.TRANSCRIBED -> Color(0xFF00897B)
    VoiceNoteStatus.COMPLETE -> Color(0xFF2E7D32)
    VoiceNoteStatus.FAILED -> Color(0xFFD32F2F)
}

/**
 * Voice Notes & Meeting Minutes: record a voice note, turn it into a transcript and organised minutes, read them, and listen to the original recording or
 * to the minutes. One screen with its own small navigation (Home, Record, My Voice Notes, a note's details); Back steps up one level.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceNotesScreen(personId: String, congregationId: String, onBack: () -> Unit, viewModel: VoiceNotesViewModel = koinViewModel()) {
    val notes by remember(personId) { viewModel.notes(personId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val online by viewModel.online.collectAsStateWithLifecycle()
    LaunchedEffect(personId) { viewModel.start(personId) }

    var page by rememberSaveable { mutableStateOf("home") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var openTab by rememberSaveable { mutableStateOf(0) }
    fun go(to: String, id: String? = null, tab: Int = 0) { page = to; openId = id; openTab = tab }
    BackHandler(enabled = page != "home") { if (page == "detail") go("library") else go("home") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (page == "home") "Voice Notes & Meeting Minutes" else if (page == "record") "Record Voice" else if (page == "library") "My Voice Notes" else "Voice Note") },
                navigationIcon = { IconButton(onClick = { if (page == "home") onBack() else if (page == "detail") go("library") else go("home") }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!online) {
                Row(Modifier.fillMaxWidth().background(Color(0xFFEF6C00).copy(alpha = 0.18f)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = Color(0xFFEF6C00), modifier = Modifier.size(18.dp))
                    Text("  Offline - recordings are saved on this phone and will be processed when you are online.", style = MaterialTheme.typography.bodySmall)
                }
            }
            when (page) {
                "record" -> RecorderPage(personId, congregationId, viewModel, onSaved = { id, tab -> go("detail", id, tab) })
                "library" -> LibraryPage(notes, online, viewModel, onOpen = { id, tab -> go("detail", id, tab) })
                "detail" -> {
                    val note = notes.firstOrNull { it.id == openId }
                    if (note == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This voice note is no longer available.") }
                    else DetailPage(note, online, viewModel, initialTab = openTab, onDeleted = { go("library") })
                }
                else -> HomePage(notes, online, onRecord = { go("record") }, onLibrary = { go("library") }, onPending = { viewModel.runPendingNow(personId); go("library") })
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------------------------ Home

@Composable
private fun HomePage(notes: List<VoiceNote>, online: Boolean, onRecord: () -> Unit, onLibrary: () -> Unit, onPending: () -> Unit) {
    val pending = notes.count { VoiceNoteLogic.isPending(it) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Record once, then read it, search it, and listen to it again.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BigCard(Icons.Rounded.Mic, "Record Voice", "Start a new voice note", Yellow, onRecord, primary = true)
        BigCard(Icons.AutoMirrored.Rounded.Notes, "My Voice Notes", if (notes.isEmpty()) "No notes yet" else "${notes.size} note${if (notes.size == 1) "" else "s"}", Color(0xFF1E88E5), onLibrary)
        BigCard(
            Icons.Rounded.Sync, "Pending Transcriptions",
            if (pending == 0) "Nothing waiting" else "$pending waiting" + if (online) " - tap to process now" else " - will run when you are online",
            Color(0xFFEF6C00), onPending,
        )
    }
}

@Composable
private fun BigCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, tint: Color, onClick: () -> Unit, primary: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), tint).clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(if (primary) 64.dp else 52.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(if (primary) 34.dp else 26.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = if (primary) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------- Record

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecorderPage(personId: String, congregationId: String, viewModel: VoiceNotesViewModel, onSaved: (String, Int) -> Unit) {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context, viewModel.store) }
    val player = remember { VoicePlayer(context) }
    var draftId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftSeconds by rememberSaveable { mutableStateOf(0) }
    var title by rememberSaveable { mutableStateOf("") }
    var denied by remember { mutableStateOf(false) }
    var confirmRerecord by remember { mutableStateOf(false) }

    fun begin() {
        val id = viewModel.newNoteId()
        if (recorder.start(id)) { draftId = null; player.release() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) { denied = false; begin() } else denied = true }
    fun requestRecord() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun finish() {
        val id = recorder.noteId
        val seconds = recorder.stop()
        if (id != null && seconds != null) {
            draftId = id; draftSeconds = seconds
            if (title.isBlank()) title = "Voice note - " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
            player.load(viewModel.store.file(id), null)
        }
    }

    // The timer and level meter; also pauses recording whenever the screen leaves the foreground (never records in the background).
    LaunchedEffect(Unit) { while (true) { if (recorder.tick()) finish(); player.tick(); kotlinx.coroutines.delay(100) } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) { recorder.pause(); player.pause() } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.release(); if (recorder.state != RecordingState.IDLE) recorder.stop().also { id -> if (id == null) recorder.discard() } }
    }
    // A recording that was being made when the person left is kept as an unsaved file only if it was stopped properly; otherwise it is discarded above.

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Recording starts only when you tap Record, and the red indicator below shows while the microphone is on. Nothing is uploaded until you choose Transcribe.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (denied) {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), Color(0xFFD32F2F)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Microphone access is off", fontWeight = FontWeight.Bold)
                Text("GoPreach needs the microphone only to record your voice note. You can allow it in the phone's settings for this app.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }) { Text("Open settings") }
            }
        }
        recorder.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        val recording = recorder.state != RecordingState.IDLE
        if (recording || draftId == null) {
            RecordingIndicator(recorder)
            Text(VoiceNoteLogic.clock((recorder.elapsedMs / 1000).toInt()), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            LevelBars(recorder.levels.value)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                when (recorder.state) {
                    RecordingState.IDLE -> BigRound(Icons.Rounded.Mic, "Record", Yellow) { requestRecord() }
                    RecordingState.RECORDING -> BigRound(Icons.Rounded.Pause, "Pause", Color(0xFF1E88E5)) { recorder.pause() }
                    RecordingState.PAUSED -> BigRound(Icons.Rounded.Mic, "Resume", Yellow) { recorder.resume() }
                }
                if (recording) BigRound(Icons.Rounded.Stop, "Stop", Color(0xFFD32F2F)) { finish() }
            }
        } else {
            val id = draftId!!
            Text("Your recording (${VoiceNoteLogic.clock(draftSeconds)})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            PlayerCard(player)
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            fun save(then: (String) -> Unit) {
                viewModel.saveRecording(id, personId, congregationId, draftSeconds, title.trim().ifBlank { "Voice note" })
                then(id)
            }
            Button(onClick = { save { viewModel.transcribe(it); onSaved(it, 1) } }, colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Color.Black), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Transcribe Recording", fontWeight = FontWeight.Bold) }
            Button(onClick = { save { viewModel.generateMinutes(it, "detailed"); onSaved(it, 2) } }, colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Color.Black), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Generate Meeting Minutes", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = { save { onSaved(it, 0) } }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Save, contentDescription = null); Text("  Save Draft") }
            TextButton(onClick = { confirmRerecord = true }) { Icon(Icons.Rounded.Replay, contentDescription = null); Text("  Re-record") }
            Text("Transcribing and minutes use an online service. If you are offline they wait and run when you are connected.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmRerecord) {
        AlertDialog(
            onDismissRequest = { confirmRerecord = false },
            title = { Text("Discard this recording?") },
            text = { Text("The recording you just made is deleted and you start again.") },
            confirmButton = { TextButton(onClick = { confirmRerecord = false; draftId?.let { viewModel.store.delete(it) }; player.release(); draftId = null; title = "" }) { Text("Discard and re-record", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmRerecord = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun RecordingIndicator(recorder: VoiceRecorder) {
    val pulse = rememberInfiniteTransition(label = "rec").animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "recAlpha").value
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (recorder.state) {
            RecordingState.RECORDING -> { Box(Modifier.size(14.dp).alpha(pulse).clip(CircleShape).background(Color(0xFFD32F2F))); Text("Recording - microphone is on", fontWeight = FontWeight.SemiBold, color = Color(0xFFD32F2F)) }
            RecordingState.PAUSED -> { Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFFEF6C00))); Text("Paused - microphone is off", fontWeight = FontWeight.SemiBold, color = Color(0xFFEF6C00)) }
            RecordingState.IDLE -> Text("Ready to record", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LevelBars(levels: List<Float>) {
    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        levels.forEach { l ->
            Box(Modifier.weight(1f).fillMaxHeight(0.08f + 0.92f * l).clip(RoundedCornerShape(3.dp)).background(Yellow.copy(alpha = 0.35f + 0.65f * l)))
        }
    }
}

@Composable
private fun BigRound(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(tint).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = Color.Black, modifier = Modifier.size(36.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    }
}

// --------------------------------------------------------------------------------------------------------------- Player

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerCard(player: VoicePlayer) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), Yellow).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Original recording (your own voice)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (player.state == PlayerState.PLAYING) PlayCircle(Icons.Rounded.Pause, "Pause") { player.pause() } else PlayCircle(Icons.Rounded.PlayArrow, "Play") { player.play() }
            PlayCircle(Icons.Rounded.Stop, "Stop") { player.stop() }
            Spacer(Modifier.weight(1f))
            Text(VoiceNoteLogic.clock(player.positionMs / 1000) + " / " + VoiceNoteLogic.clock(player.durationMs / 1000), style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = player.positionMs.toFloat(), onValueChange = { player.seekTo(it.toInt()) }, valueRange = 0f..(player.durationMs.coerceAtLeast(1)).toFloat(), enabled = player.durationMs > 0)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s -> FilterChip(selected = player.speed == s, onClick = { player.changeSpeed(s) }, label = { Text(if (s == 1f) "1x" else "${s}x") }) }
        }
        if (player.state == PlayerState.LOADING) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("  Loading...", style = MaterialTheme.typography.bodySmall) }
        player.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PlayCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Yellow).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, tint = Color.Black) }
}

// -------------------------------------------------------------------------------------------------------------- Library

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryPage(notes: List<VoiceNote>, online: Boolean, viewModel: VoiceNotesViewModel, onOpen: (String, Int) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var statusFilter by rememberSaveable { mutableStateOf("ALL") }
    var sort by rememberSaveable { mutableStateOf(VoiceNoteLogic.Sort.NEWEST.name) }
    var category by rememberSaveable { mutableStateOf("") }
    var range by rememberSaveable { mutableStateOf("ANY") }
    var renaming by remember { mutableStateOf<VoiceNote?>(null) }
    var deleting by remember { mutableStateOf<VoiceNote?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val now = remember { System.currentTimeMillis() }
    val categories = remember(notes) { notes.map { it.category }.filter { it.isNotBlank() }.distinct().sorted() }
    val shown = remember(notes, query, statusFilter, sort, category, range, online) {
        VoiceNoteLogic.sorted(
            notes.filter { n ->
                val st = VoiceNoteLogic.status(n, online)
                VoiceNoteLogic.matches(n, query) &&
                    (category.isBlank() || n.category == category) &&
                    (range == "ANY" || n.recordedAt >= now - (if (range == "7") 7L else 30L) * 86_400_000L) &&
                    when (statusFilter) {
                        "DRAFT" -> st == VoiceNoteStatus.LOCAL_DRAFT
                        "PENDING" -> VoiceNoteLogic.isPending(n)
                        "DONE" -> st == VoiceNoteStatus.COMPLETE || st == VoiceNoteStatus.TRANSCRIBED
                        "FAILED" -> st == VoiceNoteStatus.FAILED
                        else -> true
                    }
            },
            VoiceNoteLogic.Sort.valueOf(sort),
        )
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StandardSearchField(value = query, onValueChange = { query = it }, placeholder = "Search title, transcript or minutes", modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                listOf("ALL" to "All", "DRAFT" to "Drafts", "PENDING" to "Pending", "DONE" to "Completed", "FAILED" to "Failed").forEach { (k, l) -> FilterChip(selected = statusFilter == k, onClick = { statusFilter = k }, label = { Text(l) }) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                listOf("ANY" to "Any time", "7" to "Last 7 days", "30" to "Last 30 days").forEach { (k, l) -> FilterChip(selected = range == k, onClick = { range = k }, label = { Text(l) }) }
                categories.forEach { c -> FilterChip(selected = category == c, onClick = { category = if (category == c) "" else c }, label = { Text(c) }) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                VoiceNoteLogic.Sort.entries.forEach { s -> FilterChip(selected = sort == s.name, onClick = { sort = s.name }, label = { Text(s.label) }) }
            }
            Text("Records Found: ${shown.size}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(if (notes.isEmpty()) "No voice notes yet. Tap Record Voice on the home page." else "No voice notes match. Try clearing the search or filters.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown, key = { it.id }) { n ->
                    val st = VoiceNoteLogic.status(n, online)
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), statusColor(st)).clickable { onOpen(n.id, if (n.meetingMinutesText.isNotBlank()) 2 else 0) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(n.title.ifBlank { "Untitled voice note" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            StatusChip(st)
                        }
                        Text(stamp.format(Date(n.recordedAt)) + "  -  " + VoiceNoteLogic.clock(n.durationSeconds), style = MaterialTheme.typography.bodySmall)
                        Text("Modified " + stamp.format(Date(n.updatedAt)) + if (n.category.isNotBlank()) "  -  ${n.category}" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (n.lastError.isNotBlank()) Text(n.lastError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            QuickAction(Icons.AutoMirrored.Rounded.Notes, "Read") { onOpen(n.id, 2) }
                            QuickAction(Icons.Rounded.Headphones, "Listen") { onOpen(n.id, 0) }
                            QuickAction(Icons.Rounded.Edit, "Rename") { renaming = n }
                            QuickAction(Icons.Rounded.Delete, "Delete", MaterialTheme.colorScheme.error) { deleting = n }
                        }
                    }
                }
            }
        }
    }
    renaming?.let { n -> RenameDialog(n, onDismiss = { renaming = null }) { t, c -> viewModel.rename(n.id, t); viewModel.setCategory(n.id, c); renaming = null } }
    deleting?.let { n -> DeleteDialog(n, onDismiss = { deleting = null }) { viewModel.deleteNote(n.id) { message = it }; deleting = null } }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)); Text(" $label", color = tint, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StatusChip(s: VoiceNoteStatus) {
    val c = statusColor(s)
    Text(s.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = c, modifier = Modifier.clip(CircleShape).background(c.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 3.dp))
}

@Composable
private fun RenameDialog(n: VoiceNote, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var t by remember { mutableStateOf(n.title) }
    var c by remember { mutableStateOf(n.category) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Rename voice note") }, scrollBody = true,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = t, onValueChange = { t = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = c, onValueChange = { c = it }, label = { Text("Category or tag (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { ActionButton(ActionKind.Save, onClick = { onSave(t, c) }, label = "Save") },
        dismissButton = { ActionButton(ActionKind.Cancel, onClick = onDismiss, label = "Cancel") },
    )
}

@Composable
private fun DeleteDialog(n: VoiceNote, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Delete this voice note?") },
        text = { Text("\"${n.title.ifBlank { "Untitled voice note" }}\" - the recording, the transcript and the minutes are permanently deleted from this phone and from the server. This cannot be undone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { ActionButton(ActionKind.Cancel, onClick = onDismiss, label = "Cancel") },
    )
}

// --------------------------------------------------------------------------------------------------------------- Detail

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailPage(note: VoiceNote, online: Boolean, viewModel: VoiceNotesViewModel, initialTab: Int, onDeleted: () -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable(note.id) { mutableStateOf(initialTab) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteMessage by remember { mutableStateOf<String?>(null) }
    val st = VoiceNoteLogic.status(note, online)
    val busy = VoiceNoteLogic.isPending(note) && st != VoiceNoteStatus.FAILED

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(note.title.ifBlank { "Untitled voice note" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stamp.format(Date(note.recordedAt)) + "  -  " + VoiceNoteLogic.clock(note.durationSeconds), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusChip(st)
            ActionIconButton(ActionKind.Edit, onClick = { renaming = true }, contentDescription = "Rename")
            ActionIconButton(ActionKind.Delete, onClick = { deleting = true }, contentDescription = "Delete")
        }
        if (busy || note.lastError.isNotBlank()) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (online && st != VoiceNoteStatus.WAITING) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.CloudOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (!online) "Saved. It will be processed when you are online." else if (st == VoiceNoteStatus.WAITING) "Saved. Waiting to be processed." else st.label + "...", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.cancel(note.id) }) { Icon(Icons.Rounded.Cancel, contentDescription = null, modifier = Modifier.size(16.dp)); Text(" Cancel") }
                }
                if (note.lastError.isNotBlank()) {
                    Text(note.lastError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (!busy || st == VoiceNoteStatus.FAILED) TextButton(onClick = { viewModel.retry(note.id) }) { Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(16.dp)); Text(" Retry") }
                }
            }
        }
        TabRow(selectedTabIndex = tab) {
            listOf("Original Audio", "Transcript", "Meeting Minutes").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) }) }
        }
        when (tab) {
            0 -> AudioTab(note, viewModel)
            1 -> TranscriptTab(note, busy, viewModel)
            else -> MinutesTab(note, busy, viewModel)
        }
    }
    if (renaming) RenameDialog(note, onDismiss = { renaming = false }) { t, c -> viewModel.rename(note.id, t); viewModel.setCategory(note.id, c); renaming = false }
    if (deleting) DeleteDialog(note, onDismiss = { deleting = false }) { deleting = false; viewModel.deleteNote(note.id) { msg -> if (msg == null) onDeleted() else deleteMessage = msg } }
    deleteMessage?.let { m -> AlertDialog(onDismissRequest = { deleteMessage = null }, title = { Text("Not deleted yet") }, text = { Text(m) }, confirmButton = { ActionButton(ActionKind.Save, onClick = { deleteMessage = null }, label = "OK") }) }
}

@Composable
private fun AudioTab(note: VoiceNote, viewModel: VoiceNotesViewModel) {
    val context = LocalContext.current
    val player = remember(note.id) { VoicePlayer(context) }
    LaunchedEffect(note.id, note.audioUrl) { player.load(viewModel.store.file(note.id), note.audioUrl) }
    LaunchedEffect(note.id) { while (true) { player.tick(); kotlinx.coroutines.delay(150) } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, note.id) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.release() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlayerCard(player)
        Text(
            "This is your own voice exactly as recorded. It is never changed by the transcript or the minutes, even if you edit them or the AI steps fail.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!viewModel.store.exists(note.id) && note.audioUploaded) Text("This recording is not stored on this phone; it plays from your private copy on the server.", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TranscriptTab(note: VoiceNote, busy: Boolean, viewModel: VoiceNotesViewModel) {
    var editing by rememberSaveable(note.id) { mutableStateOf(false) }
    var text by rememberSaveable(note.id, note.transcriptText) { mutableStateOf(note.transcriptText) }
    var find by rememberSaveable(note.id) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (note.transcriptText.isBlank() && !editing) {
            Text("No transcript yet.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { viewModel.transcribe(note.id) }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Color.Black), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Transcribe Recording", fontWeight = FontWeight.Bold) }
            Text("Taglish (English and Filipino mixed) is supported. If you are offline it waits and runs when you are connected.", style = MaterialTheme.typography.labelSmall)
        } else {
            if (editing) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Transcript") }, modifier = Modifier.fillMaxWidth().height(320.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(ActionKind.Save, onClick = { viewModel.saveTranscript(note.id, text); editing = false }, label = "Save")
                    ActionButton(ActionKind.Cancel, onClick = { text = note.transcriptText; editing = false }, label = "Cancel")
                }
            } else {
                OutlinedTextField(value = find, onValueChange = { find = it }, label = { Text("Find in transcript") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (find.isNotBlank()) Text("${countOf(note.transcriptText, find)} match(es)", style = MaterialTheme.typography.labelMedium)
                Text(highlight(note.transcriptText, find), style = MaterialTheme.typography.bodyLarge)
                ExportRow(note.transcriptText, note.title)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, contentDescription = null); Text("  Edit transcript") }
                    OutlinedButton(onClick = { viewModel.generateMinutes(note.id, note.minutesDetail) }, enabled = !busy) { Text("Generate Minutes") }
                }
                Text("Words marked [inaudible] or [unclear] could not be heard clearly - correct them from the audio if you can.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MinutesTab(note: VoiceNote, busy: Boolean, viewModel: VoiceNotesViewModel) {
    var editing by rememberSaveable(note.id) { mutableStateOf(false) }
    var text by rememberSaveable(note.id, note.meetingMinutesText) { mutableStateOf(note.meetingMinutesText) }
    var detail by rememberSaveable(note.id) { mutableStateOf(note.minutesDetail) }
    var find by rememberSaveable(note.id) { mutableStateOf("") }
    var confirmRegenerate by remember { mutableStateOf(false) }
    var showTranscript by rememberSaveable(note.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Length", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = detail == "short", onClick = { detail = "short" }, label = { Text("Short summary") })
            FilterChip(selected = detail == "detailed", onClick = { detail = "detailed" }, label = { Text("Detailed summary") })
        }
        if (note.meetingMinutesText.isBlank()) {
            Button(onClick = { viewModel.generateMinutes(note.id, detail) }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Color.Black), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Generate Meeting Minutes", fontWeight = FontWeight.Bold) }
            Text(if (note.transcriptText.isBlank()) "This transcribes the recording first, then writes the minutes." else "The minutes are written only from your transcript.", style = MaterialTheme.typography.labelSmall)
        } else if (editing) {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Meeting minutes") }, modifier = Modifier.fillMaxWidth().height(360.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(ActionKind.Save, onClick = { viewModel.saveMinutes(note.id, text); editing = false }, label = "Save")
                ActionButton(ActionKind.Cancel, onClick = { text = note.meetingMinutesText; editing = false }, label = "Cancel")
            }
        } else {
            Text("AI-generated summary of your recording. It is not a replacement for the original audio or the transcript.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = find, onValueChange = { find = it }, label = { Text("Find in minutes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (find.isNotBlank()) Text("${countOf(note.meetingMinutesText, find)} match(es)", style = MaterialTheme.typography.labelMedium)
            VoiceNoteLogic.titleFromMinutes(note.meetingMinutesText)?.let { Text(highlight(it, find), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            VoiceNoteLogic.sections(note.meetingMinutesText).forEach { s ->
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.heading?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                    Text(highlight(s.body.replace("**", ""), find), style = MaterialTheme.typography.bodyMedium)
                }
            }
            ListenPanel(script = remember(note.meetingMinutesText) { VoiceNoteLogic.spoken(note.meetingMinutesText) }, label = "Listen to the minutes (computer voice)")
            Text("The spoken minutes use a computer voice that reads the text above. They are not your own recording.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ExportRow(note.meetingMinutesText, note.title)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, contentDescription = null); Text("  Edit") }
                OutlinedButton(onClick = { confirmRegenerate = true }, enabled = !busy) { Icon(Icons.Rounded.Replay, contentDescription = null); Text("  Regenerate") }
                if (note.previousMinutesText.isNotBlank()) ActionButton(ActionKind.Refresh, onClick = { viewModel.restorePreviousMinutes(note.id) }, label = "Restore previous version")
            }
            if (note.transcriptText.isNotBlank()) {
                TextButton(onClick = { showTranscript = !showTranscript }) { Text(if (showTranscript) "Hide original transcript" else "Show original transcript beside the minutes") }
                if (showTranscript) Text(note.transcriptText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(12.dp)).padding(12.dp))
            }
        }
    }
    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("Regenerate the minutes?") },
            text = { Text("New minutes are written from your current transcript (${if (detail == "short") "short" else "detailed"}). Your current minutes, including any edits, are kept and you can restore them with \"Restore previous version\".") },
            confirmButton = { TextButton(onClick = { confirmRegenerate = false; viewModel.generateMinutes(note.id, detail) }) { Text("Regenerate") } },
            dismissButton = { ActionButton(ActionKind.Cancel, onClick = { confirmRegenerate = false }, label = "Cancel") },
        )
    }
}

@Composable
private fun ExportRow(text: String, title: String) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { copyText(context, text) }) { Icon(Icons.Rounded.ContentCopy, contentDescription = null); Text("  Copy") }
        OutlinedButton(onClick = { shareText(context, title, text) }) { Icon(Icons.Rounded.Share, contentDescription = null); Text("  Share") }
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("GoPreach voice note", text))
    android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, title); putExtra(Intent.EXTRA_TEXT, text) }
    context.startActivity(Intent.createChooser(send, "Share"))
}

private fun countOf(text: String, find: String): Int = if (find.isBlank()) 0 else Regex(Regex.escape(find), RegexOption.IGNORE_CASE).findAll(text).count()

@Composable
private fun highlight(text: String, find: String) = buildAnnotatedString {
    if (find.isBlank()) { append(text); return@buildAnnotatedString }
    var i = 0
    Regex(Regex.escape(find), RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
        append(text.substring(i, m.range.first))
        withStyle(SpanStyle(background = Yellow, color = Color.Black, fontWeight = FontWeight.Bold)) { append(m.value) }
        i = m.range.last + 1
    }
    append(text.substring(i))
}
