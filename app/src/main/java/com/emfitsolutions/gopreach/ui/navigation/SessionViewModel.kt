package com.emfitsolutions.gopreach.ui.navigation

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.domain.SessionState
import com.emfitsolutions.gopreach.domain.UserSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Thin Compose-facing wrapper so [GoPreachNavGraph] can drive routing off the
 * app-wide [UserSession] without every screen needing its own copy of it. */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val userSession: UserSession,
    private val authRepository: AuthRepository,
) : ViewModel() {
    val state: StateFlow<SessionState> = userSession.state

    /** Spec §9 — an account deactivated/suspended mid-session must lose access
     * right away, not just be blocked from a *future* sign-in. Called by
     * [GoPreachNavGraph] the moment [SessionState.isAccountBlocked] goes true. */
    fun signOut() = authRepository.signOut()

    /** "Multiple Role Login Detection & Role Selection" spec §7 — called once
     * from [com.emfitsolutions.gopreach.ui.screens.login.SelectRoleScreen]
     * when the user taps Continue; see [UserSession.selectRole]. */
    fun selectRole(assignmentId: String) = userSession.selectRole(assignmentId)
}
