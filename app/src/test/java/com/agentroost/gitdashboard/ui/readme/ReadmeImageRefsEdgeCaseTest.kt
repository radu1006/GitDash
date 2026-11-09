package com.agentroost.gitdashboard.ui.readme

import com.agentroost.gitdashboard.github.encodeContentPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security-relevant resolution rules for README image sources: an empty source, path traversal, and
 * the guarantee that a repository path never escapes the repository root.
 */
class ReadmeImageRefsEdgeCaseTest {

    private fun resolve(src: String, readmePath: String = "README.md"): ReadmeImageSource =
        ReadmeImageRefs.resolve(src, readmePath)

    @Test
    fun `an empty or blank source is unsupported and never fetched`() {
        for (src in listOf("", " ", "\t", "\n")) {
            assertEquals("src=[$src]", ReadmeImageSource.Unsupported, resolve(src))
        }
    }

    @Test
    fun `dot dot paths above the root are clamped to the repository`() {
        // Root README: every leading '..' must be dropped, not kept as a relative path.
        assertEquals(ReadmeImageSource.RepoPath("secret.png"), resolve("../../secret.png"))
        assertEquals(
            ReadmeImageSource.RepoPath("etc/passwd"),
            resolve("../../../../etc/passwd"),
        )
        assertEquals(
            ReadmeImageSource.RepoPath("assets/shot.png"),
            resolve("../../assets/shot.png", readmePath = "docs/guide/README.md"),
        )
    }

    @Test
    fun `a resolved path never starts with a dot segment`() {
        val srcs = listOf(
            "../shot.png",
            "../../shot.png",
            "./../shot.png",
            "a/../../shot.png",
            "%2e%2e/shot.png",
            "%2E%2E%2F%2E%2E%2Fshot.png",
        )

        for (src in srcs) {
            val source = resolve(src, readmePath = "docs/README.md")
            val path = (source as ReadmeImageSource.RepoPath).path
            assertTrue("src=$src resolved to $path", path.split('/').none { it == ".." || it == "." })
            assertTrue("src=$src resolved to $path", !path.startsWith("/"))
        }
    }

    @Test
    fun `percent encoded separators stay inside the repository path`() {
        assertEquals(
            ReadmeImageSource.RepoPath("docs/a/b.png"),
            resolve("a%2Fb.png", readmePath = "docs/README.md"),
        )
    }

    @Test
    fun `unicode and spaces survive percent decoding`() {
        assertEquals(
            ReadmeImageSource.RepoPath("docs/my shot ✓.png"),
            resolve("my%20shot%20%E2%9C%93.png", readmePath = "docs/README.md"),
        )
        // The contents API path must be re-encoded per segment, spaces as %20 and not '+'.
        assertEquals("docs/my%20shot%20%E2%9C%93.png", encodeContentPath("docs/my shot ✓.png"))
    }

    @Test
    fun `a fragment is dropped from a relative source`() {
        assertEquals(
            ReadmeImageSource.RepoPath("docs/shot.png"),
            resolve("shot.png#section", readmePath = "docs/README.md"),
        )
    }

    @Test
    fun `an encoded absolute path is repository root relative`() {
        assertEquals(
            ReadmeImageSource.RepoPath("assets/shot.png"),
            resolve("%2Fassets%2Fshot.png", readmePath = "docs/README.md"),
        )
    }

    @Test
    fun `an absolute url keeps its query except the raw marker`() {
        assertEquals(
            ReadmeImageSource.Remote("https://example.com/shot.png?v=2"),
            resolve("https://example.com/shot.png?v=2"),
        )
        assertEquals(
            ReadmeImageSource.Remote("https://example.com/shot.png"),
            resolve("https://example.com/shot.png?raw=true"),
        )
    }

    @Test
    fun `data uris with a payload are unsupported`() {
        val srcs = listOf(
            "data:image/png;base64,iVBORw0KGgo=",
            "DATA:image/svg+xml;utf8,<svg></svg>",
            "data:",
        )

        for (src in srcs) {
            assertEquals(src, ReadmeImageSource.Unsupported, resolve(src))
        }
    }
}
