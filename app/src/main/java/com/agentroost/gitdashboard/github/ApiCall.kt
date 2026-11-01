package com.agentroost.gitdashboard.github

import com.agentroost.gitdashboard.github.model.ApiErrorDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException

/**
 * Runs [call] on [Dispatchers.IO] and converts every possible failure into a typed [ApiError].
 *
 * The GitHub API returns `Response<T>` (not `T`) so non-2xx responses and their headers stay
 * available for rate-limit handling.
 */
suspend fun <T> safeApiCall(
    json: Json = GitHubApiFactory.json,
    call: suspend () -> Response<T>,
): ApiResult<T> = withContext(Dispatchers.IO) {
    try {
        val response = call()
        val body = response.body()
        when {
            response.isSuccessful && body != null ->
                ApiResult.Success(body, ApiMeta.from(response.headers()))

            response.isSuccessful ->
                ApiResult.Failure(ApiError.Unknown("Successful response with an empty body"))

            else -> ApiResult.Failure(response.toApiError(json))
        }
    } catch (cancellation: CancellationException) {
        // Never swallow coroutine cancellation.
        throw cancellation
    } catch (io: IOException) {
        ApiResult.Failure(ApiError.Network(io.message))
    } catch (serialization: SerializationException) {
        ApiResult.Failure(ApiError.Serialization(serialization.message))
    } catch (exception: Exception) {
        ApiResult.Failure(ApiError.Unknown(exception.message))
    }
}

/** Maps a non-2xx response to a typed [ApiError], honouring GitHub's rate-limit headers. */
fun Response<*>.toApiError(json: Json = GitHubApiFactory.json): ApiError {
    val statusCode = code()
    val apiMessage = parseApiMessage(json)
    val remaining = headers()["X-RateLimit-Remaining"]?.toIntOrNull()
    val resetAt = headers()["X-RateLimit-Reset"]?.toLongOrNull()

    // Primary rate limits come back as 403 with X-RateLimit-Remaining: 0. Secondary limits can
    // also be a 403 (or 429) where only the message mentions the rate limit.
    val looksRateLimited =
        remaining == 0 || apiMessage?.contains("rate limit", ignoreCase = true) == true

    return when {
        statusCode == 401 -> ApiError.Unauthorized
        statusCode == 403 && looksRateLimited -> ApiError.RateLimited(resetAt, apiMessage)
        statusCode == 403 -> ApiError.Forbidden(apiMessage)
        statusCode == 429 -> ApiError.RateLimited(resetAt, apiMessage)
        statusCode == 404 -> ApiError.NotFound
        else -> ApiError.Http(statusCode, apiMessage)
    }
}

private fun Response<*>.parseApiMessage(json: Json): String? {
    val raw = try {
        errorBody()?.string()
    } catch (exception: IOException) {
        null
    } ?: return null

    if (raw.isBlank()) return null
    return try {
        json.decodeFromString<ApiErrorDto>(raw).message?.takeIf { it.isNotBlank() }
    } catch (exception: Exception) {
        // A non-JSON error body (proxy/firewall page); fall back to a short prefix.
        raw.take(200).takeIf { it.isNotBlank() }
    }
}
