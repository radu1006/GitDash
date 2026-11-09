package com.agentroost.gitdashboard.ui.repos

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
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

/** Regression suite for the repository list screen (ticket 1 behaviour). */
@OptIn(ExperimentalCoroutinesApi::class)
class RepoListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val api = FakeGitHubApi()
    private val repository = GitHubRepository(api)

    private fun viewModel() = RepoListViewModel(repository)

    @Test
    fun `first page is loaded and mapped`() = runTest {
        api.onUserRepos = { _, _ ->
            Response.success(FakeGitHubApi.repositoryDtoList("alpha", "beta"))
        }

        val state = assertInstanceOf<RepoListUiState.Content>(
            viewModel().uiState.awaitState { it is RepoListUiState.Content },
        )

        assertEquals(listOf("alpha", "beta"), state.repositories.map { it.name })
        assertEquals(2, state.loadedCount)
        assertFalse(state.hasMore)
        assertFalse(state.isRefreshing)
        assertEquals(listOf(1), api.repoListRequests)
    }

    @Test
    fun `the Link header drives load more`() = runTest {
        // Each page must carry repository ids that are unique across pages: the ViewModel merges
        // pages with distinctBy(id), so reusing the same id would (correctly) drop the new page.
        api.onUserRepos = { page, _ ->
            Response.success(
                listOf(FakeGitHubApi.repositoryDto(repo = "page-$page").copy(id = page.toLong())),
                Headers.Builder()
                    .add("Link", """<https://api.github.com/user/repos?page=${page + 1}>; rel="next"""")
                    .build(),
            )
        }

        val viewModel = viewModel()
        viewModel.uiState.awaitState { it is RepoListUiState.Content }

        viewModel.loadMore()
        val state = assertInstanceOf<RepoListUiState.Content>(
            viewModel.uiState.awaitState { it is RepoListUiState.Content && it.loadedCount == 2 },
        )

        assertEquals(listOf(1, 2), api.repoListRequests)
        assertTrue(state.hasMore)
    }

    @Test
    fun `the search query filters by name and full name`() = runTest {
        api.onUserRepos = { _, _ ->
            Response.success(FakeGitHubApi.repositoryDtoList("alpha", "beta", "gamma"))
        }

        val viewModel = viewModel()
        viewModel.uiState.awaitState { it is RepoListUiState.Content }

        viewModel.onQueryChange("BET")
        val filtered = assertInstanceOf<RepoListUiState.Content>(viewModel.uiState.value)
        assertEquals(listOf("beta"), filtered.repositories.map { it.name })
        assertEquals(3, filtered.loadedCount)

        viewModel.onQueryChange("octocat/alp")
        val byFullName = assertInstanceOf<RepoListUiState.Content>(viewModel.uiState.value)
        assertEquals(listOf("alpha"), byFullName.repositories.map { it.name })

        viewModel.onQueryChange("")
        val cleared = assertInstanceOf<RepoListUiState.Content>(viewModel.uiState.value)
        assertEquals(3, cleared.repositories.size)
    }

    @Test
    fun `a failed first page is an error and retry recovers`() = runTest {
        var attempt = 0
        api.onUserRepos = { _, _ ->
            attempt++
            if (attempt == 1) {
                Contents.error(500, "Server Error")
            } else {
                Response.success(FakeGitHubApi.repositoryDtoList("alpha"))
            }
        }

        val viewModel = viewModel()
        val error = assertInstanceOf<RepoListUiState.Error>(
            viewModel.uiState.awaitState { it is RepoListUiState.Error },
        )
        assertEquals(ApiError.Http(statusCode = 500, apiMessage = "Server Error"), error.error)

        viewModel.retry()

        val content = assertInstanceOf<RepoListUiState.Content>(
            viewModel.uiState.awaitState { it is RepoListUiState.Content },
        )
        assertEquals(listOf("alpha"), content.repositories.map { it.name })
    }

    @Test
    fun `an empty account shows the empty state`() = runTest {
        api.onUserRepos = { _, _ -> Response.success(emptyList()) }

        val state = assertInstanceOf<RepoListUiState.Empty>(
            viewModel().uiState.awaitState { it is RepoListUiState.Empty },
        )

        assertEquals("", state.query)
    }

    @Test
    fun `401 emits AuthRequired`() = runTest {
        api.onUserRepos = { _, _ -> Contents.error(401, "Bad credentials") }

        val viewModel = viewModel()
        val events = mutableListOf<RepoListEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events += it }
        }

        val error = assertInstanceOf<RepoListUiState.Error>(
            viewModel.uiState.awaitState { it is RepoListUiState.Error },
        )

        assertEquals(ApiError.Unauthorized, error.error)
        runCurrent()
        assertEquals(listOf(RepoListEvent.AuthRequired), events)
    }
}
