package com.agentroost.gitdashboard.github

import com.agentroost.gitdashboard.github.model.RepositoryDto
import com.agentroost.gitdashboard.github.model.UserDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit definition of the GitHub REST endpoints used by the app.
 *
 * All calls return [Response] so headers (rate limit, Link pagination) and non-2xx statuses are
 * available to [safeApiCall]. Repository detail and contents were added in place for ticket 2.
 */
interface GitHubApi {

    @GET("user")
    suspend fun getCurrentUser(): Response<UserDto>

    @GET("user/repos")
    suspend fun getUserRepos(
        @Query("per_page") perPage: Int,
        @Query("page") page: Int,
        @Query("sort") sort: String,
        @Query("affiliation") affiliation: String,
    ): Response<List<RepositoryDto>>

    /** `GET /repos/{owner}/{repo}` - the same payload shape as a repository list item. */
    @GET("repos/{owner}/{repo}")
    suspend fun getRepository(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): Response<RepositoryDto>

    /**
     * `GET /repos/{owner}/{repo}/contents/{path}`.
     *
     * GitHub answers with a JSON object for a file and a JSON array for a directory, which a
     * single converter type cannot express. The body is therefore returned raw and decoded by
     * [GitHubContentDecoder] in [GitHubRepository]; it is still the one Retrofit stack.
     *
     * [path] must already be percent-encoded per segment ([encodeContentPath]); an empty string
     * addresses the repository root. [ref] is a branch, tag or commit; null means "default branch".
     */
    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getContents(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path(value = "path", encoded = true) path: String,
        @Query("ref") ref: String? = null,
    ): Response<ResponseBody>

    companion object {
        const val BASE_URL = "https://api.github.com/"
        const val DEFAULT_PER_PAGE = 30
        const val SORT_UPDATED = "updated"
        const val AFFILIATION_OWNER_COLLABORATOR_ORG = "owner,collaborator,organization_member"
    }
}
