package com.agentroost.gitdashboard.ui.browser

import com.agentroost.gitdashboard.domain.model.ContentEntry
import com.agentroost.gitdashboard.github.ApiError

/**
 * Explicit states rendered by the file browser.
 *
 * Every state carries the [path] it describes, so the current-path row stays correct while a folder
 * is loading or after a failure, not only once its entries arrive.
 */
sealed interface BrowseUiState {

    val path: String

    /** A listing for [path] is in flight and nothing is cached yet. */
    data class Loading(override val path: String) : BrowseUiState

    /** The folder exists but has no entries (also used for the root of an empty repository). */
    data class Empty(override val path: String) : BrowseUiState

    /** [entries] is already sorted: folders first, then case-insensitively by name. */
    data class Content(
        override val path: String,
        val entries: List<ContentEntry>,
        val isRefreshing: Boolean = false,
    ) : BrowseUiState

    /** The listing for [path] failed and there is nothing to show. */
    data class Error(
        override val path: String,
        val error: ApiError,
    ) : BrowseUiState
}
