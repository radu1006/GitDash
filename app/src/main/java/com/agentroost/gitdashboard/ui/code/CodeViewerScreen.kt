package com.agentroost.gitdashboard.ui.code

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentroost.gitdashboard.R
import com.agentroost.gitdashboard.di.AppContainer
import com.agentroost.gitdashboard.domain.model.FilePreview
import com.agentroost.gitdashboard.domain.model.PreviewUnavailableReason
import com.agentroost.gitdashboard.ui.components.ErrorPane
import com.agentroost.gitdashboard.ui.components.LoadingPane
import com.agentroost.gitdashboard.ui.components.OpenOnGitHubButton
import com.agentroost.gitdashboard.ui.components.apiErrorMessage
import com.agentroost.gitdashboard.ui.components.rememberUrlOpener
import com.agentroost.gitdashboard.ui.theme.CodePaletteDark
import com.agentroost.gitdashboard.ui.theme.LocalCodePalette
import com.agentroost.gitdashboard.ui.theme.MonospaceTextStyle

/**
 * Read-only viewer for one file, with syntax colouring and a line-number gutter.
 *
 * The file body is decoded by the data layer and tokenised by [CodeViewerViewModel] off the main
 * thread; this composable only paints the immutable result on [CodeTheme.Background].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeViewerScreen(
    container: AppContainer,
    owner: String,
    repo: String,
    path: String,
    onBack: () -> Unit,
    onAuthRequired: () -> Unit,
    viewModel: CodeViewerViewModel = viewModel(
        factory = CodeViewerViewModel.factory(container, owner, repo, path),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val openUrl = rememberUrlOpener()
    val githubUrl = (state as? CodeViewerUiState.Content)?.preview?.githubHtmlUrl

    LaunchedEffect(viewModel) {
        viewModel.authRequired.collect { onAuthRequired() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (path.isBlank()) {
                            stringResource(R.string.code_title)
                        } else {
                            path
                        },
                        style = MonospaceTextStyle.copy(fontSize = 16.sp, lineHeight = 22.sp),
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
                    if (!githubUrl.isNullOrBlank()) {
                        IconButton(onClick = { openUrl(githubUrl) }) {
                            Icon(
                                imageVector = Icons.Filled.ExitToApp,
                                contentDescription = stringResource(R.string.action_open_github),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (val current = state) {
                CodeViewerUiState.Loading -> LoadingPane()

                is CodeViewerUiState.Error -> ErrorPane(
                    message = apiErrorMessage(current.error),
                    onRetry = viewModel::retry,
                )

                is CodeViewerUiState.Content -> when (val body = current.body) {
                    is CodeViewerBody.Highlighted -> CodeBlock(
                        code = body.code,
                        modifier = Modifier.fillMaxSize(),
                    )

                    is CodeViewerBody.Unavailable -> UnavailablePreview(
                        preview = current.preview,
                        reason = body.reason,
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(
    code: HighlightedCode,
    modifier: Modifier = Modifier,
) {
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val palette = LocalCodePalette.current
    val annotated = remember(code.annotated, palette) {
        code.annotated.recolored(from = CodePaletteDark, to = palette)
    }

    Row(
        modifier = modifier
            .background(palette.background)
            .verticalScroll(verticalScrollState)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The gutter scrolls vertically with the code but stays put horizontally.
        Text(
            text = code.lineNumbers,
            style = CodeTextStyle,
            color = palette.gutterText,
            textAlign = TextAlign.End,
            softWrap = false,
            modifier = Modifier
                .background(palette.gutterBackground)
                .padding(start = 12.dp, end = 12.dp),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(horizontalScrollState),
        ) {
            Text(
                text = annotated,
                style = CodeTextStyle.copy(color = palette.text),
                softWrap = false,
                modifier = Modifier.padding(end = 16.dp),
            )
        }
    }
}

@Composable
private fun UnavailablePreview(
    preview: FilePreview,
    reason: PreviewUnavailableReason,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.code_preview_unavailable_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = previewUnavailableMessage(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        OpenOnGitHubButton(url = preview.githubHtmlUrl)
    }
}

/** One readable message per [PreviewUnavailableReason]; the `when` stays exhaustive on purpose. */
@Composable
private fun previewUnavailableMessage(reason: PreviewUnavailableReason): String = when (reason) {
    PreviewUnavailableReason.TOO_LARGE -> stringResource(R.string.code_preview_too_large)
    PreviewUnavailableReason.TOO_LARGE_TO_RENDER ->
        stringResource(R.string.code_preview_too_large_to_render)

    PreviewUnavailableReason.NOT_BASE64 -> stringResource(R.string.code_preview_not_base64)
    PreviewUnavailableReason.BINARY -> stringResource(R.string.code_preview_binary)
    PreviewUnavailableReason.TRUNCATED -> stringResource(R.string.code_preview_truncated)
    PreviewUnavailableReason.DECODE_FAILED -> stringResource(R.string.code_preview_decode_failed)
    PreviewUnavailableReason.NOT_A_FILE -> stringResource(R.string.code_preview_not_a_file)
}
