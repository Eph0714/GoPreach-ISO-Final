package com.emfitsolutions.gopreach.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import com.emfitsolutions.gopreach.data.location.formatCoordinatesDms

/**
 * "Make all the coordinates clickable" — every place this app shows a saved
 * GPS pair (Searching/Return Visit/Bible Study records, Shared Location) uses
 * this one composable so tapping any of them opens the same `geo:` intent
 * [com.emfitsolutions.gopreach.ui.screens.sharelocation.ShareLocationScreen]
 * already used for its other-publishers list — Google Maps if installed,
 * otherwise whatever the device offers for a `geo:` URI. Styled as a link
 * (primary color, underlined) so it visibly *looks* tappable too, not just
 * technically is.
 */
@Composable
fun ClickableCoordinatesText(
    lat: Double,
    lng: Double,
    label: String? = null,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    prefix: String = "Coordinates: ",
) {
    val context = LocalContext.current
    Text(
        "$prefix${formatCoordinatesDms(lat, lng)}",
        style = style,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = modifier.clickable { openCoordinatesInMaps(context, lat, lng, label) },
    )
}

/** Opens [lat]/[lng] in Google Maps (or any app/browser that handles `geo:`
 * URIs) — swallows [ActivityNotFoundException] rather than crashing on a
 * stripped-down device with nothing installed to handle it. */
fun openCoordinatesInMaps(context: Context, lat: Double, lng: Double, label: String? = null) {
    val query = if (label != null) "$lat,$lng($label)" else "$lat,$lng"
    val uri = Uri.parse("geo:$lat,$lng?q=${Uri.encode(query)}")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        // Nothing sensible to fall back to short of a Toast; swallow rather
        // than crash the app, same as FindLocationScreen's openDirections.
    }
}
