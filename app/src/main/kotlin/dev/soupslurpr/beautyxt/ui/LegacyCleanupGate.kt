package dev.soupslurpr.beautyxt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.soupslurpr.beautyxt.LegacyCleanup
import dev.soupslurpr.beautyxt.LegacyCleanupStatus
import dev.soupslurpr.beautyxt.R

private val CleanupPadding = 24.dp
private val CleanupSpacing = 16.dp
private val CleanupProgressSize = 40.dp

/** Keeps document workflows closed while asynchronous legacy cleanup is incomplete. */
@Composable
internal fun LegacyCleanupGate(
    cleanup: LegacyCleanup,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    val status by cleanup.status.collectAsStateWithLifecycle()
    if (status == LegacyCleanupStatus.Ready) {
        content()
        return
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(CleanupPadding)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CleanupSpacing, Alignment.CenterVertically)
        ) {
            if (status == LegacyCleanupStatus.Failed) {
                Text(
                    stringResource(R.string.cleanup_failed),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    stringResource(R.string.cleanup_retry_message),
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(onClick = cleanup::start) { Text(stringResource(R.string.action_retry)) }
            } else {
                CircularProgressIndicator(modifier = Modifier.size(CleanupProgressSize))
                Text(
                    stringResource(R.string.cleanup_preparing),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    stringResource(R.string.cleanup_removing),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
        }
    }
}
