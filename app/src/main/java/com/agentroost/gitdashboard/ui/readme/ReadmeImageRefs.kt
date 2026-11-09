package com.agentroost.gitdashboard.ui.readme

import com.agentroost.gitdashboard.github.GitHubWebUrls
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Where an image referenced by a README actually lives.
 *
 * [RepoPath] is a repository-relative path (no leading slash) and must be fetched through the
 * authenticated contents endpoint so private-repository images work. [Remote] is an absolute
 * `http(s)` URL and must be fetched **without** an Authorization header: the app promises to send
 * its token only to `https://api.github.com`. [Unsupported] covers `data:` URIs and anything the
 * viewer cannot load.
 */
sealed interface ReadmeImageSource {

    data class RepoPath(val path: String) : ReadmeImageSource

    data class Remote(val url: String) : ReadmeImageSource

    data object Unsupported : ReadmeImageSource
}

/**
 * Resolves markdown image `src` values relative to the README that contains them.
 *
 * Rules:
 * - `http(s)://...` is [ReadmeImageSource.Remote] unchanged except for a trailing `?raw=true`.
 * - `//host/path` is treated as `https://host/path`.
 * - `/path` is repository-root-relative.
 * - `./` and `../` are normalised against the README's directory; `..` never escapes the root.
 * - Percent escapes such as `%20` are decoded into the repository path.
 * - `data:` URIs, other schemes and empty sources are [ReadmeImageSource.Unsupported].
 */
object ReadmeImageRefs {

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /** Resolves [src]; [readmePath] is the repository path of the README itself (e.g. `docs/README.md`). */
    fun resolve(src: String, readmePath: String): ReadmeImageSource {
        val raw = src.trim()
        if (raw.isEmpty()) return ReadmeImageSource.Unsupported

        if (raw.startsWith("data:", ignoreCase = true)) return ReadmeImageSource.Unsupported

        if (raw.startsWith("//")) {
            return ReadmeImageSource.Remote(stripRawSuffix("https:$raw"))
        }

        val scheme = SCHEME.find(raw)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        if (scheme != null) {
            return if (scheme == "http" || scheme == "https") {
                ReadmeImageSource.Remote(stripRawSuffix(raw))
            } else {
                ReadmeImageSource.Unsupported
            }
        }

        val withoutFragment = raw.substringBefore('#')
        val decoded = percentDecode(stripRawSuffix(withoutFragment))
        if (decoded.isBlank()) return ReadmeImageSource.Unsupported

        val path = if (decoded.startsWith("/")) {
            normalize(decoded.trimStart('/'))
        } else {
            val baseDirectory = readmePath.substringBeforeLast('/', missingDelimiterValue = "")
            normalize(if (baseDirectory.isBlank()) decoded else "$baseDirectory/$decoded")
        }
        return if (path.isBlank()) ReadmeImageSource.Unsupported else ReadmeImageSource.RepoPath(path)
    }

    /**
     * The web URL for "Open on GitHub": a blob URL for a repository path, or the remote URL as-is.
     * Returns null for [ReadmeImageSource.Unsupported].
     */
    fun webUrl(
        source: ReadmeImageSource,
        owner: String,
        repo: String,
        ref: String?,
    ): String? = when (source) {
        is ReadmeImageSource.RepoPath -> GitHubWebUrls.blob(owner, repo, ref.orEmpty(), source.path)
        is ReadmeImageSource.Remote -> source.url
        ReadmeImageSource.Unsupported -> null
    }

    private fun stripRawSuffix(url: String): String {
        val suffix = "?raw=true"
        return if (url.endsWith(suffix, ignoreCase = true)) url.dropLast(suffix.length) else url
    }

    private fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        return try {
            // URLDecoder is form decoding: protect a literal '+' before decoding the rest.
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        } catch (exception: IllegalArgumentException) {
            value
        }
    }

    private fun normalize(path: String): String {
        val segments = mutableListOf<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return segments.joinToString("/")
    }
}
