package com.agentroost.gitdashboard.ui.readme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.agentroost.gitdashboard.di.AppContainer
import com.agentroost.gitdashboard.domain.model.ContentEntry
import com.agentroost.gitdashboard.domain.model.ContentKind
import com.agentroost.gitdashboard.domain.model.FileBody
import com.agentroost.gitdashboard.domain.model.FilePreview
import com.agentroost.gitdashboard.domain.model.PreviewUnavailableReason
import com.agentroost.gitdashboard.github.ApiError
import com.agentroost.gitdashboard.github.ApiResult
import com.agentroost.gitdashboard.github.GitHubRepository
import com.agentroost.gitdashboard.github.GitHubWebUrls
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
 * Loads the repository root, finds its README and parses it into [MarkdownBlock]s.
 *
 * The listing is `GET /repos/{owner}/{repo}/contents/`; the README body comes from the same
 * endpoint through [GitHubRepository.filePreview], which decodes base64 off the main thread. Only
 * the markdown parse runs here on [Dispatchers.Default], so no screen ever lays out a README before
 * its text is ready.
 *
 * Missing READMEs are a normal outcome, not an error: an empty root (GitHub answers 404 for a
 * repository without commits) and a root without a README both become [ReadmeUiState.NoReadme]. A
 * README GitHub refuses to inline becomes [ReadmeUiState.Unavailable], which the screen renders with
 * an Open-on-GitHub action instead of markdown.
 */
class ReadmeViewModel(
    private val gitHubRepository: GitHubRepository,
    private val owner: String,
    private val repo: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReadmeUiState>(ReadmeUiState.Loading)
    val uiState: StateFlow<ReadmeUiState> = _uiState.asStateFlow()

    private val _authRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val authRequired: SharedFlow<Unit> = _authRequired.asSharedFlow()

    /**
     * Image loader owned by the ViewModel so screenshots survive a configuration change. Constructed
     * lazily: a screen that never shows an image never builds the bitmap cache or HTTP client.
     */
    internal val imageLoader: ReadmeImageLoader by lazy {
        ReadmeImageLoader(
            fetcher = ReadmeImageFetcher(gitHubRepository, owner, repo),
            scope = viewModelScope,
        )
    }

    private var loadJob: Job? = null

    init {
        load()
    }

    /** Retries the listing/README request after [ReadmeUiState.Error]. */
    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        _uiState.value = ReadmeUiState.Loading

        loadJob = viewModelScope.launch {
            when (val listing = gitHubRepository.repositoryContents(owner, repo, "")) {
                is ApiResult.Failure -> {
                    if (listing.error is ApiError.NotFound) {
                        // GitHub answers 404 for the root of a repository with no commits, and does
                        // not distinguish that from a root the token cannot see.
                        _uiState.value = ReadmeUiState.NoReadme(NoReadmeReason.EMPTY_REPOSITORY)
                    } else {
                        fail(listing.error)
                    }
                }

                is ApiResult.Success -> {
                    val entry = ReadmeFiles.pick(listing.data)
                    when {
                        entry != null -> loadReadme(entry)
                        listing.data.isEmpty() ->
                            _uiState.value = ReadmeUiState.NoReadme(NoReadmeReason.EMPTY_REPOSITORY)

                        else -> _uiState.value = ReadmeUiState.NoReadme(NoReadmeReason.NO_README)
                    }
                }
            }
        }
    }

    private suspend fun loadReadme(entry: ContentEntry) {
        when (val result = gitHubRepository.filePreview(owner, repo, entry.path)) {
            is ApiResult.Failure -> fail(result.error)
            is ApiResult.Success -> render(result.data)
        }
    }

    private suspend fun render(preview: FilePreview) {
        val url = preview.githubHtmlUrl
            ?: GitHubWebUrls.blob(owner, repo, preview.ref.orEmpty(), preview.entry.path)

        when (val body = preview.body) {
            is FileBody.Unavailable -> {
                _uiState.value = ReadmeUiState.Unavailable(body.reason, preview.entry.path, url)
            }

            is FileBody.Text -> {
                if (body.text.length > MAX_MARKDOWN_CHARS) {
                    _uiState.value = ReadmeUiState.Unavailable(
                        reason = PreviewUnavailableReason.TOO_LARGE_TO_RENDER,
                        readmePath = preview.entry.path,
                        githubHtmlUrl = url,
                    )
                } else {
                    val blocks = withContext(Dispatchers.Default) {
                        MarkdownParser.parse(body.text)
                    }
                    _uiState.value = ReadmeUiState.Content(
                        blocks = blocks,
                        readmePath = preview.entry.path,
                        githubHtmlUrl = url,
                    )
                }
            }
        }
    }

    private fun fail(error: ApiError) {
        if (error is ApiError.Unauthorized) _authRequired.tryEmit(Unit)
        _uiState.value = ReadmeUiState.Error(error)
    }

    companion object {
        /**
         * Parsing is linear in the text length, but laying out a 1 MB README would freeze the
         * screen. GitHub refuses to inline anything above 1 MB anyway; this tighter budget keeps a
         * pathological markdown document off the UI thread's critical path.
         */
        internal const val MAX_MARKDOWN_CHARS = 200_000

        fun factory(
            container: AppContainer,
            owner: String,
            repo: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ReadmeViewModel(
                    gitHubRepository = container.gitHubRepository,
                    owner = owner,
                    repo = repo,
                )
            }
        }
    }
}

/**
 * The root README names this viewer understands, case-insensitively.
 *
 * Only these two names are rendered as markdown: an arbitrary `.md` file is still opened in the code
 * viewer by the file browser, and `README.rst`/`README.txt` are deliberately out of scope.
 */
object ReadmeFiles {

    private const val README_MD = "readme.md"
    private const val README_MARKDOWN = "readme.markdown"

    fun isReadme(name: String): Boolean =
        name.equals(README_MD, ignoreCase = true) ||
            name.equals(README_MARKDOWN, ignoreCase = true)

    /** Picks the README from a root listing, preferring `README.md` over `README.markdown`. */
    fun pick(entries: List<ContentEntry>): ContentEntry? {
        val files = entries.filter { it.kind == ContentKind.FILE }
        return files.firstOrNull { it.name.equals(README_MD, ignoreCase = true) }
            ?: files.firstOrNull { it.name.equals(README_MARKDOWN, ignoreCase = true) }
    }
}
