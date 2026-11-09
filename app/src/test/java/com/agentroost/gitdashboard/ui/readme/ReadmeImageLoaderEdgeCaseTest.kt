package com.agentroost.gitdashboard.ui.readme

import com.agentroost.gitdashboard.github.GitHubRepository
import com.agentroost.gitdashboard.testing.Contents
import com.agentroost.gitdashboard.testing.FakeGitHubApi
import com.agentroost.gitdashboard.testing.assertInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64

/**
 * End-to-end image loading for the README viewer: resolve → fetch → decode → state, including the
 * failure paths that must render a placeholder instead of crashing.
 *
 * Robolectric is needed for a real `BitmapFactory`/`LruCache`; the network half uses a
 * `MockWebServer` for remote URLs and [FakeGitHubApi] for the authenticated contents API.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadmeImageLoaderEdgeCaseTest {

    private lateinit var server: MockWebServer
    private val api = FakeGitHubApi()
    private val repository = GitHubRepository(api)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun loader(): ReadmeImageLoader =
        ReadmeImageLoader(
            fetcher = ReadmeImageFetcher(repository, owner = "octocat", repo = "hello-world"),
            scope = scope,
        )

    private suspend fun awaitSettled(flow: StateFlow<ReadmeImageState>): ReadmeImageState =
        withTimeout(30_000) { flow.first { it !is ReadmeImageState.Loading } }

    private fun pngResponse(): MockResponse =
        MockResponse()
            .setBody(Buffer().write(PNG_BYTES))
            .setHeader("Content-Type", "image/png")

    @Test
    fun `a real png from an external host decodes to a bitmap without an Authorization header`() =
        runBlocking {
            server.enqueue(pngResponse())
            val source = ReadmeImageSource.Remote(server.url("/shot.png").toString())
            val loader = loader()

            loader.load(source)
            val state = awaitSettled(loader.state(source))

            val loaded = assertInstanceOf<ReadmeImageState.Loaded>(state)
            assertEquals(2, loaded.image.width)
            assertEquals(1, loaded.image.height)

            val recorded = server.takeRequest()
            assertNull(
                "the token must never reach an external image host",
                recorded.getHeader("Authorization"),
            )
            assertNull(recorded.getHeader("X-GitHub-Api-Version"))
            // Host-independent guarantee: the client used for remote images has no interceptor at
            // all, so no request built by it can carry the token.
            assertTrue(ReadmeImageFetcher.plainClient().interceptors.isEmpty())
            assertTrue(api.contentsRequests.isEmpty())
        }

    @Test
    fun `a repository image is fetched through the authenticated contents API and decodes`() =
        runBlocking {
            api.onContents = {
                Contents.single(
                    Contents.fileOfBytes(
                        name = "shot.png",
                        path = "docs/shot.png",
                        bytes = PNG_BYTES,
                    ),
                )
            }
            val source = ReadmeImageSource.RepoPath("docs/shot.png")
            val loader = loader()

            loader.load(source)
            val loaded = assertInstanceOf<ReadmeImageState.Loaded>(awaitSettled(loader.state(source)))

            assertEquals(2, loaded.image.width)
            assertEquals(1, loaded.image.height)
            assertEquals("docs/shot.png", api.contentsRequests.single().path)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `a traversing src is fetched as the clamped repository path`() = runBlocking {
        api.onContents = {
            Contents.single(
                Contents.fileOfBytes(name = "shot.png", path = "shot.png", bytes = PNG_BYTES),
            )
        }
        val source = ReadmeImageRefs.resolve("../../shot.png", readmePath = "README.md")
        assertEquals(ReadmeImageSource.RepoPath("shot.png"), source)
        val loader = loader()

        loader.load(source)
        assertInstanceOf<ReadmeImageState.Loaded>(awaitSettled(loader.state(source)))

        assertEquals("shot.png", api.contentsRequests.single().path)
    }

    @Test
    fun `invalid base64 renders the placeholder and is not refetched`() = runBlocking {
        api.onContents = {
            Contents.single(
                Contents.file("shot.png", base64 = "!!!! not base64 !!!!", size = 16),
            )
        }
        val source = ReadmeImageSource.RepoPath("shot.png")
        val loader = loader()

        loader.load(source)
        val failed = assertInstanceOf<ReadmeImageState.Failed>(awaitSettled(loader.state(source)))
        assertEquals(ReadmeImageFailure.NOT_AN_IMAGE, failed.reason)

        // Loading again (recomposition / list recycling) must not start a second request.
        loader.load(source)
        assertEquals(ReadmeImageState.Failed(ReadmeImageFailure.NOT_AN_IMAGE), loader.state(source).value)
        assertEquals(1, api.contentsRequests.size)
    }

    @Test
    fun `valid base64 that is not an image renders the placeholder`() = runBlocking {
        api.onContents = {
            Contents.single(
                Contents.file("shot.png", text = "this is not a png"),
            )
        }
        val source = ReadmeImageSource.RepoPath("shot.png")
        val loader = loader()

        loader.load(source)

        assertEquals(
            ReadmeImageFailure.NOT_AN_IMAGE,
            assertInstanceOf<ReadmeImageState.Failed>(awaitSettled(loader.state(source))).reason,
        )
    }

    @Test
    fun `a remote image above the size ceiling is TOO_LARGE`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(9 * 1024 * 1024))))
        val source = ReadmeImageSource.Remote(server.url("/huge.png").toString())
        val loader = loader()

        loader.load(source)

        assertEquals(
            ReadmeImageFailure.TOO_LARGE,
            assertInstanceOf<ReadmeImageState.Failed>(awaitSettled(loader.state(source))).reason,
        )
    }

    @Test
    fun `a failing remote image shows the placeholder and is not retried in a loop`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val source = ReadmeImageSource.Remote(server.url("/missing.png").toString())
        val loader = loader()

        loader.load(source)
        assertEquals(
            ReadmeImageFailure.NETWORK,
            assertInstanceOf<ReadmeImageState.Failed>(awaitSettled(loader.state(source))).reason,
        )

        repeat(3) { loader.load(source) }

        assertEquals(1, server.requestCount)
        assertEquals(ReadmeImageState.Failed(ReadmeImageFailure.NETWORK), loader.state(source).value)
    }

    @Test
    fun `an unsupported source renders the placeholder without any network call`() = runBlocking {
        for (src in listOf("", "   ", "data:image/png;base64,iVBORw0KGgo=")) {
            val source = ReadmeImageRefs.resolve(src, readmePath = "README.md")
            assertEquals(ReadmeImageSource.Unsupported, source)
            val loader = loader()

            loader.load(source)

            assertEquals(
                ReadmeImageFailure.NOT_SUPPORTED,
                assertInstanceOf<ReadmeImageState.Failed>(awaitSettled(loader.state(source))).reason,
            )
        }
        assertTrue(api.contentsRequests.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the repository payload bytes round-trip unchanged`() = runBlocking {
        api.onContents = {
            Contents.single(
                Contents.fileOfBytes(name = "shot.png", path = "shot.png", bytes = PNG_BYTES),
            )
        }
        val fetcher = ReadmeImageFetcher(repository, owner = "octocat", repo = "hello-world")

        val fetched = assertInstanceOf<ReadmeImageFetch.Bytes>(
            fetcher.fetch(ReadmeImageSource.RepoPath("shot.png")),
        )

        assertArrayEquals(PNG_BYTES, fetched.bytes)
        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            PNG_BYTES.copyOfRange(0, 8),
        )
    }

    private companion object {
        /** A valid 2x1 RGBA PNG; [Base64] checked in [the repository payload bytes round-trip unchanged]. */
        const val PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADklEQVR42mP4z8AAQv8BD/kD/Zh51wAAAAAASUVORK5CYII="

        val PNG_BYTES: ByteArray = Base64.getDecoder().decode(PNG_BASE64)
    }
}
