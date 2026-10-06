package com.emfitsolutions.gopreach.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.emfitsolutions.gopreach.data.model.SupportingImage
import com.emfitsolutions.gopreach.data.model.SupportingImageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min
import androidx.compose.ui.window.DialogProperties

private const val TAG = "SupportingImageCapture"

/**
 * Full capture / preview-confirm / change / clear UI for one [SupportingImage]
 * (spec's "Interested Person – Supporting Place Image Capture" feature §1-§7,
 * §10). Self-contained: manages the camera permission request, the captured-
 * but-not-yet-confirmed preview state, and the clear-confirmation dialog —
 * the caller only ever sees the two committed outcomes, [onImageConfirmed]
 * and [onClear].
 *
 * Replacement safety (spec §6): [onImageConfirmed] is only called *after* the
 * user taps "Use Photo" on the freshly-captured preview — the existing
 * [currentImage] is never touched until that happens, so a cancelled or
 * abandoned capture can never lose the photo that was already saved.
 *
 * Bug fix: this used to launch the camera with [ActivityResultContracts
 * .TakePicturePreview], which hands the *full-resolution* photo back as a
 * `Bitmap` riding inside the onActivityResult `Intent` extras — a cross-
 * process Binder transaction capped at ~1MB. Many camera apps (stock Camera
 * on several OEMs in particular) return a "preview" that is really the full
 * shot, which either threw `TransactionTooLargeException` or, once it did
 * cross, got scaled/compressed synchronously on the main thread — a bitmap
 * that size (tens of MB as ARGB_8888) reliably triggered an `OutOfMemoryError`
 * and took the whole app down right at "Use Photo"/"Add"/"Save", which is
 * exactly the "app closes when I click create or save" report. Fixed by
 * switching to [ActivityResultContracts.TakePicture], which writes the photo
 * straight to a file (no Binder size limit), then decoding it back with
 * [BitmapFactory.Options.inSampleSize] so the full-resolution bitmap is never
 * actually allocated — only an already-downscaled one — and doing that decode
 * plus the JPEG compression on a background dispatcher instead of blocking
 * the UI thread.
 */
@Composable
fun SupportingImageSection(
    currentImage: SupportingImage?,
    onImageConfirmed: (SupportingImage) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var pendingBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingUri
        if (success && uri != null) {
            isProcessing = true
            coroutineScope.launch {
                val decoded = withContext(Dispatchers.Default) { decodeDownsampledBitmap(context, uri) }
                isProcessing = false
                if (decoded != null) pendingBitmap = decoded
            }
        } else {
            isProcessing = false
        }
    }
    val requestCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCameraInternal(context) { pendingUri = it; takePicture.launch(it) } else permissionDenied = true
    }
    val launchCamera = {
        permissionDenied = false
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            launchCameraInternal(context) { pendingUri = it; takePicture.launch(it) }
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Supporting Place Image", style = MaterialTheme.typography.titleSmall)

        val preview = pendingBitmap
        when {
            isProcessing -> {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }

            // Section 1.3-1.4 / 2.2-2.4: captured, not yet saved — Use Photo / Retake Photo.
            preview != null -> {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "Captured supporting image preview",
                    modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val capturedType = currentImage?.type ?: SupportingImageType.HOUSE.name
                        isProcessing = true
                        coroutineScope.launch {
                            val encoded = withContext(Dispatchers.Default) { compressToBase64Jpeg(preview) }
                            isProcessing = false
                            onImageConfirmed(
                                SupportingImage(
                                    type = capturedType,
                                    base64Jpeg = encoded,
                                    capturedAt = System.currentTimeMillis(),
                                ),
                            )
                            pendingBitmap = null
                        }
                    }) { Text("Use Photo") }
                    OutlinedButton(onClick = { pendingBitmap = null; launchCamera() }) { Text("Retake Photo") }
                }
            }

            currentImage != null && currentImage.base64Jpeg.isNotBlank() -> {
                val decoded = remember(currentImage.base64Jpeg) { decodeBase64ToBitmap(currentImage.base64Jpeg) }
                if (decoded != null) {
                    Image(
                        bitmap = decoded.asImageBitmap(),
                        contentDescription = "Supporting image",
                        modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = launchCamera) { Text("Change Image") }
                    TextButton(onClick = { showClearConfirm = true }) { Text("Clear Image") }
                }
            }

            else -> {
                Text("No supporting image", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = launchCamera) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("Capture Image")
                }
            }
        }

        if (permissionDenied) {
            Text(
                "Camera permission is required to capture a supporting image.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Supporting Image?") },
            text = { Text("This will remove the saved image from this Interested Person record.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    onClear()
                }) { Text("Clear Image") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") } },
        )
    }
}

/** Creates a fresh temp file under cacheDir/camera/ and hands the camera app
 * a content:// [Uri] for it via [FileProvider] — no Binder-transaction size
 * limit involved, unlike [ActivityResultContracts.TakePicturePreview]'s
 * in-Intent Bitmap. */
private inline fun launchCameraInternal(context: android.content.Context, onReady: (Uri) -> Unit) {
    runCatching {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        onReady(uri)
    }.onFailure { Log.e(TAG, "Failed to prepare camera capture file", it) }
}

/** Decodes [uri] straight to an already-downscaled bitmap — the full-
 * resolution photo the camera wrote to disk is never allocated in memory,
 * only a bitmap already at-or-below [maxDimension] on its long edge, via the
 * standard two-pass `inJustDecodeBounds` + `inSampleSize` technique. Runs on
 * a background dispatcher (see call site); safe to call off the main thread. */
private fun decodeDownsampledBitmap(context: android.content.Context, uri: Uri, maxDimension: Int = 1024): Bitmap? = runCatching {
    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOptions) }
    var sampleSize = 1
    var width = boundsOptions.outWidth
    var height = boundsOptions.outHeight
    while (width / (sampleSize * 2) >= maxDimension || height / (sampleSize * 2) >= maxDimension) {
        sampleSize *= 2
    }
    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
}.onFailure { Log.e(TAG, "Failed to decode captured photo", it) }.getOrNull()

/** Downscales to at most 1024px on the long edge and JPEG-compresses at 60%
 * quality — small enough to comfortably fit inside Firestore's 1 MiB
 * per-document limit (typically well under 200KB in practice) while still
 * being a recognizable photo of a house/gate/landmark. Called on a background
 * dispatcher (see call site) since this is real CPU work. */
private fun compressToBase64Jpeg(bitmap: Bitmap, maxDimension: Int = 1024, quality: Int = 60): String {
    val scale = min(1f, maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height))
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    } else {
        bitmap
    }
    val stream = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
    return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
}

private fun decodeBase64ToBitmap(base64Jpeg: String): Bitmap? = runCatching {
    val bytes = Base64.decode(base64Jpeg, Base64.NO_WRAP)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()
