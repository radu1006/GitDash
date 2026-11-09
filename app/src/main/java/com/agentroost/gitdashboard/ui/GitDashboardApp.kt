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
import com.agentroost.gitdashboard.ui.browser.BrowseScreen
import com.agentroost.gitdashboard.ui.code.CodeViewerScreen
import com.agentroost.gitdashboard.ui.components.LoadingPane
import com.agentroost.gitdashboard.ui.readme.ReadmeScreen
import com.agentroost.gitdashboard.ui.repo.RepoDetailScreen
import com.agentroost.gitdashboard.ui.repos.RepoListScreen
import com.agentroost.gitdashboard.ui.token.TokenScreen
import android.net.Uri

/** Route names for the authenticated graph. */
object Routes {
    const val REPOS = "repos"
    const val SETTINGS = "settings"
    const val REPO_DETAIL = "repos/{owner}/{repo}"
    const val BROWSE = "repos/{owner}/{repo}/browse?path={path}"
    const val CODE = "repos/{owner}/{repo}/code?path={path}"
    const val README = "repos/{owner}/{repo}/readme"

    fun repoDetail(owner: String, repo: String): String =
        "repos/${Uri.encode(owner)}/${Uri.encode(repo)}"

    /** [path] is repository-relative and `""` for the root. */
    fun browse(owner: String, repo: String, path: String = ""): String =
        "repos/${Uri.encode(owner)}/${Uri.encode(repo)}/browse?path=${Uri.encode(path)}"

    fun code(owner: String, repo: String, path: String): String =
        "repos/${Uri.encode(owner)}/${Uri.encode(repo)}/code?path=${Uri.encode(path)}"

    fun readme(owner: String, repo: String): String =
        "repos/${Uri.encode(owner)}/${Uri.encode(repo)}/readme"
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
            val owner = entry.arguments?.getString("owner").orEmpty()
            val repo = entry.arguments?.getString("repo").orEmpty()

            RepoDetailScreen(
                container = container,
                owner = owner,
                repo = repo,
                onBack = { navController.popBackStack() },
                onBrowse = { repository ->
                    navController.navigate(Routes.browse(repository.owner, repository.name))
                },
                onViewReadme = { navController.navigate(Routes.readme(owner, repo)) },
                onAuthRequired = onSessionExpired,
            )
        }

        composable(
            route = Routes.BROWSE,
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType },
                navArgument("repo") { type = NavType.StringType },
                navArgument("path") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val owner = entry.arguments?.getString("owner").orEmpty()
            val repo = entry.arguments?.getString("repo").orEmpty()

            BrowseScreen(
                container = container,
                owner = owner,
                repo = repo,
                initialPath = entry.arguments?.getString("path").orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenFile = { path -> navController.navigate(Routes.code(owner, repo, path)) },
                onOpenReadme = { navController.navigate(Routes.readme(owner, repo)) },
                onAuthRequired = onSessionExpired,
            )
        }

        composable(
            route = Routes.README,
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType },
                navArgument("repo") { type = NavType.StringType },
            ),
        ) { entry ->
            ReadmeScreen(
                container = container,
                owner = entry.arguments?.getString("owner").orEmpty(),
                repo = entry.arguments?.getString("repo").orEmpty(),
                onBack = { navController.popBackStack() },
                onAuthRequired = onSessionExpired,
            )
        }

        composable(
            route = Routes.CODE,
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType },
                navArgument("repo") { type = NavType.StringType },
                navArgument("path") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            CodeViewerScreen(
                container = container,
                owner = entry.arguments?.getString("owner").orEmpty(),
                repo = entry.arguments?.getString("repo").orEmpty(),
                path = entry.arguments?.getString("path").orEmpty(),
                onBack = { navController.popBackStack() },
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
