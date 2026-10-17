package com.emfitsolutions.gopreach.ui.screens.pipeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.TerritoryAssignmentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The filters of the House Holder Records screen. A null id / blank name means "All". */
data class HouseHolderFilter(
    val congregationId: String? = null,
    val fsGroupId: String? = null,
    val province: String? = null,
    val city: String? = null,
    val barangay: String? = null,
)

/**
 * Records of one stage plus the dropdown options. Province / Municipality-City / Barangay come from what the congregation
 * has enrolled in Territory Assignment (the areas its Territory Map shows), not from the records themselves.
 */
data class HouseHolderRecordsState(
    val records: List<InterestedPerson> = emptyList(),
    val provinces: List<String> = emptyList(),
    val cities: List<String> = emptyList(),
    val barangays: List<String> = emptyList(),
)

private fun String?.key() = this?.trim()?.lowercase().orEmpty()

/**
 * Backs [HouseHolderRecordsScreen]. A House Holder belongs to an FS Group when the publisher it is assigned to is a
 * member of that group (the publisher's RoleAssignment.groupId).
 */
class HouseHolderRecordsViewModel(
    private val interestedPersonRepository: InterestedPersonRepository,
    private val groupRepository: GroupRepository,
    private val congregationRepository: CongregationRepository,
    private val territoryAssignmentRepository: TerritoryAssignmentRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val personRepository: PersonRepository,
) : ViewModel() {

    val congregations: StateFlow<List<Congregation>> =
        congregationRepository.observeAll().map { list -> list.filter { it.status == RecordStatus.ACTIVE }.sortedBy { it.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groups: StateFlow<List<Group>> =
        groupRepository.observeAll().map { list -> list.filter { it.status == RecordStatus.ACTIVE } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** personId -> full name, so the table view can show each record's assigned publisher without a flow per row. */
    val personNames: StateFlow<Map<String, String>> =
        personRepository.observeAll().map { people -> people.associate { it.id to it.fullName } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun personName(personId: String): Flow<String?> =
        personRepository.observeAll().map { people -> people.firstOrNull { it.id == personId }?.fullName }

    /**
     * Records at [stage] inside [congregationIds] (null = every congregation), narrowed to [ownGroupId]'s publishers for a
     * Regular Elder, then by [filter]. The Province / Municipality-City / Barangay options come from the congregation's
     * enrolled territory (Territory Assignment), cascading Province -> Municipality -> Barangay.
     */
    fun records(stage: PipelineStage, congregationIds: Set<String>?, ownGroupId: String?, filter: HouseHolderFilter): Flow<HouseHolderRecordsState> =
        combine(
            interestedPersonRepository.observeAll(),
            roleAssignmentRepository.observeAll(),
            territoryAssignmentRepository.observeAssignments(),
            territoryAssignmentRepository.observeBarangayClaims(),
        ) { people, assignments, territories, claims ->
            val ownGroupPublishers = if (ownGroupId == null) null else assignments
                .filter { it.resolvedRoleTypeOrNull() is RoleType.Publisher && it.groupId == ownGroupId }
                .map { it.personId }
                .toSet()
            val fsGroupPublishers: Set<String>? = filter.fsGroupId?.let { gid ->
                assignments
                    .filter { it.resolvedRoleTypeOrNull() is RoleType.Publisher && it.groupId == gid }
                    .map { it.personId }
                    .toSet()
            }
            val inGroup = people
                .asSequence()
                .filter { it.pipelineStage == stage }
                .filter { congregationIds == null || it.congregationId in congregationIds }
                .filter { filter.congregationId == null || it.congregationId == filter.congregationId }
                .filter { ownGroupPublishers == null || it.publisherPersonId in ownGroupPublishers }
                .filter { fsGroupPublishers == null || it.publisherPersonId in fsGroupPublishers }
                .toList()
            // What the in-scope congregation(s) have enrolled: provinces (Territory Assignment headers), and the
            // municipalities / barangays claimed under them.
            val inScope = { congregationId: String ->
                (congregationIds == null || congregationId in congregationIds) &&
                    (filter.congregationId == null || congregationId == filter.congregationId)
            }
            val scopedTerritories = territories.filter { inScope(it.congregationId) }
            val scopedClaims = claims.filter { inScope(it.congregationId) }
            val provinceIds = if (filter.province == null) null else
                scopedTerritories.filter { it.provinceName.key() == filter.province.key() }.map { it.provinceId }.toSet()
            val claimsInProvince = if (provinceIds == null) scopedClaims else scopedClaims.filter { it.provinceId in provinceIds }
            val claimsInCity = if (filter.city == null) claimsInProvince else claimsInProvince.filter { it.muncityName.key() == filter.city.key() }

            val filtered = inGroup
                .filter { filter.province == null || it.province.key() == filter.province.key() }
                .filter { filter.city == null || it.cityMunicipality.key() == filter.city.key() }
                .filter { filter.barangay == null || it.barangay.key() == filter.barangay.key() }
            HouseHolderRecordsState(
                records = filtered,
                provinces = scopedTerritories.map { it.provinceName.trim() }.filter(String::isNotEmpty).distinctBy { it.key() }.sorted(),
                cities = claimsInProvince.map { it.muncityName.trim() }.filter(String::isNotEmpty).distinctBy { it.key() }.sorted(),
                barangays = claimsInCity.map { it.barangayName.trim() }.filter(String::isNotEmpty).distinctBy { it.key() }.sorted(),
            )
        }
}
