package com.agentroost.gitdashboard.github

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Percent-encodes a repository path for use inside a URL path segment.
 *
 * `/` is kept as a separator (GitHub's contents endpoint expects real path separators) while every
 * other reserved character is encoded, so names with spaces, `#`, `?` or unicode work.
 */
fun encodeContentPath(path: String): String =
    path.split('/')
        .filter { it.isNotEmpty() }
        .joinToString("/") { segment -> encodeSegment(segment) }

internal fun encodeSegment(segment: String): String =
    URLEncoder.encode(segment, StandardCharsets.UTF_8)
        // URLEncoder is form encoding: spaces become '+', which is wrong in a URL path.
        .replace("+", "%20")

/** Web (github.com) URLs used by the "Open on GitHub" actions. */
object GitHubWebUrls {

    private const val WEB_BASE = "https://github.com"

    /** `https://github.com/{owner}/{repo}/blob/{ref}/{path}` - falls back to `HEAD`. */
    fun blob(owner: String, repo: String, ref: String, path: String): String {
        val encodedRef = encodeContentPath(ref).ifBlank { "HEAD" }
        return "$WEB_BASE/$owner/$repo/blob/$encodedRef/${encodeContentPath(path)}"
    }

    /** `https://github.com/{owner}/{repo}/tree/{ref}/{path}`. */
    fun tree(owner: String, repo: String, ref: String, path: String): String {
        val encodedRef = encodeContentPath(ref).ifBlank { "HEAD" }
        val suffix = encodeContentPath(path)
        return if (suffix.isBlank()) {
            "$WEB_BASE/$owner/$repo/tree/$encodedRef"
        } else {
            "$WEB_BASE/$owner/$repo/tree/$encodedRef/$suffix"
        }
    }
}
