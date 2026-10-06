package com.emfitsolutions.gopreach.ui.screens.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.location.LocationTracker
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.PhilippineLocationRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.domain.PermissionChecker
import com.emfitsolutions.gopreach.ui.components.PublisherFormState
import com.emfitsolutions.gopreach.data.repository.TempCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PublisherEnrollmentUiState(
    /** Every Publisher field — the same set the Edit Publisher dialog uses (see [PublisherFormState]). */
    val form: PublisherFormState = PublisherFormState(),
    /** [isCapturingLocation]/[locationError] back the optional "Use Current Location" button that
     * fills the address levels and coordinates automatically (best-effort, still editable). */
    val isCapturingLocation: Boolean = false,
    val locationError: String? = null,
    /** Super-Admin-only — narrows [PublisherEnrollmentViewModel.groups] to
     * that congregation's groups. Anyone scoped to a single fixed
     * congregation (Admin/Coordinator Elder/Service Overseer) never sets
     * this directly; it's derived from
     * [PublisherEnrollmentViewModel.fixedCongregationId] instead. */
    val selectedCongregationId: String? = null,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val result: TempCredentials? = null,
)

/**
 * "CREATING PUBLISHER" spec — created by Super-Admin, Admin (own
 * congregation), Coordinator Elder, or Service Overseer (own congregation) —
 * no per-congregation cap.
 *
 * [fixedCongregationId] is the actual scope/security boundary, resolved once
 * by the caller (see GoPreachNavGraph) from the enrolling session's own
 * role, exactly like every other Manage screen's `fixedCongregationId`/
 * `visibleCongregationId` convention: `null` means "Super-Admin, may enroll
 * into any congregation" and shows the Select Congregation field; a real id
 * means "restricted to this one congregation."
 */
class PublisherEnrollmentViewModel(
    private val authRepository: AuthRepository,
    private val groupRepository: GroupRepository,
    private val locationTracker: LocationTracker,
    private val philippineLocationRepository: PhilippineLocationRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val publisherCongregationContext: com.emfitsolutions.gopreach.data.repository.PublisherCongregationContext,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    private val allGroups: StateFlow<List<Group>> =
        groupRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Super-Admin only — the full Congregation list for the "Select
     * Congregation" dropdown. Unused (and never rendered) when
     * [fixedCongregationId] is non-null. */
    val congregations: StateFlow<List<Congregation>> =
        congregationRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Set once, from the nav graph, before this screen is ever composed —
     * see the class doc. */
    var fixedCongregationId: String? = null
        private set

    /** [congregationId] non-null: fixed to that congregation (their own). Null: a Super-Admin, who starts in the
     * congregation they are working in inside the Publisher module, and can still change it here. */
    fun restrictTo(congregationId: String?) {
        fixedCongregationId = congregationId
        val start = congregationId ?: publisherCongregationContext.selectedCongregationId.value
        _uiState.update {
            // Keep a congregation the user already picked on this form.
            if (it.selectedCongregationId != null && congregationId == null) it
            else it.copy(selectedCongregationId = start, form = it.form.copy(congregationId = start))
        }
    }

    private val _uiState = MutableStateFlow(PublisherEnrollmentUiState())
    val uiState: StateFlow<PublisherEnrollmentUiState> = _uiState.asStateFlow()

    /** The Group dropdown's actual options — "groups only associated in the
     * congregation" (spec): every group when nothing scopes it yet is
     * deliberately *not* one of them — an Admin/Coordinator Elder/Service
     * Overseer is always scoped ([fixedCongregationId] non-null) so this is
     * immediately narrowed for them; a Super-Admin only sees groups once
     * they've picked a Congregation. */
    val groups: StateFlow<List<Group>> = combine(allGroups, _uiState) { all, state ->
        val congregationId = fixedCongregationId ?: state.selectedCongregationId
        if (congregationId == null) emptyList() else all.filter { it.congregationId == congregationId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onFormChange(form: PublisherFormState) = _uiState.update {
        // The form's Congregation field is the source of truth for which congregation's groups are offered.
        it.copy(form = form, selectedCongregationId = form.congregationId ?: it.selectedCongregationId, errorMessage = null)
    }

    fun hasLocationPermission(): Boolean = locationTracker.hasLocationPermission()

    /** "It can be automatic if the publisher will capture the coordinates,
     * the system will automatically fill-up the City, Municipalities, Town
     * and barangay" — same reverse-geocode-then-match-against-PSGC approach
     * as [com.emfitsolutions.gopreach.ui.screens.pipeline.PipelineViewModel
     * .saveGpsLocation]; only overwrites a level that actually resolved. */
    fun captureLocation() {
        _uiState.update { it.copy(isCapturingLocation = true, locationError = null) }
        viewModelScope.launch {
            val location = locationTracker.getCurrentLocation()
            if (location == null) {
                _uiState.update { it.copy(isCapturingLocation = false, locationError = "Could not get a GPS fix. Make sure location is turned on and try again.") }
                return@launch
            }
            val geocoded = runCatching { locationTracker.reverseGeocodeAddress(location.lat, location.lng) }.getOrNull()
            val resolved = geocoded?.let { philippineLocationRepository.resolveFromGeocode(it) }
            _uiState.update {
                it.copy(
                    isCapturingLocation = false,
                    form = it.form.copy(
                        latitudeText = location.lat.toString(),
                        longitudeText = location.lng.toString(),
                        province = resolved?.provinceName ?: it.form.province,
                        cityMunicipality = resolved?.muncityName ?: it.form.cityMunicipality,
                        barangay = resolved?.barangayName ?: it.form.barangay,
                    ),
                )
            }
        }
    }

    /** Super-Admin only — picking a different Congregation clears whatever
     * Group was already selected, since it almost certainly belonged to the
     * previous congregation and silently keeping it would let a Publisher
     * end up in a Group that doesn't match their selected Congregation. */
    fun onCongregationSelected(id: String) = _uiState.update {
        it.copy(selectedCongregationId = id, form = it.form.copy(groupId = null), errorMessage = null)
    }

    fun save(enrollingPersonId: String) {
        val state = _uiState.value
        // Super-Admin (fixedCongregationId == null) must pick a Congregation
        // before a Group even becomes selectable in the UI, but re-check here
        // too rather than trust that alone.
        if (fixedCongregationId == null && state.selectedCongregationId == null) {
            _uiState.update { it.copy(errorMessage = "Select a congregation.") }
            return
        }
        val form = state.form
        if (form.lastName.isBlank() || form.firstName.isBlank() || form.address.isBlank() || form.contact.isBlank() ||
            form.groupId == null || form.category == null ||
            form.province.isNullOrBlank() || form.cityMunicipality.isNullOrBlank() || form.barangay.isNullOrBlank()
        ) {
            _uiState.update { it.copy(errorMessage = "First name, last name, full address, Province, Municipality/City, Barangay, contact, category and group are all required.") }
            return
        }
        form.formProblem?.let { problem -> _uiState.update { it.copy(errorMessage = problem) }; return }
        val group = groups.value.firstOrNull { it.id == form.groupId }
        if (group == null || (fixedCongregationId != null && group.congregationId != fixedCongregationId)) {
            // The second half of that check is a defense-in-depth guard, not
            // just a UI nicety: it's the same "never trust a caller-supplied
            // congregation/group id without re-verifying it against the
            // session's own authorized scope" rule this app applies
            // everywhere else (see PermissionChecker/DashboardStatsViewModel).
            _uiState.update { it.copy(errorMessage = "Selected group not found.") }
            return
        }
        val category = form.category ?: return
        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val enrollerAssignments = roleAssignmentRepository.observeForPerson(enrollingPersonId).first()
                if (PermissionChecker.fullCrudAssignment(enrollerAssignments) == null) {
                    _uiState.update { it.copy(isSaving = false, errorMessage = PermissionChecker.NO_ACCESS_MESSAGE) }
                    return@launch
                }
                val credentials = authRepository.createAccountWithTempCredentials(
                    person = form.applyTo(Person()),
                    roleAssignment = { personId ->
                        RoleAssignment(
                            personId = personId,
                            roleType = RoleType.serialize(RoleType.Publisher(category)),
                            congregationId = group.congregationId,
                            groupId = group.id,
                            status = RoleAssignmentStatus.ACTIVE,
                            dateAssigned = System.currentTimeMillis(),
                            assignedByPersonId = enrollingPersonId,
                        )
                    },
                    enrollingPersonId = enrollingPersonId,
                )
                _uiState.update { it.copy(isSaving = false, result = credentials) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, errorMessage = e.localizedMessage ?: "Couldn't enroll this publisher. Please try again.")
                }
            }
        }
    }
}
