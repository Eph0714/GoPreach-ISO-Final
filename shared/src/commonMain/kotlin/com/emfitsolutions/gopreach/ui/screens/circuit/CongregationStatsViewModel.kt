package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The three numbers shown on a congregation card. */
data class CongregationQuickStats(val publishers: Int = 0, val elders: Int = 0, val groups: Int = 0)

/**
 * Counts for the congregation cards of the Circuit Overseer's picker. It only counts what this device holds, and a Circuit
 * Overseer's device only ever receives the congregations assigned to them, so a congregation outside the assignment can
 * neither appear on a card nor get a count here.
 */
class CongregationStatsViewModel(
    roleAssignmentRepository: RoleAssignmentRepository,
    groupRepository: GroupRepository,
) : ViewModel() {
    private val elderRoles = setOf(AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY, AdminRole.REGULAR_ELDER)

    val stats: StateFlow<Map<String, CongregationQuickStats>> =
        combine(roleAssignmentRepository.observeAll(), groupRepository.observeAll()) { assignments, groups ->
            val active = assignments.filter { it.status == RoleAssignmentStatus.ACTIVE }
            val byCongregation = active.filter { !it.congregationId.isNullOrBlank() }.groupBy { it.congregationId.orEmpty() }
            val groupCounts = groups.groupingBy { it.congregationId }.eachCount()
            (byCongregation.keys + groupCounts.keys).filter { it.isNotBlank() }.associateWith { id ->
                val mine = byCongregation[id].orEmpty()
                CongregationQuickStats(
                    publishers = mine
                        .mapNotNull { a -> (a.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category?.takeIf { it != PublisherCategory.REMOVED_PUBLISHER }?.let { a.personId } }
                        .toSet().size,
                    elders = mine.mapNotNull { a -> (a.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role?.takeIf { it in elderRoles }?.let { a.personId } }.toSet().size,
                    groups = groupCounts[id] ?: 0,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}
