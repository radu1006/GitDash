package com.agentroost.gitdashboard.ui.repos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
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
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = repository.initials,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = repository.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.repos_updated,
                        relativeTime(repository.updatedAt),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.repos_stars, repository.stars),
                style = MaterialTheme.typography.labelLarge,
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
            LanguageDot(language = repository.language)
            Spacer(Modifier.width(6.dp))
            Text(
                text = repository.language ?: stringResource(R.string.repos_unknown_language),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (repository.isPrivate) {
                RepoPill(
                    text = stringResource(R.string.repos_private),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (repository.isFork) {
                RepoPill(
                    text = stringResource(R.string.repo_detail_fork),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            if (repository.isArchived) {
                RepoPill(
                    text = stringResource(R.string.repo_detail_archived),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/** Small colour cue next to the language name; mapped to theme roles, never a raw literal. */
@Composable
private fun LanguageDot(language: String?) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(languageAccent(language)),
    )
}

@Composable
private fun languageAccent(language: String?) = when (language?.lowercase()) {
    "kotlin", "swift" -> MaterialTheme.colorScheme.primary
    "java", "c", "c++", "c#", "shell" -> MaterialTheme.colorScheme.tertiary
    "javascript", "typescript", "html", "css" -> MaterialTheme.colorScheme.secondary
    "python", "ruby", "go", "rust" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun RepoPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}
