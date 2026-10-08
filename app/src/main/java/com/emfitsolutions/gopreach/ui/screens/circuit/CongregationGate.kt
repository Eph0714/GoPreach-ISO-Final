package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.ui.components.co.CoCongregationPicker
import com.emfitsolutions.gopreach.ui.components.co.CoModuleHeader
import org.koin.compose.viewmodel.koinViewModel

/**
 * The congregation chosen for the detailed Circuit Overseer lists — only if it is one of [congregations] (the ones this
 * account may see). Anything else (a stale choice from another circuit/account, a removed assignment) counts as "none".
 */
fun validCongregation(congregations: List<Congregation>, chosenId: String?): Congregation? =
    congregations.firstOrNull { it.id == chosenId }

/**
 * "SELECT CONGREGATION" — shown before ANY detailed record is loaded or listed. A visual list of congregation cards (never a dropdown)
 * offering only [congregations] (the ones this account may see); there is deliberately no "All Congregations" choice here.
 */
@Composable
fun SelectCongregationPrompt(
    congregations: List<Congregation>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    statsViewModel: CongregationStatsViewModel = koinViewModel(),
) {
    val stats by statsViewModel.stats.collectAsStateWithLifecycle()
    CoCongregationPicker(
        congregations = congregations,
        stats = stats,
        onSelect = onSelect,
        modifier = modifier,
        hint = hint ?: "Choose a congregation under your assigned Circuit to view its records.",
    )
}

/** The selected congregation, prominent, with [Change Congregation] — stays visible while a congregation's records are shown. */
@Composable
fun SelectedCongregationBar(
    name: String,
    onChange: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    CoModuleHeader(congregationName = name, onChangeCongregation = onChange, modifier = modifier, title = title, actions = actions)
}
