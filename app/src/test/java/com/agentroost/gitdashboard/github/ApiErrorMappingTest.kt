package com.agentroost.gitdashboard.github

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

/** HTTP status -> [ApiError] mapping (drives every error message in the app). */
class ApiErrorMappingTest {

    private val json = GitHubApiFactory.json

    private fun response(
        code: Int,
        body: String? = """{"message":"boom"}""",
        headers: Map<String, String> = emptyMap(),
    ): Response<Unit> {
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://api.github.com/user").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("HTTP $code")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

        return Response.error(body.orEmpty().toResponseBody(JSON), raw)
    }

    @Test
    fun `401 is Unauthorized`() {
        assertEquals(ApiError.Unauthorized, response(401).toApiError(json))
    }

    @Test
    fun `404 is NotFound`() {
        assertEquals(ApiError.NotFound, response(404).toApiError(json))
    }

    @Test
    fun `500 keeps the status and the API message`() {
        assertEquals(
            ApiError.Http(statusCode = 500, apiMessage = "boom"),
            response(500).toApiError(json),
        )
    }

    @Test
    fun `a plain 403 is Forbidden with the API message`() {
        assertEquals(
            ApiError.Forbidden(apiMessage = "Resource not accessible by integration"),
            response(403, """{"message":"Resource not accessible by integration"}""").toApiError(json),
        )
    }

    @Test
    fun `403 with an exhausted rate limit is RateLimited`() {
        val error = response(
            403,
            """{"message":"API rate limit exceeded for user ID 1."}""",
            mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "1717000000"),
        ).toApiError(json)

        assertEquals(
            ApiError.RateLimited(
                resetAtEpochSeconds = 1_717_000_000L,
                apiMessage = "API rate limit exceeded for user ID 1.",
            ),
            error,
        )
    }

    @Test
    fun `403 whose message mentions the rate limit is RateLimited`() {
        val error = response(403, """{"message":"You have exceeded a secondary rate limit."}""")
            .toApiError(json)

        assertEquals(true, (error as ApiError.RateLimited).apiMessage?.contains("secondary rate limit"))
    }

    @Test
    fun `429 is RateLimited`() {
        val error = response(
            429,
            """{"message":"Too many requests"}""",
            mapOf("X-RateLimit-Reset" to "1717000123"),
        ).toApiError(json)

        assertEquals(
            ApiError.RateLimited(resetAtEpochSeconds = 1_717_000_123L, apiMessage = "Too many requests"),
            error,
        )
    }

    @Test
    fun `a non-json error body falls back to its prefix`() {
        val error = response(422, "<html>nginx error page</html>").toApiError(json)

        assertEquals(ApiError.Http(statusCode = 422, apiMessage = "<html>nginx error page</html>"), error)
    }

    @Test
    fun `an empty error body yields a null message`() {
        assertEquals(ApiError.Http(statusCode = 502, apiMessage = null), response(502, "").toApiError(json))
    }

    @Test
    fun `the Link header is parsed into a next page`() {
        assertEquals(
            3,
            LinkHeader.nextPage("""<https://api.github.com/user/repos?page=3>; rel="next"""", currentPage = 2),
        )
        assertEquals(
            null,
            LinkHeader.nextPage("""<https://api.github.com/user/repos?page=1>; rel="prev"""", currentPage = 2),
        )
        assertEquals(null, LinkHeader.nextPage(null))
        assertEquals(5, LinkHeader.nextPage("""<https://api.github.com/user/repos>; rel="next"""", currentPage = 4))
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
