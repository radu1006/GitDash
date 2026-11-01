package com.agentroost.gitdashboard.ui.repos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.agentroost.gitdashboard.di.AppContainer
import com.agentroost.gitdashboard.domain.model.Repository
import com.agentroost.gitdashboard.github.ApiError
import com.agentroost.gitdashboard.github.ApiResult
import com.agentroost.gitdashboard.github.GitHubRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Repository list state machine (ticket 1 screen, completed here because the screen was referenced
 * by the navigation graph but missing from the workspace).
 *
 * The first page is loaded once from `init`, never from composition, and a single in-flight request
 * is allowed at a time, so recomposition or repeated taps cannot start duplicate API calls.
 */
class RepoListViewModel(
    private val gitHubRepository: GitHubRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<RepoListUiState>(RepoListUiState.Loading)
    val uiState: StateFlow<RepoListUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<RepoListEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<RepoListEvent> = _events.asSharedFlow()

    private var loaded: List<Repository> = emptyList()
    private var query: String = ""
    private var nextPage: Int? = 1
    private var isRefreshing: Boolean = false
    private var isLoadingMore: Boolean = false
    private var lastError: ApiError? = null
    private var lastFailedPage: Int? = null
    private var isBusy: Boolean = false

    init {
        load(page = 1, replacing = true)
    }

    fun onQueryChange(value: String) {
        if (value == query) return
        query = value
        publish()
    }

    fun refresh() {
        load(page = 1, replacing = true)
    }

    fun loadMore() {
        val page = nextPage ?: return
        load(page = page, replacing = false)
    }

    /** Retries whichever request failed last (first page vs. next page). */
    fun retry() {
        val failedPage = lastFailedPage
        if (failedPage != null && failedPage > 1) loadMore() else refresh()
    }

    private fun load(page: Int, replacing: Boolean) {
        if (isBusy) return
        isBusy = true
        lastError = null
        if (replacing) isRefreshing = true else isLoadingMore = true
        publish()

        viewModelScope.launch {
            when (val result = gitHubRepository.userRepositories(page)) {
                is ApiResult.Success -> {
                    loaded = if (replacing) {
                        result.data.items
                    } else {
                        (loaded + result.data.items).distinctBy { it.id }
                    }
                    nextPage = result.data.nextPage
                    lastError = null
                    lastFailedPage = null
                }

                is ApiResult.Failure -> {
                    lastError = result.error
                    lastFailedPage = page
                    if (result.error is ApiError.Unauthorized) {
                        _events.tryEmit(RepoListEvent.AuthRequired)
                    }
                }
            }

            isBusy = false
            isRefreshing = false
            isLoadingMore = false
            publish()
        }
    }

    private fun publish() {
        val error = lastError
        _uiState.value = when {
            loaded.isEmpty() && error != null -> RepoListUiState.Error(error)
            loaded.isEmpty() && isRefreshing -> RepoListUiState.Loading
            loaded.isEmpty() -> RepoListUiState.Empty(query = query, isRefreshing = isRefreshing)
            else -> RepoListUiState.Content(
                repositories = filter(loaded, query),
                query = query,
                loadedCount = loaded.size,
                hasMore = nextPage != null,
                isRefreshing = isRefreshing,
                isLoadingMore = isLoadingMore,
                error = lastError,
            )
        }
    }

    private fun filter(repositories: List<Repository>, query: String): List<Repository> {
        if (query.isBlank()) return repositories
        return repositories.filter { repository ->
            repository.name.contains(query, ignoreCase = true) ||
                repository.fullName.contains(query, ignoreCase = true)
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { RepoListViewModel(container.gitHubRepository) }
        }
    }
}
