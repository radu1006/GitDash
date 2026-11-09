package com.agentroost.gitdashboard.ui.repo

import com.agentroost.gitdashboard.github.ApiError
import com.agentroost.gitdashboard.github.GitHubRepository
import com.agentroost.gitdashboard.testing.Contents
import com.agentroost.gitdashboard.testing.FakeGitHubApi
import com.agentroost.gitdashboard.testing.MainDispatcherRule
import com.agentroost.gitdashboard.testing.assertInstanceOf
import com.agentroost.gitdashboard.testing.awaitState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

/** Regression suite for the repository detail screen that hosts the Browse files button. */
@OptIn(ExperimentalCoroutinesApi::class)
class RepoDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val api = FakeGitHubApi()
    private val repository = GitHubRepository(api)

    private fun viewModel() = RepoDetailViewModel(repository, owner = "octocat", repo = "hello-world")

    @Test
    fun `detail is loaded from the repository endpoint`() = runTest {
        val state = assertInstanceOf<RepoDetailUiState.Content>(
            viewModel().uiState.awaitState { it is RepoDetailUiState.Content },
        )

        assertEquals("octocat/hello-world", state.repository.fullName)
        assertEquals("main", state.repository.defaultBranch)
        assertEquals(12, state.repository.stars)
        assertNull(state.error)
        assertTrue(!state.isRefreshing)
    }

    @Test
    fun `a failed load is an error and refresh recovers`() = runTest {
        var attempt = 0
        api.onRepository = { _, _ ->
            attempt++
            if (attempt == 1) Contents.error(404) else Response.success(FakeGitHubApi.repositoryDto())
        }

        val viewModel = viewModel()
        val error = assertInstanceOf<RepoDetailUiState.Error>(
            viewModel.uiState.awaitState { it is RepoDetailUiState.Error },
        )
        assertEquals(ApiError.NotFound, error.error)

        viewModel.refresh()

        val content = assertInstanceOf<RepoDetailUiState.Content>(
            viewModel.uiState.awaitState { it is RepoDetailUiState.Content },
        )
        assertEquals("octocat/hello-world", content.repository.fullName)
    }

    @Test
    fun `refresh keeps the header visible while reloading`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var attempt = 0
        api.onRepository = { _, _ ->
            attempt++
            if (attempt > 1) gate.await()
            Response.success(FakeGitHubApi.repositoryDto())
        }

        val viewModel = viewModel()
        val first = assertInstanceOf<RepoDetailUiState.Content>(
            viewModel.uiState.awaitState { it is RepoDetailUiState.Content },
        )

        viewModel.refresh()

        val refreshing = assertInstanceOf<RepoDetailUiState.Content>(viewModel.uiState.value)
        assertTrue(refreshing.isRefreshing)
        assertEquals(first.repository, refreshing.repository)

        gate.complete(Unit)
        val settled = viewModel.uiState.awaitState {
            it is RepoDetailUiState.Content && !it.isRefreshing
        }
        assertNull(assertInstanceOf<RepoDetailUiState.Content>(settled).error)
        assertEquals(2, attempt)
    }

    @Test
    fun `a failed refresh keeps the last header and surfaces the error`() = runTest {
        var attempt = 0
        api.onRepository = { _, _ ->
            attempt++
            if (attempt == 1) {
                Response.success(FakeGitHubApi.repositoryDto())
            } else {
                Contents.error(403, "Forbidden")
            }
        }

        val viewModel = viewModel()
        viewModel.uiState.awaitState { it is RepoDetailUiState.Content }

        viewModel.refresh()

        val failed = assertInstanceOf<RepoDetailUiState.Content>(
            viewModel.uiState.awaitState {
                it is RepoDetailUiState.Content && it.error != null
            },
        )
        assertEquals("octocat/hello-world", failed.repository.fullName)
        assertEquals(ApiError.Forbidden(apiMessage = "Forbidden"), failed.error)
        assertTrue(!failed.isRefreshing)
    }
}
