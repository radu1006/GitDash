package com.agentroost.gitdashboard.ui.readme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentroost.gitdashboard.R
import com.agentroost.gitdashboard.di.AppContainer
import com.agentroost.gitdashboard.domain.model.PreviewUnavailableReason
import com.agentroost.gitdashboard.ui.components.EmptyPane
import com.agentroost.gitdashboard.ui.components.ErrorPane
import com.agentroost.gitdashboard.ui.components.LoadingPane
import com.agentroost.gitdashboard.ui.components.OpenOnGitHubButton
import com.agentroost.gitdashboard.ui.components.apiErrorMessage
import com.agentroost.gitdashboard.ui.components.rememberUrlOpener
import com.agentroost.gitdashboard.ui.theme.CodePalette
import com.agentroost.gitdashboard.ui.theme.LocalCodePalette
import com.agentroost.gitdashboard.ui.theme.MonospaceTextStyle

/**
 * Read-only README viewer.
 *
 * The README text is fetched and parsed by [ReadmeViewModel]; this composable paints the resulting
 * immutable [MarkdownBlock] list in one vertical [LazyColumn]. Screenshots are fetched lazily by
 * [ReadmeImageLoader]: repository-relative sources through the authenticated contents API, absolute
 * URLs through a token-free HTTP client.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadmeScreen(
    container: AppContainer,
    owner: String,
    repo: String,
    onBack: () -> Unit,
    onAuthRequired: () -> Unit,
    viewModel: ReadmeViewModel = viewModel(
        factory = ReadmeViewModel.factory(container, owner, repo),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val openUrl = rememberUrlOpener()

    LaunchedEffect(viewModel) {
        viewModel.authRequired.collect { onAuthRequired() }
    }

    val readmePath = when (val current = state) {
        is ReadmeUiState.Content -> current.readmePath
        is ReadmeUiState.Unavailable -> current.readmePath
        else -> null
    }
    val githubUrl = when (val current = state) {
        is ReadmeUiState.Content -> current.githubHtmlUrl
        is ReadmeUiState.Unavailable -> current.githubHtmlUrl
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = readmePath ?: stringResource(R.string.readme_title),
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
                    IconButton(onClick = viewModel::retry) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                        )
                    }
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
                ReadmeUiState.Loading -> LoadingPane()

                is ReadmeUiState.Error -> ErrorPane(
                    message = apiErrorMessage(current.error),
                    onRetry = viewModel::retry,
                )

                is ReadmeUiState.NoReadme -> NoReadmePane(current.reason)

                is ReadmeUiState.Unavailable -> ReadmeUnavailablePane(current)

                is ReadmeUiState.Content -> ReadmeContent(
                    state = current,
                    imageLoader = viewModel.imageLoader,
                    owner = owner,
                    repo = repo,
                )
            }
        }
    }
}

@Composable
private fun NoReadmePane(reason: NoReadmeReason) {
    val body = when (reason) {
        NoReadmeReason.EMPTY_REPOSITORY -> R.string.readme_empty_repository_body
        NoReadmeReason.NO_README -> R.string.readme_no_readme_body
    }
    EmptyPane(
        title = stringResource(R.string.readme_no_readme_title),
        body = stringResource(body),
    )
}

@Composable
private fun ReadmeUnavailablePane(state: ReadmeUiState.Unavailable) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.readme_unavailable_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = previewUnavailableMessage(state.reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        OpenOnGitHubButton(url = state.githubHtmlUrl)
    }
}

@Composable
private fun ReadmeContent(
    state: ReadmeUiState.Content,
    imageLoader: ReadmeImageLoader,
    owner: String,
    repo: String,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = state.blocks) { block ->
            MarkdownBlockView(
                block = block,
                readmePath = state.readmePath,
                imageLoader = imageLoader,
                owner = owner,
                repo = repo,
            )
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    readmePath: String,
    imageLoader: ReadmeImageLoader,
    owner: String,
    repo: String,
) {
    when (block) {
        is MarkdownBlock.Heading -> MarkdownBodyText(
            spans = block.spans,
            style = headingStyle(block.level, MaterialTheme.typography),
        )

        is MarkdownBlock.Paragraph -> MarkdownBodyText(
            spans = block.spans,
            style = MaterialTheme.typography.bodyLarge,
        )

        is MarkdownBlock.BulletList -> MarkdownList(
            items = block.items,
            start = null,
            readmePath = readmePath,
            imageLoader = imageLoader,
            owner = owner,
            repo = repo,
        )

        is MarkdownBlock.OrderedList -> MarkdownList(
            items = block.items,
            start = block.start,
            readmePath = readmePath,
            imageLoader = imageLoader,
            owner = owner,
            repo = repo,
        )

        is MarkdownBlock.CodeBlock -> MarkdownCodeBlock(block)

        is MarkdownBlock.Quote -> MarkdownQuote(
            blocks = block.blocks,
            readmePath = readmePath,
            imageLoader = imageLoader,
            owner = owner,
            repo = repo,
        )

        MarkdownBlock.Divider -> HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        is MarkdownBlock.Image -> MarkdownImageView(
            image = block,
            readmePath = readmePath,
            imageLoader = imageLoader,
            owner = owner,
            repo = repo,
        )
    }
}

private fun headingStyle(level: Int, typography: Typography): TextStyle = when (level) {
    1 -> typography.headlineMedium
    2 -> typography.headlineSmall
    3 -> typography.titleLarge
    4 -> typography.titleMedium
    else -> typography.titleSmall
}

@Composable
private fun MarkdownBodyText(
    spans: List<MarkdownSpan>,
    style: TextStyle,
) {
    val openUrl = rememberUrlOpener()
    val linkColor = MaterialTheme.colorScheme.primary
    val palette = LocalCodePalette.current
    val annotated = remember(spans, linkColor, palette) {
        annotatedMarkdown(spans, linkColor, palette)
    }

    SelectionContainer {
        ClickableText(
            text = annotated,
            style = style,
            modifier = Modifier.fillMaxWidth(),
            onClick = { offset ->
                annotated.getStringAnnotations(LINK_TAG, offset, offset)
                    .firstOrNull()
                    ?.let { annotation -> openUrl(annotation.item) }
            },
        )
    }
}

private fun annotatedMarkdown(
    spans: List<MarkdownSpan>,
    linkColor: Color,
    palette: CodePalette,
): AnnotatedString = buildAnnotatedString {
    appendMarkdownSpans(spans, linkColor, palette)
}

private fun AnnotatedString.Builder.appendMarkdownSpans(
    spans: List<MarkdownSpan>,
    linkColor: Color,
    palette: CodePalette,
) {
    for (span in spans) {
        when (span) {
            is MarkdownSpan.Plain -> append(span.text)

            is MarkdownSpan.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendMarkdownSpans(span.spans, linkColor, palette)
            }

            is MarkdownSpan.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendMarkdownSpans(span.spans, linkColor, palette)
            }

            is MarkdownSpan.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = palette.text,
                    background = palette.background,
                ),
            ) {
                append(span.text)
            }

            is MarkdownSpan.Link -> withStyle(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            ) {
                pushStringAnnotation(tag = LINK_TAG, annotation = span.url)
                append(span.text)
                pop()
            }
        }
    }
}

@Composable
private fun MarkdownList(
    items: List<List<MarkdownBlock>>,
    start: Int?,
    readmePath: String,
    imageLoader: ReadmeImageLoader,
    owner: String,
    repo: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (start == null) BULLET else "${start + index}.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(28.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (child in item) {
                        MarkdownBlockView(
                            block = child,
                            readmePath = readmePath,
                            imageLoader = imageLoader,
                            owner = owner,
                            repo = repo,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownQuote(
    blocks: List<MarkdownBlock>,
    readmePath: String,
    imageLoader: ReadmeImageLoader,
    owner: String,
    repo: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (child in blocks) {
                MarkdownBlockView(
                    block = child,
                    readmePath = readmePath,
                    imageLoader = imageLoader,
                    owner = owner,
                    repo = repo,
                )
            }
        }
    }
}

@Composable
private fun MarkdownCodeBlock(block: MarkdownBlock.CodeBlock) {
    val palette = LocalCodePalette.current
    val scroll = rememberScrollState()

    SelectionContainer {
        Text(
            text = block.code,
            style = MonospaceTextStyle.copy(color = palette.text),
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.background)
                .horizontalScroll(scroll)
                .padding(12.dp),
        )
    }
}

/**
 * One screenshot.
 *
 * A fixed 16:9 placeholder is shown while the image loads, so the list does not jump when the
 * bitmap arrives; on failure the placeholder carries the alt text and opens the image's web URL on
 * tap. Content state lives in [ReadmeImageLoader], so recomposition never re-triggers a fetch.
 */
@Composable
private fun MarkdownImageView(
    image: MarkdownBlock.Image,
    readmePath: String,
    imageLoader: ReadmeImageLoader,
    owner: String,
    repo: String,
) {
    val openUrl = rememberUrlOpener()
    val source = remember(image.src, readmePath) {
        ReadmeImageRefs.resolve(image.src, readmePath)
    }
    val stateFlow = remember(imageLoader, source) { imageLoader.state(source) }
    val imageState by stateFlow.collectAsStateWithLifecycle()

    LaunchedEffect(imageLoader, source) { imageLoader.load(source) }

    val webUrl = remember(source, owner, repo) {
        ReadmeImageRefs.webUrl(source, owner, repo, ref = null)
    }
    val alt = image.alt.ifBlank { image.title.orEmpty() }
    val shape = RoundedCornerShape(12.dp)

    when (val current = imageState) {
        is ReadmeImageState.Loaded -> {
            val ratio = current.image.width.toFloat() /
                current.image.height.coerceAtLeast(1)
            Image(
                bitmap = current.image,
                contentDescription = alt.ifBlank { null },
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .aspectRatio(ratio),
            )
        }

        else -> ImagePlaceholder(
            loading = current is ReadmeImageState.Loading,
            alt = alt,
            webUrl = webUrl,
            onOpen = openUrl,
            shape = shape,
        )
    }
}

@Composable
private fun ImagePlaceholder(
    loading: Boolean,
    alt: String,
    webUrl: String?,
    onOpen: (String) -> Unit,
    shape: RoundedCornerShape,
) {
    val target = webUrl.takeIf { !loading }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = target != null) {
                if (target != null) onOpen(target)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.readme_image_unavailable),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (alt.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = alt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (target != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.readme_image_open),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** One readable message per [PreviewUnavailableReason], matching the code viewer's wording. */
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

private const val LINK_TAG = "readme-link"

/** U+2022 is a symbol, not user-facing English, so it stays out of `strings.xml`. */
private const val BULLET = "\u2022"
