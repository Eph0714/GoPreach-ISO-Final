package com.emfitsolutions.gopreach.ui.screens.findlocation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.ui.components.map.MapLibreHost
import com.emfitsolutions.gopreach.ui.components.map.rememberCurrentLocation
import com.emfitsolutions.gopreach.ui.components.map.MapLoadState
import com.emfitsolutions.gopreach.ui.components.map.maptilerStyleUrl
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

private const val PREVIEW_SOURCE = "preview-src"
private const val PREVIEW_LAYER = "preview-pin"

/**
 * A small, non-interactive map showing one found coordinate — the "Map/
 * Location Preview" on Find Location's LOCATION FOUND card. A native MapLibre
 * map (see [MapLibreHost]) showing satellite imagery with a single red
 * marker. Gestures are switched off so the preview never fights the
 * surrounding scroll; exploring the area is what Look Around is for. Needs an
 * internet connection for map tiles — offline it falls back to a short note,
 * never blocking the actions beside it.
 */
@Composable
fun LocationPreviewMap(lat: Double, lng: Double, modifier: Modifier = Modifier) {
    var loadState by remember { mutableStateOf(MapLoadState.LOADING) }
    val myLocation by rememberCurrentLocation()

    Box(modifier = modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp))) {
        MapLibreHost(
            // Plain satellite imagery — "just a real map", rooftops visible.
            styleUrl = maptilerStyleUrl("satellite"),
            interactive = false,
            myLocation = myLocation,
            onLoadStateChange = { loadState = it },
            onStyleReady = { map, style ->
                style.addSource(GeoJsonSource(PREVIEW_SOURCE, Feature.fromGeometry(Point.fromLngLat(lng, lat))))
                style.addLayer(
                    CircleLayer(PREVIEW_LAYER, PREVIEW_SOURCE).withProperties(
                        PropertyFactory.circleRadius(9f),
                        PropertyFactory.circleColor("#D93025"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(3f),
                    ),
                )
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 17.0))
            },
            modifier = Modifier.fillMaxSize(),
        )
        when (loadState) {
            MapLoadState.LOADING -> Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            MapLoadState.FAILED -> Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Map preview unavailable. Check your internet connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MapLoadState.LOADED -> Unit
        }
    }
}
