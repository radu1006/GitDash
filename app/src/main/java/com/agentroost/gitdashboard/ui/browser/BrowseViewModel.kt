package com.agentroost.gitdashboard.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.agentroost.gitdashboard.di.AppContainer
import com.agentroost.gitdashboard.domain.model.ContentEntry
import com.agentroost.gitdashboard.github.ApiError
import com.agentroost.gitdashboard.github.ApiResult
import com.agentroost.gitdashboard.github.GitHubRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Directory state machine for `GET /repos/{owner}/{repo}/contents/{path}`.
 *
 * The path lives here rather than in the navigation graph: moving between folders replaces the
 * current listing in place, so the back stack keeps one browser entry and Back always returns to
 * the repository detail screen. At most one listing request is in flight; opening another folder
 * cancels the previous request instead of letting a stale answer overwrite the newer folder.
 */
class BrowseViewModel(
    private val gitHubRepository: GitHubRepository,
    private val owner: String,
    private val repo: String,
    initialPath: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BrowseUiState>(
        BrowseUiState.Loading(normalizePath(initialPath)),
    )
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    private val _authRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val authRequired: SharedFlow<Unit> = _authRequired.asSharedFlow()

    private var currentPath: String = normalizePath(initialPath)
    private var loadJob: Job? = null

    init {
        load()
    }

    /** Re-requests the current folder, keeping the listing visible while it refreshes. */
    fun refresh() = load()

    /** Retries after [BrowseUiState.Error]. */
    fun retry() = load()

    /** Enters [path] (repository-relative, `""` for the root). */
    fun openDirectory(path: String) {
        val normalized = normalizePath(path)
        if (normalized == currentPath) return
        currentPath = normalized
        load()
    }

    /** Moves to the parent of the current folder; a no-op at the root. */
    fun openParent() {
        if (currentPath.isBlank()) return
        openDirectory(currentPath.substringBeforeLast('/', missingDelimiterValue = ""))
    }

    private fun load() {
        val requestedPath = currentPath
        loadJob?.cancel()
        _uiState.update { current ->
            if (current is BrowseUiState.Content && current.path == requestedPath) {
                current.copy(isRefreshing = true)
            } else {
                BrowseUiState.Loading(requestedPath)
            }
        }

        loadJob = viewModelScope.launch {
            when (val result = gitHubRepository.repositoryContents(owner, repo, requestedPath)) {
                is ApiResult.Success -> {
                    if (requestedPath != currentPath) return@launch
                    val entries = sortEntries(result.data)
                    _uiState.value = if (entries.isEmpty()) {
                        BrowseUiState.Empty(requestedPath)
                    } else {
                        BrowseUiState.Content(requestedPath, entries)
                    }
                }

                is ApiResult.Failure -> {
                    if (result.error is ApiError.Unauthorized) _authRequired.tryEmit(Unit)
                    if (requestedPath != currentPath) return@launch
                    // GitHub answers 404 for the root of a repository without commits; show that
                    // as an empty folder. A 404 deeper down means the path no longer exists.
                    _uiState.value = if (requestedPath.isBlank() &&
                        result.error is ApiError.NotFound
                    ) {
                        BrowseUiState.Empty(requestedPath)
                    } else {
                        BrowseUiState.Error(requestedPath, result.error)
                    }
                }
            }
        }
    }

    private fun sortEntries(entries: List<ContentEntry>): List<ContentEntry> =
        entries.sortedWith(
            compareByDescending<ContentEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )

    private fun normalizePath(path: String): String =
        path.split('/').filter { it.isNotEmpty() }.joinToString("/")

    companion object {
        fun factory(
            container: AppContainer,
            owner: String,
            repo: String,
            initialPath: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BrowseViewModel(
                    gitHubRepository = container.gitHubRepository,
                    owner = owner,
                    repo = repo,
                    initialPath = initialPath,
                )
            }
        }
    }
}
