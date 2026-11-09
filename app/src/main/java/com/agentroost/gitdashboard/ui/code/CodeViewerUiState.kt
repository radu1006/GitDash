package com.agentroost.gitdashboard.ui.code

import com.agentroost.gitdashboard.domain.model.FilePreview
import com.agentroost.gitdashboard.domain.model.PreviewUnavailableReason
import com.agentroost.gitdashboard.github.ApiError

/** Explicit states rendered by the code viewer. */
sealed interface CodeViewerUiState {

    data object Loading : CodeViewerUiState

    /** The file was loaded; [body] is either highlighted text or the reason it cannot be shown. */
    data class Content(
        val preview: FilePreview,
        val body: CodeViewerBody,
    ) : CodeViewerUiState

    /** The file request failed and there is nothing to show. */
    data class Error(val error: ApiError) : CodeViewerUiState
}

/** Renderable result of a [FilePreview] after the highlight pass. */
sealed interface CodeViewerBody {

    data class Highlighted(val code: HighlightedCode) : CodeViewerBody

    data class Unavailable(val reason: PreviewUnavailableReason) : CodeViewerBody
}
