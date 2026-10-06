package com.emfitsolutions.gopreach.ui.screens.territoryassignments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.MunicipalitySelection
import com.emfitsolutions.gopreach.data.repository.PhilippineLocationRepository
import com.emfitsolutions.gopreach.data.repository.PsgcOption
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.TerritoryAssignmentRepository
import com.emfitsolutions.gopreach.domain.PermissionChecker
import com.emfitsolutions.gopreach.data.repository.TerritoryAssignmentResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A barangay row for the Step 3 checklist — [takenByGroupName] non-null
 * means another Group already claims it (never set for a barangay this same
 * Group already holds — see [TerritoryAssignmentWizardViewModel
 * .takenBarangays]'s own doc comment). */
data class BarangayChecklistRow(val option: PsgcOption, val takenByGroupName: String?)

data class WizardUiState(
    val isSaving: Boolean = false,
    /** The result of the last Save attempt — [TerritoryAssignmentResult
     * .Success] dismisses the wizard; [Conflict]/[Offline]/[Error] render as
     * a blocking inline message on the confirm step, same role [FormDialog]'s
     * own `errorMessage` plays elsewhere in this app. */
    val saveResult: TerritoryAssignmentResult? = null,
)

@HiltViewModel
class TerritoryAssignmentWizardViewModel @Inject constructor(
    private val territoryAssignmentRepository: TerritoryAssignmentRepository,
    private val groupRepository: GroupRepository,
    private val philippineLocationRepository: PhilippineLocationRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WizardUiState())
    val uiState: StateFlow<WizardUiState> = _uiState

    /** Super-Admin only — picking which congregation a brand-new session
     * belongs to; unused (and hidden by the screen) once [fixedCongregationId]
     * is non-null, or once a Group is already picked (congregationId is
     * immutable from that point on, matching [TerritoryAssignmentRepository
     * .saveGroupTerritoryForProvince]). */
    val congregations: Flow<List<Congregation>> = congregationRepository.observeAll()

    fun groupsFor(congregationId: String): Flow<List<Group>> =
        groupRepository.observeAll().map { groups ->
            groups.filter { it.congregationId == congregationId && it.status == RecordStatus.ACTIVE }.sortedWith(com.emfitsolutions.gopreach.domain.GroupNameOrder)
        }

    suspend fun searchProvinces(query: String): List<PsgcOption> = philippineLocationRepository.searchProvinces(query)
    suspend fun searchMunicipalities(provinceId: Int, query: String): List<PsgcOption> =
        philippineLocationRepository.searchCitiesMunicipalities(provinceId, query)
    suspend fun searchBarangays(muncityId: Int, query: String): List<PsgcOption> =
        philippineLocationRepository.searchBarangays(muncityId, query)

    /** "Display its current territory assignments to prevent accidental
     * duplication" — what this Group already holds in [provinceId], as the
     * same [MunicipalitySelection] shape the wizard edits and eventually
     * saves, so both the "Add" and "Edit" entry points converge on identical
     * preload logic (see this module's own design notes on unifying them). */
    suspend fun existingMunicipalitiesFor(congregationId: String, groupId: String, provinceId: Int): List<MunicipalitySelection> {
        val assignments = territoryAssignmentRepository.observeAssignments().first()
            .filter { it.congregationId == congregationId && it.groupId == groupId && it.provinceId == provinceId }
        val claimsByAssignment = territoryAssignmentRepository.observeBarangayClaims().first()
            .filter { claim -> assignments.any { it.id == claim.assignmentId } }
            .groupBy { it.assignmentId }
        return assignments.map { assignment ->
            MunicipalitySelection(
                provinceId = assignment.provinceId,
                provinceName = assignment.provinceName,
                muncityId = assignment.muncityId,
                muncityName = assignment.muncityName,
                barangays = (claimsByAssignment[assignment.id] ?: emptyList())
                    .map { PsgcOption(it.barangayId, it.barangayName) }
                    .sortedBy { it.name },
            )
        }.sortedBy { it.muncityName }
    }

    /** Which of a municipality's barangays are already claimed by a
     * DIFFERENT Group in this congregation — client-side "already taken,
     * disabled" hint for the Step 3 checklist. [excludeGroupId] is this same
     * wizard's own Group, so barangays it already holds (possibly under a
     * municipality still in this session) never show as unavailable to
     * itself. This is UX only; the actual, unbypassable guarantee is the
     * transaction inside [TerritoryAssignmentRepository] at Save. */
    suspend fun takenBarangays(congregationId: String, excludeGroupId: String?): Map<Int, String> =
        territoryAssignmentRepository.observeBarangayClaims().first()
            .filter { it.congregationId == congregationId && it.groupId != excludeGroupId }
            .associate { it.barangayId to it.groupName }

    fun save(
        congregationId: String,
        groupId: String,
        groupName: String,
        provinceId: Int,
        provinceName: String,
        municipalities: List<MunicipalitySelection>,
        actorPersonId: String,
    ) {
        _uiState.update { it.copy(isSaving = true, saveResult = null) }
        viewModelScope.launch {
            val actorAssignments = roleAssignmentRepository.observeForPerson(actorPersonId).first()
            if (PermissionChecker.fullCrudAssignment(actorAssignments) == null) {
                _uiState.update {
                    it.copy(isSaving = false, saveResult = TerritoryAssignmentResult.Error(PermissionChecker.NO_ACCESS_MESSAGE))
                }
                return@launch
            }
            val result = territoryAssignmentRepository.saveGroupTerritoryForProvince(
                congregationId, groupId, groupName, provinceId, provinceName, municipalities, actorPersonId,
            )
            _uiState.update { it.copy(isSaving = false, saveResult = result) }
        }
    }

    fun consumeSaveResult() {
        _uiState.update { it.copy(saveResult = null) }
    }
}
