package com.emfitsolutions.gopreach.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Streetview
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun coordinateText(lat: Double, lng: Double) = "%.6f, %.6f".format(lat, lng)

/**
 * The one place-card every record and map spot uses: the coordinates (with Copy) and two clear choices - Street View, or Open in Maps (the
 * phone's map app, which is Google Maps where installed). Only shown for a place that really has a location, and only to people who can already
 * see that record or spot. Street View shows Google's street photos; where Google has none, it says so itself.
 */
@Composable
fun RecordLocationActions(lat: Double, lng: Double, name: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // The website's place card: a thin border, the pin and the coordinates (monospace, bold) with Copy, then two bordered compact buttons side by side.
    Surface(
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = androidx.compose.ui.graphics.Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    coordinateText(lat, lng),
                    fontSize = 14.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                        .semantics { contentDescription = "Coordinates: latitude ${"%.6f".format(lat)}, longitude ${"%.6f".format(lng)}" },
                )
                IconButton(onClick = { copyCoordinates(context, lat, lng) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy coordinates", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
            }
            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaceButton(Icons.Rounded.Streetview, "Street View", Modifier.weight(1f)) { openStreetViewExternally(context, lat, lng) }
                PlaceButton(Icons.Rounded.Map, "Open in Maps", Modifier.weight(1f)) { openCoordinatesInMaps(context, lat, lng, name?.ifBlank { null }) }
            }
        }
    }
}

/** The website's place button: a thin border, no fill, the icon in the theme colour and a short label. */
@Composable
private fun PlaceButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(10.dp), color = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier.height(40.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/**
 * The same place choices as [RecordLocationActions] on one light line for dense lists: the coordinates, Copy, and small Street View / Maps
 * buttons that wrap under the coordinates only when the row is too narrow.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RecordLocationCompact(lat: Double, lng: Double, name: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val tight = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                coordinateText(lat, lng), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                modifier = Modifier.semantics { contentDescription = "Coordinates: latitude ${"%.6f".format(lat)}, longitude ${"%.6f".format(lng)}" },
            )
            IconButton(onClick = { copyCoordinates(context, lat, lng) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy coordinates", modifier = Modifier.size(16.dp))
            }
        }
        ActionIconButton(ActionKind.Map, onClick = { openStreetViewExternally(context, lat, lng) }, contentDescription = "Street View")
        ActionIconButton(ActionKind.Map, onClick = { openCoordinatesInMaps(context, lat, lng, name?.ifBlank { null }) }, contentDescription = "Open in Maps")
    }
}

private fun copyCoordinates(context: Context, lat: Double, lng: Double) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Coordinates", coordinateText(lat, lng)))
    Toast.makeText(context, "Coordinates copied", Toast.LENGTH_SHORT).show()
}
