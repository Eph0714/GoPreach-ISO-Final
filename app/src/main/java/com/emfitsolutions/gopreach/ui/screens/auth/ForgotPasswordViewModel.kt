package com.emfitsolutions.gopreach.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ForgotPasswordUiState(
    val username: String = "",
    val isLoading: Boolean = false,
    val submitted: Boolean = false,
)

/**
 * Spec §4.5: lost credentials are recovered from whoever enrolled the person, not
 * a self-service emailed link (email is optional on Person) — this just files the
 * request for that role to see and action.
 */
@HiltViewModel
class ForgotPasswordViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ForgotPasswordUiState())
    val uiState: StateFlow<ForgotPasswordUiState> = _uiState.asStateFlow()

    fun onUsernameChange(value: String) = _uiState.update { it.copy(username = value) }

    fun submit() {
        val username = _uiState.value.username.trim()
        if (username.isBlank()) return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            authRepository.requestPasswordReset(username)
            _uiState.update { it.copy(isLoading = false, submitted = true) }
        }
    }
}
