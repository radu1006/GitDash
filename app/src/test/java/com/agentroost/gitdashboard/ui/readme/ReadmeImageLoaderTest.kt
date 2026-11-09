package com.agentroost.gitdashboard.ui.readme

import com.agentroost.gitdashboard.github.GitHubRepository
import com.agentroost.gitdashboard.testing.Contents
import com.agentroost.gitdashboard.testing.FakeGitHubApi
import com.agentroost.gitdashboard.testing.assertInstanceOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * The loader's URL-to-request decision, which is also the token-privacy boundary:
 * repository paths go through the authenticated contents API, absolute URLs through a plain client.
 */
class ReadmeImageLoaderTest {

    private lateinit var server: MockWebServer
    private val api = FakeGitHubApi()
    private val repository = GitHubRepository(api)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fetcher(): ReadmeImageFetcher =
        ReadmeImageFetcher(repository, owner = "octocat", repo = "hello-world")

    @Test
    fun `a repository-relative image goes through the authenticated repository`() = runBlocking {
        api.onContents = {
            Contents.single(
                Contents.fileOfBytes(
                    name = "shot.png",
                    path = "docs/shot.png",
                    bytes = PNG_BYTES,
                ),
            )
        }

        val result = fetcher().fetch(ReadmeImageSource.RepoPath("docs/shot.png"))

        assertInstanceOf<ReadmeImageFetch.Bytes>(result)
        val request = api.contentsRequests.single()
        assertEquals("octocat", request.owner)
        assertEquals("hello-world", request.repo)
        assertEquals("docs/shot.png", request.path)
    }

    @Test
    fun `an absolute image URL is requested without an Authorization header`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(PNG_BYTES))
                .setHeader("Content-Type", "image/png"),
        )

        val result = fetcher().fetch(
            ReadmeImageSource.Remote(server.url("/shot.png").toString()),
        )

        assertInstanceOf<ReadmeImageFetch.Bytes>(result)
        assertTrue("the API must not be used for remote images", api.contentsRequests.isEmpty())

        val recorded = server.takeRequest()
        assertNull("the token must never reach an external image host", recorded.getHeader("Authorization"))
        assertNull(recorded.getHeader("X-GitHub-Api-Version"))
        assertEquals("image/*", recorded.getHeader("Accept"))
    }

    @Test
    fun `an unsupported source is never fetched`() = runBlocking {
        val result = fetcher().fetch(ReadmeImageSource.Unsupported)

        assertEquals(
            ReadmeImageFailure.NOT_SUPPORTED,
            assertInstanceOf<ReadmeImageFetch.Failed>(result).reason,
        )
        assertTrue(api.contentsRequests.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a failing remote request becomes a typed failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = fetcher().fetch(
            ReadmeImageSource.Remote(server.url("/missing.png").toString()),
        )

        assertEquals(
            ReadmeImageFailure.NETWORK,
            assertInstanceOf<ReadmeImageFetch.Failed>(result).reason,
        )
    }

    @Test
    fun `a chunked remote body above the ceiling is TOO_LARGE`() = runBlocking {
        server.enqueue(
            MockResponse().setChunkedBody(Buffer().write(ByteArray(9 * 1024 * 1024)), 8 * 1024),
        )

        val result = fetcher().fetch(
            ReadmeImageSource.Remote(server.url("/chunked.png").toString()),
        )

        assertEquals(
            ReadmeImageFailure.TOO_LARGE,
            assertInstanceOf<ReadmeImageFetch.Failed>(result).reason,
        )
    }

    /**
     * A body without a `Content-Length` (what a chunked response reports) can only be bounded by
     * reading it in pieces and stopping at the ceiling. The body is generated lazily and every byte
     * the fetcher pulls is counted, so buffering the payload shows up as a 64 MB read.
     */
    @Test
    fun `a remote body of unknown length stops being read at the ceiling`() = runBlocking {
        val readBytes = AtomicLong()
        val unbounded = object : ResponseBody() {
            override fun contentType() = "image/png".toMediaType()

            override fun contentLength() = -1L

            override fun source(): BufferedSource = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val remaining = UNBOUNDED_BYTES - readBytes.get()
                    if (remaining <= 0L) return -1L
                    val count = minOf(byteCount, READ_CHUNK_BYTES, remaining)
                    sink.write(ByteArray(count.toInt()))
                    readBytes.addAndGet(count)
                    return count
                }

                override fun timeout(): Timeout = Timeout.NONE

                override fun close() = Unit
            }.buffer()
        }
        val client = OkHttpClient.Builder()
            .addInterceptor {
                Response.Builder()
                    .request(it.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(unbounded)
                    .build()
            }
            .build()

        val result = ReadmeImageFetcher(
            repository,
            owner = "octocat",
            repo = "hello-world",
            remoteClient = client,
        ).fetch(ReadmeImageSource.Remote("https://images.example.com/unbounded.png"))

        assertEquals(
            ReadmeImageFailure.TOO_LARGE,
            assertInstanceOf<ReadmeImageFetch.Failed>(result).reason,
        )
        assertTrue(
            "the fetcher read ${readBytes.get()} bytes; it must stop just past the 8 MB ceiling",
            readBytes.get() < 9L * 1024 * 1024,
        )
    }

    private companion object {
        /** The fetch path never decodes, so only the PNG signature is needed here. */
        val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /** How much the lazy body would serve if nothing stopped it. */
        const val UNBOUNDED_BYTES = 64L * 1024 * 1024

        /** Chunk size the lazy body hands out per read. */
        const val READ_CHUNK_BYTES = 8L * 1024
    }
}
