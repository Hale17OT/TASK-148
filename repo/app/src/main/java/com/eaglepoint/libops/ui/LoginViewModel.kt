package com.eaglepoint.libops.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.auth.AuthRepository
import com.eaglepoint.libops.auth.SessionStore
import com.eaglepoint.libops.domain.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(
    private val repo: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun login(username: String, password: CharArray) {
        if (_state.value == UiState.Submitting) return
        _state.value = UiState.Submitting
        viewModelScope.launch {
            val result = repo.login(username, password)
            _state.value = when (result) {
                is AppResult.Success -> UiState.Authenticated(result.data)
                is AppResult.Locked -> UiState.Locked(result.minutesRemaining)
                is AppResult.ValidationError -> UiState.Error(result.fieldErrors.joinToString("\n") { it.message })
                is AppResult.Conflict -> UiState.Error("Account ${result.reason.replace('_', ' ')}")
                is AppResult.PermissionDenied -> UiState.Error("Permission denied: ${result.permission}")
                is AppResult.NotFound -> UiState.Error("Not found: ${result.entity}")
                is AppResult.SystemError -> UiState.Error("System error (${result.correlationId})")
            }
            // Zero the password buffer regardless of outcome
            password.fill('\u0000')
        }
    }

    fun loginViaBiometric(username: String) {
        if (_state.value == UiState.Submitting) return
        _state.value = UiState.Submitting
        viewModelScope.launch {
            val result = repo.resumeViaBiometric(username)
            _state.value = when (result) {
                is AppResult.Success -> UiState.Authenticated(result.data)
                is AppResult.Locked -> UiState.Locked(result.minutesRemaining)
                is AppResult.ValidationError -> UiState.Error(result.fieldErrors.joinToString("\n") { it.message })
                is AppResult.Conflict -> UiState.Error("Biometric unavailable: ${result.reason.replace('_', ' ')}")
                is AppResult.PermissionDenied -> UiState.Error("Permission denied: ${result.permission}")
                is AppResult.NotFound -> UiState.Error("User not found")
                is AppResult.SystemError -> UiState.Error("System error (${result.correlationId})")
            }
        }
    }

    fun reset() { _state.value = UiState.Idle }

    sealed interface UiState {
        data object Idle : UiState
        data object Submitting : UiState
        data class Authenticated(val session: SessionStore.ActiveSession) : UiState
        data class Locked(val minutesRemaining: Int) : UiState
        data class Error(val message: String) : UiState
    }
}

class LoginViewModelFactory(private val app: LibOpsApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LoginViewModel(app.authRepository) as T
}
