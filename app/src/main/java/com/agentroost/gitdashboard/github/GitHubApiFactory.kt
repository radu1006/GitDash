package com.agentroost.gitdashboard.github

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Builds the OkHttp/Retrofit stack. The app owns exactly one client for normal traffic; token
 * verification builds a short-lived client bound to the candidate token.
 *
 * No logging interceptor is installed on purpose: an HTTP logger would print the Authorization
 * header. Tokens must never reach logcat.
 */
object GitHubApiFactory {

    const val BASE_URL = GitHubApi.BASE_URL

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    fun okHttpClient(tokenProvider: () -> String?): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenProvider))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    fun create(tokenProvider: () -> String?, baseUrl: String = BASE_URL): GitHubApi =
        createClient(okHttpClient(tokenProvider), baseUrl)

    fun create(client: OkHttpClient, baseUrl: String = BASE_URL): GitHubApi =
        createClient(client, baseUrl)

    private fun createClient(client: OkHttpClient, baseUrl: String): GitHubApi =
        retrofit(client, baseUrl).create(GitHubApi::class.java)

    fun retrofit(client: OkHttpClient, baseUrl: String = BASE_URL): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }
}
