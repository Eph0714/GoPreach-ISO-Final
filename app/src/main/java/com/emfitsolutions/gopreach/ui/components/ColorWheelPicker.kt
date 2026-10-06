package com.emfitsolutions.gopreach.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import android.graphics.Color as AndroidColor

/**
 * "For theme color, let the users select from color wheel" — a hand-rolled
 * HSV wheel (hue = angle, saturation = distance from center), no third-party
 * picker library (this app's established "no new dependency" preference —
 * same reasoning as [com.emfitsolutions.gopreach.ui.theme.generateSwatch]
 * next to it). Value/brightness is a separate slider underneath, the usual
 * split for a wheel drawn at a fixed Value=1.0.
 */
@Composable
fun ColorWheelPicker(
    color: Color,
    onColorChanged: (Color) -> Unit,
    modifier: Modifier = Modifier,
    wheelSizeDp: Int = 220,
) {
    val density = LocalDensity.current
    val sizePx = remember(wheelSizeDp, density) { with(density) { wheelSizeDp.dp.roundToPx() } }
    val wheelBitmap = remember(sizePx) { buildWheelBitmap(sizePx).asImageBitmap() }

    val hsv = remember(color) {
        val out = FloatArray(3)
        AndroidColor.colorToHSV(color.toArgb(), out)
        out
    }
    var value by remember(color) { mutableStateOf(hsv[2]) }

    fun colorAt(offset: Offset): Color? {
        val radius = sizePx / 2f
        val dx = offset.x - radius
        val dy = offset.y - radius
        val distance = sqrt(dx * dx + dy * dy)
        if (distance > radius) return null
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0) angle += 360f
        val saturation = (distance / radius).coerceIn(0f, 1f)
        return Color(AndroidColor.HSVToColor(floatArrayOf(angle, saturation, value)))
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(wheelSizeDp.dp)
                .pointerInput(sizePx, value) {
                    detectTapGestures { offset -> colorAt(offset)?.let(onColorChanged) }
                }
                .pointerInput(sizePx, value) {
                    detectDragGestures { change, _ -> colorAt(change.position)?.let(onColorChanged) }
                },
        ) {
            Image(bitmap = wheelBitmap, contentDescription = "Color wheel — tap or drag to pick a hue and saturation")

            // A ring marker at the currently-selected hue/saturation.
            val radius = sizePx / 2f
            val angleRad = Math.toRadians(hsv[0].toDouble())
            val markerRadiusPx = hsv[1] * radius
            val markerXPx = radius + (markerRadiusPx * cos(angleRad)).toFloat()
            val markerYPx = radius + (markerRadiusPx * sin(angleRad)).toFloat()
            val markerX = with(density) { markerXPx.toDp() }
            val markerY = with(density) { markerYPx.toDp() }
            Box(
                modifier = Modifier
                    .offset(x = markerX - 9.dp, y = markerY - 9.dp)
                    .size(18.dp)
                    .border(2.dp, Color.White, CircleShape)
                    .border(1.dp, Color.Black.copy(alpha = 0.3f), CircleShape),
            )
        }

        Text("Brightness", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
        Slider(
            value = value,
            onValueChange = { newValue ->
                value = newValue
                onColorChanged(Color(AndroidColor.HSVToColor(floatArrayOf(hsv[0], hsv[1], newValue))))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Precomputes an HSV hue/saturation wheel bitmap once per size, at fixed
 * Value=1.0 — recomputing per-pixel on every recomposition would be far too
 * slow; [ColorWheelPicker] keys this on [sizePx] so it only ever runs once
 * per wheel size shown. */
private fun buildWheelBitmap(sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val radius = sizePx / 2f
    val hsv = floatArrayOf(0f, 0f, 1f)
    for (y in 0 until sizePx) {
        for (x in 0 until sizePx) {
            val dx = x - radius
            val dy = y - radius
            val distance = sqrt(dx * dx + dy * dy)
            if (distance <= radius) {
                var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (angle < 0) angle += 360f
                hsv[0] = angle
                hsv[1] = (distance / radius).coerceIn(0f, 1f)
                bitmap.setPixel(x, y, AndroidColor.HSVToColor(hsv))
            } else {
                bitmap.setPixel(x, y, AndroidColor.TRANSPARENT)
            }
        }
    }
    return bitmap
}

/**
 * "The user can eyedrop a color he wants" — picks a photo from the gallery
 * (no camera/storage permission needed for a one-off `GetContent` pick) and
 * samples whatever pixel is tapped. [ContentScale.FillBounds] (stretch, not
 * crop) keeps the tap-position -> bitmap-pixel mapping exact with no
 * letterboxing/crop-offset math to get wrong.
 */
@Composable
fun EyedropperImagePicker(onColorPicked: (Color) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var displaySize by remember { mutableStateOf(IntSize.Zero) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val decoded = runCatching {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            bitmap = decoded
            imageBitmap = decoded?.asImageBitmap()
        }
    }

    Column(modifier = modifier) {
        OutlinedButton(onClick = { pickImage.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
            Text("Eyedrop From Photo")
        }
        val currentBitmap = bitmap
        val currentImageBitmap = imageBitmap
        if (currentBitmap != null && currentImageBitmap != null) {
            Text(
                "Tap anywhere on the photo to pick that color.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Image(
                bitmap = currentImageBitmap,
                contentDescription = "Tap to pick a color",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(top = 8.dp)
                    .onSizeChanged { displaySize = it }
                    .pointerInput(currentBitmap) {
                        detectTapGestures { offset ->
                            if (displaySize.width <= 0 || displaySize.height <= 0) return@detectTapGestures
                            val px = ((offset.x / displaySize.width) * currentBitmap.width).toInt()
                                .coerceIn(0, currentBitmap.width - 1)
                            val py = ((offset.y / displaySize.height) * currentBitmap.height).toInt()
                                .coerceIn(0, currentBitmap.height - 1)
                            onColorPicked(Color(currentBitmap.getPixel(px, py)))
                        }
                    },
            )
        }
    }
}
