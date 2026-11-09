package com.agentroost.gitdashboard.ui.browser

import com.agentroost.gitdashboard.domain.model.ContentKind
import com.agentroost.gitdashboard.github.ApiError
import com.agentroost.gitdashboard.github.GitHubRepository
import com.agentroost.gitdashboard.testing.Contents
import com.agentroost.gitdashboard.testing.ContentsRequest
import com.agentroost.gitdashboard.testing.FakeGitHubApi
import com.agentroost.gitdashboard.testing.MainDispatcherRule
import com.agentroost.gitdashboard.testing.assertInstanceOf
import com.agentroost.gitdashboard.testing.awaitState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * State machine of the file browser (defect D-1 fix).
 *
 * Every test drives the real [BrowseViewModel] through the real [GitHubRepository] and `safeApiCall`;
 * only the Retrofit [com.agentroost.gitdashboard.github.GitHubApi] at the bottom is faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val api = FakeGitHubApi()
    private val repository = GitHubRepository(api)

    private fun viewModel(
        initialPath: String = "",
        owner: String = "octocat",
        repo: String = "hello-world",
    ): BrowseViewModel = BrowseViewModel(repository, owner, repo, initialPath)

    @Test
    fun `initial state is Loading for the requested path`() = runTest {
        api.onContents = { Contents.listing(Contents.file("README.md")) }

        val viewModel = viewModel()

        // Nothing has run yet: the request is queued on the test dispatcher.
        assertEquals(BrowseUiState.Loading(""), viewModel.uiState.value)
        assertEquals(emptyList<ContentsRequest>(), api.contentsRequests)

        // Let the queued load finish before the test ends. The request body runs on Dispatchers.IO,
        // and a coroutine still resuming to Main after MainDispatcherRule resets it is reported as
        // an uncaught exception before whichever test runs next.
        assertInstanceOf<BrowseUiState.Content>(
            viewModel.uiState.awaitState { it is BrowseUiState.Content },
        )
    }

    @Test
    fun `loads the root listing with folders first`() = runTest {
        api.onContents = {
            Contents.listing(
                Contents.file("README.md"),
                Contents.dir("src"),
                Contents.file("Main.kt"),
            )
        }

        val state = assertInstanceOf<BrowseUiState.Content>(
            viewModel().uiState.awaitState { it is BrowseUiState.Content },
        )

        assertEquals("", state.path)
        // Folder first, then files case-insensitively ("Main.kt" < "README.md").
        assertEquals(listOf("src", "Main.kt", "README.md"), state.entries.map { it.name })
        assertFalse(state.isRefreshing)
        assertEquals(
            listOf(ContentsRequest(owner = "octocat", repo = "hello-world", path = "", ref = null)),
            api.contentsRequests,
        )
    }

    @Test
    fun `sorts folders first then case-insensitively by name`() = runTest {
        api.onContents = {
            Contents.listing(
                Contents.file("b.txt"),
                Contents.dir("beta"),
                Contents.file("A.txt"),
                Contents.dir("Alpha"),
                Contents.file("c.txt"),
                Contents.dir("Zulu"),
            )
        }

        val state = assertInstanceOf<BrowseUiState.Content>(
            viewModel().uiState.awaitState { it is BrowseUiState.Content },
        )

        assertEquals(
            listOf("Alpha", "beta", "Zulu", "A.txt", "b.txt", "c.txt"),
            state.entries.map { it.name },
        )
        assertEquals(
            listOf(
                ContentKind.DIRECTORY,
                ContentKind.DIRECTORY,
                ContentKind.DIRECTORY,
                ContentKind.FILE,
                ContentKind.FILE,
                ContentKind.FILE,
            ),
            state.entries.map { it.kind },
        )
    }

    @Test
    fun `an empty listing becomes the empty state`() = runTest {
        api.onContents = { Contents.listing() }

        val state = viewModel().uiState.awaitState { it is BrowseUiState.Empty }

        assertEquals("", state.path)
    }

    @Test
    fun `404 at the repository root is shown as an empty folder`() = runTest {
        api.onContents = { Contents.failure(404) }

        val state = viewModel().uiState.awaitState { it is BrowseUiState.Empty }

        assertEquals("", state.path)
    }

    @Test
    fun `404 below the root is an error`() = runTest {
        api.onContents = { Contents.failure(404) }

        val state = assertInstanceOf<BrowseUiState.Error>(
            viewModel(initialPath = "src/gone").uiState.awaitState { it is BrowseUiState.Error },
        )

        assertEquals("src/gone", state.path)
        assertEquals(ApiError.NotFound, state.error)
    }

    @Test
    fun `a failed load can be retried`() = runTest {
        var attempt = 0
        api.onContents = {
            attempt++
            if (attempt == 1) {
                Contents.failure(500, "Server Error")
            } else {
                Contents.listing(Contents.file("README.md"))
            }
        }

        val viewModel = viewModel()
        val error = assertInstanceOf<BrowseUiState.Error>(
            viewModel.uiState.awaitState { it is BrowseUiState.Error },
        )
        assertEquals(ApiError.Http(statusCode = 500, apiMessage = "Server Error"), error.error)
        assertEquals("", error.path)

        viewModel.retry()

        val content = assertInstanceOf<BrowseUiState.Content>(
            viewModel.uiState.awaitState { it is BrowseUiState.Content },
        )
        assertEquals(listOf("README.md"), content.entries.map { it.name })
        assertEquals(2, attempt)
    }

    @Test
    fun `refresh keeps the current listing visible while reloading`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var attempt = 0
        api.onContents = {
            attempt++
            if (attempt > 1) gate.await()
            Contents.listing(Contents.file("README.md"))
        }

        val viewModel = viewModel()
        val first = assertInstanceOf<BrowseUiState.Content>(
            viewModel.uiState.awaitState { it is BrowseUiState.Content },
        )

        viewModel.refresh()

        // The refresh flag is set synchronously, before the second request completes.
        val refreshing = assertInstanceOf<BrowseUiState.Content>(viewModel.uiState.value)
        assertTrue(refreshing.isRefreshing)
        assertEquals(first.entries, refreshing.entries)

        gate.complete(Unit)
        val settled = viewModel.uiState.awaitState { it is BrowseUiState.Content && !it.isRefreshing }
        assertFalse(assertInstanceOf<BrowseUiState.Content>(settled).isRefreshing)
        assertEquals(2, attempt)
    }

    @Test
    fun `opening a folder loads that path and reports it while loading`() = runTest {
        api.onContents = { request ->
            if (request.path.isBlank()) {
                Contents.listing(Contents.dir("src"))
            } else {
                Contents.listing(Contents.file("Main.kt", path = "${request.path}/Main.kt"))
            }
        }

        val viewModel = viewModel()
        viewModel.uiState.awaitState { it is BrowseUiState.Content }

        viewModel.openDirectory("src")

        assertEquals(BrowseUiState.Loading("src"), viewModel.uiState.value)

        val content = assertInstanceOf<BrowseUiState.Content>(
            viewModel.uiState.awaitState { it is BrowseUiState.Content && it.path == "src" },
        )
        assertEquals(listOf("Main.kt"), content.entries.map { it.name })
        assertEquals(listOf("", "src"), api.contentsRequests.map { it.path })
    }

    @Test
    fun `initial path is normalised and re-opening the same folder does not refetch`() = runTest {
        api.onContents = { Contents.listing(Contents.file("Main.kt")) }

        val viewModel = viewModel(initialPath = "/src//main/")
        val content = assertInstanceOf<BrowseUiState.Content>(
            viewModel.uiState.awaitState { it is BrowseUiState.Content },
        )
        assertEquals("src/main", content.path)
        assertEquals(listOf("src/main"), api.contentsRequests.map { it.path })

        viewModel.openDirectory("src/main")
        viewModel.openDirectory("/src/main/")
        advanceUntilIdle()

        assertEquals(1, api.contentsRequests.size)
    }

    @Test
    fun `openParent walks up to the root and stops there`() = runTest {
        api.onContents = { Contents.listing(Contents.file("file.txt")) }

        val viewModel = viewModel(initialPath = "a/b/c")
        viewModel.uiState.awaitState { it is BrowseUiState.Content && it.path == "a/b/c" }

        val visited = mutableListOf<String>()
        repeat(4) {
            viewModel.openParent()
            visited += viewModel.uiState.awaitState { it is BrowseUiState.Content }.path
        }

        assertEquals(listOf("a/b", "a", "", ""), visited)
        // The final openParent() at the root was a no-op: no extra request.
        assertEquals(listOf("a/b/c", "a/b", "a", ""), api.contentsRequests.map { it.path })
    }

    @Test
    fun `openParent from a single segment returns to the root`() = runTest {
        api.onContents = { Contents.listing(Contents.file("file.txt")) }

        val viewModel = viewModel(initialPath = "src")
        viewModel.uiState.awaitState { it is BrowseUiState.Content && it.path == "src" }

        viewModel.openParent()

        val root = assertInstanceOf<BrowseUiState.Content>(
            viewModel.uiState.awaitState { it is BrowseUiState.Content && it.path.isEmpty() },
        )
        assertEquals("", root.path)
        assertEquals(listOf("src", ""), api.contentsRequests.map { it.path })
    }

    @Test
    fun `a superseded slow folder never overwrites the newer listing`() = runTest {
        val rootGate = CompletableDeferred<Unit>()
        api.onContents = { request ->
            if (request.path.isBlank()) {
                rootGate.await()
                Contents.listing(Contents.file("root.txt"))
            } else {
                Contents.listing(Contents.file("nested.txt"))
            }
        }

        val viewModel = viewModel()
        viewModel.openDirectory("src")

        val content = assertInstanceOf<BrowseUiState.Content>(
            viewModel.uiState.awaitState { it is BrowseUiState.Content },
        )
        assertEquals("src", content.path)
        assertEquals(listOf("nested.txt"), content.entries.map { it.name })

        // Release the cancelled root request: its answer must be dropped.
        rootGate.complete(Unit)
        advanceUntilIdle()

        val finalState = assertInstanceOf<BrowseUiState.Content>(viewModel.uiState.value)
        assertEquals("src", finalState.path)
        assertEquals(listOf("nested.txt"), finalState.entries.map { it.name })
    }

    @Test
    fun `401 emits authRequired and exposes the error`() = runTest {
        api.onContents = { Contents.failure(401, "Bad credentials") }

        val viewModel = viewModel()
        val authEvents = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.authRequired.collect { authEvents += it }
        }

        val error = assertInstanceOf<BrowseUiState.Error>(
            viewModel.uiState.awaitState { it is BrowseUiState.Error },
        )

        assertEquals(ApiError.Unauthorized, error.error)
        runCurrent()
        assertEquals(1, authEvents.size)
    }
}
