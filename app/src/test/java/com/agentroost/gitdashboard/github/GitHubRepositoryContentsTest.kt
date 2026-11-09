package com.agentroost.gitdashboard.github

import com.agentroost.gitdashboard.domain.model.ContentKind
import com.agentroost.gitdashboard.domain.model.FileBody
import com.agentroost.gitdashboard.domain.model.PreviewUnavailableReason
import com.agentroost.gitdashboard.domain.model.RepoImage
import com.agentroost.gitdashboard.testing.Contents
import com.agentroost.gitdashboard.testing.FakeGitHubApi
import com.agentroost.gitdashboard.testing.MainDispatcherRule
import com.agentroost.gitdashboard.testing.assertInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Base64

/** End-to-end (fake Retrofit underneath) checks of the contents and file-preview endpoints. */
@OptIn(ExperimentalCoroutinesApi::class)
class GitHubRepositoryContentsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val api = FakeGitHubApi()
    private val repository = GitHubRepository(api)

    @Test
    fun `a listing is mapped to domain entries`() = runTest {
        api.onContents = {
            Contents.listing(
                """{"type":"dir","name":"src","path":"src","size":0,"sha":"abc"}""",
                """{"type":"file","name":"README.md","path":"README.md","size":42,"sha":"def",
                    "html_url":"https://github.com/octocat/hello-world/blob/main/README.md",
                    "download_url":"https://raw.githubusercontent.com/octocat/hello-world/main/README.md"}""",
                """{"type":"symlink","name":"link","path":"link","size":9,"target":"README.md"}""",
                """{"type":"submodule","name":"dep","path":"dep","submodule_git_url":"https://example.com/dep.git"}""",
                """{"type":"something-new","name":"odd","path":"odd"}""",
                """{"type":"file","path":"no-name.txt","size":1}""",
            )
        }

        val result = assertInstanceOf<ApiResult.Success<List<com.agentroost.gitdashboard.domain.model.ContentEntry>>>(
            repository.repositoryContents("octocat", "hello-world", ""),
        )

        val entries = result.data
        assertEquals(
            listOf(
                ContentKind.DIRECTORY,
                ContentKind.FILE,
                ContentKind.SYMLINK,
                ContentKind.SUBMODULE,
                ContentKind.UNKNOWN,
                ContentKind.FILE,
            ),
            entries.map { it.kind },
        )
        assertEquals(42L, entries[1].sizeBytes)
        assertEquals("README.md", entries[2].symlinkTarget)
        assertEquals("https://example.com/dep.git", entries[3].submoduleUrl)
        assertEquals("no-name.txt", entries[5].name)
        assertTrue(entries[0].isDirectory)
        assertTrue(entries[1].isViewableFile)
        assertEquals(false, entries[2].isViewableFile)
    }

    @Test
    fun `the path is percent-encoded per segment and the ref is forwarded`() = runTest {
        api.onContents = { Contents.listing(Contents.file("Main.kt")) }

        repository.repositoryContents(
            owner = "octocat",
            repo = "hello-world",
            path = "src dir/sub/My File.kt",
            ref = "feature/x",
        )

        val request = api.contentsRequests.single()
        assertEquals("octocat", request.owner)
        assertEquals("hello-world", request.repo)
        assertEquals("src%20dir/sub/My%20File.kt", request.path)
        assertEquals("feature/x", request.ref)
    }

    @Test
    fun `a file payload becomes a one-entry listing`() = runTest {
        api.onContents = { Contents.single(Contents.file("Main.kt", path = "src/Main.kt", text = "x")) }

        val result = assertInstanceOf<ApiResult.Success<List<com.agentroost.gitdashboard.domain.model.ContentEntry>>>(
            repository.repositoryContents("octocat", "hello-world", "src/Main.kt"),
        )

        assertEquals(listOf("src/Main.kt"), result.data.map { it.path })
        assertEquals(ContentKind.FILE, result.data.single().kind)
    }

    @Test
    fun `http errors become typed failures`() = runTest {
        val cases = mapOf(
            401 to ApiError.Unauthorized,
            404 to ApiError.NotFound,
        )

        for ((status, expected) in cases) {
            api.onContents = { Contents.failure(status) }
            val result = repository.repositoryContents("octocat", "hello-world", "")
            assertEquals("status=$status", expected, assertInstanceOf<ApiResult.Failure>(result).error)
        }

        api.onContents = { Contents.failure(500, "Server Error") }
        assertEquals(
            ApiError.Http(statusCode = 500, apiMessage = "Server Error"),
            assertInstanceOf<ApiResult.Failure>(
                repository.repositoryContents("octocat", "hello-world", ""),
            ).error,
        )
    }

    @Test
    fun `a file preview returns the decoded body and the html url`() = runTest {
        api.onContents = {
            Contents.single(
                Contents.file(
                    "Main.kt",
                    path = "src/Main.kt",
                    text = "val x = 1\n",
                    htmlUrl = "https://github.com/octocat/hello-world/blob/main/src/Main.kt",
                ),
            )
        }

        val preview = assertInstanceOf<ApiResult.Success<com.agentroost.gitdashboard.domain.model.FilePreview>>(
            repository.filePreview("octocat", "hello-world", "src/Main.kt"),
        ).data

        assertEquals(FileBody.Text("val x = 1\n"), preview.body)
        assertEquals("src/Main.kt", preview.entry.path)
        assertEquals("https://github.com/octocat/hello-world/blob/main/src/Main.kt", preview.githubHtmlUrl)
        assertEquals(null, preview.ref)
        assertTrue(preview.isPreviewAvailable)
    }

    @Test
    fun `a file preview without html_url falls back to the web blob url`() = runTest {
        api.onContents = {
            Contents.single(Contents.file("a b.txt", path = "docs/a b.txt", text = "hi", htmlUrl = null))
        }

        val preview = assertInstanceOf<ApiResult.Success<com.agentroost.gitdashboard.domain.model.FilePreview>>(
            repository.filePreview("octocat", "hello-world", "docs/a b.txt", ref = "dev"),
        ).data

        assertEquals(
            "https://github.com/octocat/hello-world/blob/dev/docs/a%20b.txt",
            preview.githubHtmlUrl,
        )
        assertEquals("dev", preview.ref)
    }

    @Test
    fun `a directory payload is a serialization failure for the viewer`() = runTest {
        api.onContents = { Contents.listing(Contents.dir("src")) }

        val failure = assertInstanceOf<ApiResult.Failure>(
            repository.filePreview("octocat", "hello-world", "src"),
        )

        assertTrue(failure.error is ApiError.Serialization)
    }

    @Test
    fun `image bytes round-trip a base64 png through the encoded contents path`() = runTest {
        // A real PNG signature plus a few bytes: binary enough that decodeFile would reject it.
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        )
        api.onContents = {
            Contents.single(
                Contents.fileOfBytes(
                    name = "shot one.png",
                    path = "docs/shot one.png",
                    bytes = png,
                ),
            )
        }

        val image = assertInstanceOf<ApiResult.Success<RepoImage>>(
            repository.imageBytes(
                owner = "octocat",
                repo = "hello-world",
                path = "docs/shot one.png",
                ref = "dev",
            ),
        ).data

        assertArrayEquals(png, assertInstanceOf<RepoImage.Bytes>(image).bytes)
        val request = api.contentsRequests.single()
        assertEquals("docs/shot%20one.png", request.path)
        assertEquals("dev", request.ref)
    }

    @Test
    fun `an image with invalid base64 is DECODE_FAILED`() = runTest {
        // "!!!!" decodes to nothing under the MIME decoder, which used to pass for TRUNCATED (or
        // even for an empty image at size 0) instead of reporting a decode failure.
        val sizes = listOf(4L, 0L)

        for (size in sizes) {
            api.onContents = {
                Contents.single(Contents.file("shot.png", base64 = "!!!!", size = size))
            }

            assertEquals(
                "size=$size",
                RepoImage.Unavailable(PreviewUnavailableReason.DECODE_FAILED),
                assertInstanceOf<ApiResult.Success<RepoImage>>(
                    repository.imageBytes("octocat", "hello-world", "shot.png"),
                ).data,
            )
        }
    }

    @Test
    fun `an image wrapped at 60 characters decodes`() = runTest {
        val bytes = ByteArray(120) { index -> index.toByte() }
        val wrapped = Base64.getMimeEncoder(60, "\r\n".toByteArray())
            .encodeToString(bytes)
        api.onContents = {
            Contents.single(Contents.fileOfBytes("wrapped.png", bytes = bytes, base64 = wrapped))
        }

        val image = assertInstanceOf<ApiResult.Success<RepoImage>>(
            repository.imageBytes("octocat", "hello-world", "wrapped.png"),
        ).data

        assertArrayEquals(bytes, assertInstanceOf<RepoImage.Bytes>(image).bytes)
    }

    @Test
    fun `a non-base64 image payload is NOT_BASE64`() = runTest {
        api.onContents = {
            Contents.single(
                Contents.fileOfBytes("lfs.png", bytes = ByteArray(4), encoding = "none"),
            )
        }

        assertEquals(
            RepoImage.Unavailable(PreviewUnavailableReason.NOT_BASE64),
            assertInstanceOf<ApiResult.Success<RepoImage>>(
                repository.imageBytes("octocat", "hello-world", "lfs.png"),
            ).data,
        )
    }

    @Test
    fun `an image whose decoded size does not match is TRUNCATED`() = runTest {
        api.onContents = {
            Contents.single(Contents.fileOfBytes("short.png", bytes = ByteArray(8), size = 100))
        }

        assertEquals(
            RepoImage.Unavailable(PreviewUnavailableReason.TRUNCATED),
            assertInstanceOf<ApiResult.Success<RepoImage>>(
                repository.imageBytes("octocat", "hello-world", "short.png"),
            ).data,
        )
    }

    @Test
    fun `an image above the inline limit is TOO_LARGE`() = runTest {
        api.onContents = {
            Contents.single(
                Contents.fileOfBytes(
                    "huge.png",
                    bytes = ByteArray(4),
                    size = GitHubContentDecoder.MAX_INLINE_BYTES + 1,
                ),
            )
        }

        assertEquals(
            RepoImage.Unavailable(PreviewUnavailableReason.TOO_LARGE),
            assertInstanceOf<ApiResult.Success<RepoImage>>(
                repository.imageBytes("octocat", "hello-world", "huge.png"),
            ).data,
        )
    }

    @Test
    fun `a directory payload is a serialization failure for the image loader`() = runTest {
        api.onContents = { Contents.listing(Contents.dir("docs")) }

        val failure = assertInstanceOf<ApiResult.Failure>(
            repository.imageBytes("octocat", "hello-world", "docs"),
        )

        assertTrue(failure.error is ApiError.Serialization)
    }

    @Test
    fun `encodeContentPath keeps separators and encodes each segment`() {
        assertEquals("", encodeContentPath(""))
        assertEquals("a/b", encodeContentPath("/a//b/"))
        assertEquals("a%20b/My%20File.kt", encodeContentPath("a b/My File.kt"))
        assertEquals("a%23b", encodeContentPath("a#b"))
        assertEquals("a%3Fb", encodeContentPath("a?b"))
    }

    @Test
    fun `web urls fall back to HEAD and drop the trailing separator at the root`() {
        assertEquals(
            "https://github.com/octocat/hello-world/blob/HEAD/src/Main.kt",
            GitHubWebUrls.blob("octocat", "hello-world", ref = "", path = "src/Main.kt"),
        )
        assertEquals(
            "https://github.com/octocat/hello-world/tree/main",
            GitHubWebUrls.tree("octocat", "hello-world", ref = "main", path = ""),
        )
        assertEquals(
            "https://github.com/octocat/hello-world/tree/main/src",
            GitHubWebUrls.tree("octocat", "hello-world", ref = "main", path = "src"),
        )
    }
}
