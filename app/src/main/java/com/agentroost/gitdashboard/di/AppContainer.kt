package com.agentroost.gitdashboard.di

import android.content.Context
import com.agentroost.gitdashboard.data.auth.EncryptedTokenRepository
import com.agentroost.gitdashboard.data.auth.TokenRepository
import com.agentroost.gitdashboard.github.GitHubApi
import com.agentroost.gitdashboard.github.GitHubApiFactory
import com.agentroost.gitdashboard.github.GitHubRepository

/**
 * Manual dependency container (no Hilt/Koin).
 *
 * One OkHttp client for the whole process. The token is read per request via the interceptor, so
 * replacing the token does not require rebuilding the network stack.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val tokenRepository: TokenRepository = EncryptedTokenRepository(appContext)

    private val httpClient = GitHubApiFactory.okHttpClient(
        tokenProvider = { tokenRepository.currentToken() },
    )

    val gitHubApi: GitHubApi = GitHubApiFactory.create(httpClient)

    val gitHubRepository: GitHubRepository = GitHubRepository(gitHubApi)
}
