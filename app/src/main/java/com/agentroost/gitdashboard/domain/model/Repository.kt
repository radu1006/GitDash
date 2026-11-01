package com.agentroost.gitdashboard.domain.model

/** Signed-in GitHub account, as returned by `GET /user`. */
data class GitHubUser(
    val id: Long,
    val login: String,
    val name: String?,
    val avatarUrl: String?,
    val htmlUrl: String?,
)

/**
 * UI/domain representation of a repository. Ticket 2 (repo detail + file browser) extends this
 * model in place rather than adding a second repository type.
 */
data class Repository(
    val id: Long,
    val name: String,
    val fullName: String,
    val owner: String,
    val description: String?,
    val language: String?,
    val stars: Int,
    val forks: Int,
    val watchers: Int,
    val openIssues: Int,
    val isPrivate: Boolean,
    val isFork: Boolean,
    val isArchived: Boolean,
    val defaultBranch: String,
    val htmlUrl: String?,
    val updatedAt: String?,
    /** GitHub `visibility`: `public`, `private` or `internal`; null when the API omitted it. */
    val visibility: String? = null,
) {
    /** Single letter shown in the avatar-less initials badge. */
    val initials: String
        get() = owner.firstOrNull()?.uppercase() ?: "?"

    /** `owner/name` split used by detail and contents routes in ticket 2. */
    val ownerLogin: String
        get() = fullName.substringBefore('/', missingDelimiterValue = owner)
}
