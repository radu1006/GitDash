package com.agentroost.gitdashboard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.agentroost.gitdashboard.data.auth.TokenRepository
import com.agentroost.gitdashboard.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * App-level session state. [sessionKey] changes whenever the token changes, which recreates the
 * authenticated NavHost (and every ViewModel inside it) so no screen keeps data from the previous
 * account/token.
 */
data class AppUiState(
    val isLoading: Boolean = true,
    val hasToken: Boolean = false,
    val sessionKey: Long = 0L,
)

class AppViewModel(
    private val tokenRepository: TokenRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val token = tokenRepository.loadToken()
            _uiState.update { it.copy(isLoading = false, hasToken = token != null) }
        }
    }

    /** Called after a token has been verified and persisted. */
    fun onTokenSaved() {
        _uiState.update { it.copy(hasToken = true, sessionKey = it.sessionKey + 1) }
    }

    /** Called on explicit sign-out or when GitHub rejects the stored token (401). */
    fun onSessionExpired() {
        viewModelScope.launch {
            tokenRepository.clearToken()
            _uiState.update { it.copy(hasToken = false, sessionKey = it.sessionKey + 1) }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AppViewModel(container.tokenRepository) }
        }
    }
}
