package com.agentroost.gitdashboard.github

import okhttp3.Headers

/**
 * Result of a single GitHub API call. Every network entry point in the `github` package returns
 * this type, so no [java.io.IOException], HTTP status code or serialization failure can ever reach
 * the UI layer as an exception.
 */
sealed interface ApiResult<out T> {

    data class Success<T>(
        val data: T,
        val meta: ApiMeta = ApiMeta(),
    ) : ApiResult<T>

    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

/** Response headers the app cares about, decoded once per call. */
data class ApiMeta(
    val linkHeader: String? = null,
    val rateLimitLimit: Int? = null,
    val rateLimitRemaining: Int? = null,
    val rateLimitResetEpochSeconds: Long? = null,
) {
    companion object {
        fun from(headers: Headers): ApiMeta = ApiMeta(
            linkHeader = headers["Link"],
            rateLimitLimit = headers["X-RateLimit-Limit"]?.toIntOrNull(),
            rateLimitRemaining = headers["X-RateLimit-Remaining"]?.toIntOrNull(),
            rateLimitResetEpochSeconds = headers["X-RateLimit-Reset"]?.toLongOrNull(),
        )
    }
}

/** Typed failures, mapped from HTTP status codes and transport exceptions. */
sealed interface ApiError {

    /** HTTP 401 - token missing, expired or revoked. The UI should route back to the token screen. */
    data object Unauthorized : ApiError

    /**
     * HTTP 403 (with an exhausted rate limit) or HTTP 429.
     * [resetAtEpochSeconds] comes from `X-RateLimit-Reset` when GitHub sent it.
     */
    data class RateLimited(
        val resetAtEpochSeconds: Long? = null,
        val apiMessage: String? = null,
    ) : ApiError

    /** HTTP 403 that is not a rate limit (missing scope / forbidden resource). */
    data class Forbidden(val apiMessage: String? = null) : ApiError

    /** HTTP 404 - repo, branch or path does not exist (or is not visible to the token). */
    data object NotFound : ApiError

    /** Any other non-2xx HTTP status. */
    data class Http(val statusCode: Int, val apiMessage: String? = null) : ApiError

    /** Transport failure (DNS, TLS, timeout, connection reset). */
    data class Network(val detail: String? = null) : ApiError

    /** The payload could not be decoded into the expected [kotlinx.serialization.Serializable] type. */
    data class Serialization(val detail: String? = null) : ApiError

    /** Anything unexpected. [detail] is for diagnostics/tests, not for display. */
    data class Unknown(val detail: String? = null) : ApiError
}

/** Maps the payload of a [ApiResult.Success] while preserving its [ApiMeta]. */
inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data), meta)
    is ApiResult.Failure -> this
}

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Success)?.data
