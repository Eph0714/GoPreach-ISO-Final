package com.emfitsolutions.gopreach.ui.components

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Streetview
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.emfitsolutions.gopreach.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import kotlin.coroutines.cancellation.CancellationException

/** True when in-app Street View is set up (a free Mapillary client token in local.properties). */
val inAppStreetViewConfigured: Boolean get() = BuildConfig.MAPILLARY_TOKEN.isNotBlank() || BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()

/** Without the in-app token, Street View opens in the Google Maps app (or a browser) instead. */
fun openStreetViewExternally(context: Context, lat: Double, lng: Double) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("google.streetview:cbll=$lat,$lng")).setPackage("com.google.android.apps.maps")
    try {
        context.startActivity(app)
    } catch (_: ActivityNotFoundException) {
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=$lat,$lng"))
        try { context.startActivity(web) } catch (_: ActivityNotFoundException) { Toast.makeText(context, "No app or browser can open Street View.", Toast.LENGTH_SHORT).show() }
    }
}

private sealed interface StreetViewState {
    data object Checking : StreetViewState
    data class Available(val imageId: String, val google: Boolean = false) : StreetViewState
    data object None : StreetViewState
    data object Failed : StreetViewState
    /** Photos were found but the viewer never showed one (it reported nothing within 15 seconds): said plainly instead of a blank black screen. */
    data object NotShown : StreetViewState
}

private data class StreetPhoto(val id: String, val isPano: Boolean, val capturedAt: Long)

private fun parsePhotos(text: String): List<StreetPhoto> =
    Json.parseToJsonElement(text).jsonObject["data"]?.jsonArray.orEmpty().mapNotNull { e ->
        val o = e.jsonObject
        val id = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        StreetPhoto(id, o["is_pano"]?.jsonPrimitive?.booleanOrNull == true, o["captured_at"]?.jsonPrimitive?.longOrNull ?: 0L)
    }


/** Google's own "is there Street View here?" check (free, needs the Google key). Returns the nearest outdoor panorama's id within about 60 m, or null. */
private suspend fun findGooglePano(client: HttpClient, lat: Double, lng: Double): String? {
    val text = withTimeout(15_000L) {
        client.get("https://maps.googleapis.com/maps/api/streetview/metadata") {
            parameter("location", "$lat,$lng")
            parameter("radius", 60)
            parameter("source", "outdoor")
            parameter("key", BuildConfig.GOOGLE_MAPS_API_KEY)
        }.bodyAsText()
    }
    val root = Json.parseToJsonElement(text).jsonObject
    return when (val status = root["status"]?.jsonPrimitive?.contentOrNull) {
        "OK" -> root["pano_id"]?.jsonPrimitive?.contentOrNull
        "ZERO_RESULTS", "NOT_FOUND" -> null
        else -> throw IllegalStateException("Google Street View check failed: $status ${root["error_message"]}")
    }
}
/** The best street photo within about 60 m of the spot (a 360 photo first, then the newest), or null when there is none. */
private suspend fun findStreetPhoto(client: HttpClient, lat: Double, lng: Double): String? {
    val d = 0.0014 // about 150 m each way
    val text = withTimeout(15_000L) {
        client.get("https://graph.mapillary.com/images") {
            parameter("access_token", BuildConfig.MAPILLARY_TOKEN)
            parameter("fields", "id,is_pano,captured_at")
            parameter("bbox", "${lng - d},${lat - d},${lng + d},${lat + d}")
            parameter("limit", 50)
        }.bodyAsText()
    }
    Json.parseToJsonElement(text).jsonObject["error"]?.let { throw IllegalStateException("Mapillary refused the request: $it") }
    val images = parsePhotos(text)
    return images.sortedWith(compareByDescending<StreetPhoto> { it.isPano }.thenByDescending { it.capturedAt }).firstOrNull()?.id
}

/**
 * Street View on its own, full screen, inside the app. It first asks whether there are street photos at the spot; only if there are does it show
 * them (an interactive viewer you can drag to look around). Otherwise it says so plainly - it never falls back to a map. Closing it (the arrow, or
 * the phone's Back) returns straight to whatever opened it: the record sheet or the long-press choices.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun StreetViewDialog(lat: Double, lng: Double, title: String?, onClose: () -> Unit) {
    val client: HttpClient = koinInject()
    var state by remember { mutableStateOf<StreetViewState>(StreetViewState.Checking) }
    var viewerLoading by remember { mutableStateOf(true) }
    var photoReady by remember { mutableStateOf(false) }
    val webView = remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) { onDispose { webView.value?.apply { stopLoading(); destroy() } } }

    // The viewer must show a photo within 15 seconds, or the screen says so (and offers the standard map) instead of staying blank.
    LaunchedEffect(state) {
        if (state is StreetViewState.Available) {
            kotlinx.coroutines.delay(15_000L)
            if (!photoReady) state = StreetViewState.NotShown
        }
    }

    LaunchedEffect(lat, lng) {
        state = StreetViewState.Checking
        state = try {
            val googlePano = if (BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()) findGooglePano(client, lat, lng) else null
            if (googlePano != null) StreetViewState.Available(googlePano, google = true)
            else (if (BuildConfig.MAPILLARY_TOKEN.isNotBlank()) findStreetPhoto(client, lat, lng) else null)?.let { StreetViewState.Available(it) } ?: StreetViewState.None
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            StreetViewState.Failed
        } catch (e: Exception) {
            StreetViewState.Failed
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                when (val s = state) {
                    is StreetViewState.Available -> {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    setBackgroundColor(android.graphics.Color.BLACK)
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) { viewerLoading = false; if (s.google) photoReady = true }
                                    }
                                    // The Mapillary page names its title "gp-ready" once a street photo is really on screen.
                                    webChromeClient = object : android.webkit.WebChromeClient() {
                                        override fun onReceivedTitle(view: WebView?, title: String?) { if (title == "gp-ready") photoReady = true }
                                    }
                                    val page = if (s.google) """
                                        <html><body style="margin:0;background:#000">
                                        <iframe src="https://www.google.com/maps/embed/v1/streetview?key=${BuildConfig.GOOGLE_MAPS_API_KEY}&pano=${s.imageId}&fov=90" style="border:0;width:100vw;height:100vh" allowfullscreen></iframe>
                                        </body></html>
                                    """.trimIndent() else """
                                        <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
                                        <link href="https://unpkg.com/mapillary-js@4.1.2/dist/mapillary.css" rel="stylesheet"/>
                                        <script src="https://unpkg.com/mapillary-js@4.1.2/dist/mapillary.js"></script>
                                        <style>html,body{margin:0;height:100%;background:#000}#mly{width:100vw;height:100vh}</style></head>
                                        <body><div id="mly"></div>
                                        <script>var v=new mapillary.Viewer({accessToken:'${BuildConfig.MAPILLARY_TOKEN}',container:'mly',imageId:'${s.imageId}'});v.on('image',function(){document.title='gp-ready';});</script>
                                        </body></html>
                                    """.trimIndent()
                                    loadDataWithBaseURL("https://www.mapillary.com/", page, "text/html", "utf-8", null)
                                    webView.value = this
                                }
                            },
                        )
                        if (viewerLoading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                    }
                    StreetViewState.Checking -> {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(color = Color.White)
                            Text("Looking for street photos...", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    StreetViewState.None -> Message("No Street View here", "There are no street photos close to this spot.") { OpenInMapsButton(lat, lng, title) }
                    StreetViewState.NotShown -> Message("Street photo could not be displayed", "Street photos exist near this spot, but they could not be shown right now. Check your internet connection and try again, or open the standard map.") { OpenInMapsButton(lat, lng, title) }
                    StreetViewState.Failed -> Message("Street View could not be checked", "Street View needs an internet connection. Check it and try again, or open the standard map instead.") { OpenInMapsButton(lat, lng, title) }
                }
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close Street View", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text("Street View", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        title?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                    }
                }
            }
        }
    }
}

/** The fallback when there are no street photos (or they cannot be loaded): the ordinary map of the same saved spot, never passed off as Street View. */
@Composable
private fun OpenInMapsButton(lat: Double, lng: Double, title: String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.OutlinedButton(
        onClick = { openCoordinatesInMaps(context, lat, lng, title?.ifBlank { null }) },
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
    ) { Text("Open the standard map") }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Message(title: String, body: String, action: @Composable () -> Unit = {}) {
    Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.Streetview, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(body, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        action()
    }
}
