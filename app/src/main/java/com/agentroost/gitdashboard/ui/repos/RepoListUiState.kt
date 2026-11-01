package com.agentroost.gitdashboard.ui.repos

import com.agentroost.gitdashboard.domain.model.Repository
import com.agentroost.gitdashboard.github.ApiError

/** Explicit states rendered by the repository list. */
sealed interface RepoListUiState {

    /** First page in flight and nothing cached yet. */
    data object Loading : RepoListUiState

    /** Request succeeded but the account has no repositories. */
    data class Empty(
        val query: String,
        val isRefreshing: Boolean,
    ) : RepoListUiState

    /**
     * Repository rows are available. [repositories] is already filtered by [query]; [loadedCount]
     * is the unfiltered count of loaded rows (used for pagination prefetch).
     * [error] is set when a refresh/load-more failed while earlier data is still shown.
     */
    data class Content(
        val repositories: List<Repository>,
        val query: String,
        val loadedCount: Int,
        val hasMore: Boolean,
        val isRefreshing: Boolean,
        val isLoadingMore: Boolean,
        val error: ApiError? = null,
    ) : RepoListUiState

    /** First page failed and there is nothing to show. [error] drives the message and retry. */
    data class Error(
        val error: ApiError,
    ) : RepoListUiState
}

sealed interface RepoListEvent {
    /** GitHub answered 401: the stored token is no longer valid. */
    data object AuthRequired : RepoListEvent
}
