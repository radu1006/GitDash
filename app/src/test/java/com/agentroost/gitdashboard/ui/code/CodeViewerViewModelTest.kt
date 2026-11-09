package com.agentroost.gitdashboard.ui.code

import com.agentroost.gitdashboard.domain.model.FileBody
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * File loading + highlight pass of the code viewer.
 *
 * The payloads go through the real [GitHubRepository.filePreview] and the real
 * [com.agentroost.gitdashboard.github.GitHubContentDecoder], so every
 * [PreviewUnavailableReason] produced by the decoder is asserted at the ViewModel boundary where the
 * UI consumes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodeViewerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val api = FakeGitHubApi()
    private val repository = GitHubRepository(api)

    private fun viewModel(path: String = "Main.kt"): CodeViewerViewModel =
        CodeViewerViewModel(repository, owner = "octocat", repo = "hello-world", path = path)

    private suspend fun unavailableReason(
        payload: String,
        path: String = "file.bin",
    ): PreviewUnavailableReason {
        api.onContents = { Contents.single(payload) }
        val state = viewModel(path).uiState.awaitState { it !is CodeViewerUiState.Loading }
        val content = assertInstanceOf<CodeViewerUiState.Content>(state)
        return assertInstanceOf<CodeViewerBody.Unavailable>(content.body).reason
    }

    @Test
    fun `starts in Loading and requests the file path`() = runTest {
        api.onContents = {
            Contents.single(Contents.file("Main.kt", path = "src/Main.kt", text = "val x = 1\n"))
        }

        val viewModel = viewModel(path = "src/Main.kt")

        assertEquals(CodeViewerUiState.Loading, viewModel.uiState.value)
        val content = assertInstanceOf<CodeViewerUiState.Content>(
            viewModel.uiState.awaitState { it !is CodeViewerUiState.Loading },
        )
        assertEquals("src/Main.kt", content.preview.entry.path)
        assertEquals("src/Main.kt", api.contentsRequests.single().path)
    }

    @Test
    fun `a text file is highlighted with line numbers and keyword colours`() = runTest {
        val text = "val x = 1\nfun main() {\n}\n"
        api.onContents = {
            Contents.single(
                Contents.file(
                    name = "Main.kt",
                    path = "src/Main.kt",
                    text = text,
                    htmlUrl = "https://github.com/octocat/hello-world/blob/main/src/Main.kt",
                ),
            )
        }

        val content = assertInstanceOf<CodeViewerUiState.Content>(
            viewModel("src/Main.kt").uiState.awaitState { it !is CodeViewerUiState.Loading },
        )
        val body = assertInstanceOf<CodeViewerBody.Highlighted>(content.body)

        assertEquals(text, body.code.annotated.text)
        assertEquals(4, body.code.lineCount)
        assertEquals("1\n2\n3\n4", body.code.lineNumbers)
        assertTrue(
            "expected keyword spans for a Kotlin file",
            body.code.annotated.spanStyles.isNotEmpty(),
        )
        assertEquals(
            "https://github.com/octocat/hello-world/blob/main/src/Main.kt",
            content.preview.githubHtmlUrl,
        )
        assertTrue(content.preview.isPreviewAvailable)
    }

    @Test
    fun `an empty file renders as highlighted empty text`() = runTest {
        api.onContents = { Contents.single(Contents.file("empty.txt", text = "")) }

        val content = assertInstanceOf<CodeViewerUiState.Content>(
            viewModel("empty.txt").uiState.awaitState { it !is CodeViewerUiState.Loading },
        )
        val body = assertInstanceOf<CodeViewerBody.Highlighted>(content.body)

        assertEquals("", body.code.annotated.text)
        assertEquals(1, body.code.lineCount)
    }

    @Test
    fun `TOO_LARGE - GitHub refuses to inline files above 1 MB`() = runTest {
        val reason = unavailableReason(
            Contents.file(
                "huge.bin",
                text = "",
                size = 2_000_000,
                base64 = "AAAA",
            ),
        )

        assertEquals(PreviewUnavailableReason.TOO_LARGE, reason)
    }

    @Test
    fun `TOO_LARGE_TO_RENDER - text above the highlight budget is refused`() = runTest {
        val text = "x".repeat(CodeHighlighter.MAX_HIGHLIGHT_CHARS + 1)

        val reason = unavailableReason(Contents.file("big.txt", text = text), path = "big.txt")

        assertEquals(PreviewUnavailableReason.TOO_LARGE_TO_RENDER, reason)
    }

    @Test
    fun `text of exactly the highlight budget is still rendered`() = runTest {
        val text = "x".repeat(CodeHighlighter.MAX_HIGHLIGHT_CHARS)
        api.onContents = { Contents.single(Contents.file("limit.txt", text = text)) }

        val content = assertInstanceOf<CodeViewerUiState.Content>(
            viewModel("limit.txt").uiState.awaitState { it !is CodeViewerUiState.Loading },
        )
        val body = assertInstanceOf<CodeViewerBody.Highlighted>(content.body)

        assertEquals(CodeHighlighter.MAX_HIGHLIGHT_CHARS, body.code.annotated.text.length)
    }

    @Test
    fun `NOT_BASE64 - non-base64 encodings cannot be previewed`() = runTest {
        val reason = unavailableReason(
            Contents.file("blob.bin", encoding = "none", base64 = null, size = 42),
        )

        assertEquals(PreviewUnavailableReason.NOT_BASE64, reason)
    }

    @Test
    fun `BINARY - NUL bytes are not rendered`() = runTest {
        val reason = unavailableReason(
            Contents.fileOfBytes("binary.dat", bytes = byteArrayOf(0x00, 0x01, 0x02, 0x03)),
        )

        assertEquals(PreviewUnavailableReason.BINARY, reason)
    }

    @Test
    fun `TRUNCATED - a payload shorter than the reported size is not shown`() = runTest {
        val reason = unavailableReason(
            Contents.fileOfBytes(
                "truncated.txt",
                bytes = "hello".toByteArray(),
                size = 5_000,
            ),
        )

        assertEquals(PreviewUnavailableReason.TRUNCATED, reason)
    }

    @Test
    fun `DECODE_FAILED - invalid base64 is reported, not thrown`() = runTest {
        val reason = unavailableReason(
            Contents.file("broken.txt", base64 = "!!!!", size = 3),
        )

        assertEquals(PreviewUnavailableReason.DECODE_FAILED, reason)
    }

    @Test
    fun `NOT_A_FILE - a directory payload is rejected`() = runTest {
        val reason = unavailableReason(Contents.dir("src"))

        assertEquals(PreviewUnavailableReason.NOT_A_FILE, reason)
    }

    @Test
    fun `a failed file request can be retried`() = runTest {
        var attempt = 0
        api.onContents = {
            attempt++
            if (attempt == 1) {
                Contents.failure(404)
            } else {
                Contents.single(Contents.file("Main.kt", text = "val x = 1\n"))
            }
        }

        val viewModel = viewModel()
        val error = assertInstanceOf<CodeViewerUiState.Error>(
            viewModel.uiState.awaitState { it !is CodeViewerUiState.Loading },
        )
        assertEquals(ApiError.NotFound, error.error)

        viewModel.retry()

        val content = assertInstanceOf<CodeViewerUiState.Content>(
            viewModel.uiState.awaitState { it !is CodeViewerUiState.Loading },
        )
        assertInstanceOf<CodeViewerBody.Highlighted>(content.body)
        assertEquals(2, attempt)
    }

    @Test
    fun `401 emits authRequired and keeps the error state`() = runTest {
        api.onContents = { Contents.failure(401, "Bad credentials") }

        val viewModel = viewModel()
        val authEvents = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.authRequired.collect { authEvents += it }
        }

        val error = assertInstanceOf<CodeViewerUiState.Error>(
            viewModel.uiState.awaitState { it !is CodeViewerUiState.Loading },
        )

        assertEquals(ApiError.Unauthorized, error.error)
        runCurrent()
        assertEquals(1, authEvents.size)
    }

    @Test
    fun `a file with no html url still gets a GitHub web fallback`() = runTest {
        api.onContents = {
            Contents.single(Contents.file("Main.kt", path = "src/Main.kt", text = "val x = 1\n", htmlUrl = null))
        }

        val content = assertInstanceOf<CodeViewerUiState.Content>(
            viewModel("src/Main.kt").uiState.awaitState { it !is CodeViewerUiState.Loading },
        )

        assertEquals(
            "https://github.com/octocat/hello-world/blob/HEAD/src/Main.kt",
            content.preview.githubHtmlUrl,
        )
        assertTrue(content.preview.isPreviewAvailable)
        assertFalse(content.preview.body is FileBody.Unavailable)
    }
}
