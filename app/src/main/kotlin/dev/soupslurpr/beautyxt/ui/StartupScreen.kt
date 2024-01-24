package dev.soupslurpr.beautyxt.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.settings.PreferencesUiState

/**
 * Composable on startup that shows options like opening an existing file,
 * and creating a new one.
 */
@Composable
fun StartupScreen(
    modifier: Modifier,
    splashMessage: String,
    onOpenTxtButtonClicked: () -> Unit,
    onOpenMdButtonClicked: () -> Unit,
    onOpenTypstProjectButtonClicked: () -> Unit,
    onOpenAnyButtonClicked: () -> Unit,
    onCreateTxtButtonClicked: () -> Unit,
    onCreateMdButtonClicked: () -> Unit,
    onCreateTypstProjectButtonClicked: () -> Unit,
    onSettingsButtonClicked: () -> Unit,
    fileViewModel: FileViewModel,
    preferencesUiState: PreferencesUiState,
    typstProjectViewModel: TypstProjectViewModel,
) {
    var isOpenFileTypeAlertDialogShown by remember { mutableStateOf(false) }
    var isCreateFileTypeAlertDialogShown by remember { mutableStateOf(false) }

    // clear FileUiState when exiting file editor and going back to startup screen.
    LaunchedEffect(key1 = Unit) {
        if (fileViewModel.rustService != null) {
            fileViewModel.clearUiState()
        }
        if (typstProjectViewModel.rustService != null) {
            typstProjectViewModel.clearUiState()
        }
    }

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
            contentDescription = null,
            modifier = modifier.size(200.dp)
        )
        Text(
            text = stringResource(R.string.welcome),
            style = MaterialTheme.typography.headlineLarge
        )
        Text(
            text = splashMessage,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
        FilledTonalButton(
            modifier = modifier.fillMaxWidth(),
            onClick = { isOpenFileTypeAlertDialogShown = true }
        ) {
            Icon(
                painter = painterResource(R.drawable.baseline_file_open_24),
                contentDescription = null
            )
            Spacer(modifier = modifier.width(8.dp))
            Text(stringResource(R.string.open))
            FileTypeSelectionDialog(
                isShown = isOpenFileTypeAlertDialogShown,
                onDismissRequest = { isOpenFileTypeAlertDialogShown = false }
            ) {
                Text(
                    text = stringResource(R.string.open),
                    style = MaterialTheme.typography.headlineSmall
                )
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onOpenTxtButtonClicked()
                        isOpenFileTypeAlertDialogShown = false
                    }
                ) {
                    Text(text = stringResource(R.string.txt))
                }
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onOpenMdButtonClicked()
                        isOpenFileTypeAlertDialogShown = false
                    }
                ) {
                    Text(text = stringResource(R.string.md))
                }
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onOpenTypstProjectButtonClicked()
                        isOpenFileTypeAlertDialogShown = false
                    }
                ) {
                    Text(text = stringResource(R.string.typst_project))
                }
                if (preferencesUiState.experimentalFeatureOpenAnyFileType.second.value) {
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onOpenAnyButtonClicked()
                            isOpenFileTypeAlertDialogShown = false
                        }
                    ) {
                        Text(text = stringResource(R.string.any))
                    }
                }
            }
        }
        FilledTonalButton(
            modifier = modifier.fillMaxWidth(),
            onClick = { isCreateFileTypeAlertDialogShown = true }
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null
            )
            Spacer(modifier = modifier.width(8.dp))
            Text(stringResource(R.string.create))
            FileTypeSelectionDialog(
                isShown = isCreateFileTypeAlertDialogShown,
                onDismissRequest = { isCreateFileTypeAlertDialogShown = false },
            ) {
                Text(
                    text = stringResource(R.string.create),
                    style = MaterialTheme.typography.headlineSmall
                )
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        isCreateFileTypeAlertDialogShown = false
                        onCreateTxtButtonClicked()
                    },
                ) {
                    Text(text = stringResource(R.string.txt))
                }
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        isCreateFileTypeAlertDialogShown = false
                        onCreateMdButtonClicked()
                    },
                ) {
                    Text(text = stringResource(R.string.md))
                }
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        isCreateFileTypeAlertDialogShown = false
                        onCreateTypstProjectButtonClicked()
                    },
                ) {
                    Text(text = stringResource(R.string.create_typst_project, stringResource(R.string.typst_project)))
                }
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

        Spacer(Modifier.padding(WindowInsets.navigationBars.asPaddingValues()))
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FileTypeSelectionDialog(
    isShown: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (isShown) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
        ) {
            Surface(
                modifier = Modifier
                    .wrapContentWidth()
                    .wrapContentHeight(),
                shape = MaterialTheme.shapes.large,
                tonalElevation = AlertDialogDefaults.TonalElevation
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    content()
                }
            }
        }
    }
}
