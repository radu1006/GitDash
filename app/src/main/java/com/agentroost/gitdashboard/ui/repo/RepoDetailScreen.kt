package com.agentroost.gitdashboard.ui.repo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentroost.gitdashboard.R
import com.agentroost.gitdashboard.di.AppContainer
import com.agentroost.gitdashboard.domain.model.Repository
import com.agentroost.gitdashboard.ui.components.ErrorPane
import com.agentroost.gitdashboard.ui.components.LoadingPane
import com.agentroost.gitdashboard.ui.components.OpenOnGitHubButton
import com.agentroost.gitdashboard.ui.components.apiErrorMessage
import com.agentroost.gitdashboard.util.formatCount
import com.agentroost.gitdashboard.util.relativeTime

/**
 * Repository header (name, owner, description, visibility, default branch, stats, language) with the
 * entry point into the file browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailScreen(
    container: AppContainer,
    owner: String,
    repo: String,
    onBack: () -> Unit,
    onBrowse: (Repository) -> Unit,
    onAuthRequired: () -> Unit,
    viewModel: RepoDetailViewModel = viewModel(
        factory = RepoDetailViewModel.factory(container, owner, repo),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.authRequired.collect { onAuthRequired() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = (state as? RepoDetailUiState.Content)
                            ?.repository
                            ?.fullName
                            ?: "$owner/$repo",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (val current = state) {
                RepoDetailUiState.Loading -> LoadingPane()

                is RepoDetailUiState.Error -> ErrorPane(
                    message = apiErrorMessage(current.error),
                    onRetry = viewModel::refresh,
                )

                is RepoDetailUiState.Content -> RepoDetailContent(
                    state = current,
                    onBrowse = { onBrowse(current.repository) },
                )
            }
        }
    }
}

@Composable
private fun RepoDetailContent(
    state: RepoDetailUiState.Content,
    onBrowse: () -> Unit,
) {
    val repository = state.repository

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = repository.name,
            style = MaterialTheme.typography.headlineSmall,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = repository.owner,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Badge(text = visibilityLabel(repository))
            if (repository.isFork) {
                Spacer(Modifier.width(6.dp))
                Badge(text = stringResource(R.string.repo_detail_fork))
            }
            if (repository.isArchived) {
                Spacer(Modifier.width(6.dp))
                Badge(text = stringResource(R.string.repo_detail_archived))
            }
        }

        Text(
            text = repository.description?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.repo_detail_no_description),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (state.isRefreshing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.state_loading),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (state.error != null) {
            ErrorPane(
                message = apiErrorMessage(state.error),
                onRetry = onBrowse.takeIf { false },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        StatGrid(repository)

        InfoRow(
            label = stringResource(R.string.repo_detail_default_branch),
            value = repository.defaultBranch,
        )
        InfoRow(
            label = stringResource(R.string.repo_detail_language),
            value = repository.language ?: stringResource(R.string.repos_unknown_language),
        )
        InfoRow(
            label = stringResource(R.string.repo_detail_updated),
            value = relativeTime(repository.updatedAt),
        )

        Spacer(Modifier.height(4.dp))

        Button(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.repo_detail_browse))
        }

        OpenOnGitHubButton(
            url = repository.htmlUrl,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StatGrid(repository: Repository) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatTile(
            label = stringResource(R.string.repo_detail_stars),
            value = formatCount(repository.stars),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(R.string.repo_detail_forks),
            value = formatCount(repository.forks),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(R.string.repo_detail_watchers),
            value = formatCount(repository.watchers),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(vertical = 10.dp, horizontal = 12.dp),
    ) {
        Text(text = value, style = MaterialTheme.typography.titleMedium)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Badge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun visibilityLabel(repository: Repository): String = when (repository.visibility?.lowercase()) {
    "public" -> stringResource(R.string.repo_detail_public)
    "internal" -> stringResource(R.string.repo_detail_internal)
    "private" -> stringResource(R.string.repo_detail_private)
    else -> if (repository.isPrivate) {
        stringResource(R.string.repo_detail_private)
    } else {
        stringResource(R.string.repo_detail_public)
    }
}
