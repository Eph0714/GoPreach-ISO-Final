package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.TimerSessionStatus
import com.emfitsolutions.gopreach.domain.DayBounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private fun formatHoursMinutes(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h == 0) "${m}m" else if (m == 0) "${h}h" else "${h}h ${m}m"
}

/**
 * Ministry Timer (spec §20, extended with real Play/Pause/Stop) — a circular
 * dial matching the "Pioneer Planner" reference design, reusable on both the
 * Publisher Main Interface and My Planner → Day. Elapsed time is computed
 * live from the active session's own `startTime`/`accumulatedSeconds` (see
 * [MinistryTimerViewModel]) rather than a local counter this composable
 * owns — the same still-active Firestore document is what actually survives
 * navigating away and back, this is just a display of it.
 */
@Composable
fun MinistryTimerCard(
    publisherPersonId: String,
    modifier: Modifier = Modifier,
    /** My Planner → Day already renders its own "Ministry timer" section
     * label above this composable, so it passes false to avoid a duplicate
     * heading; every other call site keeps the default. */
    showLabel: Boolean = true,
    /** "The saved Ministry Hour and Minutes must belong to the selected
     * planner date" — My Planner → Day passes its own `dayStart` (the date
     * currently being viewed there, same target every other Day-tab edit
     * already writes to), so a session stopped while looking at a different
     * day than today still saves to the day on screen. Every other call site
     * (Publisher Main Form) has no such date picker, so it defaults to
     * today. */
    targetDayMillis: Long = DayBounds.of(System.currentTimeMillis()).startInclusive,
    viewModel: MinistryTimerViewModel = hiltViewModel(),
) {
    // remember(publisherPersonId): without this, the 1-second ticking effect
    // below would cause this to call runningSessionFor() fresh on every
    // recomposition — a brand-new Flow subscription (and a flash back to
    // "no session" before it re-emits) instead of the same live one.
    val session by remember(publisherPersonId) { viewModel.runningSessionFor(publisherPersonId) }
        .collectAsStateWithLifecycle(initialValue = null)
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val isRunning = session?.status == TimerSessionStatus.RUNNING
    val coroutineScope = rememberCoroutineScope()

    // STOP's two confirmation dialogs (spec: "the system must never
    // automatically overwrite an existing Ministry Time record"):
    // pendingSaveResult drives "Do you want to save today's H/M ministry?"
    // (Yes with no existing record just saves directly); pendingAddResult
    // drives the follow-up "already saved for this day, add to it?" prompt,
    // shown only when Yes was chosen and the target day already has minutes.
    var pendingSaveResult by remember { mutableStateOf<MinistryTimerStopResult?>(null) }
    var pendingAddResult by remember { mutableStateOf<MinistryTimerStopResult?>(null) }

    LaunchedEffect(session?.id, isRunning) {
        while (isRunning) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }

    val elapsedSeconds = session?.let { s ->
        s.accumulatedSeconds + if (s.status == TimerSessionStatus.RUNNING) ((nowMillis - s.startTime) / 1000).coerceAtLeast(0) else 0L
    } ?: 0L
    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60
    val timeText = if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
    // Cosmetic sweep marker (not a real progress bar — there's no fixed
    // duration to complete) showing the dial is live: one full revolution
    // per minute of elapsed ministry time. Frozen (last position) while
    // PAUSED, since the count itself isn't advancing either.
    val sweepAngleDegrees = (seconds % 60) / 60f * 360f - 90f

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (showLabel) {
                Text("MINISTRY TIMER", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = {
                            when {
                                session == null -> viewModel.start(publisherPersonId)
                                isRunning -> viewModel.pause(publisherPersonId)
                                else -> viewModel.resume(publisherPersonId)
                            }
                        },
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(
                            if (isRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = when {
                                session == null -> "Start ministry timer"
                                isRunning -> "Pause ministry timer"
                                else -> "Resume ministry timer"
                            },
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    if (session != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                            IconButton(onClick = {
                                coroutineScope.launch {
                                    val result = viewModel.stopAndFinalize(publisherPersonId, targetDayMillis)
                                    if (result != null) pendingSaveResult = result
                                }
                            }) {
                                Icon(Icons.Rounded.Stop, contentDescription = "Stop ministry timer", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { viewModel.reset(publisherPersonId) }) {
                                Icon(Icons.Rounded.Replay, contentDescription = "Reset ministry timer", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                Box(modifier = Modifier.size(160.dp).padding(start = 24.dp), contentAlignment = Alignment.Center) {
                    val ringColor = MaterialTheme.colorScheme.outlineVariant
                    val markerColor = MaterialTheme.colorScheme.primary
                    Canvas(modifier = Modifier.size(160.dp)) {
                        val strokeWidthPx = 3.dp.toPx()
                        val radius = size.minDimension / 2 - strokeWidthPx
                        drawCircle(color = ringColor, radius = radius, style = Stroke(width = strokeWidthPx))
                        if (session != null) {
                            val angleRad = Math.toRadians(sweepAngleDegrees.toDouble())
                            val markerCenter = Offset(
                                x = center.x + radius * cos(angleRad).toFloat(),
                                y = center.y + radius * sin(angleRad).toFloat(),
                            )
                            drawCircle(color = markerColor, radius = 6.dp.toPx(), center = markerCenter)
                        }
                    }
                    Text(timeText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    pendingSaveResult?.let { result ->
        AlertDialog(
            onDismissRequest = { pendingSaveResult = null },
            title = { Text("Save Ministry Time?") },
            text = { Text("Do you want to save today's ${formatHoursMinutes(result.elapsedMinutes)} ministry?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingSaveResult = null
                    if (result.existingMinutesForTargetDay > 0) {
                        pendingAddResult = result
                    } else {
                        coroutineScope.launch { viewModel.confirmSaveElapsedMinutes(publisherPersonId, targetDayMillis, result.elapsedMinutes) }
                    }
                }) { Text("Yes") }
            },
            // "No" — the session is already stopped (stopAndFinalize already
            // marked it COMPLETED); this only skips adding its time into the
            // day's total, leaving any existing record untouched.
            dismissButton = { TextButton(onClick = { pendingSaveResult = null }) { Text("No") } },
        )
    }

    pendingAddResult?.let { result ->
        AlertDialog(
            onDismissRequest = { pendingAddResult = null },
            title = { Text("Add to Existing Record?") },
            text = {
                Text(
                    "There is already ${formatHoursMinutes(result.existingMinutesForTargetDay)} saved for this day. " +
                        "Do you want to add ${formatHoursMinutes(result.elapsedMinutes)} to the record?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingAddResult = null
                    coroutineScope.launch { viewModel.confirmSaveElapsedMinutes(publisherPersonId, targetDayMillis, result.elapsedMinutes) }
                }) { Text("Yes") }
            },
            dismissButton = { TextButton(onClick = { pendingAddResult = null }) { Text("No") } },
        )
    }
}
