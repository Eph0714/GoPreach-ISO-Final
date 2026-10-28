package com.emfitsolutions.gopreach.ui.screens.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.repository.AdminSession
import com.emfitsolutions.gopreach.data.repository.AuthFailure
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.repository.ApprovalLimits
import com.emfitsolutions.gopreach.data.repository.AuthService
import com.emfitsolutions.gopreach.data.repository.DeviceGroup
import com.emfitsolutions.gopreach.data.repository.DeviceSession
import com.emfitsolutions.gopreach.data.repository.HistoryFilter
import com.emfitsolutions.gopreach.data.repository.SecurityEvent
import com.emfitsolutions.gopreach.data.repository.SecurityFailure
import com.emfitsolutions.gopreach.data.repository.SecurityService
import com.emfitsolutions.gopreach.data.repository.SecurityStatus
import com.emfitsolutions.gopreach.data.repository.TotpSetup
import com.emfitsolutions.gopreach.data.repository.TrustedDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The event names of the security log, in words. */
val SecurityEventNames = mapOf(
    "LOGIN_SUCCESS" to "Signed in", "LOGIN_FAILED" to "Failed sign-in", "TWO_FACTOR_SUCCESS" to "Two-factor code accepted", "TWO_FACTOR_FAILED" to "Wrong two-factor code",
    "TWO_FACTOR_ENABLED" to "Two-factor sign-in turned on", "TWO_FACTOR_DISABLED" to "Two-factor sign-in turned off", "RECOVERY_CODES_REGENERATED" to "New recovery codes made",
    "NEW_DEVICE_LOGIN" to "New device signed in", "TRUSTED_DEVICE_ADDED" to "Trusted device added", "TRUSTED_DEVICE_REMOVED" to "Trusted device removed",
    "DEVICE_LOGOUT" to "A device was logged out", "LOG_OUT_OTHER_DEVICES" to "Other devices logged out", "LOG_OUT_ALL_DEVICES" to "All devices logged out",
    "PASSWORD_CHANGED" to "Password changed", "ACCOUNT_SECURITY_SETTINGS_CHANGED" to "Security settings changed", "ADMIN_SESSION_REVOKED" to "An administrator ended a session",
    "ADMIN_REVOKED_A_SESSION" to "Session ended by this administrator",
    "DEVICE_APPROVAL_ON" to "Approve new devices turned on", "DEVICE_APPROVAL_OFF" to "Approve new devices turned off", "LOGIN_APPROVAL_REQUESTED" to "New device is waiting for approval",
    "LOGIN_APPROVAL_APPROVED" to "New device approved", "LOGIN_APPROVAL_REJECTED" to "New device rejected", "LOGIN_APPROVAL_USED" to "Approved device signed in", "APPROVAL_LIMITS_CHANGED" to "New-device request limits changed",
)

/** The kinds of activity the login history can be narrowed to (label to the event codes the server filters on). */
val SecurityHistoryKinds = listOf(
    "Sign-ins" to listOf("LOGIN_SUCCESS", "LOGIN_FAILED", "NEW_DEVICE_LOGIN", "LOGIN_APPROVAL_USED"),
    "Two-factor" to listOf("TWO_FACTOR_SUCCESS", "TWO_FACTOR_FAILED", "TWO_FACTOR_ENABLED", "TWO_FACTOR_DISABLED", "RECOVERY_CODES_REGENERATED"),
    "Devices" to listOf("TRUSTED_DEVICE_ADDED", "TRUSTED_DEVICE_REMOVED", "DEVICE_LOGOUT", "LOG_OUT_OTHER_DEVICES", "LOG_OUT_ALL_DEVICES", "ADMIN_SESSION_REVOKED"),
    "Approval" to listOf("DEVICE_APPROVAL_ON", "DEVICE_APPROVAL_OFF", "LOGIN_APPROVAL_REQUESTED", "LOGIN_APPROVAL_APPROVED", "LOGIN_APPROVAL_REJECTED"),
)

data class SecurityUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val status: SecurityStatus? = null,
    val devices: List<DeviceSession> = emptyList(),
    val trusted: List<TrustedDevice> = emptyList(),
    val events: List<SecurityEvent> = emptyList(),
    val notices: List<SecurityEvent> = emptyList(),
    /** The notification centre: every notice, newest first, unseen ones flagged. */
    val everyNotice: List<SecurityEvent> = emptyList(),
    val historyFilter: HistoryFilter = HistoryFilter(),
    val historyLoading: Boolean = false,
    /** Set while the authenticator-app set-up dialog is open. */
    val setup: TotpSetup? = null,
    /** The recovery codes, shown once in a dialog and then forgotten. */
    val recoveryCodes: List<String>? = null,
    val message: String? = null,
    val error: String? = null,
)

/**
 * Security & Device Management: two-factor settings, the devices signed in to the account, trusted devices and the person's own security log.
 * Every change asks for the current password again (the server insists on a recently typed one); ending a session is carried out by the server.
 */
class SecurityViewModel(
    private val security: SecurityService,
    private val auth: AuthService,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SecurityUiState())
    val state: StateFlow<SecurityUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            try {
                val status = security.status()
                val overview = security.devices()
                val events = security.events(40, _state.value.historyFilter)
                val everyNotice = security.notices(all = true)
                val notices = everyNotice.filter { !it.seen }
                _state.update { it.copy(loading = false, status = status, devices = overview.devices, trusted = overview.trusted, events = events, notices = notices, everyNotice = everyNotice, error = null) }
                if (notices.isNotEmpty()) runCatching { security.markNoticesSeen() }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = messageOf(e)) }
            }
        }
    }

    /** Applies the login-history filters on the server and shows the matching entries. */
    fun setHistoryFilter(filter: HistoryFilter) {
        _state.update { it.copy(historyFilter = filter, historyLoading = true) }
        viewModelScope.launch {
            try {
                val events = security.events(100, filter)
                _state.update { it.copy(events = events, historyLoading = false) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(historyLoading = false, error = messageOf(e)) }
            }
        }
    }

    private fun messageOf(e: Throwable): String = when (e) {
        is SecurityFailure -> e.message ?: "Something went wrong."
        is AuthFailure.InvalidCredentials -> "Your current password is not correct."
        is AuthFailure.TooManyRequests -> "Too many attempts. Please wait a few minutes."
        is AuthFailure.Network -> "No internet connection. Please check your connection and try again."
        else -> "Something went wrong. Please try again."
    }

    /** Proves the password again, then runs [block]; shows [done] (or the error). [after] runs on success (for example signing this device out). */
    private fun secured(password: String, done: String?, after: (suspend () -> Unit)? = null, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                auth.reauthenticate(password)
                block()
                after?.invoke()
                _state.update { it.copy(busy = false, message = done) }
                if (after == null) refresh()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = messageOf(e)) }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null, error = null) }
    fun dismissRecoveryCodes() = _state.update { it.copy(recoveryCodes = null) }
    fun cancelSetup() = _state.update { it.copy(setup = null) }

    // ---- two-factor ----
    fun startSetup(password: String) = secured(password, null) {
        val setup = security.totpSetup()
        _state.update { it.copy(setup = setup) }
    }

    fun confirmSetup(code: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val codes = security.totpConfirm(code)
                _state.update { it.copy(busy = false, setup = null, recoveryCodes = codes, message = "Two-factor sign-in is on. Save your recovery codes now: they are shown only this once.") }
                refresh()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = messageOf(e)) }
            }
        }
    }

    fun disable(password: String, code: String) = secured(password, "Two-factor sign-in is off.") { security.disableTwoFactor(code) }
    fun regenerate(password: String, code: String) = secured(password, "New recovery codes were made. The old ones no longer work.") {
        val codes = security.regenerateRecoveryCodes(code)
        _state.update { it.copy(recoveryCodes = codes) }
    }
    fun setRecoveryEmail(password: String, email: String) = secured(password, "We sent a code to that address. Enter it to confirm.") { security.setRecoveryEmail(email) }
    fun verifyRecoveryEmail(code: String) {
        viewModelScope.launch {
            try { security.verifyRecoveryEmail(code); _state.update { it.copy(message = "Your recovery e-mail is confirmed.") }; refresh() }
            catch (e: CancellationException) { throw e } catch (e: Exception) { _state.update { it.copy(error = messageOf(e)) } }
        }
    }

    // ---- devices ----
    /** Renames a device (all its sessions); an older session without a device id is renamed on its own. */
    fun renameDevice(g: DeviceGroup, name: String) {
        viewModelScope.launch {
            try {
                val id = g.deviceId
                if (id != null) security.renameDeviceById(id, name) else g.sessionId?.let { security.renameDevice(it, name) }
                _state.update { it.copy(message = "The device was renamed.") }; refresh()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { _state.update { it.copy(error = messageOf(e)) } }
        }
    }

    /** Removes a device: logged out wherever it is signed in, its trust ends. Needs the password again. */
    fun removeDevice(g: DeviceGroup, password: String) = secured(password, "The device was removed. It is logged out and must sign in again.") {
        val id = g.deviceId
        if (id != null) security.removeDevice(id) else g.sessionId?.let { security.revokeDevice(it) }
    }

    fun rename(sessionId: String, name: String) {
        viewModelScope.launch {
            try { security.renameDevice(sessionId, name); _state.update { it.copy(message = "The device was renamed.") }; refresh() }
            catch (e: CancellationException) { throw e } catch (e: Exception) { _state.update { it.copy(error = messageOf(e)) } }
        }
    }

    /** Ends one device's session on the server. Ending THIS device's own session also signs this phone out. */
    fun revoke(device: DeviceSession, password: String) = secured(
        password, if (device.current) "This device was logged out." else "That device was logged out. It can no longer open GoPreach.",
        after = if (device.current) ({ authRepository.signOut() }) else null,
    ) { security.revokeDevice(device.sessionId) }

    fun revokeOthers(password: String) {
        var n = 0
        secured(password, null) { n = security.revokeOtherDevices(); _state.update { it.copy(message = if (n == 0) "There were no other devices." else "Logged out $n other device${if (n == 1) "" else "s"}. They must sign in again.") } }
    }

    fun revokeAll(password: String) = secured(password, "All devices were logged out.", after = { authRepository.signOut() }) { security.revokeAllDevices() }

    fun trustThisDevice(password: String, code: String) = secured(password, "This device is trusted. It skips the code until the trust expires or you remove it.") { security.trustThisDevice(code) }
    fun setDeviceApproval(enabled: Boolean, password: String) = secured(
        password,
        if (enabled) "From now on a new device waits until you approve it from a device that is already signed in. This device is trusted." else "New devices now sign in without a request.",
    ) { security.setDeviceApproval(enabled) }
    fun removeTrusted(id: String, password: String) = secured(password, "That device is no longer trusted. It will ask for a code again.") {
        security.removeTrusted(id)
        // A removed trust must also be forgotten here, so this phone never sends a dead credential.
        auth.clearTrustedDeviceToken()
    }
}

data class AdminDevicesUiState(
    val loading: Boolean = true,
    val sessions: List<AdminSession> = emptyList(),
    val query: String = "",
    val status: String = "",
    val type: String = "",
    val role: String = "",
    val history: Pair<String, List<SecurityEvent>>? = null,
    val message: String? = null,
    val error: String? = null,
)

/** Device & Session Management for the Super Admin (everyone) and a congregation Admin (their own congregation). The server decides what is visible and what may be ended. */
class AdminDevicesViewModel(private val security: SecurityService) : ViewModel() {
    private val _state = MutableStateFlow(AdminDevicesUiState())
    val state: StateFlow<AdminDevicesUiState> = _state.asStateFlow()

    init { search() }

    fun setQuery(v: String) = _state.update { it.copy(query = v) }
    fun setStatus(v: String) { _state.update { it.copy(status = v) }; search() }
    fun setType(v: String) { _state.update { it.copy(type = v) }; search() }
    fun setRole(v: String) { _state.update { it.copy(role = v) }; search() }

    fun search() {
        val s = _state.value
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val rows = security.adminSessions(mapOf("q" to s.query, "status" to s.status, "type" to s.type, "role" to s.role, "limit" to "300"))
                _state.update { it.copy(loading = false, sessions = rows) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = (e as? SecurityFailure)?.message ?: "Could not load the sessions.") }
            }
        }
    }

    fun revoke(sessionId: String) = act("That session was ended on the server.") { security.adminRevokeSession(sessionId) }
    fun revokeAll(personId: String) = act(null) { val n = security.adminRevokeAll(personId); _state.update { it.copy(message = "$n session${if (n == 1) "" else "s"} ended on the server.") } }

    fun showHistory(personId: String, label: String) {
        viewModelScope.launch {
            try { _state.update { it.copy(history = label to security.adminHistory(personId)) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { _state.update { it.copy(error = (e as? SecurityFailure)?.message ?: "Could not load the history.") } }
        }
    }

    fun closeHistory() = _state.update { it.copy(history = null) }
    fun clearMessage() = _state.update { it.copy(message = null, error = null) }

    private fun act(done: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block(); if (done != null) _state.update { it.copy(message = done) }; search() }
            catch (e: CancellationException) { throw e } catch (e: Exception) { _state.update { it.copy(error = (e as? SecurityFailure)?.message ?: "Could not do this.") } }
        }
    }
}

data class SecurityLogUiState(
    val loading: Boolean = true, val events: List<SecurityEvent> = emptyList(), val personId: String = "", val type: String = "", val error: String? = null,
    val limits: ApprovalLimits = ApprovalLimits(), val limitsBusy: Boolean = false, val limitsMessage: String? = null, val limitsError: String? = null,
)

/** Security Audit Logs: the append-only record of security events across GoPreach (Super Admin only; the server enforces it). */
class SecurityLogViewModel(private val security: SecurityService, private val auth: AuthService) : ViewModel() {
    private val _state = MutableStateFlow(SecurityLogUiState())
    val state: StateFlow<SecurityLogUiState> = _state.asStateFlow()

    init { load(); loadLimits() }

    private fun loadLimits() {
        viewModelScope.launch { runCatching { security.adminApprovalLimits() }.onSuccess { l -> _state.update { it.copy(limits = l) } } }
    }

    /** Saves the new-device request limits after the Super Admin types their password again; the server checks the bounds and logs the change. */
    fun saveLimits(limits: ApprovalLimits, password: String) {
        if (_state.value.limitsBusy) return
        _state.update { it.copy(limitsBusy = true, limitsError = null, limitsMessage = null) }
        viewModelScope.launch {
            try {
                auth.reauthenticate(password)
                security.saveApprovalLimits(limits)
                _state.update { it.copy(limitsBusy = false, limits = limits, limitsMessage = "Saved. The new limits apply at once.") }
                load()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(limitsBusy = false, limitsError = when (e) {
                    is SecurityFailure -> if (e.code == "invalid") "Use 1-20 requests, 1-120 minutes and 1-30 minutes of waiting." else e.message ?: "Not saved."
                    is AuthFailure.InvalidCredentials -> "Your current password is not correct."
                    else -> "Not saved. Please try again."
                }) }
            }
        }
    }

    fun setPerson(v: String) = _state.update { it.copy(personId = v) }
    fun setType(v: String) { _state.update { it.copy(type = v) }; load() }

    fun load() {
        val s = _state.value
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try { _state.update { it.copy(loading = false, events = security.adminEvents(s.personId, s.type)) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { _state.update { it.copy(loading = false, error = (e as? SecurityFailure)?.message ?: "Could not load the log.") } }
        }
    }
}
