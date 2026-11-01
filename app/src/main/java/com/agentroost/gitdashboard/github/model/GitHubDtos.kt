package com.agentroost.gitdashboard.github.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /user` payload (only the fields this app renders). */
@Serializable
data class UserDto(
    val id: Long = 0,
    val login: String = "",
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val type: String? = null,
    @SerialName("public_repos") val publicRepos: Int? = null,
)

/** Owner object embedded in repository payloads. */
@Serializable
data class OwnerDto(
    val id: Long = 0,
    val login: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val type: String? = null,
)

/**
 * `GET /user/repos` item. Fields required by the repository list are decoded; everything else is
 * ignored so GitHub can add fields without breaking the client.
 */
@Serializable
data class RepositoryDto(
    val id: Long = 0,
    val name: String = "",
    @SerialName("full_name") val fullName: String = "",
    val owner: OwnerDto? = null,
    @SerialName("private") val isPrivate: Boolean = false,
    val fork: Boolean = false,
    val archived: Boolean = false,
    val description: String? = null,
    val language: String? = null,
    @SerialName("stargazers_count") val stargazersCount: Int = 0,
    @SerialName("forks_count") val forksCount: Int = 0,
    @SerialName("watchers_count") val watchersCount: Int = 0,
    @SerialName("subscribers_count") val subscribersCount: Int? = null,
    @SerialName("open_issues_count") val openIssuesCount: Int = 0,
    @SerialName("default_branch") val defaultBranch: String? = null,
    val visibility: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("pushed_at") val pushedAt: String? = null,
)

/**
 * One element of `GET /repos/{owner}/{repo}/contents/{path}`.
 *
 * The endpoint returns a JSON object for a file and a JSON array for a directory, so the raw body
 * is decoded into this type by [com.agentroost.gitdashboard.github.GitHubContentDecoder] instead of
 * being bound by the Retrofit converter.
 */
@Serializable
data class ContentDto(
    val type: String? = null,
    val name: String = "",
    val path: String = "",
    val sha: String? = null,
    val size: Long = 0,
    val encoding: String? = null,
    val content: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("git_url") val gitUrl: String? = null,
    @SerialName("submodule_git_url") val submoduleGitUrl: String? = null,
    val target: String? = null,
    val url: String? = null,
)

/** GitHub error payload, e.g. `{"message":"API rate limit exceeded", ...}`. */
@Serializable
data class ApiErrorDto(
    val message: String? = null,
    @SerialName("documentation_url") val documentationUrl: String? = null,
)
