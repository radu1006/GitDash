package com.agentroost.gitdashboard.ui.repo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.agentroost.gitdashboard.di.AppContainer
import com.agentroost.gitdashboard.github.ApiError
import com.agentroost.gitdashboard.github.ApiResult
import com.agentroost.gitdashboard.github.GitHubRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Loads `GET /repos/{owner}/{repo}` once for the detail header.
 *
 * [owner] and [repo] come from the navigation arguments, so a process death restore re-creates this
 * ViewModel with the same repository without any extra persistence.
 */
class RepoDetailViewModel(
    private val gitHubRepository: GitHubRepository,
    private val owner: String,
    private val repo: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow<RepoDetailUiState>(RepoDetailUiState.Loading)
    val uiState: StateFlow<RepoDetailUiState> = _uiState.asStateFlow()

    private val _authRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val authRequired: SharedFlow<Unit> = _authRequired.asSharedFlow()

    private var isBusy: Boolean = false

    init {
        load()
    }

    fun refresh() = load()

    private fun load() {
        if (isBusy) return
        isBusy = true
        _uiState.update { current ->
            when (current) {
                is RepoDetailUiState.Content -> current.copy(isRefreshing = true, error = null)
                else -> RepoDetailUiState.Loading
            }
        }

        viewModelScope.launch {
            when (val result = gitHubRepository.repositoryDetail(owner, repo)) {
                is ApiResult.Success -> _uiState.value =
                    RepoDetailUiState.Content(result.data, isRefreshing = false)

                is ApiResult.Failure -> {
                    if (result.error is ApiError.Unauthorized) _authRequired.tryEmit(Unit)
                    _uiState.update { current ->
                        if (current is RepoDetailUiState.Content) {
                            current.copy(isRefreshing = false, error = result.error)
                        } else {
                            RepoDetailUiState.Error(result.error)
                        }
                    }
                }
            }
            isBusy = false
        }
    }

    companion object {
        fun factory(
            container: AppContainer,
            owner: String,
            repo: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { RepoDetailViewModel(container.gitHubRepository, owner, repo) }
        }
    }
}
