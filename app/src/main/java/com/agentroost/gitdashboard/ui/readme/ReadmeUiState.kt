package com.agentroost.gitdashboard.ui.readme

import com.agentroost.gitdashboard.domain.model.PreviewUnavailableReason
import com.agentroost.gitdashboard.github.ApiError

/** Explicit states rendered by the README viewer. */
sealed interface ReadmeUiState {

    data object Loading : ReadmeUiState

    /** The listing or README request failed and there is nothing to show. */
    data class Error(val error: ApiError) : ReadmeUiState

    /** The repository has no root README this viewer can render. */
    data class NoReadme(val reason: NoReadmeReason) : ReadmeUiState

    /** The README was found but has no markdown to render (too large, binary, LFS, ...). */
    data class Unavailable(
        val reason: PreviewUnavailableReason,
        val readmePath: String,
        val githubHtmlUrl: String?,
    ) : ReadmeUiState

    /** The README was loaded and parsed; [blocks] keep their document order. */
    data class Content(
        val blocks: List<MarkdownBlock>,
        val readmePath: String,
        val githubHtmlUrl: String?,
    ) : ReadmeUiState
}

/** Why [ReadmeUiState.NoReadme] was reached. */
enum class NoReadmeReason {

    /** The repository root is empty (no commits) or GitHub answered 404 for it. */
    EMPTY_REPOSITORY,

    /** The root listing loaded, but it contains neither `README.md` nor `README.markdown`. */
    NO_README,
}
