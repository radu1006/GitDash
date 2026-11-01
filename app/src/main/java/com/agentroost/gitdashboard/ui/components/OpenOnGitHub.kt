package com.agentroost.gitdashboard.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agentroost.gitdashboard.R

/**
 * Opens an https URL in the user's browser.
 *
 * Used by every "Open on GitHub" action; a device without a browser simply stays on the screen
 * instead of crashing.
 */
@Composable
fun rememberUrlOpener(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { url: String ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (exception: ActivityNotFoundException) {
                // No browser installed: nothing to do, the user stays where they are.
            }
        }
    }
}

/** Secondary action used on the detail screen and in the "preview unavailable" state. */
@Composable
fun OpenOnGitHubButton(
    url: String?,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.action_open_github),
) {
    if (url.isNullOrBlank()) return
    val open = rememberUrlOpener()
    TextButton(onClick = { open(url) }, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.ExitToApp,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
