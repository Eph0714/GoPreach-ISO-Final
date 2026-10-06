package com.emfitsolutions.gopreach.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.DashboardModuleId
import com.emfitsolutions.gopreach.data.model.DashboardModuleLayout
import com.emfitsolutions.gopreach.data.model.DashboardModuleLocation
import com.emfitsolutions.gopreach.data.model.PlannerSection
import com.emfitsolutions.gopreach.data.model.withPlannerSectionVisible
import com.emfitsolutions.gopreach.data.model.moved
import com.emfitsolutions.gopreach.data.model.reset
import com.emfitsolutions.gopreach.data.model.sidePanelModules
import com.emfitsolutions.gopreach.data.repository.DashboardModuleLayoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Publishers App – Customizable Module Navigation Redesign" — a thin
 * read/write layer over [DashboardModuleLayoutRepository] for
 * [com.emfitsolutions.gopreach.ui.screens.home.PublisherHomeScreen]. Holds no
 * permission logic of its own (spec §10/§34's own reasoning from the earlier
 * Admin Dashboard reorg applies identically here): every module id this
 * screen ever offers to move is already an authorized, already-reachable
 * tile — this class only ever changes *where* one is drawn.
 */
@HiltViewModel
class PublisherDashboardLayoutViewModel @Inject constructor(
    private val repository: DashboardModuleLayoutRepository,
) : ViewModel() {

    private val personId = MutableStateFlow<String?>(null)

    /** Called every time the screen (re)composes with a resolved session —
     * a plain reassignment, not a one-shot latch, since unlike a
     * congregation-scoping boundary this is never a security narrowing that
     * must resist widening back; it just needs to always point at whichever
     * account is actually signed in. */
    fun setPersonId(id: String) {
        if (id.isNotBlank() && personId.value != id) personId.value = id
    }

    val layout: StateFlow<DashboardModuleLayout> = personId.filterNotNull()
        .flatMapLatest { repository.observeFor(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardModuleLayout())

    /** Spec §2 — moves [moduleId] to [to] and saves once immediately; the
     * screen's own [layout] StateFlow (backed by the same offline-first
     * cache every save reads back from) refreshes on its own the moment the
     * write lands, satisfying spec §2/§11's "refresh the dashboard
     * automatically" without this function doing anything extra. */
    fun moveModule(moduleId: DashboardModuleId, to: DashboardModuleLocation) {
        val id = personId.value ?: return
        val current = layout.value.copy(personId = id)
        viewModelScope.launch { repository.save(current.moved(moduleId, to)) }
    }

    /** My Planner → Planner Sections: show/hide one section for this
     * Publisher. Saved on their own layout document (synced per account);
     * display-only — no records are ever touched. */
    fun setPlannerSectionVisible(section: PlannerSection, visible: Boolean) {
        val id = personId.value ?: return
        viewModelScope.launch { repository.save(layout.value.copy(personId = id).withPlannerSectionVisible(section, visible)) }
    }

    /** "Reset Dashboard Layout" (spec §9). */
    fun resetLayout() {
        val id = personId.value ?: return
        viewModelScope.launch { repository.save(layout.value.copy(personId = id).reset()) }
    }
}

/** Where [moduleId] currently is under [layout] — used by the long-press
 * action menu to decide which single destination to offer (spec §2: "Move
 * to Side Panel" only appears for a Main Form module, and vice versa). */
fun DashboardModuleLayout.locationOf(moduleId: DashboardModuleId): DashboardModuleLocation =
    if (moduleId in sidePanelModules()) DashboardModuleLocation.SIDE_PANEL else DashboardModuleLocation.MAIN_FORM
