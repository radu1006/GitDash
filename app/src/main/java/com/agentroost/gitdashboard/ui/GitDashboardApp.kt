package com.agentroost.gitdashboard.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.agentroost.gitdashboard.di.AppContainer
import com.agentroost.gitdashboard.ui.components.LoadingPane
import com.agentroost.gitdashboard.ui.repo.RepoDetailScreen
import com.agentroost.gitdashboard.ui.repos.RepoListScreen
import com.agentroost.gitdashboard.ui.token.TokenScreen
import android.net.Uri

/** Route names for the authenticated graph. Ticket 2 adds detail/browser routes here. */
object Routes {
    const val REPOS = "repos"
    const val SETTINGS = "settings"
    const val REPO_DETAIL = "repos/{owner}/{repo}"

    fun repoDetail(owner: String, repo: String): String =
        "repos/${Uri.encode(owner)}/${Uri.encode(repo)}"
}

@Composable
fun GitDashboardApp(container: AppContainer) {
    val appViewModel: AppViewModel = viewModel(factory = AppViewModel.factory(container))
    val state by appViewModel.uiState.collectAsStateWithLifecycle()

    when {
        state.isLoading -> LoadingPane(modifier = Modifier.fillMaxSize())

        !state.hasToken -> TokenScreen(
            container = container,
            onAuthenticated = appViewModel::onTokenSaved,
            onBack = null,
        )

        else -> key(state.sessionKey) {
            AuthenticatedNavHost(
                container = container,
                onTokenSaved = appViewModel::onTokenSaved,
                onSessionExpired = appViewModel::onSessionExpired,
            )
        }
    }
}

@Composable
private fun AuthenticatedNavHost(
    container: AppContainer,
    onTokenSaved: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.REPOS) {
        composable(Routes.REPOS) {
            RepoListScreen(
                container = container,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenRepo = { repository ->
                    navController.navigate(Routes.repoDetail(repository.owner, repository.name))
                },
                onAuthRequired = onSessionExpired,
            )
        }

        composable(
            route = Routes.REPO_DETAIL,
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType },
                navArgument("repo") { type = NavType.StringType },
            ),
        ) { entry ->
            RepoDetailScreen(
                container = container,
                owner = entry.arguments?.getString("owner").orEmpty(),
                repo = entry.arguments?.getString("repo").orEmpty(),
                onBack = { navController.popBackStack() },
                // QA note: the file browser / code viewer screens are missing from the workspace,
                // so this callback has no destination yet (see QA_REPORT.md, defect D-1).
                onBrowse = {},
                onAuthRequired = onSessionExpired,
            )
        }

        composable(Routes.SETTINGS) {
            TokenScreen(
                container = container,
                onAuthenticated = onTokenSaved,
                onSignedOut = onSessionExpired,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
