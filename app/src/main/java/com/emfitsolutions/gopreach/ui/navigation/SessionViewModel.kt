package com.emfitsolutions.gopreach.ui.navigation

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.domain.SessionState
import com.emfitsolutions.gopreach.domain.UserSession
import kotlinx.coroutines.flow.StateFlow

/** Thin Compose-facing wrapper so [GoPreachNavGraph] can drive routing off the
 * app-wide [UserSession] without every screen needing its own copy of it. */
class SessionViewModel(
    private val userSession: UserSession,
    private val authRepository: AuthRepository,
) : ViewModel() {
    val state: StateFlow<SessionState> = userSession.state

    /** Spec §9 — an account deactivated/suspended mid-session must lose access
     * right away, not just be blocked from a *future* sign-in. Called by
     * [GoPreachNavGraph] the moment [SessionState.isAccountBlocked] goes true. */
    fun signOut() = authRepository.signOut()
}
