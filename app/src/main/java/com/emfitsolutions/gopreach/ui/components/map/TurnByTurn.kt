package com.emfitsolutions.gopreach.ui.components.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.RampRight
import androidx.compose.material.icons.rounded.RoundaboutRight
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSharpLeft
import androidx.compose.material.icons.rounded.TurnSharpRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material.icons.rounded.UTurnRight
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.navigation.VoiceGuide
import com.emfitsolutions.gopreach.data.navigation.RoutingService
import com.emfitsolutions.gopreach.domain.navigation.Maneuver
import com.emfitsolutions.gopreach.domain.navigation.NavigationEngine
import com.emfitsolutions.gopreach.domain.navigation.NavigationProgress
import com.emfitsolutions.gopreach.domain.navigation.Route
import com.emfitsolutions.gopreach.domain.navigation.RouteChoice
import com.emfitsolutions.gopreach.domain.navigation.RouteChoices
import com.emfitsolutions.gopreach.domain.navigation.RouteFormat
import com.emfitsolutions.gopreach.domain.navigation.RoutingException
import com.emfitsolutions.gopreach.platform.formatDate
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** PREVIEW: the routes were found and the driver is choosing one; nothing is being followed yet. */
enum class GuidancePhase { IDLE, ROUTING, PREVIEW, ACTIVE, REROUTING, ARRIVED }

data class GuidanceDestination(val name: String, val lat: Double, val lng: Double)

/**
 * In-app turn-by-turn guidance on top of Driving Mode. It fetches a driving route to one place, follows the filtered GPS positions along it,
 * speaks each instruction and asks for a new route when the vehicle leaves the road it was given. It is a navigation aid only: it never
 * changes territories, assignments, statuses or reports, and it always tells the driver to follow road signs and traffic rules.
 */
@Stable
class TurnByTurnState(
    private val routing: RoutingService,
    private val scope: CoroutineScope,
    private val voice: VoiceGuide,
    /** Whether spoken guidance starts on (the driver's remembered choice), and what to do when they change it. */
    initialVoiceOn: Boolean = true,
    private val onVoiceChanged: (Boolean) -> Unit = {},
) {
    var phase by mutableStateOf(GuidancePhase.IDLE)
        private set
    var route by mutableStateOf<Route?>(null)
        private set
    var progress by mutableStateOf<NavigationProgress?>(null)
        private set
    var destination by mutableStateOf<GuidanceDestination?>(null)
        private set
    /** A plain-language problem to show to the driver (no connection, no route...), or null. */
    var message by mutableStateOf<String?>(null)
        private set
    var voiceOn by mutableStateOf(initialVoiceOn)
        private set

    /** The routes on offer while previewing (quickest first) and which one is picked. Empty outside the preview. */
    var choices by mutableStateOf<List<RouteChoice>>(emptyList())
        private set
    var selectedIndex by mutableStateOf(0)
        private set

    init { voice.muted = !initialVoiceOn }

    private var engine: NavigationEngine? = null
    private var job: Job? = null
    private var lastRerouteAt = 0L
    private var warnedOfflineReroute = false
    val voiceAvailable: Boolean get() = voice.available
    val active: Boolean get() = phase != GuidancePhase.IDLE

    /** Asks for a route from the current position. [from] is the filtered GPS position. */
    fun start(to: GuidanceDestination, fromLat: Double, fromLng: Double) {
        stop()
        destination = to
        phase = GuidancePhase.ROUTING
        message = null
        job = scope.launch { fetch(fromLat, fromLng, first = true) }
    }

    /** Finds the routes (the best one and any alternatives) and shows them to choose from; nothing is followed until [begin]. */
    fun preview(to: GuidanceDestination, fromLat: Double, fromLng: Double) {
        stop()
        destination = to
        phase = GuidancePhase.ROUTING
        message = null
        job = scope.launch {
            try {
                val found = RouteChoices.label(routing.routes(fromLat, fromLng, to.lat, to.lng, alternatives = 2))
                choices = found
                selectedIndex = 0
                route = found.first().route
                phase = GuidancePhase.PREVIEW
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoutingException) {
                phase = GuidancePhase.IDLE; destination = null; route = null; choices = emptyList()
                message = e.message
            }
        }
    }

    fun select(index: Int) {
        val c = choices.getOrNull(index) ?: return
        selectedIndex = index
        route = c.route
    }

    /** Starts guidance on the chosen route. */
    fun begin() {
        val r = route ?: return
        if (phase != GuidancePhase.PREVIEW) return
        engine = NavigationEngine(r)
        progress = null
        choices = emptyList()
        phase = GuidancePhase.ACTIVE
    }

    private suspend fun fetch(fromLat: Double, fromLng: Double, first: Boolean) {
        val to = destination ?: return
        try {
            val r = routing.route(fromLat, fromLng, to.lat, to.lng)
            route = r
            engine = NavigationEngine(r)
            progress = null
            message = null
            warnedOfflineReroute = false
            phase = GuidancePhase.ACTIVE
            if (!first) voice.speak("Route updated.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: RoutingException) {
            if (first) { phase = GuidancePhase.IDLE; destination = null; route = null; engine = null }
            else phase = GuidancePhase.ACTIVE // keep driving on the old route's last known guidance
            message = e.message
        }
    }

    /** Feed every accepted GPS position. [online] says whether a new route could be fetched right now. */
    fun onPosition(lat: Double, lng: Double, accuracyMeters: Float?, speedMps: Float?, online: Boolean) {
        val e = engine ?: return
        if (phase != GuidancePhase.ACTIVE && phase != GuidancePhase.REROUTING) return
        val p = e.update(lat, lng, accuracyMeters, speedMps)
        progress = p
        if (p.announcements.isNotEmpty()) voice.speak(p.announcements.joinToString(" "))
        when {
            p.arrived -> { phase = GuidancePhase.ARRIVED; message = null }
            p.offRoute && phase == GuidancePhase.ACTIVE -> reroute(lat, lng, online)
        }
    }

    private fun reroute(lat: Double, lng: Double, online: Boolean) {
        val now = nowMillis()
        if (now - lastRerouteAt < REROUTE_GAP_MS) return
        if (!online) {
            message = "You are off the route and offline, so a new route cannot be found."
            if (!warnedOfflineReroute) { warnedOfflineReroute = true; voice.speak("You are off the route. A new route needs an internet connection.") }
            return
        }
        lastRerouteAt = now
        phase = GuidancePhase.REROUTING
        voice.speak("Rerouting.")
        job = scope.launch { fetch(lat, lng, first = false) }
    }

    fun setVoice(on: Boolean) {
        voiceOn = on
        voice.muted = !on
        if (!on) voice.stopSpeaking()
        onVoiceChanged(on)
    }

    fun stop() {
        job?.cancel()
        voice.stopSpeaking()
        phase = GuidancePhase.IDLE
        route = null; engine = null; progress = null; destination = null; message = null
        choices = emptyList(); selectedIndex = 0
        warnedOfflineReroute = false
    }

    fun dismissMessage() { message = null }

    companion object { const val REROUTE_GAP_MS = 10_000L }
}

internal fun Maneuver.icon(): ImageVector = when (this) {
    Maneuver.LEFT -> Icons.Rounded.TurnLeft
    Maneuver.RIGHT -> Icons.Rounded.TurnRight
    Maneuver.SLIGHT_LEFT -> Icons.Rounded.TurnSlightLeft
    Maneuver.SLIGHT_RIGHT -> Icons.Rounded.TurnSlightRight
    Maneuver.SHARP_LEFT -> Icons.Rounded.TurnSharpLeft
    Maneuver.SHARP_RIGHT -> Icons.Rounded.TurnSharpRight
    Maneuver.UTURN_LEFT -> Icons.Rounded.UTurnLeft
    Maneuver.UTURN_RIGHT -> Icons.Rounded.UTurnRight
    Maneuver.RAMP, Maneuver.EXIT -> Icons.Rounded.RampRight
    Maneuver.MERGE -> Icons.Rounded.Merge
    Maneuver.ROUNDABOUT -> Icons.Rounded.RoundaboutRight
    Maneuver.DESTINATION -> Icons.Rounded.Flag
    Maneuver.START, Maneuver.STRAIGHT -> Icons.Rounded.Straight
}

/** Road-number suffixes such as "/1/AH26" are for maps, not for reading at a glance. */
private fun cleanInstruction(text: String): String = text.replace(Regex("(/[A-Za-z0-9]+)+"), "")

/** The top card: what to do next and how far away it is. */
@Composable
fun GuidanceBanner(state: TurnByTurnState, modifier: Modifier = Modifier) {
    val r = state.route
    val p = state.progress
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                state.phase == GuidancePhase.ROUTING -> {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.inverseOnSurface)
                    Spacer(Modifier.width(12.dp))
                    Text("Finding the route...", color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.titleMedium)
                }
                state.phase == GuidancePhase.ARRIVED -> {
                    Icon(Icons.Rounded.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("You have arrived", color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        state.destination?.let { Text(it.name, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                r != null -> {
                    val idx = p?.stepIndex ?: 0
                    val next = r.steps.getOrNull(idx + 1) ?: r.steps.last()
                    val to = p?.metersToManeuver ?: r.steps.getOrNull(0)?.lengthMeters ?: 0.0
                    val text = if (state.phase == GuidancePhase.REROUTING) "Rerouting..." else cleanInstruction(next.instruction)
                    Icon(next.maneuver.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "In ${RouteFormat.spokenDistance(to)}, $text" }) {
                        Text(RouteFormat.distance(to), color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(text, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * "Choose a route": every route the provider found, with its real travel time, distance and arrival time, labelled only from those numbers
 * (see [RouteChoices]). Picking one redraws it on the map; Start navigation follows it. Times are the routing service's estimate for normal
 * driving, not live traffic.
 */
@Composable
fun RoutePreviewPanel(state: TurnByTurnState, onStart: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val now = nowMillis()
    Surface(modifier = modifier, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 10.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (state.choices.size > 1) "Choose a route" else "Route found", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    state.destination?.name?.let { Text("To $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, contentDescription = "Cancel") }
            }
            state.choices.forEachIndexed { index, choice ->
                val selected = index == state.selectedIndex
                val r = choice.route
                Surface(
                    onClick = { state.select(index) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${choice.label}, ${RouteFormat.duration(r.durationSeconds)}, ${RouteFormat.distance(r.distanceMeters)}" },
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(choice.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "${RouteFormat.duration(r.durationSeconds)} · ${RouteFormat.distance(r.distanceMeters)}",
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            )
                        }
                        Text("Arrive ${formatDate(now + (r.durationSeconds * 1000).toLong(), "h:mm a")}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(
                "Estimated for normal driving, not live traffic. Follow road signs and traffic rules.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start navigation") }
        }
    }
}

/** The bottom card: distance and time left, a note when something is wrong, the voice switch and End. */
@Composable
fun GuidancePanel(state: TurnByTurnState, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val p = state.progress
    val r = state.route
    Surface(modifier = modifier, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 10.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!state.voiceAvailable) Text("Spoken guidance is not available on this phone, so instructions are shown only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val remaining = p?.remainingMeters ?: r?.distanceMeters
                    val seconds = p?.remainingSeconds ?: r?.durationSeconds
                    if (remaining != null && seconds != null && state.phase != GuidancePhase.ARRIVED) {
                        Text(
                            "${RouteFormat.duration(seconds)} · ${RouteFormat.distance(remaining)} · ${formatDate(nowMillis() + (seconds * 1000).toLong(), "h:mm a")}",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        state.destination?.name?.let { "To $it" } ?: "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text("Follow road signs and traffic rules.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { state.setVoice(!state.voiceOn) }) {
                    Icon(if (state.voiceOn) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, contentDescription = if (state.voiceOn) "Mute spoken guidance" else "Turn spoken guidance on")
                }
                IconButton(onClick = onEnd) { Icon(Icons.Rounded.Close, contentDescription = "End navigation") }
            }
        }
    }
}

/**
 * The route drawn on the map, styled like a premium navigation app: a soft blue glow, a deep-blue edge, a bright blue road core, a thin light
 * highlight down the middle, and small white chevrons pointing the way to go. Everything sits under the My Location arrow and above the base map,
 * and every width grows with the zoom so it reads like a highway at any scale.
 */
object RouteLineLayer {
    private const val SRC = "gp-route-src"
    private const val ALT_SRC = "gp-route-alt-src"
    private const val ALT_CASING = "gp-route-alt-casing"
    private const val ALT_LINE = "gp-route-alt-line"
    private const val GLOW = "gp-route-glow"
    private const val CASING = "gp-route-casing"
    private const val LINE = "gp-route-line"
    private const val SHINE = "gp-route-shine"
    private const val ARROWS = "gp-route-arrows"
    private const val ARROW_IMAGE = "gp-route-chevron"

    private val glowColor = "#3D8BFF"
    private val edgeColor = "#0B3C9E"
    private val coreColor = "#2A7BFF"
    private val shineColor = "#9CCBFF"

    /** A width in pixels that grows with the zoom (values at zoom 12 / 15 / 17 / 19). */
    private fun widthByZoom(at12: Float, at15: Float, at17: Float, at19: Float) = org.maplibre.android.style.expressions.Expression.interpolate(
        org.maplibre.android.style.expressions.Expression.exponential(1.4f), org.maplibre.android.style.expressions.Expression.zoom(),
        org.maplibre.android.style.expressions.Expression.stop(12, at12), org.maplibre.android.style.expressions.Expression.stop(15, at15),
        org.maplibre.android.style.expressions.Expression.stop(17, at17), org.maplibre.android.style.expressions.Expression.stop(19, at19),
    )

    /** A white chevron pointing right (east); the map turns it to follow the line, so it always points the way to go. */
    private fun chevron(): android.graphics.Bitmap {
        val size = 64
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val path = android.graphics.Path().apply {
            moveTo(size * 0.26f, size * 0.20f)
            lineTo(size * 0.74f, size * 0.50f)
            lineTo(size * 0.26f, size * 0.80f)
            lineTo(size * 0.40f, size * 0.50f)
            close()
        }
        canvas.drawPath(path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.FILL })
        return bitmap
    }

    /** [points] is the route being followed or previewed; [alternatives] are the other choices, drawn thinner and greyer underneath it. */
    fun set(style: org.maplibre.android.maps.Style, points: List<Pair<Double, Double>>?, alternatives: List<List<Pair<Double, Double>>> = emptyList()) {
        val round = org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
        val roundJoin = org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND
        if (style.getSource(SRC) == null) {
            style.addSource(org.maplibre.android.style.sources.GeoJsonSource(SRC, org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList<org.maplibre.geojson.Feature>())))
            style.addImage(ARROW_IMAGE, chevron())
            style.addSource(org.maplibre.android.style.sources.GeoJsonSource(ALT_SRC, org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList<org.maplibre.geojson.Feature>())))
            val altCasing = org.maplibre.android.style.layers.LineLayer(ALT_CASING, ALT_SRC).withProperties(
                org.maplibre.android.style.layers.PropertyFactory.lineColor("#FFFFFF"), org.maplibre.android.style.layers.PropertyFactory.lineOpacity(0.85f),
                org.maplibre.android.style.layers.PropertyFactory.lineWidth(widthByZoom(8f, 14f, 22f, 30f)), org.maplibre.android.style.layers.PropertyFactory.lineCap(round), org.maplibre.android.style.layers.PropertyFactory.lineJoin(roundJoin),
            )
            val altLine = org.maplibre.android.style.layers.LineLayer(ALT_LINE, ALT_SRC).withProperties(
                org.maplibre.android.style.layers.PropertyFactory.lineColor("#7C8CA6"),
                org.maplibre.android.style.layers.PropertyFactory.lineWidth(widthByZoom(4.5f, 9f, 15f, 21f)), org.maplibre.android.style.layers.PropertyFactory.lineCap(round), org.maplibre.android.style.layers.PropertyFactory.lineJoin(roundJoin),
            )
            val glow = org.maplibre.android.style.layers.LineLayer(GLOW, SRC).withProperties(
                org.maplibre.android.style.layers.PropertyFactory.lineColor(glowColor), org.maplibre.android.style.layers.PropertyFactory.lineOpacity(0.28f), org.maplibre.android.style.layers.PropertyFactory.lineBlur(6f), org.maplibre.android.style.layers.PropertyFactory.lineWidth(widthByZoom(14f, 30f, 46f, 62f)), org.maplibre.android.style.layers.PropertyFactory.lineCap(round), org.maplibre.android.style.layers.PropertyFactory.lineJoin(roundJoin),
            )
            val casing = org.maplibre.android.style.layers.LineLayer(CASING, SRC).withProperties(
                org.maplibre.android.style.layers.PropertyFactory.lineColor(edgeColor), org.maplibre.android.style.layers.PropertyFactory.lineWidth(widthByZoom(9f, 20f, 32f, 44f)), org.maplibre.android.style.layers.PropertyFactory.lineCap(round), org.maplibre.android.style.layers.PropertyFactory.lineJoin(roundJoin),
            )
            val line = org.maplibre.android.style.layers.LineLayer(LINE, SRC).withProperties(
                org.maplibre.android.style.layers.PropertyFactory.lineColor(coreColor), org.maplibre.android.style.layers.PropertyFactory.lineWidth(widthByZoom(6f, 14f, 24f, 34f)), org.maplibre.android.style.layers.PropertyFactory.lineCap(round), org.maplibre.android.style.layers.PropertyFactory.lineJoin(roundJoin),
            )
            val shine = org.maplibre.android.style.layers.LineLayer(SHINE, SRC).withProperties(
                org.maplibre.android.style.layers.PropertyFactory.lineColor(shineColor), org.maplibre.android.style.layers.PropertyFactory.lineOpacity(0.7f), org.maplibre.android.style.layers.PropertyFactory.lineWidth(widthByZoom(1.5f, 4f, 7f, 10f)), org.maplibre.android.style.layers.PropertyFactory.lineCap(round), org.maplibre.android.style.layers.PropertyFactory.lineJoin(roundJoin),
            )
            val arrows = org.maplibre.android.style.layers.SymbolLayer(ARROWS, SRC).withProperties(
                org.maplibre.android.style.layers.PropertyFactory.symbolPlacement(org.maplibre.android.style.layers.Property.SYMBOL_PLACEMENT_LINE),
                org.maplibre.android.style.layers.PropertyFactory.symbolSpacing(64f),
                org.maplibre.android.style.layers.PropertyFactory.iconImage(ARROW_IMAGE),
                org.maplibre.android.style.layers.PropertyFactory.iconSize(widthByZoom(0.30f, 0.50f, 0.72f, 0.95f)),
                org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment(org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_MAP),
                org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
                org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true),
                org.maplibre.android.style.layers.PropertyFactory.iconOpacity(0.92f),
            )
            val layers = listOf(altCasing, altLine, glow, casing, line, shine, arrows)
            if (style.getLayer(CurrentLocationLayer.LAYER_ID) != null) layers.forEach { style.addLayerBelow(it, CurrentLocationLayer.LAYER_ID) } else layers.forEach { style.addLayer(it) }
        }
        style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>(ALT_SRC)?.setGeoJson(
            org.maplibre.geojson.FeatureCollection.fromFeatures(
                alternatives.filter { it.size >= 2 }.map { alt ->
                    org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(alt.map { org.maplibre.geojson.Point.fromLngLat(it.second, it.first) }))
                },
            ),
        )
        val src = style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>(SRC) ?: return
        if (points == null || points.size < 2) {
            src.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList<org.maplibre.geojson.Feature>()))
        } else {
            src.setGeoJson(
                org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(points.map { org.maplibre.geojson.Point.fromLngLat(it.second, it.first) })),
            )
        }
    }
}
