package com.agentroost.gitdashboard.ui.readme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resolution of markdown image sources against the README's repository path. */
class ReadmeImageRefsTest {

    private fun resolve(src: String, readmePath: String = "README.md"): ReadmeImageSource =
        ReadmeImageRefs.resolve(src, readmePath)

    @Test
    fun `absolute https urls are remote and untouched`() {
        assertEquals(
            ReadmeImageSource.Remote("https://example.com/shot.png"),
            resolve("https://example.com/shot.png"),
        )
    }

    @Test
    fun `absolute http urls are accepted as remote`() {
        assertEquals(
            ReadmeImageSource.Remote("http://example.com/shot.png"),
            resolve("http://example.com/shot.png"),
        )
    }

    @Test
    fun `a trailing raw query is stripped from remote urls`() {
        assertEquals(
            ReadmeImageSource.Remote("https://example.com/shot.png"),
            resolve("https://example.com/shot.png?raw=true"),
        )
        // Only the exact trailing marker is removed; the rest of the URL is untouched.
        assertEquals(
            ReadmeImageSource.Remote("https://example.com/shot.png?raw=true&size=large"),
            resolve("https://example.com/shot.png?raw=true&size=large"),
        )
    }

    @Test
    fun `protocol relative urls become https remotes`() {
        assertEquals(
            ReadmeImageSource.Remote("https://cdn.example.com/shot.png"),
            resolve("//cdn.example.com/shot.png"),
        )
    }

    @Test
    fun `a leading slash is repository root relative`() {
        assertEquals(
            ReadmeImageSource.RepoPath("assets/shot.png"),
            resolve("/assets/shot.png", readmePath = "docs/README.md"),
        )
    }

    @Test
    fun `a relative path resolves against the README directory`() {
        assertEquals(
            ReadmeImageSource.RepoPath("docs/images/shot.png"),
            resolve("images/shot.png", readmePath = "docs/README.md"),
        )
    }

    @Test
    fun `dot slash is normalised`() {
        assertEquals(
            ReadmeImageSource.RepoPath("docs/images/shot.png"),
            resolve("./images/shot.png", readmePath = "docs/README.md"),
        )
    }

    @Test
    fun `dot dot walks up one directory`() {
        assertEquals(
            ReadmeImageSource.RepoPath("assets/shot.png"),
            resolve("../assets/shot.png", readmePath = "docs/README.md"),
        )
    }

    @Test
    fun `dot dot never escapes the repository root`() {
        assertEquals(
            ReadmeImageSource.RepoPath("shot.png"),
            resolve("../../shot.png", readmePath = "docs/README.md"),
        )
    }

    @Test
    fun `percent escapes are decoded into the repository path`() {
        assertEquals(
            ReadmeImageSource.RepoPath("docs/my shot.png"),
            resolve("my%20shot.png", readmePath = "docs/README.md"),
        )
    }

    @Test
    fun `percent encoded dot segments are normalised`() {
        assertEquals(
            ReadmeImageSource.RepoPath("shot.png"),
            resolve("%2E%2E/shot.png", readmePath = "docs/README.md"),
        )
    }

    @Test
    fun `a raw query on a relative path is stripped`() {
        assertEquals(
            ReadmeImageSource.RepoPath("docs/shot.png"),
            resolve("shot.png?raw=true", readmePath = "docs/README.md"),
        )
    }

    @Test
    fun `data uris are unsupported`() {
        assertEquals(
            ReadmeImageSource.Unsupported,
            resolve("data:image/png;base64,iVBORw0KGgo="),
        )
    }

    @Test
    fun `other schemes and empty sources are unsupported`() {
        val sources = listOf(
            "mailto:someone@example.com",
            "ftp://example.com/shot.png",
            "javascript:alert(1)",
            "",
            "   ",
        )

        for (source in sources) {
            assertEquals("source=$source", ReadmeImageSource.Unsupported, resolve(source))
        }
    }

    @Test
    fun `the web url is a blob url for repo paths and the url for remotes`() {
        assertEquals(
            "https://github.com/octocat/hello-world/blob/main/docs/shot.png",
            ReadmeImageRefs.webUrl(
                ReadmeImageSource.RepoPath("docs/shot.png"),
                owner = "octocat",
                repo = "hello-world",
                ref = "main",
            ),
        )
        assertEquals(
            "https://example.com/shot.png",
            ReadmeImageRefs.webUrl(
                ReadmeImageSource.Remote("https://example.com/shot.png"),
                owner = "octocat",
                repo = "hello-world",
                ref = "main",
            ),
        )
        assertNull(
            ReadmeImageRefs.webUrl(
                ReadmeImageSource.Unsupported,
                owner = "octocat",
                repo = "hello-world",
                ref = "main",
            ),
        )
    }

    @Test
    fun `resolution never throws for arbitrary strings`() {
        val values = listOf(
            "",
            "%",
            "%zz",
            "%2",
            "::::",
            "//",
            "/",
            "../",
            "./",
            "\u0000",
            "https://",
            "data:",
            "a b/c d.png",
        )

        for (value in values) {
            val result = runCatching { ReadmeImageRefs.resolve(value, "docs/README.md") }
            assertTrue("resolve threw for $value: $result", result.isSuccess)
        }
    }
}
