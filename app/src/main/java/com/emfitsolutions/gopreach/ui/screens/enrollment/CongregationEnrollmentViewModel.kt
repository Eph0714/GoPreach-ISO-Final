package com.emfitsolutions.gopreach.ui.screens.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.location.LocationTracker
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.PhilippineLocationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CongregationEnrollmentUiState(
    val name: String = "",
    /** "The Address must be replaced with (Province/City, Municipality,
     * Barangay)" — see [Congregation.province]'s own doc comment; no more
     * free-text address field anywhere in this form. */
    val province: String? = null,
    val cityMunicipality: String? = null,
    val barangay: String? = null,
    val gpsLat: Double? = null,
    val gpsLng: Double? = null,
    val isCapturingLocation: Boolean = false,
    val locationError: String? = null,
    val code: String = "",
    /** "Language(s) Used" — optional, multiple, free-form (e.g. "Iloko", "Ibanag"). */
    val languages: List<String> = emptyList(),
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val saved: Boolean = false,
)

/** Spec §4.1 — Super-Admin only. */
@HiltViewModel
class CongregationEnrollmentViewModel @Inject constructor(
    private val congregationRepository: CongregationRepository,
    private val auditLogRepository: AuditLogRepository,
    private val locationTracker: LocationTracker,
    private val philippineLocationRepository: PhilippineLocationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CongregationEnrollmentUiState())
    val uiState: StateFlow<CongregationEnrollmentUiState> = _uiState.asStateFlow()

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value.uppercase(), errorMessage = null) }

    /** "The publisher will browse manually" — the manual half, wired to
     * [com.emfitsolutions.gopreach.ui.components.PhilippineAddressPicker]. */
    fun onAddressLevelsChanged(province: String?, cityMunicipality: String?, barangay: String?) = _uiState.update {
        it.copy(province = province, cityMunicipality = cityMunicipality, barangay = barangay, errorMessage = null)
    }

    fun onCodeChange(value: String) = _uiState.update { it.copy(code = value.uppercase(), errorMessage = null) }
    fun onAddLanguage(value: String) = _uiState.update { it.copy(languages = it.languages + value) }
    fun onRemoveLanguage(value: String) = _uiState.update { it.copy(languages = it.languages - value) }

    fun hasLocationPermission(): Boolean = locationTracker.hasLocationPermission()

    /** "It can be automatic if the publisher will capture the coordinates,
     * the system will automatically fill-up the (Province/City,
     * Municipality, Barangay)" — same reverse-geocode-then-match-against-
     * PSGC approach as every other enrollment screen's own capture button;
     * only overwrites a level that actually resolved. */
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
                    gpsLat = location.lat,
                    gpsLng = location.lng,
                    province = resolved?.provinceName ?: it.province,
                    cityMunicipality = resolved?.muncityName ?: it.cityMunicipality,
                    barangay = resolved?.barangayName ?: it.barangay,
                )
            }
        }
    }

    fun save(createdByPersonId: String) {
        val state = _uiState.value
        if (state.name.isBlank() || state.province.isNullOrBlank() || state.cityMunicipality.isNullOrBlank() ||
            state.barangay.isNullOrBlank() || state.code.isBlank()
        ) {
            _uiState.update { it.copy(errorMessage = "Name, Province, Municipality/City, Barangay, and code are required.") }
            return
        }
        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                if (!congregationRepository.isCodeAvailable(state.code.trim())) {
                    _uiState.update { it.copy(isSaving = false, errorMessage = "That congregation code is already in use.") }
                    return@launch
                }
                val congregation = congregationRepository.save(
                    Congregation(
                        name = state.name.trim(),
                        // Derived, human-readable fallback — see Congregation
                        // .address's own doc comment.
                        address = listOfNotNull(state.barangay, state.cityMunicipality, state.province).joinToString(", "),
                        province = state.province,
                        cityMunicipality = state.cityMunicipality,
                        barangay = state.barangay,
                        code = state.code.trim(),
                        languages = state.languages,
                        createdAt = System.currentTimeMillis(),
                        createdByPersonId = createdByPersonId,
                    )
                )
                auditLogRepository.log(
                    actorPersonId = createdByPersonId,
                    action = "CREATE_CONGREGATION",
                    targetType = "Congregation",
                    targetId = congregation.id,
                    congregationId = congregation.id,
                )
                _uiState.update { it.copy(isSaving = false, saved = true) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, errorMessage = e.localizedMessage ?: "Couldn't create this congregation. Please try again.")
                }
            }
        }
    }
}
