package com.agentroost.gitdashboard.github

import com.agentroost.gitdashboard.domain.model.ContentEntry
import com.agentroost.gitdashboard.domain.model.FilePreview
import com.agentroost.gitdashboard.domain.model.GitHubUser
import com.agentroost.gitdashboard.domain.model.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.ResponseBody

/**
 * The single entry point the ViewModels use for GitHub data.
 *
 * Every method is a suspend function that executes off the main thread (see [safeApiCall]) and
 * returns [ApiResult]; callers never deal with Retrofit responses or exceptions.
 *
 * Ticket 2 added `repositoryDetail`, `repositoryContents` and `filePreview` in place; they reuse the
 * same Retrofit client and [safeApiCall] as the ticket 1 endpoints.
 */
class GitHubRepository(
    private val api: GitHubApi,
    private val apiForToken: (String) -> GitHubApi = { token ->
        GitHubApiFactory.create(tokenProvider = { token })
    },
) {

    /** Validates the currently configured token (`GET /user`). */
    suspend fun currentUser(): ApiResult<GitHubUser> =
        safeApiCall { api.getCurrentUser() }.map { it.toDomain() }

    /**
     * Validates a candidate token before it is persisted. Uses a throw-away client bound to
     * [token], so an invalid token never replaces the token that is currently stored.
     */
    suspend fun verifyToken(token: String): ApiResult<GitHubUser> =
        safeApiCall { apiForToken(token).getCurrentUser() }.map { it.toDomain() }

    /**
     * `GET /user/repos` for the authenticated user, newest `updated` first.
     *
     * @param page 1-based page number.
     */
    suspend fun userRepositories(
        page: Int,
        perPage: Int = GitHubApi.DEFAULT_PER_PAGE,
    ): ApiResult<Paged<Repository>> {
        val response = safeApiCall {
            api.getUserRepos(
                perPage = perPage,
                page = page,
                sort = GitHubApi.SORT_UPDATED,
                affiliation = GitHubApi.AFFILIATION_OWNER_COLLABORATOR_ORG,
            )
        }

        return when (response) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> {
                val dtos = response.data
                val nextPage = LinkHeader.nextPage(response.meta.linkHeader, page)
                    ?: if (dtos.size >= perPage) page + 1 else null

                ApiResult.Success(
                    Paged(
                        items = dtos.map { it.toDomain() },
                        page = page,
                        nextPage = nextPage,
                        meta = response.meta,
                    ),
                    response.meta,
                )
            }
        }
    }

    /** `GET /repos/{owner}/{repo}` - header data for the repository detail screen. */
    suspend fun repositoryDetail(owner: String, repo: String): ApiResult<Repository> =
        safeApiCall { api.getRepository(owner, repo) }.map { it.toDomain() }

    /**
     * `GET /repos/{owner}/{repo}/contents/{path}`.
     *
     * A directory yields its entries; a path that points at a file yields a single entry (the
     * browser then shows it so the user can open it). The root of an empty repository answers 404,
     * which surfaces as [ApiError.NotFound] and is rendered as an empty state.
     *
     * @param path repository-relative path, `""` for the root, not URL-encoded.
     * @param ref branch/tag/commit; null uses the repository default branch.
     */
    suspend fun repositoryContents(
        owner: String,
        repo: String,
        path: String,
        ref: String? = null,
    ): ApiResult<List<ContentEntry>> {
        val encodedPath = encodeContentPath(path)
        return when (val response = safeApiCall { api.getContents(owner, repo, encodedPath, ref) }) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> decodeContents(response) { payload ->
                when (payload) {
                    is ContentsPayload.Listing -> payload.items.map { it.toDomain() }
                    is ContentsPayload.Single -> listOf(payload.item.toDomain())
                }
            }
        }
    }

    /**
     * Same endpoint as [repositoryContents], decoded for the code viewer: base64 content is
     * decoded off the main thread and large/binary/truncated payloads become typed reasons rather
     * than exceptions.
     */
    suspend fun filePreview(
        owner: String,
        repo: String,
        path: String,
        ref: String? = null,
    ): ApiResult<FilePreview> {
        val encodedPath = encodeContentPath(path)
        return when (val response = safeApiCall { api.getContents(owner, repo, encodedPath, ref) }) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> decodeContents(response) { payload ->
                when (payload) {
                    is ContentsPayload.Single -> {
                        val entry = payload.item.toDomain()
                        FilePreview(
                            entry = entry,
                            body = GitHubContentDecoder.decodeFile(payload.item),
                            githubHtmlUrl = entry.htmlUrl
                                ?: GitHubWebUrls.blob(owner, repo, ref.orEmpty(), entry.path.ifBlank { path }),
                            ref = ref,
                        )
                    }

                    // The viewer is only reached from a file row; a directory here means a stale
                    // link or a path that changed between listing and tap.
                    is ContentsPayload.Listing -> throw SerializationException("Expected a file at /$path")
                }
            }
        }
    }

    /**
     * Reads and decodes the raw contents body on [Dispatchers.IO] (reading the body is blocking),
     * preserving the response metadata of [response].
     */
    private suspend fun <T> decodeContents(
        response: ApiResult.Success<ResponseBody>,
        transform: (ContentsPayload) -> T,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            // string() also closes the body, so no connection is leaked even on a parse failure.
            val payload = GitHubContentDecoder.parse(GitHubApiFactory.json, response.data.string())
            ApiResult.Success(transform(payload), response.meta)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (serialization: SerializationException) {
            ApiResult.Failure(ApiError.Serialization(serialization.message))
        } catch (exception: Exception) {
            ApiResult.Failure(ApiError.Unknown(exception.message))
        }
    }
}
