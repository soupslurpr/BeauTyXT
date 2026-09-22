@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.soupslurpr.beautyxt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val NoticesLinesPerSection = 64
private val NoticesHorizontalPadding = 20.dp
private val NoticesVerticalPadding = 16.dp

/** Displays the notices for third-party software distributed with BeauTyXT. */
class ThirdPartyNoticesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BeauTyXTTheme {
                ThirdPartyNoticesScreen(onBack = ::finish)
            }
        }
    }

    /** Displays a selectable local copy of the bundled notices. */
    @Composable
    private fun ThirdPartyNoticesScreen(onBack: () -> Unit) {
        val sections by produceState<List<String>?>(initialValue = null) {
            value = withContext(Dispatchers.IO) {
                resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { reader ->
                    reader.lineSequence()
                        .map(String::trim)
                        .chunked(NoticesLinesPerSection)
                        .map { lines -> lines.joinToString("\n") }
                        .toList()
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.about_licenses)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.navigation_back)
                            )
                        }
                    },
                    windowInsets =
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                        )
                )
            },
            contentWindowInsets =
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
        ) { contentPadding ->
            val loadedSections = sections
            if (loadedSections == null) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.about_loading_licenses))
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(contentPadding),
                        contentPadding = PaddingValues(
                            horizontal = NoticesHorizontalPadding,
                            vertical = NoticesVerticalPadding
                        )
                    ) {
                        items(loadedSections) { section ->
                            Text(
                                text = section,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
