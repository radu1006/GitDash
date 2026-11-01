package com.agentroost.gitdashboard.ui.repos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentroost.gitdashboard.R
import com.agentroost.gitdashboard.di.AppContainer
import com.agentroost.gitdashboard.domain.model.Repository
import com.agentroost.gitdashboard.ui.components.EmptyPane
import com.agentroost.gitdashboard.ui.components.ErrorPane
import com.agentroost.gitdashboard.ui.components.LoadingPane
import com.agentroost.gitdashboard.ui.components.apiErrorMessage
import com.agentroost.gitdashboard.util.relativeTime

/**
 * `GET /user/repos` list. Only collects state; every request is owned by [RepoListViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoListScreen(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenRepo: (Repository) -> Unit,
    onAuthRequired: () -> Unit,
    viewModel: RepoListViewModel = viewModel(factory = RepoListViewModel.factory(container)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event == RepoListEvent.AuthRequired) onAuthRequired()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.repos_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.action_settings),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            val current = state
            if (current is RepoListUiState.Content || current is RepoListUiState.Empty) {
                RepoFilterField(
                    query = when (current) {
                        is RepoListUiState.Content -> current.query
                        is RepoListUiState.Empty -> current.query
                        else -> ""
                    },
                    onQueryChange = viewModel::onQueryChange,
                )
            }

            when (current) {
                RepoListUiState.Loading -> LoadingPane()

                is RepoListUiState.Error -> ErrorPane(
                    message = apiErrorMessage(current.error),
                    onRetry = viewModel::retry,
                )

                is RepoListUiState.Empty -> EmptyPane(
                    title = stringResource(R.string.repos_empty_title),
                    body = if (current.query.isBlank()) {
                        stringResource(R.string.repos_empty_body)
                    } else {
                        stringResource(R.string.repos_no_matches, current.query)
                    },
                )

                is RepoListUiState.Content -> RepoListContent(
                    state = current,
                    onOpenRepo = onOpenRepo,
                    onLoadMore = viewModel::loadMore,
                    onRetry = viewModel::retry,
                    onRefresh = viewModel::refresh,
                )
            }
        }
    }
}

@Composable
private fun RepoFilterField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.repos_search_hint)) },
        leadingIcon = {
            Icon(imageVector = Icons.Filled.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.repos_search_clear),
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun RepoListContent(
    state: RepoListUiState.Content,
    onOpenRepo: (Repository) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.repositories.isEmpty()) {
            item {
                EmptyPane(
                    title = stringResource(R.string.repos_empty_title),
                    body = stringResource(R.string.repos_no_matches, state.query),
                    modifier = Modifier.height(240.dp),
                )
            }
        }

        items(items = state.repositories, key = { it.id }) { repository ->
            RepoRow(repository = repository, onClick = { onOpenRepo(repository) })
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    state.isLoadingMore -> {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.repos_loading_more),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }

                    state.error != null -> ErrorPane(
                        message = apiErrorMessage(state.error),
                        onRetry = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    state.hasMore -> TextButton(onClick = onLoadMore) {
                        Text(stringResource(R.string.repos_load_more))
                    }

                    else -> TextButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_refresh))
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoRow(repository: Repository, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = repository.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (repository.isPrivate) {
                SurfaceBadge(text = stringResource(R.string.repos_private))
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = stringResource(R.string.repos_stars, repository.stars),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        repository.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = repository.owner,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = " · ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = repository.language ?: stringResource(R.string.repos_unknown_language),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = " · ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.repos_updated, relativeTime(repository.updatedAt)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SurfaceBadge(text: String) {
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
