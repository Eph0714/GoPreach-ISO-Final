package com.emfitsolutions.gopreach.ui.screens.weather

import com.emfitsolutions.gopreach.ui.theme.glass
import androidx.compose.runtime.DisposableEffect
import com.emfitsolutions.gopreach.ui.screens.circuit.ReportNarrator
import com.emfitsolutions.gopreach.ui.screens.circuit.ReadReportPanel
import com.emfitsolutions.gopreach.domain.weather.WeatherNarration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Map
import com.emfitsolutions.gopreach.ui.components.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.domain.weather.Advisory
import com.emfitsolutions.gopreach.domain.weather.AdvisoryLevel
import com.emfitsolutions.gopreach.domain.weather.CurrentWeather
import com.emfitsolutions.gopreach.domain.weather.DailyForecast
import com.emfitsolutions.gopreach.domain.weather.HourlyForecast
import com.emfitsolutions.gopreach.domain.weather.PlaceKind
import com.emfitsolutions.gopreach.domain.weather.OfficialAlert
import com.emfitsolutions.gopreach.domain.weather.WeatherAdvisory
import com.emfitsolutions.gopreach.ui.screens.weather.AlertsStatus
import com.emfitsolutions.gopreach.domain.weather.WeatherCodes
import com.emfitsolutions.gopreach.domain.weather.WeatherKind
import com.emfitsolutions.gopreach.domain.weather.WeatherPlace
import com.emfitsolutions.gopreach.domain.weather.WeatherReport
import com.emfitsolutions.gopreach.domain.weather.compassPoint
import com.emfitsolutions.gopreach.platform.AppPermission
import com.emfitsolutions.gopreach.platform.formatDate
import com.emfitsolutions.gopreach.platform.nowMillis
import com.emfitsolutions.gopreach.platform.rememberPermissionRequester
import com.emfitsolutions.gopreach.ui.screens.weather.TerritoryWeatherScope
import com.emfitsolutions.gopreach.ui.screens.weather.WeatherSource
import com.emfitsolutions.gopreach.ui.screens.weather.WeatherUiState
import com.emfitsolutions.gopreach.ui.screens.weather.WeatherViewModel
import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

private const val PAGASA_ADVISORY = "https://pagasa.dost.gov.ph/weather/weather-advisory"
private const val PAGASA_BULLETIN = "https://pagasa.dost.gov.ph/tropical-cyclone/severe-weather-bulletin"
private const val PAGASA_ALERTS = "https://publicalert.pagasa.dost.gov.ph/"
private const val OPEN_METEO ="https://open-meteo.com/"

private val Caution = Color(0xFFE0A100)
private val Warning = Color(0xFFD32F2F)

/**
 * Weather for every signed-in user, whatever their role. It reads only public weather data and this device's own saved places;
 * it never touches congregation, publisher or report records. Territory places are limited to the territories the caller may already see.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherScreen(
    personId: String,
    territoryScope: TerritoryWeatherScope,
    onBack: () -> Unit,
    /** Shown inside another screen (the Publisher home tab): no top bar of its own; refresh moves into the list. */
    embedded: Boolean = false,
    viewModel: WeatherViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The narrator lives here, outside the scrolling list, so scrolling the player off screen never stops the speech; it pauses when the app is left.
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val narrator = remember { ReportNarrator(appContext, emptyList()) }
    // What is read aloud: the forecast, advice and official warnings of the place on screen, rebuilt whenever any of them changes.
    val narrationLang = com.emfitsolutions.gopreach.ui.components.rememberNarrationLanguage()
    val narrationScript = remember(state.report, state.alerts, state.alertsStatus, state.source, narrationLang) {
        state.report?.let { r ->
            WeatherNarration.script(r, nowMillis(), WeatherAdvisory.build(r, nowMillis()), if (state.alertsStatus == AlertsStatus.LOADED) state.alerts else null, state.source == WeatherSource.SAVED_COPY, narrationLang)
        }.orEmpty()
    }
    LaunchedEffect(narrationScript) { narrator.load(narrationScript) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) narrator.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); narrator.shutdown() }
    }
    val permission = rememberPermissionRequester { granted -> if (granted) viewModel.useCurrentLocation() else viewModel.onLocationPermissionDenied() }
    // First visit with no place chosen: use the device location (asking for permission once, now that it is needed).
    LaunchedEffect(personId) {
        viewModel.start(personId, territoryScope)
        if (viewModel.state.value.place == null) {
            if (viewModel.hasLocationPermission()) viewModel.useCurrentLocation() else permission.launch(AppPermission.LOCATION)
        }
    }
    var picker by remember { mutableStateOf<Boolean?>(null) }
    val showPicker = picker ?: (state.place == null)
    val status = statusBanner(state)
    var renaming by remember { mutableStateOf<WeatherPlace?>(null) }
    var saving by remember { mutableStateOf<WeatherPlace?>(null) }

    Scaffold(
        topBar = {
            if (!embedded) TopAppBar(
                title = { Text("Weather") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (state.place != null) {
                        if (state.loading) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 0.dp), strokeWidth = 2.dp)
                        IconButton(onClick = viewModel::refresh, enabled = !state.loading) { Icon(Icons.Rounded.Refresh, contentDescription = "Refresh weather") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (embedded && state.place != null) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    IconButton(onClick = viewModel::refresh, enabled = !state.loading) { Icon(Icons.Rounded.Refresh, contentDescription = "Refresh weather") }
                }
            }
            item { LocationHeader(state, showPicker) { picker = !showPicker } }
            item {
                AnimatedVisibility(showPicker) {
                    LocationPicker(
                        state = state,
                        onQuery = viewModel::onQueryChange,
                        onCurrent = { if (viewModel.hasLocationPermission()) viewModel.useCurrentLocation() else permission.launch(AppPermission.LOCATION) },
                        onSelect = { viewModel.select(it); picker = false },
                        onSave = { saving = it },
                        onRename = { renaming = it },
                        onRemove = { viewModel.removePlace(it.id) },
                    )
                }
            }
            state.notice?.let { msg -> item { Banner(msg, Icons.Rounded.Info, MaterialTheme.colorScheme.primary) } }
            status?.let { (msg, icon, tint) -> item { Banner(msg, icon, tint) } }

            val report = state.report
            if (report != null) {
                val now = nowMillis()
                report.current?.let { c -> item { CurrentCard(report, c, state.source == WeatherSource.SAVED_COPY, now) } }
                item { ReadReportPanel(narrator, narrationScript, label = "Listen to the weather") }
                item { AdvisorySection(WeatherAdvisory.build(report, now), state.source == WeatherSource.SAVED_COPY) }
                item { HourlySection(report, now) }
                item { DailySection(report, now) }
            } else if (state.loading) {
                item { LoadingCard() }
            } else if (state.place != null && state.error != null) {
                item { EmptyState("Weather could not be loaded for ${state.place!!.name}.", "Pull the refresh button above once you are online.") }
            } else if (state.place == null) {
                item { EmptyState("Choose a place", "Use your current location, search for a municipality, city or barangay, or pick a saved place or territory.") }
            }
            item { AlertsSection(state.place, state) }
            item { Attribution() }
        }
    }

    renaming?.let { place ->
        NameDialog("Rename saved place", place.name, "Save", onDismiss = { renaming = null }) { viewModel.renamePlace(place.id, it); renaming = null }
    }
    saving?.let { place ->
        NameDialog("Save this place", place.name, "Save", onDismiss = { saving = null }) { viewModel.savePlace(place, it); saving = null }
    }
}

private data class StatusLine(val message: String, val icon: ImageVector, val tint: Color)

@Composable
private fun statusBanner(state: WeatherUiState): StatusLine? {
    val report = state.report
    val tint = MaterialTheme.colorScheme.error
    return when {
        state.error != null -> StatusLine(state.error!!, if (state.failure == com.emfitsolutions.gopreach.domain.weather.WeatherFailure.OFFLINE) Icons.Rounded.CloudOff else Icons.Rounded.ErrorOutline, tint)
        report != null && state.source == WeatherSource.SAVED_COPY ->
            StatusLine("Saved forecast from ${stamp(report)}. It may be out of date - it is not current live weather.", Icons.Rounded.CloudOff, Caution)
        else -> null
    }
}

private fun stamp(report: WeatherReport): String = formatDate(report.fetchedAtMillis, "EEE, MMM d 'at' h:mm a")

private fun zone(report: WeatherReport): TimeZone = FixedOffsetTimeZone(UtcOffset(seconds = report.utcOffsetSeconds))

@Composable
private fun LocationHeader(state: WeatherUiState, open: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                state.place?.name ?: "No place selected",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            state.place?.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        TextButton(onClick = onToggle) { Text(if (open) "Close" else "Change") }
    }
}

@Composable
private fun Banner(message: String, icon: ImageVector, tint: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = 0.12f), border = BorderStroke(1.dp, tint.copy(alpha = 0.5f))) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LoadingCard() {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Loading the forecast...", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.WbCloudy, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

internal fun weatherIcon(code: Int?, isDay: Boolean = true): ImageVector = when (WeatherCodes.kind(code)) {
    WeatherKind.CLEAR -> if (isDay) Icons.Rounded.WbSunny else Icons.Rounded.NightsStay
    WeatherKind.PARTLY_CLOUDY -> Icons.Rounded.WbCloudy
    WeatherKind.CLOUDY, WeatherKind.FOG, WeatherKind.UNKNOWN -> Icons.Rounded.Cloud
    WeatherKind.DRIZZLE, WeatherKind.RAIN, WeatherKind.SHOWERS -> Icons.Rounded.WaterDrop
    WeatherKind.SNOW -> Icons.Rounded.AcUnit
    WeatherKind.THUNDERSTORM -> Icons.Rounded.Thunderstorm
}

private fun degrees(v: Double?) = v?.let { "${it.roundToInt()}°" } ?: "--"
private fun mm(v: Double?) = v?.let { if (it < 0.05) "0 mm" else if (it < 10) "${(it * 10).roundToInt() / 10.0} mm" else "${it.roundToInt()} mm" }

@Composable
private fun CurrentCard(report: WeatherReport, c: CurrentWeather, saved: Boolean, now: Long) {
    val today = report.daily.firstOrNull { it.dateMillis == report.startOfLocalDay(now) }
    val nextHour = report.hoursOfDay(now).firstOrNull { it.timeMillis + WeatherReport.HOUR_MS > now }
    val label = WeatherCodes.label(c.weatherCode)
    val summary = "Current weather in ${report.place.name}: $label, ${c.temperatureC?.roundToInt() ?: "unknown"} degrees Celsius" +
        (c.feelsLikeC?.let { ", feels like ${it.roundToInt()}" } ?: "") + (if (saved) ". Saved forecast, may be out of date." else "")
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp).semantics(mergeDescendants = true) { contentDescription = summary }) {
            Text(if (saved) "Last saved conditions" else "Current conditions", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(weatherIcon(c.weatherCode, c.isDay), contentDescription = null, modifier = Modifier.size(64.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(c.temperatureC?.let { "${it.roundToInt()}°C" } ?: "--", fontSize = 56.sp, fontWeight = FontWeight.Bold)
                    Text(label, style = MaterialTheme.typography.titleMedium)
                }
            }
            Text("Feels like ${degrees(c.feelsLikeC)}C", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Fact("Humidity", c.humidityPercent?.let { "$it%" } ?: "--", Modifier.weight(1f))
                    Fact("Wind", c.windSpeedKmh?.let { "${it.roundToInt()} km/h ${compassPoint(c.windDirectionDeg).orEmpty()}".trim() } ?: "--", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    Fact("Chance of rain", (nextHour?.precipitationProbability ?: today?.precipitationProbability)?.let { "$it%" } ?: "Not available", Modifier.weight(1f))
                    Fact("Rain now", mm(c.precipitationMm) ?: "Not available", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    Fact("Sunrise", today?.sunriseMillis?.let { formatDate(it, "h:mm a", zone(report)) } ?: "Not available", Modifier.weight(1f))
                    Fact("Sunset", today?.sunsetMillis?.let { formatDate(it, "h:mm a", zone(report)) } ?: "Not available", Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (saved) "Saved ${stamp(report)} (${report.place.name} time)" else "Updated ${formatDate(report.fetchedAtMillis, "h:mm a, MMM d", zone(report))}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp).semantics { heading() })
}

@Composable
private fun AdvisorySection(advisory: Advisory, saved: Boolean) {
    Column {
        SectionTitle("Field Ministry Weather Advisory")
        Surface(shape = RoundedCornerShape(16.dp), color = Color.Transparent, modifier = Modifier.glass(RoundedCornerShape(16.dp))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (saved) Text("Based on a saved forecast that may be out of date.", style = MaterialTheme.typography.labelMedium, color = Caution)
                advisory.items.forEach { item ->
                    val tint = when (item.level) { AdvisoryLevel.WARNING -> Warning; AdvisoryLevel.CAUTION -> Caution; AdvisoryLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant }
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(if (item.level == AdvisoryLevel.INFO) Icons.Rounded.Info else Icons.Rounded.Warning, contentDescription = when (item.level) { AdvisoryLevel.WARNING -> "Important"; AdvisoryLevel.CAUTION -> "Caution"; else -> "Note" }, tint = tint, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(item.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(Advisory.DISCLAIMER, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HourlySection(report: WeatherReport, now: Long) {
    val hours = report.hoursOfDay(now)
    Column {
        SectionTitle("Today, hour by hour")
        if (hours.isEmpty()) { Text("The hourly forecast is not available.", style = MaterialTheme.typography.bodyMedium); return }
        val currentIndex = hours.indexOfFirst { it.timeMillis + WeatherReport.HOUR_MS > now }.coerceAtLeast(0)
        val listState = rememberLazyListState()
        LaunchedEffect(report.fetchedAtMillis, report.place.key) { listState.scrollToItem(currentIndex) }
        LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(hours) { i, h -> HourCard(h, report, highlighted = i == currentIndex) }
        }
        Text("% is the chance of rain in that hour. mm is how much rain is expected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun HourCard(h: HourlyForecast, report: WeatherReport, highlighted: Boolean) {
    val time = formatDate(h.timeMillis, "h a", zone(report))
    val label = WeatherCodes.label(h.weatherCode)
    val speech = "$time, $label, ${h.temperatureC?.roundToInt() ?: "unknown"} degrees" +
        (h.precipitationProbability?.let { ", $it percent chance of rain" } ?: "") + (mm(h.precipitationMm)?.let { ", $it expected" } ?: "")
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = if (highlighted) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.width(84.dp).semantics(mergeDescendants = true) { contentDescription = speech },
    ) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 6.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(time, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Icon(weatherIcon(h.weatherCode, report.localHour(h.timeMillis) in 6..17), contentDescription = null, modifier = Modifier.size(28.dp))
            Text(degrees(h.temperatureC), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(h.precipitationProbability?.let { "$it%" } ?: "--%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(mm(h.precipitationMm) ?: "-- mm", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DailySection(report: WeatherReport, now: Long) {
    Column {
        SectionTitle("7-day forecast")
        if (report.daily.isEmpty()) { Text("The daily forecast is not available.", style = MaterialTheme.typography.bodyMedium); return }
        val todayStart = report.startOfLocalDay(now)
        Surface(shape = RoundedCornerShape(16.dp), color = Color.Transparent, modifier = Modifier.glass(RoundedCornerShape(16.dp))) {
            Column(Modifier.fillMaxWidth()) {
                report.daily.forEachIndexed { i, d ->
                    DayRow(d, report, isToday = d.dateMillis == todayStart)
                    if (i < report.daily.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun DayRow(d: DailyForecast, report: WeatherReport, isToday: Boolean) {
    val day = if (isToday) "Today" else formatDate(d.dateMillis, "EEEE", zone(report))
    val date = formatDate(d.dateMillis, "MMM d", zone(report))
    val label = WeatherCodes.label(d.weatherCode)
    val speech = "$day, $date. $label. High ${d.highC?.roundToInt() ?: "unknown"}, low ${d.lowC?.roundToInt() ?: "unknown"} degrees." +
        (d.precipitationProbability?.let { " $it percent chance of rain." } ?: "") + (mm(d.precipitationMm)?.let { " $it expected." } ?: "")
    Row(Modifier.fillMaxWidth().padding(12.dp).semantics(mergeDescendants = true) { contentDescription = speech }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.2f)) {
            Text(day, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(weatherIcon(d.weatherCode), contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1.4f).padding(start = 10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(d.precipitationProbability?.let { "$it% chance" }, mm(d.precipitationMm)).joinToString(" · ").ifBlank { "Rain: not available" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(degrees(d.highC), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(degrees(d.lowC), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AlertsSection(place: WeatherPlace?, state: WeatherUiState) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Official weather warnings")
        if (state.alertsStatus == AlertsStatus.LOADED) state.alerts.forEach { AlertCard(it) }
        Surface(shape = RoundedCornerShape(16.dp), color = Color.Transparent, modifier = Modifier.glass(RoundedCornerShape(16.dp))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.alertsStatus) {
                    AlertsStatus.LOADED -> {
                        Text(
                            if (state.alerts.isEmpty()) "PAGASA has no active warning for this place." else "${state.alerts.size} active PAGASA warning${if (state.alerts.size == 1) "" else "s"} for this place.",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Checked ${state.alertsCheckedAt?.let { formatDate(it, "EEE, MMM d 'at' h:mm a") } ?: "just now"}. Warnings are matched to the place by map area, " +
                                "and PAGASA may issue a warning at any time - check the PAGASA site before outdoor activities.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AlertsStatus.LOADING, AlertsStatus.IDLE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Checking PAGASA warnings...", style = MaterialTheme.typography.bodyMedium)
                    }
                    AlertsStatus.FAILED -> {
                        Text("Official warnings could not be checked.", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            (state.alertsMessage ?: "") + " That is not a sign that there is no hazard - check the PAGASA site directly.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    AlertsStatus.NOT_APPLICABLE -> Text(
                        "PAGASA warnings cover the Philippines only. Check the weather agency of this country for official warnings.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.alertsStatus != AlertsStatus.NOT_APPLICABLE && (place == null || place.isPhilippines || place.countryCode.isBlank())) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { uriHandler.openUri(PAGASA_ADVISORY) }) { Text("PAGASA advisories") }
                        OutlinedButton(onClick = { uriHandler.openUri(PAGASA_BULLETIN) }) { Text("Severe weather bulletin") }
                    }
                    Text("Opens the PAGASA website in your browser.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AlertCard(alert: OfficialAlert) {
    val tint = if (alert.severityRank >= 3) Warning else Caution
    var expanded by rememberSaveable(alert.id) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.5.dp, tint)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Warning, contentDescription = "Warning", tint = tint, modifier = Modifier.size(22.dp))
                Text(alert.event.ifBlank { alert.headline }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            Text(
                listOfNotNull(
                    "Severity: ${alert.severity}",
                    alert.sentMillis?.let { "Issued ${formatDate(it, "EEE, MMM d, h:mm a")}" },
                    alert.expiresMillis?.let { "Until ${formatDate(it, "EEE, MMM d, h:mm a")}" },
                ).joinToString(" | "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (alert.areaNames.isNotEmpty()) Text("Areas: ${alert.areaNames.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            if (alert.instruction.isNotBlank()) Text(alert.instruction.replace("**", ""), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (expanded && alert.description.isNotBlank()) Text(alert.description.replace("**", "").replace(Regex("\\n{3,}"), "\n\n"), style = MaterialTheme.typography.bodySmall)
            if (alert.description.isNotBlank()) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Show details") }
        }
    }
}

@Composable
private fun Attribution() {
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { uriHandler.openUri(OPEN_METEO) }, modifier = Modifier.fillMaxWidth()) {
        Text("Weather data by Open-Meteo.com (CC BY 4.0)", style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = { uriHandler.openUri(PAGASA_ALERTS) }, modifier = Modifier.fillMaxWidth()) {
        Text("Official warnings by PAGASA-DOST (CC BY 4.0)", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LocationPicker(
    state: WeatherUiState,
    onQuery: (String) -> Unit,
    onCurrent: () -> Unit,
    onSelect: (WeatherPlace) -> Unit,
    onSave: (WeatherPlace) -> Unit,
    onRename: (WeatherPlace) -> Unit,
    onRemove: (WeatherPlace) -> Unit,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color.Transparent, modifier = Modifier.glass(RoundedCornerShape(16.dp))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onCurrent, enabled = !state.locating, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.MyLocation, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.locating) "Finding your location..." else "Use my current location")
            }
            state.locationMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                label = { Text("Search municipality, city or barangay") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.searching) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Searching...", style = MaterialTheme.typography.bodySmall) }
            state.searchMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            state.results.forEach { place -> PlaceRow(place, Icons.Rounded.Place, onSelect = { onSelect(place) }, actions = { IconButton(onClick = { onSave(place) }) { Icon(Icons.Rounded.Star, contentDescription = "Save ${place.name}") } }) }

            if (state.saved.isNotEmpty()) {
                Text("Saved places", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                state.saved.forEach { place ->
                    PlaceRow(place, Icons.Rounded.Star, onSelect = { onSelect(place) }, actions = {
                        IconButton(onClick = { onRename(place) }) { Icon(Icons.Rounded.Edit, contentDescription = "Rename ${place.name}") }
                        IconButton(onClick = { onRemove(place) }) { Icon(Icons.Rounded.Delete, contentDescription = "Remove ${place.name}") }
                    })
                }
            }
            val chosen = state.place
            if (chosen != null && state.saved.none { it.key == chosen.key }) {
                OutlinedButton(onClick = { onSave(chosen) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Star, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Save ${chosen.name}") }
            }
            if (state.territories.isNotEmpty()) {
                Text("Territories", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Text("Weather at the middle of the territory - not necessarily where you are.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.territories.take(40).forEach { place -> PlaceRow(place, Icons.Rounded.Map, onSelect = { onSelect(place) }) }
            }
        }
    }
}

@Composable
private fun PlaceRow(place: WeatherPlace, icon: ImageVector, onSelect: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(place.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (place.detail.isNotBlank()) Text(place.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        scrollBody = true,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it.take(60) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
