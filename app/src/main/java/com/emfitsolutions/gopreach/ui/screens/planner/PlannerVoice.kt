package com.emfitsolutions.gopreach.ui.screens.planner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.emfitsolutions.gopreach.ui.screens.circuit.ReadReportPanel
import com.emfitsolutions.gopreach.ui.screens.circuit.ReportNarrator

/**
 * "Listen" for the Planner: reads [script] aloud with the phone's own offline voice (the same player as the Comparative Report and Weather), with
 * Play / Pause / Stop / Read Again, speed and "Show text". The script is replaced - and whatever was playing stops - whenever the chosen months change.
 * It pauses when the app leaves the screen.
 */
@Composable
internal fun PlannerVoice(script: List<String>, label: String = "Listen to my planner") {
    val context = LocalContext.current
    val narrator = remember { ReportNarrator(context, emptyList()) }
    LaunchedEffect(script) { narrator.load(script) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) narrator.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); narrator.shutdown() }
    }
    ReadReportPanel(narrator, script, label)
}

/** "Listen" for one Planner view (Today, Week, Month or Year): reads the period on screen, honouring the sections the Publisher has hidden. */
@Composable
internal fun PlannerPeriodVoice(period: com.emfitsolutions.gopreach.domain.PlannerNarration.Period) {
    val lang = com.emfitsolutions.gopreach.ui.components.rememberNarrationLanguage()
    val script = remember(period, lang) { com.emfitsolutions.gopreach.domain.PlannerNarration.periodScript(period, lang) }
    PlannerVoice(script, "Listen to my planner")
}

internal fun plannerPeriod(
    unit: String, label: String, totalMinutes: Int, creditMinutes: Int, returnVisits: Int, bibleStudies: Int,
    goalHours: Int, remainingMinutes: Int, surplusMinutes: Int, visibility: PlannerVisibility,
    monthRows: List<com.emfitsolutions.gopreach.domain.PlannerNarration.Month> = emptyList(),
) = com.emfitsolutions.gopreach.domain.PlannerNarration.Period(
    unit, label, totalMinutes, creditMinutes, returnVisits, bibleStudies, goalHours, remainingMinutes, surplusMinutes,
    visibility.hours, visibility.creditHours, visibility.returnVisits, visibility.bibleStudies, monthRows,
)
