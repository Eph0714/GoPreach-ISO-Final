package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.repository.CircuitCodeRepository
import com.emfitsolutions.gopreach.data.repository.CoCongregationResult
import com.emfitsolutions.gopreach.data.repository.CoCongregationService
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.UserAccessGrantRepository
import com.emfitsolutions.gopreach.data.sync.SyncEngine
import com.emfitsolutions.gopreach.domain.UserSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Congregation Management for the Circuit Overseer: the congregations of their own account. The list is what the server lets this account read (its grant) AND what is linked
 * to them; the server is the authority for every change (see CoCongregationService), this only shows and sends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoCongregationsViewModel(
    congregationRepository: CongregationRepository,
    circuitCodeRepository: CircuitCodeRepository,
    grantRepository: UserAccessGrantRepository,
    private val userSession: UserSession,
    private val service: CoCongregationService,
    private val syncEngine: SyncEngine,
) : ViewModel() {

    private val grantFlow = userSession.state.flatMapLatest { s -> s.person?.id?.let { grantRepository.observeForPerson(it) } ?: flowOf(null) }

    val mine: StateFlow<List<Congregation>> = combine(congregationRepository.observeAll(), circuitCodeRepository.observeLinks(), grantFlow, userSession.state) { all, links, grant, s ->
        val me = s.person?.id
        val granted = grant?.scopeCongregationIds.orEmpty().toSet()
        if (me == null) emptyList() else all.filter { links[it.id]?.circuitOverseerPersonId == me || it.id in granted }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The signed-in overseer's own name: shown as the assigned Circuit Overseer. */
    val myName: StateFlow<String> = combine(userSession.state, flowOf(Unit)) { s, _ -> s.person?.fullName.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val myPersonId: String? get() = userSession.state.value.person?.id

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            runCatching { syncEngine.syncOnce() }
            _refreshing.value = false
        }
    }

    /** Saves the edit on the server; [onResult] gets null on success or the message to show. */
    fun edit(congregation: Congregation, name: String, province: String, cityMunicipality: String, barangay: String, languages: List<String>, onResult: (String?) -> Unit) {
        val me = myPersonId ?: return onResult("Your session has ended. Sign in again.")
        viewModelScope.launch {
            val result = service.edit(congregation.id, name.trim(), province, cityMunicipality, barangay, languages, me)
            onResult((result as? CoCongregationResult.Failed)?.message)
        }
    }
}
