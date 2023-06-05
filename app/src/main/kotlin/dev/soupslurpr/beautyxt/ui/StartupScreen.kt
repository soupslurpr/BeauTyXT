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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    onOpenTxtButtonClicked: () -> Unit,
    onCreateTxtButtonClicked: () -> Unit,
    onSettingsButtonClicked: () -> Unit,
    onOpenAnyButtonClicked: () -> Unit,
) {
    var isOpenDropdownMenuExpanded by remember { mutableStateOf(false) }
    var isCreateDropdownMenuExpanded by remember { mutableStateOf(false) }
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
                    "NEW! You can now open custom or any file type in BeauTyXT!\n" +
                    "Just make sure they are plain text.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
        FilledTonalButton(
            modifier = modifier.fillMaxWidth(),
            onClick = { isOpenDropdownMenuExpanded = true }
        ) {
            Icon(
                painter = painterResource(id = R.drawable.baseline_file_open_24),
                contentDescription = null
            )
            Spacer(modifier = modifier.width(8.dp))
            Text(stringResource(R.string.open_existing_file))
            DropdownMenu(
                expanded = isOpenDropdownMenuExpanded,
                onDismissRequest = { isOpenDropdownMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(text = ".txt") },
                    onClick = {
                        isOpenDropdownMenuExpanded = false
                        onOpenTxtButtonClicked()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.any)) },
                    onClick = {
                        isOpenDropdownMenuExpanded = false
                        onOpenAnyButtonClicked()
                    },
                )
            }
        }
        FilledTonalButton(
            modifier = modifier.fillMaxWidth(),
            onClick = { isCreateDropdownMenuExpanded = true }
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null
            )
            Spacer(modifier = modifier.width(8.dp))
            Text(stringResource(R.string.create_new_file))
            DropdownMenu(
                expanded = isCreateDropdownMenuExpanded,
                onDismissRequest = { isCreateDropdownMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(text = ".txt") },
                    onClick = {
                        isCreateDropdownMenuExpanded = false
                        onCreateTxtButtonClicked()
                    },
                )
            }
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
        onOpenAnyButtonClicked = {},
        onOpenTxtButtonClicked = {},
        onCreateTxtButtonClicked = {},
        onSettingsButtonClicked = {},
    )
}