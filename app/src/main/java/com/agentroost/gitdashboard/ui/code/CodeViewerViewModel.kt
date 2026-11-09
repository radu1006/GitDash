package com.agentroost.gitdashboard.ui.code

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.agentroost.gitdashboard.di.AppContainer
import com.agentroost.gitdashboard.domain.model.FileBody
import com.agentroost.gitdashboard.domain.model.FilePreview
import com.agentroost.gitdashboard.domain.model.PreviewUnavailableReason
import com.agentroost.gitdashboard.github.ApiError
import com.agentroost.gitdashboard.github.ApiResult
import com.agentroost.gitdashboard.github.GitHubRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads `GET /repos/{owner}/{repo}/contents/{path}` for a single file and tokenises it.
 *
 * The base64 decode happens in [GitHubRepository.filePreview] on an IO dispatcher; syntax
 * highlighting runs here on [Dispatchers.Default], so neither ever blocks the main thread. The
 * composable only reads an immutable [HighlightedCode].
 */
class CodeViewerViewModel(
    private val gitHubRepository: GitHubRepository,
    private val owner: String,
    private val repo: String,
    private val path: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow<CodeViewerUiState>(CodeViewerUiState.Loading)
    val uiState: StateFlow<CodeViewerUiState> = _uiState.asStateFlow()

    private val _authRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val authRequired: SharedFlow<Unit> = _authRequired.asSharedFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    /** Retries the file request after [CodeViewerUiState.Error]. */
    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        _uiState.value = CodeViewerUiState.Loading

        loadJob = viewModelScope.launch {
            when (val result = gitHubRepository.filePreview(owner, repo, path)) {
                is ApiResult.Success -> _uiState.value = CodeViewerUiState.Content(
                    preview = result.data,
                    body = renderBody(result.data),
                )

                is ApiResult.Failure -> {
                    if (result.error is ApiError.Unauthorized) _authRequired.tryEmit(Unit)
                    _uiState.value = CodeViewerUiState.Error(result.error)
                }
            }
        }
    }

    private suspend fun renderBody(preview: FilePreview): CodeViewerBody =
        when (val body = preview.body) {
            is FileBody.Unavailable -> CodeViewerBody.Unavailable(body.reason)

            is FileBody.Text -> {
                if (body.text.length > CodeHighlighter.MAX_HIGHLIGHT_CHARS) {
                    // GitHub inlines up to 1 MB, which is still far too much to lay out as text.
                    CodeViewerBody.Unavailable(PreviewUnavailableReason.TOO_LARGE_TO_RENDER)
                } else {
                    CodeViewerBody.Highlighted(
                        withContext(Dispatchers.Default) {
                            CodeHighlighter.highlight(
                                code = body.text,
                                language = CodeHighlighter.languageFor(preview.entry.name),
                            )
                        },
                    )
                }
            }
        }

    companion object {
        fun factory(
            container: AppContainer,
            owner: String,
            repo: String,
            path: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CodeViewerViewModel(
                    gitHubRepository = container.gitHubRepository,
                    owner = owner,
                    repo = repo,
                    path = path,
                )
            }
        }
    }
}
