package com.agentroost.gitdashboard.ui.readme

import com.agentroost.gitdashboard.domain.model.PreviewUnavailableReason
import com.agentroost.gitdashboard.github.ApiError
import com.agentroost.gitdashboard.github.GitHubRepository
import com.agentroost.gitdashboard.testing.Contents
import com.agentroost.gitdashboard.testing.FakeGitHubApi
import com.agentroost.gitdashboard.testing.MainDispatcherRule
import com.agentroost.gitdashboard.testing.assertInstanceOf
import com.agentroost.gitdashboard.testing.awaitState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Root listing → README pick → markdown parse, exercised through the real
 * [GitHubRepository.repositoryContents]/[GitHubRepository.filePreview] and the real content decoder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadmeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val api = FakeGitHubApi()
    private val repository = GitHubRepository(api)

    private fun viewModel(): ReadmeViewModel =
        ReadmeViewModel(repository, owner = "octocat", repo = "hello-world")

    /** Serves the root listing from [entries] and the README body from [body]. */
    private fun serveReadme(
        readmePath: String = "README.md",
        entries: List<String> = listOf(Contents.dir("src"), Contents.file("README.md")),
        body: String = Contents.file(readmePath, text = "# Hello\n"),
    ) {
        api.onContents = { request ->
            when (request.path) {
                "" -> Contents.listing(*entries.toTypedArray())
                readmePath -> Contents.single(body)
                else -> error("unexpected contents request for /${request.path}")
            }
        }
    }

    @Test
    fun `a root README is found and parsed into blocks`() = runTest {
        serveReadme(
            body = Contents.file(
                name = "README.md",
                path = "README.md",
                text = "# Hello\n\nA paragraph with **bold** text.\n\n![Screenshot](docs/shot.png)\n",
                htmlUrl = "https://github.com/octocat/hello-world/blob/main/README.md",
            ),
        )

        val state = viewModel().uiState.awaitState { it !is ReadmeUiState.Loading }

        val content = assertInstanceOf<ReadmeUiState.Content>(state)
        assertEquals("README.md", content.readmePath)
        assertEquals(
            "https://github.com/octocat/hello-world/blob/main/README.md",
            content.githubHtmlUrl,
        )
        assertEquals(3, content.blocks.size)
        assertEquals(1, assertInstanceOf<MarkdownBlock.Heading>(content.blocks[0]).level)
        assertInstanceOf<MarkdownBlock.Paragraph>(content.blocks[1])
        assertEquals(
            "docs/shot.png",
            assertInstanceOf<MarkdownBlock.Image>(content.blocks[2]).src,
        )

        // The root is listed first, then the README body is requested by its repository path.
        assertEquals(listOf("", "README.md"), api.contentsRequests.map { it.path })
    }

    @Test
    fun `README lookup is case-insensitive and prefers md`() = runTest {
        api.onContents = { request ->
            when (request.path) {
                "" -> Contents.listing(
                    Contents.file("README.markdown"),
                    Contents.file("ReadMe.MD"),
                )

                "ReadMe.MD" -> Contents.single(Contents.file("ReadMe.MD", text = "hello"))
                else -> error("unexpected contents request for /${request.path}")
            }
        }

        val content = assertInstanceOf<ReadmeUiState.Content>(
            viewModel().uiState.awaitState { it !is ReadmeUiState.Loading },
        )

        assertEquals("ReadMe.MD", content.readmePath)
    }

    @Test
    fun `a root without a README is NoReadme`() = runTest {
        api.onContents = {
            Contents.listing(Contents.dir("src"), Contents.file("main.kt"))
        }

        val state = viewModel().uiState.awaitState { it !is ReadmeUiState.Loading }

        assertEquals(ReadmeUiState.NoReadme(NoReadmeReason.NO_README), state)
        assertEquals(listOf(""), api.contentsRequests.map { it.path })
    }

    @Test
    fun `a 404 at the repository root means an empty repository`() = runTest {
        api.onContents = { Contents.failure(404) }

        val state = viewModel().uiState.awaitState { it !is ReadmeUiState.Loading }

        assertEquals(ReadmeUiState.NoReadme(NoReadmeReason.EMPTY_REPOSITORY), state)
    }

    @Test
    fun `an empty root listing means an empty repository`() = runTest {
        api.onContents = { Contents.listing() }

        val state = viewModel().uiState.awaitState { it !is ReadmeUiState.Loading }

        assertEquals(ReadmeUiState.NoReadme(NoReadmeReason.EMPTY_REPOSITORY), state)
    }

    @Test
    fun `401 emits authRequired and reports the error`() = runTest {
        api.onContents = { Contents.failure(401, "Bad credentials") }

        val viewModel = viewModel()
        val authEvents = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.authRequired.collect { authEvents += it }
        }

        val error = assertInstanceOf<ReadmeUiState.Error>(
            viewModel.uiState.awaitState { it !is ReadmeUiState.Loading },
        )

        assertEquals(ApiError.Unauthorized, error.error)
        runCurrent()
        assertEquals(1, authEvents.size)
    }

    @Test
    fun `a failed listing shows an error and retry recovers`() = runTest {
        var rootAttempts = 0
        api.onContents = { request ->
            when (request.path) {
                "" -> {
                    rootAttempts++
                    if (rootAttempts == 1) {
                        Contents.failure(500, "Server error")
                    } else {
                        Contents.listing(Contents.file("README.md"))
                    }
                }

                else -> Contents.single(Contents.file("README.md", text = "# Back\n"))
            }
        }

        val viewModel = viewModel()
        val error = assertInstanceOf<ReadmeUiState.Error>(
            viewModel.uiState.awaitState { it !is ReadmeUiState.Loading },
        )
        assertEquals(500, assertInstanceOf<ApiError.Http>(error.error).statusCode)

        viewModel.retry()

        val content = assertInstanceOf<ReadmeUiState.Content>(
            viewModel.uiState.awaitState { it !is ReadmeUiState.Loading },
        )
        assertEquals("README.md", content.readmePath)
        assertEquals(2, rootAttempts)
    }

    @Test
    fun `a README GitHub will not inline surfaces the reason and the web URL`() = runTest {
        serveReadme(
            body = Contents.file(
                name = "README.md",
                path = "README.md",
                size = 2_000_000,
                base64 = "AAAA",
                htmlUrl = "https://github.com/octocat/hello-world/blob/main/README.md",
            ),
        )

        val state = assertInstanceOf<ReadmeUiState.Unavailable>(
            viewModel().uiState.awaitState { it !is ReadmeUiState.Loading },
        )

        assertEquals(PreviewUnavailableReason.TOO_LARGE, state.reason)
        assertEquals("README.md", state.readmePath)
        assertEquals(
            "https://github.com/octocat/hello-world/blob/main/README.md",
            state.githubHtmlUrl,
        )
        assertTrue(state.githubHtmlUrl!!.isNotBlank())
    }
}
