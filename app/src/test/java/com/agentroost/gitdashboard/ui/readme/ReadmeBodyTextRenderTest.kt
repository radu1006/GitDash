package com.agentroost.gitdashboard.ui.readme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import com.agentroost.gitdashboard.ui.theme.DarkColors
import com.agentroost.gitdashboard.ui.theme.GitDashboardTheme
import com.agentroost.gitdashboard.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the real README body-text composable and reads back the style Compose actually lays out
 * with, so a regression at the call site (passing an uncoloured typography style to a text
 * primitive that ignores `LocalContentColor`) fails here instead of shipping a black-on-black page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadmeBodyTextRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `dark theme lays body text out in the dark scheme onSurface colour`() {
        val laidOut = laidOutBodyTextStyle(dark = true)
        assertEquals(DarkColors.onSurface, laidOut.color)
        assertNotEquals(Color.Unspecified, laidOut.color)
    }

    @Test
    fun `light theme lays body text out in the light scheme onSurface colour`() {
        val laidOut = laidOutBodyTextStyle(dark = false)
        assertEquals(LightColors.onSurface, laidOut.color)
        assertNotEquals(Color.Unspecified, laidOut.color)
    }

    @Test
    fun `a zero-length link range renders without crashing`() {
        // `[](https://x)` is a valid markdown link with an empty label; the parser keeps it and the
        // Compose link range is empty. Rendering must stay a no-op instead of throwing.
        compose.setContent {
            GitDashboardTheme(darkTheme = true) {
                MarkdownBodyText(
                    spans = listOf(
                        MarkdownSpan.Plain("a"),
                        MarkdownSpan.Link("", "https://example.com/empty"),
                        MarkdownSpan.Plain("b"),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("ab").assertExists()
    }

    /** The style Compose used to lay out the README body text in [dark] mode. */
    private fun laidOutBodyTextStyle(dark: Boolean): TextStyle {
        compose.setContent {
            GitDashboardTheme(darkTheme = dark) {
                MarkdownBodyText(
                    spans = listOf(MarkdownSpan.Plain("README body")),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        val layoutResults = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("README body")
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layoutResults)
            ?: error("the body text node exposed no text layout result")

        return layoutResults.single().layoutInput.style
    }
}
