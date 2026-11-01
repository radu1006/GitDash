package com.agentroost.gitdashboard.github

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds the GitHub API headers to every request and the bearer token when one is configured.
 *
 * The token is read through [tokenProvider] at request time so the app never has to rebuild the
 * Retrofit/OkHttp stack when the user replaces the token. Nothing in this class logs or persists
 * the token.
 */
class AuthInterceptor(
    private val tokenProvider: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()?.trim().orEmpty()

        val request = chain.request().newBuilder()
            .header(HEADER_ACCEPT, ACCEPT_VALUE)
            .header(HEADER_API_VERSION, API_VERSION)
            .apply {
                if (token.isNotEmpty()) {
                    header(HEADER_AUTHORIZATION, "Bearer $token")
                }
            }
            .build()

        return chain.proceed(request)
    }

    companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
        const val HEADER_ACCEPT = "Accept"
        const val HEADER_API_VERSION = "X-GitHub-Api-Version"

        const val ACCEPT_VALUE = "application/vnd.github+json"
        const val API_VERSION = "2022-11-28"
    }
}
