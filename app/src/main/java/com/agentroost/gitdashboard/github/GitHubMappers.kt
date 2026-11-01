package com.agentroost.gitdashboard.github

import com.agentroost.gitdashboard.domain.model.ContentEntry
import com.agentroost.gitdashboard.domain.model.ContentKind
import com.agentroost.gitdashboard.domain.model.GitHubUser
import com.agentroost.gitdashboard.domain.model.Repository
import com.agentroost.gitdashboard.github.model.ContentDto
import com.agentroost.gitdashboard.github.model.RepositoryDto
import com.agentroost.gitdashboard.github.model.UserDto

/** Wire models -> domain models. Kept in one place so ticket 2 can extend the mapping. */
fun UserDto.toDomain(): GitHubUser = GitHubUser(
    id = id,
    login = login,
    name = name,
    avatarUrl = avatarUrl,
    htmlUrl = htmlUrl,
)

fun RepositoryDto.toDomain(): Repository = Repository(
    id = id,
    name = name,
    fullName = fullName.ifBlank { name },
    owner = owner?.login?.takeIf { it.isNotBlank() }
        ?: fullName.substringBefore('/', missingDelimiterValue = ""),
    description = description,
    language = language,
    stars = stargazersCount,
    forks = forksCount,
    // `watchers_count` is a legacy alias of the star count; `subscribers_count` is the real
    // watcher number, so prefer it when GitHub sends it.
    watchers = subscribersCount ?: watchersCount,
    openIssues = openIssuesCount,
    isPrivate = isPrivate,
    isFork = fork,
    isArchived = archived,
    defaultBranch = defaultBranch?.takeIf { it.isNotBlank() } ?: DEFAULT_BRANCH,
    htmlUrl = htmlUrl,
    updatedAt = updatedAt,
    visibility = visibility?.takeIf { it.isNotBlank() },
)

fun ContentDto.toDomain(): ContentEntry = ContentEntry(
    name = name.ifBlank { path.substringAfterLast('/') },
    path = path,
    kind = when (type?.lowercase()) {
        "file" -> ContentKind.FILE
        "dir" -> ContentKind.DIRECTORY
        "symlink" -> ContentKind.SYMLINK
        "submodule" -> ContentKind.SUBMODULE
        else -> ContentKind.UNKNOWN
    },
    sizeBytes = size.coerceAtLeast(0),
    sha = sha,
    htmlUrl = htmlUrl,
    downloadUrl = downloadUrl,
    submoduleUrl = submoduleGitUrl,
    symlinkTarget = target,
)

private const val DEFAULT_BRANCH = "main"
