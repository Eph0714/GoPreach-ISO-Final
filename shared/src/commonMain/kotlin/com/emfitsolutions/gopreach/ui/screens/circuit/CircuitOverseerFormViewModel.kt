package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.CircuitCode
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.repository.CircuitAssignmentService
import com.emfitsolutions.gopreach.data.repository.CircuitCodeRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.TempCredentials
import com.emfitsolutions.gopreach.data.repository.UserAccessGrantRepository
import com.emfitsolutions.gopreach.data.repository.messageOrNull
import com.emfitsolutions.gopreach.domain.CircuitRules
import com.emfitsolutions.gopreach.domain.CredentialGenerator
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One checkbox row: [takenBy] is the other overseer's name when the congregation can't be ticked. */
data class CongregationOption(val congregation: Congregation, val takenBy: String?)

data class CircuitOverseerFormState(
    val isEdit: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val lastName: String = "",
    val firstName: String = "",
    val username: String = "",
    /** Still on the temporary credentials (never completed the first-login change). */
    val temporary: Boolean = false,
    /** Set right after creation: the temporary username and password to give the new overseer. */
    val credentials: TempCredentials? = null,
    val circuitCode: String? = null,
    val selectedCongregationIds: Set<String> = emptySet(),
    val status: AccountStatus = AccountStatus.ACTIVE,
    val createdAt: Long = 0L,
    val error: String? = null,
    val done: Boolean = false,
)

/**
 * Create / edit / view one Circuit Overseer account. Creation order matters: the Circuit Code and the
 * congregations are **claimed first** in one server transaction (using a pre-generated person id), and the
 * login account is created only if that succeeds — so the likely failure (somebody else just took the code or
 * a congregation) leaves nothing behind. If the account step then fails, the claim is rolled back.
 */
class CircuitOverseerFormViewModel(
    private val authRepository: AuthRepository,
    private val personRepository: PersonRepository,
    private val userAccessGrantRepository: UserAccessGrantRepository,
    private val auditLogRepository: AuditLogRepository,
    private val service: CircuitAssignmentService,
    codeRepository: CircuitCodeRepository,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CircuitOverseerFormState())
    val state: StateFlow<CircuitOverseerFormState> = _state.asStateFlow()

    private val editingPersonId = MutableStateFlow<String?>(null)
    private var existingPerson: Person? = null

    /** Active codes nobody else holds (plus this overseer's own, when editing). */
    val codes: StateFlow<List<CircuitCode>> = combine(codeRepository.observeAll(), editingPersonId) { codes, me ->
        CircuitRules.availableCodes(codes, me)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Every congregation, free ones first-class and ones held by another overseer marked [CongregationOption.takenBy]. */
    val congregations: StateFlow<List<CongregationOption>> = combine(
        congregationRepository.observeAll(),
        codeRepository.observeLinks(),
        personRepository.observeAll(),
        editingPersonId,
    ) { congregations, links, people, me ->
        val peopleById = people.associateBy { it.id }
        val free = CircuitRules.availableCongregations(congregations, links, me).map { CongregationOption(it, null) }
        val taken = CircuitRules.takenCongregations(congregations, links, me).map { c ->
            val owner = CircuitRules.ownerOf(links, c.id)
            CongregationOption(c, owner?.let { peopleById[it]?.fullName } ?: "another Circuit Overseer")
        }
        (free + taken).sortedBy { it.congregation.name }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadForEdit(personId: String) {
        if (editingPersonId.value == personId) return
        editingPersonId.value = personId
        _state.update { it.copy(isEdit = true, loading = true) }
        viewModelScope.launch {
            val person = personRepository.get(personId)
            existingPerson = person
            val grant = userAccessGrantRepository.get(personId)
            _state.update {
                it.copy(
                    loading = false,
                    lastName = person?.lastName.orEmpty(),
                    firstName = person?.firstName.orEmpty(),
                    username = person?.username.orEmpty(),
                    temporary = person?.isTemporaryCredential == true,
                    circuitCode = grant?.circuitCode?.takeIf { c -> c.isNotBlank() },
                    selectedCongregationIds = grant?.scopeCongregationIds?.toSet().orEmpty(),
                    status = person?.accountStatus ?: AccountStatus.ACTIVE,
                    createdAt = person?.createdAt ?: 0L,
                )
            }
        }
    }

    private fun edit(block: (CircuitOverseerFormState) -> CircuitOverseerFormState) = _state.update { block(it).copy(error = null) }

    fun onLastName(v: String) = edit { it.copy(lastName = v.uppercase()) }
    fun onFirstName(v: String) = edit { it.copy(firstName = v.uppercase()) }
    fun onCircuitCode(v: String) = edit { it.copy(circuitCode = v) }
    fun onStatus(v: AccountStatus) = edit { it.copy(status = v) }
    fun onCongregationToggled(id: String, checked: Boolean) = edit {
        it.copy(selectedCongregationIds = if (checked) it.selectedCongregationIds + id else it.selectedCongregationIds - id)
    }

    private fun fail(message: String) = _state.update { it.copy(saving = false, error = message) }

    private fun requiredProblem(s: CircuitOverseerFormState): String? {
        val missing = buildList {
            if (s.lastName.isBlank()) add("Last Name")
            if (s.firstName.isBlank()) add("First Name")
            if (s.circuitCode.isNullOrBlank()) add("Assigned Circuit")
            if (s.selectedCongregationIds.isEmpty()) add("Assigned Congregations")
        }
        return if (missing.isEmpty()) null else "Required: ${missing.joinToString(", ")}."
    }

    fun save(actorPersonId: String) {
        val s = _state.value
        if (s.saving) return
        requiredProblem(s)?.let { fail(it); return }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                if (s.isEdit) saveEdit(s, actorPersonId) else saveNew(s, actorPersonId)
            } catch (e: Exception) {
                fail(e.message ?: "Couldn't save. Please try again.")
            }
        }
    }

    private suspend fun saveNew(s: CircuitOverseerFormState, actorPersonId: String) {
        val code = s.circuitCode!!
        val personId = CredentialGenerator.newPersonId()
        val claim = service.saveOverseerAssignment(personId, code, s.selectedCongregationIds, actorPersonId)
        claim.messageOrNull()?.let { fail(it); return }
        val credentials: TempCredentials
        try {
            credentials = authRepository.createAccountWithTempCredentials(
                person = Person(firstName = s.firstName.trim(), lastName = s.lastName.trim()),
                roleAssignment = { id ->
                    RoleAssignment(
                        personId = id,
                        roleType = RoleType.serialize(RoleType.Admin(AdminRole.CIRCUIT_OVERSEER)),
                        status = RoleAssignmentStatus.ACTIVE,
                        dateAssigned = nowMillis(),
                        assignedByPersonId = actorPersonId,
                    )
                },
                enrollingPersonId = actorPersonId,
                personId = personId,
            )
        } catch (e: Exception) {
            // The login account couldn't be created — give the code and congregations back.
            service.saveOverseerAssignment(personId, code, emptySet(), actorPersonId)
            service.releaseOverseer(personId, actorPersonId)
            fail(e.message ?: "Couldn't create the account. Nothing was assigned.")
            return
        }
        // Shown once to the Super-Admin to hand over; the new overseer must change both at first sign-in.
        _state.update { it.copy(saving = false, credentials = credentials) }
    }

    private suspend fun saveEdit(s: CircuitOverseerFormState, actorPersonId: String) {
        val personId = editingPersonId.value ?: return
        val person = existingPerson ?: personRepository.get(personId)
        if (person == null) {
            fail("This account no longer exists.")
            return
        }
        val claim = service.saveOverseerAssignment(personId, s.circuitCode!!, s.selectedCongregationIds, actorPersonId)
        claim.messageOrNull()?.let { fail(it); return }

        val updated = person.copy(
            lastName = s.lastName.trim(),
            firstName = s.firstName.trim(),
            accountStatus = s.status,
        )
        if (updated != person) personRepository.save(updated)
        if (person.accountStatus != s.status) {
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "CHANGE_USER_STATUS",
                targetType = "Person",
                targetId = personId,
                details = "status: ${person.accountStatus} -> ${s.status}",
            )
        }
        _state.update { it.copy(saving = false, done = true) }
    }
}
