package com.agentroost.gitdashboard.testing

import com.agentroost.gitdashboard.github.GitHubApi
import com.agentroost.gitdashboard.github.model.RepositoryDto
import com.agentroost.gitdashboard.github.model.UserDto
import okhttp3.ResponseBody
import retrofit2.Response

/** One recorded `GET .../contents/{path}` call. */
data class ContentsRequest(
    val owner: String,
    val repo: String,
    val path: String,
    val ref: String?,
)

/**
 * Hand-written [GitHubApi] stub.
 *
 * [com.agentroost.gitdashboard.github.GitHubRepository] is a concrete class, but it takes this
 * interface in its constructor, so no production refactor is needed to test the ViewModels: the fake
 * is plugged in underneath the real repository, the real `safeApiCall` and the real content decoder.
 */
class FakeGitHubApi : GitHubApi {

    val contentsRequests: MutableList<ContentsRequest> = mutableListOf()
    val repoListRequests: MutableList<Int> = mutableListOf()
    var currentUserRequests: Int = 0
        private set

    /** Contents responder; set it per test. Defaults to a failure so an unexpected call is loud. */
    var onContents: suspend (ContentsRequest) -> Response<ResponseBody> = {
        error("onContents was not configured for ${it.path}")
    }

    var onUserRepos: suspend (page: Int, perPage: Int) -> Response<List<RepositoryDto>> =
        { _, _ -> Response.success(emptyList()) }

    var onRepository: suspend (owner: String, repo: String) -> Response<RepositoryDto> =
        { owner, repo -> Response.success(repositoryDto(owner = owner, repo = repo)) }

    var onCurrentUser: suspend () -> Response<UserDto> = {
        Response.success(UserDto(id = 1, login = "octocat", name = "The Octocat"))
    }

    override suspend fun getCurrentUser(): Response<UserDto> {
        currentUserRequests++
        return onCurrentUser()
    }

    override suspend fun getUserRepos(
        perPage: Int,
        page: Int,
        sort: String,
        affiliation: String,
    ): Response<List<RepositoryDto>> {
        repoListRequests += page
        return onUserRepos(page, perPage)
    }

    override suspend fun getRepository(owner: String, repo: String): Response<RepositoryDto> =
        onRepository(owner, repo)

    override suspend fun getContents(
        owner: String,
        repo: String,
        path: String,
        ref: String?,
    ): Response<ResponseBody> {
        val request = ContentsRequest(owner = owner, repo = repo, path = path, ref = ref)
        contentsRequests += request
        return onContents(request)
    }

    companion object {
        fun repositoryDto(
            owner: String = "octocat",
            repo: String = "hello-world",
        ): RepositoryDto = RepositoryDto(
            id = 42,
            name = repo,
            fullName = "$owner/$repo",
            owner = com.agentroost.gitdashboard.github.model.OwnerDto(id = 7, login = owner),
            description = "A test repository",
            defaultBranch = "main",
            htmlUrl = "https://github.com/$owner/$repo",
            stargazersCount = 12,
            updatedAt = "2024-05-31T12:34:56Z",
        )

        fun repositoryDtoList(vararg names: String): List<RepositoryDto> =
            names.mapIndexed { index, name ->
                repositoryDto(owner = "octocat", repo = name).copy(id = index.toLong() + 1)
            }
    }
}
