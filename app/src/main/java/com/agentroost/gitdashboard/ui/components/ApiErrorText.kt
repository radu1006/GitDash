package com.agentroost.gitdashboard.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.agentroost.gitdashboard.R
import com.agentroost.gitdashboard.github.ApiError
import com.agentroost.gitdashboard.util.formatClockTime

/** Maps a typed [ApiError] to a user-facing message. No exception text reaches the UI. */
@Composable
fun apiErrorMessage(error: ApiError): String = when (error) {
    ApiError.Unauthorized -> stringResource(R.string.error_unauthorized)

    is ApiError.RateLimited -> {
        val resetAt = error.resetAtEpochSeconds
        if (resetAt != null) {
            stringResource(R.string.error_rate_limited_until, formatClockTime(resetAt))
        } else {
            stringResource(R.string.error_rate_limited)
        }
    }

    is ApiError.Forbidden -> error.apiMessage?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.error_forbidden)

    ApiError.NotFound -> stringResource(R.string.error_not_found)

    is ApiError.Http -> stringResource(R.string.error_http, error.statusCode)

    is ApiError.Network -> stringResource(R.string.error_network)

    is ApiError.Serialization -> stringResource(R.string.error_unexpected_response)

    is ApiError.Unknown -> stringResource(R.string.error_unknown)
}
