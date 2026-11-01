package com.agentroost.gitdashboard.ui.repo

import com.agentroost.gitdashboard.domain.model.Repository
import com.agentroost.gitdashboard.github.ApiError

/** States rendered by the repository detail screen. */
sealed interface RepoDetailUiState {

    data object Loading : RepoDetailUiState

    /**
     * Header data is available. [error] is only set when a refresh failed while [repository] from
     * the previous successful load is still on screen.
     */
    data class Content(
        val repository: Repository,
        val isRefreshing: Boolean,
        val error: ApiError? = null,
    ) : RepoDetailUiState

    /** `GET /repos/{owner}/{repo}` failed and there is nothing to show. */
    data class Error(val error: ApiError) : RepoDetailUiState
}
