package dev.soupslurpr.beautyxt.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R

/**
 * Composable on startup that shows options like opening an existing file,
 * and creating a new one.
 */
@Composable
fun StartupScreen(
    modifier: Modifier,
    onOpenButtonClicked: () -> Unit,
    onCreateButtonClicked: () -> Unit,
    onSettingsButtonClicked: () -> Unit,
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "BeauTyXT app icon",
            modifier = modifier.size(200.dp)
        )
        Text(
            text = stringResource(R.string.welcome),
            style = MaterialTheme.typography.headlineLarge
        )
        Text(
            text = "Text, but beautiful.\n" +
                    "\n" +
                    "NEW! BeauTyXT now shows up as an option" +
                    " to open files in file explorers and other apps!",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
        FilledTonalButton(
            modifier = modifier.fillMaxWidth(),
            onClick = { onOpenButtonClicked() }
        ) {
            Icon(
                painter = painterResource(id = R.drawable.baseline_file_open_24),
                contentDescription = null
            )
            Spacer(modifier = modifier.width(8.dp))
            Text(stringResource(R.string.open_existing_file))
        }
        FilledTonalButton(
            modifier = modifier.fillMaxWidth(),
            onClick = { onCreateButtonClicked() }
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null
            )
            Spacer(modifier = modifier.width(8.dp))
            Text(stringResource(R.string.create_new_file))
        }
        FilledTonalButton(
            modifier = modifier.fillMaxWidth(),
            onClick = { onSettingsButtonClicked() }
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = null
            )
            Spacer(modifier = modifier.width(8.dp))
            Text(stringResource(R.string.settings))
        }
    }
}

@Preview
@Composable
fun StartupPreview() {
    StartupScreen(
        modifier = Modifier.fillMaxSize(),
        onOpenButtonClicked = {},
        onCreateButtonClicked = {},
        onSettingsButtonClicked = {},
    )
}