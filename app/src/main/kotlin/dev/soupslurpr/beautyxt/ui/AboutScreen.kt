@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.soupslurpr.beautyxt.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.designsystem.AppMark

private const val SOURCE_CODE_URL = "https://github.com/soupslurpr/BeauTyXT"
private val AboutContentMaxWidth = 680.dp
private val AboutHorizontalPadding = 24.dp
private val AboutSectionSpacing = 24.dp
private val AboutCompactSpacing = 12.dp
private val AboutRelatedSpacing = 8.dp

/** Displays the compact About identity and the app's existing behavior explanations. */
@Composable
internal fun AboutScreen(
    versionName: String,
    onBack: () -> Unit,
    onOpenThirdPartyNotices: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sourceCodeUnavailable by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.about_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.navigation_back)
                        )
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                )
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
        )
    ) { contentPadding ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            val compact = maxHeight < 480.dp
            val sectionSpacing = if (compact) AboutCompactSpacing else AboutSectionSpacing
            Column(
                modifier = Modifier
                    .widthIn(max = AboutContentMaxWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AboutHorizontalPadding)
                    .padding(top = sectionSpacing, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)
                ) {
                    AboutIdentity()
                    Text(
                        text = stringResource(R.string.about_summary),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AboutRelatedSpacing),
                    verticalArrangement = Arrangement.spacedBy(AboutRelatedSpacing)
                ) {
                    FilledTonalButton(
                        onClick = { sourceCodeUnavailable = !tryOpenSourceCode(uriHandler) }
                    ) {
                        Text(stringResource(R.string.about_view_source))
                        Spacer(Modifier.width(AboutRelatedSpacing))
                        Icon(
                            painter = painterResource(R.drawable.ic_open_in_new),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    TextButton(onClick = onOpenThirdPartyNotices) {
                        Text(stringResource(R.string.about_licenses))
                    }
                }
                if (sourceCodeUnavailable) {
                    Column(verticalArrangement = Arrangement.spacedBy(AboutRelatedSpacing)) {
                        Text(
                            text = stringResource(R.string.about_source_unavailable),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        SelectionContainer {
                            Text(SOURCE_CODE_URL, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                AboutSection(
                    title = stringResource(R.string.about_files_title),
                    text = stringResource(R.string.about_files)
                )
                AboutSection(
                    title = stringResource(R.string.about_storage_title),
                    text = stringResource(R.string.about_storage)
                )
                AboutSection(
                    title = stringResource(R.string.about_processing_title),
                    text = stringResource(R.string.about_processing)
                )
                AboutSection(
                    title = stringResource(R.string.about_limits_title),
                    text = stringResource(R.string.about_limits)
                )
                Text(
                    text = stringResource(R.string.about_version_and_copyright, versionName),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** Places the existing app mark beside its name and purpose without a tall hero. */
@Composable
private fun AboutIdentity() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppMark(size = if (LocalDensity.current.fontScale >= 2f) 44.dp else 56.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = stringResource(R.string.home_brand_name),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = stringResource(R.string.home_tagline),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** Makes each factual topic easy to find while keeping body text at full foreground color. */
@Composable
private fun AboutSection(title: String, text: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AboutRelatedSpacing)
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium
        )
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Opens the fixed project URL or returns a recoverable Android dispatch failure. */
internal fun tryOpenSourceCode(uriHandler: UriHandler): Boolean = try {
    uriHandler.openUri(SOURCE_CODE_URL)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: IllegalArgumentException) {
    false
} catch (_: SecurityException) {
    false
}
