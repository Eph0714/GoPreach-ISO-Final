package com.emfitsolutions.gopreach.ui.screens.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.domain.weather.WeatherCodes
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

/**
 * A small weather line for the dashboard header, e.g. "29°C · Light rain · Solano". It shows only the place the person chose in the
 * Weather module, and tapping it opens that module. It is drawn on the header colour, so it uses white text on a soft translucent pill.
 */
@Composable
fun WeatherSummaryLine(personId: String, onClick: () -> Unit, modifier: Modifier = Modifier, viewModel: WeatherSummaryViewModel = koinViewModel()) {
    LaunchedEffect(personId) { viewModel.refresh(personId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    if (!s.loaded) return

    val temp = s.temperatureC?.let { "${it.roundToInt()}°C" }
    val text = when {
        s.place == null -> "Weather · Choose a location"
        temp == null -> "${s.place!!.name} · Weather unavailable"
        else -> listOfNotNull(temp, WeatherCodes.label(s.weatherCode), s.place!!.name).joinToString(" · ") + if (s.saved) " · saved" else ""
    }
    val speech = when {
        s.place == null -> "Weather. Choose a location. Opens the weather module."
        temp == null -> "Weather for ${s.place!!.name} is unavailable. Opens the weather module."
        else -> "Weather in ${s.place!!.name}: $temp, ${WeatherCodes.label(s.weatherCode)}${if (s.saved) ", saved forecast, may be out of date" else ""}. Opens the weather module."
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp)
            .semantics(mergeDescendants = true) { contentDescription = speech },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (s.place == null || temp == null) Icons.Rounded.WbSunny else weatherIcon(s.weatherCode, s.isDay), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(text, color = Color.White.copy(alpha = 0.95f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
    }
}
