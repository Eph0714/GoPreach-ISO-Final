package com.emfitsolutions.gopreach.ui.screens.findlocation

import com.emfitsolutions.gopreach.ui.components.ActionKind
import com.emfitsolutions.gopreach.ui.components.ActionButton
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.emfitsolutions.gopreach.domain.map.CoordinateCandidate
import com.emfitsolutions.gopreach.domain.map.CoordinateFormat
import com.emfitsolutions.gopreach.domain.map.CoordinateParser
import com.emfitsolutions.gopreach.domain.map.CoordinateSource
import com.emfitsolutions.gopreach.platform.AppPermission
import com.emfitsolutions.gopreach.platform.rememberPermissionRequester
import com.emfitsolutions.gopreach.ui.components.AlertDialog
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "CoordinateImageCapture"

/** What reading one picture produced. [text] is everything the recognizer saw, shown only so the person can check it. */
private data class ImageReading(val candidates: List<CoordinateCandidate>, val readText: String)

/**
 * "Capture Location Image" / "Choose Image" for Find Location. The picture is read on the phone (nothing is uploaded), the
 * coordinates found in the visible text and the GPS tags the camera stored in the photo are listed as SEPARATE kinds of
 * candidate, and the person picks one: it is only put into the search box (shown on the map, editable, saved or cancelled
 * there) and is never saved here. A picture with nothing usable says so; no coordinate is ever invented.
 */
@Composable
fun CoordinateImageCapture(onPicked: (CoordinateCandidate) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<ImageReading?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun process(uri: Uri) {
        busy = true; failure = null
        scope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { readImage(context, uri) } }
            busy = false
            result.onSuccess { reading = it }.onFailure { Log.e(TAG, "Could not read the picture", it); failure = "The picture could not be read. Try another one, or type the coordinates." }
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingUri
        if (ok && uri != null) process(uri)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) process(uri) }

    fun launchCamera() {
        runCatching {
            val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "find_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingUri = uri
            takePicture.launch(uri)
        }.onFailure { failure = "The camera could not be opened. Choose an existing picture instead, or type the coordinates." }
    }
    val requestCamera = rememberPermissionRequester { granted -> if (granted) launchCamera() else permissionDenied = true }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = {
                    permissionDenied = false
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                    else requestCamera.launch(AppPermission.CAMERA)
                },
            ) {
                Icon(Icons.Rounded.CameraAlt, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text("Capture Location Image")
            }
            OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { pickImage.launch("image/*") }) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text("Choose Image")
            }
        }
        if (busy) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("  Reading the picture…", style = MaterialTheme.typography.bodySmall)
        }
        if (permissionDenied) Text(
            "Camera permission was not given. Choose an existing picture instead, or type the coordinates.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        )
        failure?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }

    reading?.let { r ->
        AlertDialog(
            onDismissRequest = { reading = null },
            title = { Text(if (r.candidates.isEmpty()) "No coordinates found" else "Possible locations") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.candidates.isEmpty()) {
                        Text("No latitude and longitude could be read from this picture, and none is guessed. Try a clearer or closer picture, or type the coordinates yourself.")
                    } else {
                        Text(
                            if (r.candidates.size == 1) "Check this against the picture, then use it." else "More than one was found. Pick the right one.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    r.candidates.forEach { c ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    if (c.source == CoordinateSource.PHOTO_GPS_METADATA) "From the photo's GPS data (where it was taken)" else "Read from text in the picture (${c.format.label})",
                                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                                )
                                Text("%.6f, %.6f".format(c.lat, c.lng), style = MaterialTheme.typography.titleMedium)
                                if (c.source == CoordinateSource.VISIBLE_TEXT) Text("Read as: ${c.matchedText}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { reading = null; onPicked(c) }) { Text("Use this") }
                                    TextButton(onClick = {
                                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                            .setPrimaryClip(ClipData.newPlainText("Coordinates", "%.6f, %.6f".format(c.lat, c.lng)))
                                    }) { Text("Copy") }
                                }
                            }
                        }
                    }
                    ActionButton(ActionKind.Cancel, onClick = { reading = null }, label = "Cancel", modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
    }
}

/** Reads the text in the picture and the photo's own GPS tags. Heavy: call off the main thread. */
private fun readImage(context: Context, uri: Uri): ImageReading {
    val bitmap = decodeScaled(context, uri, 2048) ?: error("not an image")
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val text = try {
        Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text
    } finally {
        recognizer.close()
    }
    val fromText = CoordinateParser.findCandidates(text)
    val gps = runCatching {
        context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).latLong }
    }.getOrNull()?.let { (lat, lng) ->
        if (lat == 0.0 && lng == 0.0) null
        else CoordinateCandidate(lat, lng, CoordinateFormat.PHOTO_GPS, CoordinateSource.PHOTO_GPS_METADATA, "GPS tags in the photo file")
    }
    return ImageReading(listOfNotNull(gps) + fromText, text)
}

private fun decodeScaled(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDimension || bounds.outHeight / (sample * 2) >= maxDimension) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    // Honour the camera's rotation so the text is upright for the recognizer.
    val rotation = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
    }.getOrNull() ?: 0f
    if (rotation == 0f) return raw
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, android.graphics.Matrix().apply { postRotate(rotation) }, true)
}
