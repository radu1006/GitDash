package com.agentroost.gitdashboard.ui.readme

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.agentroost.gitdashboard.ui.theme.GitDashboardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The body-text primitive moved from `ClickableText` + `StringAnnotation` to Material 3 `Text` +
 * `LinkAnnotation`. This pins the user-visible half of that change: tapping a link must still hand
 * the URL to the platform browser.
 *
 * A real Activity host is used because `rememberUrlOpener` resolves `LocalContext` and starts the
 * intent from it; on a device that context is the Activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadmeLinkTapTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `tapping a markdown link starts a VIEW intent for its url`() {
        compose.setContent {
            GitDashboardTheme(darkTheme = true) {
                MarkdownBodyText(
                    spans = listOf(MarkdownSpan.Link("docs", "https://example.com/readme")),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        // The text node is fillMaxWidth, so aim at the glyphs rather than the empty centre.
        compose.onNodeWithText("docs").performTouchInput { click(Offset(4f, center.y)) }

        val started = shadowOf(compose.activity).nextStartedActivity
        assertNotNull("tapping a link started no activity", started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse("https://example.com/readme"), started.data)
    }
}
