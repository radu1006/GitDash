package com.agentroost.gitdashboard.github

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Regression suite for token authentication: the token must be attached to every API call and must
 * never be sent when it is missing or blank.
 */
class AuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(tokenProvider: () -> String?): OkHttpClient =
        GitHubApiFactory.okHttpClient(tokenProvider)

    private fun call(client: OkHttpClient) {
        server.enqueue(MockResponse().setBody("{}").setHeader("Content-Type", "application/json"))
        client.newCall(Request.Builder().url(server.url("/user")).build()).execute().use { response ->
            assertEquals(200, response.code)
        }
    }

    @Test
    fun `the bearer token and GitHub headers are attached`() {
        call(client { "ghp_secret" })

        val recorded = server.takeRequest()
        assertEquals("Bearer ghp_secret", recorded.getHeader("Authorization"))
        assertEquals("application/vnd.github+json", recorded.getHeader("Accept"))
        assertEquals("2022-11-28", recorded.getHeader("X-GitHub-Api-Version"))
    }

    @Test
    fun `no Authorization header when signed out`() {
        call(client { null })

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
        assertEquals("application/vnd.github+json", recorded.getHeader("Accept"))
    }

    @Test
    fun `a blank token is never sent`() {
        call(client { "   " })

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        call(client { "  ghp_secret\n" })

        assertEquals("Bearer ghp_secret", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `the token is read on every request, not captured once`() {
        var token: String? = null
        val client = client { token }

        call(client)
        assertNull(server.takeRequest().getHeader("Authorization"))

        token = "ghp_later"
        call(client)
        assertEquals("Bearer ghp_later", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a repository call over the real stack carries the token`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("""{"id":1,"login":"octocat"}""")
                .setHeader("Content-Type", "application/json"),
        )
        val api = GitHubApiFactory.create(client { "ghp_secret" }, server.url("/").toString())
        val repository = GitHubRepository(api)

        val result = repository.currentUser()

        val user = (result as ApiResult.Success).data
        assertEquals("octocat", user.login)
        assertEquals("Bearer ghp_secret", server.takeRequest().getHeader("Authorization"))
    }
}
