package com.emfitsolutions.gopreach.ui.screens.bibletext

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.emfitsolutions.gopreach.data.model.SavedVideo
import com.emfitsolutions.gopreach.data.repository.JwVideoRepository
import com.emfitsolutions.gopreach.data.repository.PlaybackSource
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What looking up a pasted link found. */
sealed interface VideoLookup {
    data class Found(val video: SavedVideo, val languageFallback: Boolean) : VideoLookup
    /** The text isn't a JW Library / jw.org video link. */
    data object NotVideoLink : VideoLookup
    /** It is a video link, but jw.org couldn't be reached or has no such video. */
    data object NotFound : VideoLookup
}

@HiltViewModel
class VideoViewModel @Inject constructor(
    private val repository: JwVideoRepository,
) : ViewModel() {
    val downloadProgress: StateFlow<Map<String, Int>> = repository.downloadProgress
    val downloaded: StateFlow<Set<String>> = repository.downloaded
    val messages: SharedFlow<String> = repository.messages

    fun keyOf(video: SavedVideo): String = repository.keyOf(video)

    /** Reads a pasted link and finds the video on jw.org, in the link's own
     * language if it names one, else [defaultLocale]. */
    suspend fun lookUp(text: String, defaultLocale: String): VideoLookup {
        val link = repository.parseLink(text) ?: return VideoLookup.NotVideoLink
        val resolved = repository.resolve(link.lank, link.jwLocale ?: defaultLocale) ?: return VideoLookup.NotFound
        return VideoLookup.Found(resolved.video, resolved.languageFallback)
    }

    suspend fun playbackSource(video: SavedVideo): PlaybackSource? = runCatching { repository.playbackSource(video) }.getOrNull()
    fun download(video: SavedVideo) = repository.startDownload(video)
    fun removeDownload(video: SavedVideo) = repository.removeDownload(video)
}

/** "12:34" / "1:02:03" for a length in seconds. */
private fun formatDuration(totalSeconds: Int): String {
    if (totalSeconds <= 0) return ""
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/**
 * The "Videos" part of the Add/Edit Bible Text form: the videos already
 * attached (each removable) and a box to paste a JW Library video link into.
 * Pasting a link — or tapping "Paste link" to take it from the clipboard —
 * looks the video up on jw.org and adds it; only its link/title are saved with
 * the record. Needs internet to add; a link that can't be looked up just shows
 * a message and nothing is added.
 */
@Composable
internal fun AddVideoSection(
    videos: List<SavedVideo>,
    onVideosChange: (List<SavedVideo>) -> Unit,
    defaultLocale: String,
    /** Show the videos already added (each removable) above the link box. */
    showExisting: Boolean = true,
    viewModel: VideoViewModel = hiltViewModel(),
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var link by remember { mutableStateOf("") }
    var isLooking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    fun addFrom(text: String) {
        if (text.isBlank() || isLooking) return
        isLooking = true
        message = null
        scope.launch {
            when (val result = viewModel.lookUp(text, defaultLocale)) {
                is VideoLookup.Found -> {
                    val video = result.video
                    if (videos.any { it.lank == video.lank && it.jwLocale == video.jwLocale }) {
                        message = "That video is already added."
                        messageIsError = false
                    } else {
                        onVideosChange(videos + video)
                        link = ""
                        message = if (result.languageFallback) "Added \"${video.title}\" (English — not available in the chosen language)." else "Added \"${video.title}\"."
                        messageIsError = false
                    }
                }
                VideoLookup.NotVideoLink -> {
                    message = "That doesn't look like a JW Library video link. In JW Library open the video, tap Share, and copy the link."
                    messageIsError = true
                }
                VideoLookup.NotFound -> {
                    message = "Couldn't find that video on jw.org. Check the link and your internet connection."
                    messageIsError = true
                }
            }
            isLooking = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showExisting) Text("Videos", style = MaterialTheme.typography.labelMedium)
        if (showExisting) videos.forEach { video ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(video.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    formatDuration(video.durationSeconds).takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { onVideosChange(videos - video) }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Remove video", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        OutlinedTextField(
            value = link,
            onValueChange = { link = it; message = null },
            label = { Text("JW Library video link") },
            placeholder = { Text("Paste the link shared from JW Library") },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { addFrom(link) }, enabled = link.isNotBlank() && !isLooking) {
                Icon(Icons.Rounded.VideoLibrary, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                Text("Add video")
            }
            TextButton(
                onClick = {
                    val copied = clipboard.getText()?.text?.trim().orEmpty()
                    if (copied.isEmpty()) {
                        message = "Nothing copied yet. In JW Library tap Share on a video and copy the link."
                        messageIsError = true
                    } else {
                        link = copied
                        addFrom(copied)
                    }
                },
                enabled = !isLooking,
            ) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                Text("Paste link")
            }
            if (isLooking) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** One video in the gallery, with what to do to remove it from wherever it is kept
 * (the event itself, or — for videos attached before events had their own
 * gallery — the Bible Text it was attached to). */
internal class GalleryVideo(val video: SavedVideo, val onRemove: () -> Unit)

/**
 * The event's videos as a gallery of boxes: each shows the video's picture
 * (tap it to play) with the title right underneath, and a row of small icon
 * buttons — download for offline use, open in JW Library, remove. Two boxes
 * per row on a phone, more on a wider screen.
 */
@Composable
internal fun VideoGallery(items: List<GalleryVideo>, viewModel: VideoViewModel = hiltViewModel()) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 200.dp).toInt().coerceIn(2, 4)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item -> VideoTile(item = item, viewModel = viewModel, modifier = Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun VideoTile(item: GalleryVideo, viewModel: VideoViewModel, modifier: Modifier = Modifier) {
    val video = item.video
    val context = LocalContext.current
    val showToast = rememberActionToast()
    val progress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloaded.collectAsStateWithLifecycle()
    val key = viewModel.keyOf(video)
    val percent = progress[key]
    var showPlayer by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black)
                .clickable { showPlayer = true },
        ) {
            if (video.thumbnailUrl.isNotBlank()) {
                AsyncImage(model = video.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(
                modifier = Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play video", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            formatDuration(video.durationSeconds).takeIf { it.isNotEmpty() }?.let {
                Text(
                    it,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        // The title sits directly under the picture.
        Text(video.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                percent != null -> Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { percent / 100f }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                key in downloaded -> IconButton(onClick = { viewModel.removeDownload(video); showToast("Offline copy removed.") }) {
                    Icon(Icons.Rounded.DownloadDone, contentDescription = "Downloaded for offline use — tap to remove the copy", tint = MaterialTheme.colorScheme.primary)
                }
                else -> IconButton(onClick = { viewModel.download(video); showToast("Downloading \"${video.title}\"…") }) {
                    Icon(Icons.Rounded.Download, contentDescription = "Download for offline use", tint = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = { if (!openVideoInJwLibrary(context, video.jwLocale, video.lank)) showToast("Couldn't open JW Library.") }) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open in JW Library", tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { confirmRemove = true }) {
                Icon(Icons.Rounded.Delete, contentDescription = "Remove video", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (showPlayer) VideoPlayerDialog(video = video, onDismiss = { showPlayer = false }, viewModel = viewModel)
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove video?") },
            text = { Text("Remove \"${video.title}\" from this event? A downloaded copy on this device is kept until you remove it.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; item.onRemove() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/** The "Add Video" dialog: paste a JW Library video link (or take it from the
 * clipboard) and the video is looked up and added to the event straight away;
 * "Done" closes it. */
@Composable
internal fun AddVideoDialog(
    videos: List<SavedVideo>,
    onVideosChange: (List<SavedVideo>) -> Unit,
    defaultLocale: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Video") },
        text = {
            AddVideoSection(videos = videos, onVideosChange = onVideosChange, defaultLocale = defaultLocale, showExisting = false)
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * Full-screen player. Plays the downloaded copy if there is one, otherwise
 * streams the video from jw.org. Uses the system [VideoView] with a simple
 * play/pause button and seek bar.
 */
@Composable
private fun VideoPlayerDialog(video: SavedVideo, onDismiss: () -> Unit, viewModel: VideoViewModel) {
    var source by remember { mutableStateOf<PlaybackSource?>(null) }
    var sourceFailed by remember { mutableStateOf(false) }
    var playbackFailed by remember { mutableStateOf(false) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var seeking by remember { mutableStateOf(false) }

    LaunchedEffect(video) {
        val found = viewModel.playbackSource(video)
        if (found == null) sourceFailed = true else source = found
    }
    LaunchedEffect(videoView, prepared, seeking) {
        while (prepared && !seeking) {
            videoView?.let {
                positionMs = it.currentPosition
                playing = it.isPlaying
            }
            delay(400)
        }
    }
    // Stop the video only when this player screen actually closes. (Keying this
    // on [videoView] would also run the cleanup the moment the view is first
    // created — which killed a downloaded video right after it started, since a
    // local file prepares faster than the recomposition.)
    DisposableEffect(Unit) { onDispose { videoView?.stopPlayback() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Text(video.title, color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White) }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val current = source
                    when {
                        sourceFailed || playbackFailed -> Text(
                            if (sourceFailed) "Couldn't load this video. It needs an internet connection unless it has been downloaded."
                            else "This video couldn't be played.",
                            color = Color.White,
                            modifier = Modifier.padding(24.dp),
                        )
                        current == null -> CircularProgressIndicator(color = Color.White)
                        else -> {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    VideoView(ctx).apply {
                                        setVideoURI(Uri.parse(current.uri))
                                        setOnPreparedListener { player ->
                                            durationMs = player.duration
                                            prepared = true
                                            start()
                                        }
                                        setOnErrorListener { _, _, _ ->
                                            playbackFailed = true
                                            true
                                        }
                                        videoView = this
                                    }
                                },
                            )
                            if (!prepared) CircularProgressIndicator(color = Color.White)
                        }
                    }
                }
                if (prepared && !playbackFailed) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        IconButton(
                            onClick = {
                                videoView?.let { view ->
                                    if (view.isPlaying) view.pause() else view.start()
                                    playing = view.isPlaying
                                }
                            },
                        ) {
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (playing) "Pause" else "Play", tint = Color.White)
                        }
                        Slider(
                            value = positionMs.toFloat(),
                            onValueChange = { seeking = true; positionMs = it.toInt() },
                            onValueChangeFinished = {
                                videoView?.seekTo(positionMs)
                                seeking = false
                            },
                            valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${formatDuration(positionMs / 1000).ifEmpty { "0:00" }} / ${formatDuration(durationMs / 1000).ifEmpty { "0:00" }}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
