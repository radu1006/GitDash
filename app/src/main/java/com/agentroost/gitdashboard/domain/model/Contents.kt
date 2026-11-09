package com.agentroost.gitdashboard.domain.model

/**
 * Kind of an entry returned by `GET /repos/{owner}/{repo}/contents/{path}`.
 *
 * GitHub reports symlinks and submodules as their own types; the browser must never try to list
 * them as folders, so they are kept distinct instead of being collapsed into "file".
 */
enum class ContentKind {
    FILE,
    DIRECTORY,
    SYMLINK,
    SUBMODULE,
    UNKNOWN,
}

/** One entry of a repository folder (or a single file when the path points at a file). */
data class ContentEntry(
    val name: String,
    val path: String,
    val kind: ContentKind,
    val sizeBytes: Long = 0,
    val sha: String? = null,
    val htmlUrl: String? = null,
    val downloadUrl: String? = null,
    /** Target repository of a submodule (`submodule_git_url`). */
    val submoduleUrl: String? = null,
    /** Target of a symlink (`target`); null for every other kind. */
    val symlinkTarget: String? = null,
) {
    val isDirectory: Boolean get() = kind == ContentKind.DIRECTORY

    /** Only plain files can be opened in the code viewer. */
    val isViewableFile: Boolean get() = kind == ContentKind.FILE
}

/** A directory listing; [path] is the requested folder (`""` for the repository root). */
data class DirectoryContents(
    val path: String,
    val entries: List<ContentEntry>,
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

/** Why a file cannot be shown in the code viewer. */
enum class PreviewUnavailableReason {
    /** Larger than the 1 MB GitHub returns inline. */
    TOO_LARGE,

    /** Larger than the app is willing to lay out; showing it would freeze the UI. */
    TOO_LARGE_TO_RENDER,

    /** `encoding != "base64"` (Git LFS pointer, huge blob, ...). */
    NOT_BASE64,

    /** Decoded bytes are binary (NUL bytes / control characters). */
    BINARY,

    /** The payload was shorter than the size GitHub reported. */
    TRUNCATED,

    /** The base64 payload could not be decoded. */
    DECODE_FAILED,

    /** The path is a directory, symlink or submodule, not a file. */
    NOT_A_FILE,
}

/** Decoded file payload: either text ready to render, or the reason it cannot be previewed. */
sealed interface FileBody {
    data class Text(val text: String) : FileBody
    data class Unavailable(val reason: PreviewUnavailableReason) : FileBody
}

/**
 * Raw bytes of an image loaded through the authenticated contents endpoint.
 *
 * Unlike [FileBody] there is no binary/UTF-8 check: bytes are exactly what GitHub returned, so the
 * UI can hand them to a decoder.
 */
sealed interface RepoImage {
    data class Bytes(val bytes: ByteArray) : RepoImage {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class Unavailable(val reason: PreviewUnavailableReason) : RepoImage
}

/**
 * A single file from the contents API, with its content already decoded off the main thread.
 * [githubHtmlUrl] is always the web (blob) URL used by the "Open on GitHub" action.
 */
data class FilePreview(
    val entry: ContentEntry,
    val body: FileBody,
    val githubHtmlUrl: String?,
    val ref: String?,
) {
    val isPreviewAvailable: Boolean get() = body is FileBody.Text
}
