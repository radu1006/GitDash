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
 * Boundary and failure states of the README screen's ViewModel.
 *
 * The rule under test: a README the viewer cannot render becomes [ReadmeUiState.Unavailable] with a
 * GitHub URL for the fallback button, and never an empty screen or an exception.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadmeViewModelEdgeCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val api = FakeGitHubApi()
    private val repository = GitHubRepository(api)

    private fun viewModel(): ReadmeViewModel =
        ReadmeViewModel(repository, owner = "octocat", repo = "hello-world")

    /** Serves a root listing containing README.md and [readmeBody] for the README request. */
    private fun serveReadme(readmeBody: String) {
        api.onContents = { request ->
            when (request.path) {
                "" -> Contents.listing(Contents.file("README.md"))
                "README.md" -> Contents.single(readmeBody)
                else -> error("unexpected contents request for /${request.path}")
            }
        }
    }

    @Test
    fun `a 2 MB README is TOO_LARGE instead of being parsed`() = runTest {
        // GitHub refuses to inline above 1 MB; this models the payload it would have sent.
        serveReadme(
            Contents.file(
                name = "README.md",
                path = "README.md",
                text = "x".repeat(2_000_000),
                htmlUrl = "https://github.com/octocat/hello-world/blob/main/README.md",
            ),
        )

        val state = assertInstanceOf<ReadmeUiState.Unavailable>(
            viewModel().uiState.awaitState { it !is ReadmeUiState.Loading },
        )

        assertEquals(PreviewUnavailableReason.TOO_LARGE, state.reason)
        assertEquals("README.md", state.readmePath)
        assertTrue(state.githubHtmlUrl!!.isNotBlank())
    }

    @Test
    fun `a README above the render budget is TOO_LARGE_TO_RENDER`() = runTest {
        // Just past MAX_MARKDOWN_CHARS, but still below the 1 MB inline limit.
        serveReadme(
            Contents.file(
                name = "README.md",
                path = "README.md",
                text = "x".repeat(ReadmeViewModel.MAX_MARKDOWN_CHARS + 1),
            ),
        )

        val state = assertInstanceOf<ReadmeUiState.Unavailable>(
            viewModel().uiState.awaitState { it !is ReadmeUiState.Loading },
        )

        assertEquals(PreviewUnavailableReason.TOO_LARGE_TO_RENDER, state.reason)
        assertEquals(
            // Contents.file's default html_url fixture for a README at the repository root.
            "https://github.com/o/r/blob/main/README.md",
            state.githubHtmlUrl,
        )
    }

    @Test
    fun `a README exactly at the render budget still renders`() = runTest {
        serveReadme(
            Contents.file(
                name = "README.md",
                path = "README.md",
                text = "x".repeat(ReadmeViewModel.MAX_MARKDOWN_CHARS),
            ),
        )

        val content = assertInstanceOf<ReadmeUiState.Content>(
            viewModel().uiState.awaitState { it !is ReadmeUiState.Loading },
        )

        assertEquals(1, content.blocks.size)
        assertInstanceOf<MarkdownBlock.Paragraph>(content.blocks.single())
    }

    @Test
    fun `an unavailable README always carries an Open-on-GitHub URL`() = runTest {
        // No html_url in the payload: the repository layer must build the blob URL itself.
        serveReadme(
            Contents.fileOfBytes(
                name = "README.md",
                path = "README.md",
                bytes = ByteArray(8),
                encoding = "none",
                htmlUrl = null,
            ),
        )

        val state = assertInstanceOf<ReadmeUiState.Unavailable>(
            viewModel().uiState.awaitState { it !is ReadmeUiState.Loading },
        )

        assertEquals(PreviewUnavailableReason.NOT_BASE64, state.reason)
        assertEquals(
            "https://github.com/octocat/hello-world/blob/HEAD/README.md",
            state.githubHtmlUrl,
        )
    }

    @Test
    fun `a NOT_BASE64 README is Unavailable with the reason`() = runTest {
        serveReadme(Contents.fileOfBytes("README.md", bytes = ByteArray(8), encoding = "none"))

        assertEquals(
            PreviewUnavailableReason.NOT_BASE64,
            assertInstanceOf<ReadmeUiState.Unavailable>(
                viewModel().uiState.awaitState { it !is ReadmeUiState.Loading },
            ).reason,
        )
    }

    @Test
    fun `a BINARY README is Unavailable with the reason`() = runTest {
        serveReadme(
            Contents.fileOfBytes(
                name = "README.md",
                path = "README.md",
                bytes = byteArrayOf(0x00, 0x01, 0x02, 0x00),
            ),
        )

        assertEquals(
            PreviewUnavailableReason.BINARY,
            assertInstanceOf<ReadmeUiState.Unavailable>(
                viewModel().uiState.awaitState { it !is ReadmeUiState.Loading },
            ).reason,
        )
    }

    @Test
    fun `a TRUNCATED README is Unavailable instead of rendering a partial document`() = runTest {
        serveReadme(Contents.fileOfBytes("README.md", bytes = ByteArray(8), size = 1_000))

        assertEquals(
            PreviewUnavailableReason.TRUNCATED,
            assertInstanceOf<ReadmeUiState.Unavailable>(
                viewModel().uiState.awaitState { it !is ReadmeUiState.Loading },
            ).reason,
        )
    }

    @Test
    fun `a repository with only README rst shows the no-markdown README state`() = runTest {
        api.onContents = {
            Contents.listing(
                Contents.file("README.rst"),
                Contents.file("README.txt"),
                Contents.dir("docs"),
            )
        }

        assertEquals(
            ReadmeUiState.NoReadme(NoReadmeReason.NO_README),
            viewModel().uiState.awaitState { it !is ReadmeUiState.Loading },
        )
        assertEquals(listOf(""), api.contentsRequests.map { it.path })
    }

    @Test
    fun `a directory named README md is not treated as a README`() = runTest {
        api.onContents = { Contents.listing(Contents.dir("README.md"), Contents.file("src.kt")) }

        assertEquals(
            ReadmeUiState.NoReadme(NoReadmeReason.NO_README),
            viewModel().uiState.awaitState { it !is ReadmeUiState.Loading },
        )
    }

    @Test
    fun `a 401 while fetching the README body also returns to onboarding`() = runTest {
        api.onContents = { request ->
            when (request.path) {
                "" -> Contents.listing(Contents.file("README.md"))
                else -> Contents.failure(401, "Bad credentials")
            }
        }
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
    fun `refresh reloads the README text`() = runTest {
        serveReadme(Contents.file("README.md", text = "# One\n"))
        val viewModel = viewModel()
        assertEquals(
            1,
            assertInstanceOf<ReadmeUiState.Content>(
                viewModel.uiState.awaitState { it !is ReadmeUiState.Loading },
            ).blocks.count { it is MarkdownBlock.Heading },
        )

        // The second read returns a different document; the refresh must show it.
        api.onContents = { request ->
            when (request.path) {
                "" -> Contents.listing(Contents.file("README.md"))
                else -> Contents.single(Contents.file("README.md", text = "# One\n\n## Two\n"))
            }
        }
        viewModel.retry()

        val refreshed = assertInstanceOf<ReadmeUiState.Content>(
            viewModel.uiState.awaitState {
                it is ReadmeUiState.Content && it.blocks.count { block -> block is MarkdownBlock.Heading } == 2
            },
        )
        assertEquals(2, refreshed.blocks.count { it is MarkdownBlock.Heading })
        assertEquals(2, api.contentsRequests.count { it.path == "" })
        assertEquals(2, api.contentsRequests.count { it.path == "README.md" })
    }
}
