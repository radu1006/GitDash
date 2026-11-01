package com.agentroost.gitdashboard.ui.token

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.agentroost.gitdashboard.data.auth.TokenRepository
import com.agentroost.gitdashboard.di.AppContainer
import com.agentroost.gitdashboard.github.ApiError
import com.agentroost.gitdashboard.github.ApiResult
import com.agentroost.gitdashboard.github.GitHubRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TokenUiState(
    val tokenInput: String = "",
    val savedTokenMask: String? = null,
    val isVerifying: Boolean = false,
    val verifiedLogin: String? = null,
    val error: ApiError? = null,
    val storageUnavailable: Boolean = false,
) {
    val hasSavedToken: Boolean get() = savedTokenMask != null
    val canSubmit: Boolean get() = tokenInput.isNotBlank() && !isVerifying
}

class TokenViewModel(
    private val tokenRepository: TokenRepository,
    private val gitHubRepository: GitHubRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TokenUiState())
    val uiState: StateFlow<TokenUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = tokenRepository.loadToken()
            _uiState.update { it.copy(savedTokenMask = TokenRepository.mask(stored)) }
        }
    }

    fun onTokenInputChange(value: String) {
        // Tokens are case-sensitive and must be sent verbatim; only trim on submit.
        _uiState.update {
            it.copy(tokenInput = value, error = null, verifiedLogin = null, storageUnavailable = false)
        }
    }

    /** Verifies the pasted token against `GET /user`, then persists it only if GitHub accepts it. */
    fun verifyAndSave() {
        val candidate = _uiState.value.tokenInput.trim()
        if (candidate.isEmpty() || _uiState.value.isVerifying) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(isVerifying = true, error = null, verifiedLogin = null, storageUnavailable = false)
            }

            when (val result = gitHubRepository.verifyToken(candidate)) {
                is ApiResult.Success -> {
                    val login = result.data.login
                    val stored = tokenRepository.saveToken(candidate)
                    _uiState.update { current ->
                        if (stored) {
                            current.copy(
                                isVerifying = false,
                                tokenInput = "",
                                verifiedLogin = login,
                                savedTokenMask = TokenRepository.mask(candidate),
                            )
                        } else {
                            current.copy(
                                isVerifying = false,
                                verifiedLogin = null,
                                storageUnavailable = true,
                            )
                        }
                    }
                }

                is ApiResult.Failure -> _uiState.update {
                    it.copy(isVerifying = false, error = result.error, verifiedLogin = null)
                }
            }
        }
    }

    fun clearSavedToken() {
        viewModelScope.launch {
            tokenRepository.clearToken()
            _uiState.value = TokenUiState()
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TokenViewModel(container.tokenRepository, container.gitHubRepository)
            }
        }
    }
}
