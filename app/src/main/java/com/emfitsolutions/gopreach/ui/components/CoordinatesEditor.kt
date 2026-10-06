package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** A plain lat/lng(/accuracy) value with no capture metadata attached — used
 * wherever coordinates are being edited locally (not yet persisted), unlike
 * [com.emfitsolutions.gopreach.data.model.InterestedPerson]'s own
 * gpsLat/gpsLng/gpsAccuracy/gpsCapturedAt/gpsCapturedBy/gpsUpdatedAt, which
 * only make sense once there's an actual record and actor to attribute a
 * capture to. */
data class CoordinatesValue(val lat: Double, val lng: Double, val accuracyMeters: Float? = null)

/** "Enter Location Manually" spec — a real geographic coordinate, not
 * arbitrary text (spec §12: "do not store coordinates as arbitrary formatted
 * text") — [parseLocationInput] is the only thing allowed to turn free-form
 * text into one of these, and it always validates through here. */
fun isValidLatitude(value: Double): Boolean = value in -90.0..90.0
fun isValidLongitude(value: Double): Boolean = value in -180.0..180.0

private val COORDINATE_PAIR_REGEX = Regex("""^(-?\d{1,3}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)$""")

/** Every shape a coordinate pair shows up in across the Google Maps links
 * publishers actually paste/share: `@lat,lng,zoom` (opened-place URLs),
 * `?q=lat,lng` / `&query=lat,lng` (share/search links), `?ll=lat,lng`
 * (older-style links), and `!3dlat!4dlng` (a place's *own* pin, embedded
 * inside a `/maps/place/...` URL — distinct from the `@lat,lng` in the same
 * URL, which is just wherever the map was scrolled to, not the pin itself;
 * checked first for that reason). */
private val URL_COORDINATE_REGEXES = listOf(
    Regex("""!3d(-?\d{1,3}\.\d+)!4d(-?\d{1,3}\.\d+)"""),
    Regex("""[?&](?:q|query|ll)=(-?\d{1,3}\.\d+),(-?\d{1,3}\.\d+)"""),
    Regex("""@(-?\d{1,3}\.\d+),(-?\d{1,3}\.\d+)"""),
)

private fun coordinatesFromUrlText(text: String): CoordinatesValue? {
    for (regex in URL_COORDINATE_REGEXES) {
        val match = regex.find(text) ?: continue
        val lat = match.groupValues[1].toDoubleOrNull() ?: continue
        val lng = match.groupValues[2].toDoubleOrNull() ?: continue
        if (isValidLatitude(lat) && isValidLongitude(lng)) return CoordinatesValue(lat, lng)
    }
    return null
}

/** A shared (`maps.app.goo.gl`, `goo.gl/maps`, `g.co`) link carries no
 * coordinates in the URL itself — only the server's redirect target does.
 * Follows up to [maxHops] redirects by hand (no new HTTP-client dependency;
 * [HttpURLConnection] with redirects off, one hop at a time) and returns
 * whichever URL it lands on — the caller re-runs [coordinatesFromUrlText] on
 * that. Never throws its own way out to the caller; any I/O failure just
 * returns the URL as given, which then simply fails to yield coordinates,
 * same as a link that never had any. */
private fun resolveRedirects(startUrl: String, maxHops: Int = 5): String {
    var current = startUrl
    repeat(maxHops) {
        val connection = runCatching {
            (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
        }.getOrNull() ?: return current
        val next = runCatching {
            val code = connection.responseCode
            if (code !in 300..399) return@runCatching null
            connection.getHeaderField("Location")
        }.getOrNull()
        connection.disconnect()
        if (next.isNullOrBlank()) return current
        current = runCatching { URL(URL(current), next).toString() }.getOrDefault(next)
    }
    return current
}

/** "The publisher can paste a location link or an exact gps at once" — tries,
 * in order: (1) a plain "lat, lng" pair — the original behavior, still
 * instant, no network; (2) coordinates embedded directly in a pasted map URL;
 * (3) for a shortened link with no coordinates of its own (a Maps "Share"
 * link), following its redirect chain and trying again on where it lands.
 * Null means none of that found a valid coordinate — the caller shows its
 * own "couldn't find a location" error rather than guessing. */
suspend fun parseLocationInput(input: String): CoordinatesValue? = withContext(Dispatchers.IO) {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return@withContext null

    COORDINATE_PAIR_REGEX.find(trimmed)?.let { match ->
        val lat = match.groupValues[1].toDoubleOrNull()
        val lng = match.groupValues[2].toDoubleOrNull()
        if (lat != null && lng != null && isValidLatitude(lat) && isValidLongitude(lng)) {
            return@withContext CoordinatesValue(lat, lng)
        }
    }

    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        coordinatesFromUrlText(trimmed)?.let { return@withContext it }
        val resolved = runCatching { resolveRedirects(trimmed) }.getOrNull()
        if (resolved != null && resolved != trimmed) {
            coordinatesFromUrlText(resolved)?.let { return@withContext it }
        }
    }

    null
}

/**
 * [ENTER LOCATION MANUALLY] spec — one field that accepts a pasted map
 * location link *or* exact GPS coordinates typed by hand, resolved through
 * [parseLocationInput]; Save is unreachable until that actually yields a
 * valid coordinate, rather than accepting bad input and failing later.
 */
@Composable
fun ManualCoordinatesDialog(
    initial: CoordinatesValue?,
    onConfirm: (CoordinatesValue) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialText = remember { initial?.let { "${it.lat}, ${it.lng}" }.orEmpty() }
    var inputText by remember { mutableStateOf(initialText) }
    var isResolving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun submit() {
        val text = inputText.trim()
        if (text.isEmpty()) {
            errorText = "Paste a location link, or enter exact GPS coordinates."
            return
        }
        errorText = null
        isResolving = true
        coroutineScope.launch {
            val value = parseLocationInput(text)
            isResolving = false
            if (value != null) {
                onConfirm(value)
            } else {
                errorText = "Couldn't find GPS coordinates there. Paste a Google Maps location link, or type exact coordinates like 14.599512, 120.984222."
            }
        }
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = "Enter Location Manually",
        onConfirm = ::submit,
        confirmLabel = "SAVE",
        dismissLabel = "CANCEL",
        confirmEnabled = !isResolving,
        maxContentHeight = 280.dp,
        hasUnsavedChanges = inputText != initialText,
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it; errorText = null },
            label = { Text("Location Link or GPS Coordinates") },
            placeholder = { Text("Paste a maps link, or e.g. 14.599512, 120.984222") },
            singleLine = true,
            isError = errorText != null,
            enabled = !isResolving,
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        if (isResolving) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text("Looking up that location…", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (errorText != null) {
            Text(errorText.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
