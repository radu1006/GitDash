package com.agentroost.gitdashboard.ui.browser

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.agentroost.gitdashboard.domain.model.ContentEntry
import com.agentroost.gitdashboard.domain.model.ContentKind
import com.agentroost.gitdashboard.github.GitHubWebUrls
import com.agentroost.gitdashboard.ui.components.EmptyPane
import com.agentroost.gitdashboard.ui.components.ErrorPane
import com.agentroost.gitdashboard.ui.components.FileIcon
import com.agentroost.gitdashboard.ui.components.FolderIcon
import com.agentroost.gitdashboard.ui.components.LoadingPane
import com.agentroost.gitdashboard.ui.components.OpenOnGitHubButton
import com.agentroost.gitdashboard.ui.components.apiErrorMessage
import com.agentroost.gitdashboard.ui.readme.ReadmeFiles
import com.agentroost.gitdashboard.ui.theme.MonospaceTextStyle
import com.agentroost.gitdashboard.util.formatFileSize

/**
 * File browser for one repository folder.
 *
 * Only collects state; every request is owned by [BrowseViewModel]. A directory row opens the
 * folder in place, a file row hands its path to [onOpenFile] (the code viewer route), and symlinks,
 * submodules and unknown kinds are labelled but never treated as folders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    container: AppContainer,
    owner: String,
    repo: String,
    initialPath: String,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenReadme: () -> Unit,
    onAuthRequired: () -> Unit,
    viewModel: BrowseViewModel = viewModel(
        factory = BrowseViewModel.factory(container, owner, repo, initialPath),
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
                        text = "$owner/$repo",
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
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            BrowsePathBar(
                path = state.path,
                folderUrl = GitHubWebUrls.tree(owner, repo, ref = "", path = state.path),
                onUp = viewModel::openParent,
            )

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when (val current = state) {
                    is BrowseUiState.Loading -> LoadingPane()

                    is BrowseUiState.Error -> ErrorPane(
                        message = apiErrorMessage(current.error),
                        onRetry = viewModel::retry,
                    )

                    is BrowseUiState.Empty -> EmptyPane(
                        title = stringResource(R.string.browse_empty_title),
                        body = stringResource(R.string.browse_empty_body),
                    )

                    is BrowseUiState.Content -> BrowseList(
                        state = current,
                        onOpenDirectory = viewModel::openDirectory,
                        onOpenFile = onOpenFile,
                        onOpenReadme = onOpenReadme,
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowsePathBar(
    path: String,
    folderUrl: String,
    onUp: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onUp, enabled = path.isNotBlank()) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.browse_up),
                )
            }
            Text(
                text = if (path.isBlank()) {
                    stringResource(R.string.browse_path_root)
                } else {
                    stringResource(R.string.browse_path, path)
                },
                style = MonospaceTextStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            OpenOnGitHubButton(url = folderUrl)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun BrowseList(
    state: BrowseUiState.Content,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenReadme: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.isRefreshing) {
            item {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        items(items = state.entries, key = { it.path }) { entry ->
            EntryRow(
                entry = entry,
                onClick = {
                    when {
                        entry.isDirectory -> onOpenDirectory(entry.path)

                        // Only the repository root README has a viewer route; nested markdown stays
                        // in the code viewer.
                        state.path.isBlank() && entry.isViewableFile &&
                            ReadmeFiles.isReadme(entry.name) -> onOpenReadme()

                        entry.isViewableFile -> onOpenFile(entry.path)
                    }
                },
            )
        }
    }
}

@Composable
private fun EntryRow(entry: ContentEntry, onClick: () -> Unit) {
    val canOpen = entry.isDirectory || entry.isViewableFile
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(enabled = canOpen, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (entry.isDirectory) FolderIcon else FileIcon,
                contentDescription = null,
                tint = if (entry.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        when (entry.kind) {
            ContentKind.SYMLINK -> KindBadge(stringResource(R.string.browse_symlink))
            ContentKind.SUBMODULE -> KindBadge(stringResource(R.string.browse_submodule))
            else -> Unit
        }

        if (entry.kind == ContentKind.FILE) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatFileSize(entry.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KindBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
