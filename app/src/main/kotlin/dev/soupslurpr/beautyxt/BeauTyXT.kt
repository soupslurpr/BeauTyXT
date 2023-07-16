package dev.soupslurpr.beautyxt

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import dev.soupslurpr.beautyxt.settings.PreferencesViewModel
import dev.soupslurpr.beautyxt.ui.CreditsScreen
import dev.soupslurpr.beautyxt.ui.FileEditScreen
import dev.soupslurpr.beautyxt.ui.FileViewModel
import dev.soupslurpr.beautyxt.ui.LicenseScreen
import dev.soupslurpr.beautyxt.ui.PrivacyPolicyScreen
import dev.soupslurpr.beautyxt.ui.ReviewPrivacyPolicyAndLicense
import dev.soupslurpr.beautyxt.ui.SettingsScreen
import dev.soupslurpr.beautyxt.ui.StartupScreen
import java.time.LocalDateTime

enum class BeauTyXTScreens(@StringRes val title: Int) {
    Start(title = R.string.app_name),
    FileEdit(title = R.string.file_editor),
    Settings(title = R.string.settings),
    License(title = R.string.license),
    PrivacyPolicy(title = R.string.privacy_policy),
    Credits(title = R.string.credits),
    ReviewPrivacyPolicyAndLicense(title = R.string.review_privacy_policy_and_license),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeauTyXTAppBar(
    currentScreen: BeauTyXTScreens,
    canNavigateBack: Boolean,
    navigateUp: () -> Unit,
    infoShown: Boolean,
    onInfoDismissRequest: () -> Unit,
    onInfoButtonClicked: () -> Unit,
    infoDialogContent: @Composable () -> Unit,
    modifier: Modifier
) {
    TopAppBar(
        title = {
            Text(stringResource(currentScreen.title))
        },
        modifier = modifier,
        navigationIcon = {
            if (canNavigateBack) {
                IconButton(onClick =  navigateUp) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back_button)
                    )
                }
            }
        },
        actions = {
            if (currentScreen == BeauTyXTScreens.FileEdit) {
                IconButton(
                    onClick = onInfoButtonClicked,
                    content = {
                        Icon(imageVector = Icons.Filled.Info, contentDescription = stringResource(R.string.file_info))
                    }
                )
            }
            if (infoShown) {
                AlertDialog(
                    onDismissRequest = onInfoDismissRequest,
                ) {
                    Surface(
                        modifier = Modifier
                            .wrapContentWidth()
                            .wrapContentHeight()
                            .fillMaxWidth(0.95f),
                        shape = MaterialTheme.shapes.large,
                        tonalElevation = AlertDialogDefaults.TonalElevation
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(text = stringResource(R.string.file_info), style = typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
                            infoDialogContent()
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun InfoDialogItem(info: String, value: String) {
    Text(
        text = "$info:\n$value",
    )
}

@Composable
fun BeauTyXTApp(
    fileViewModel: FileViewModel = viewModel(),
    preferencesViewModel: PreferencesViewModel = viewModel(),
    modifier: Modifier,
) {
    val navController = rememberNavController()

    val backStackEntry by navController.currentBackStackEntryAsState()

    val currentScreen = BeauTyXTScreens.valueOf(
        backStackEntry?.destination?.route ?: BeauTyXTScreens.Start.name
    )

    val context = LocalContext.current

    val fileUiState by fileViewModel.uiState.collectAsState()

    val preferencesUiState by preferencesViewModel.uiState.collectAsState()

    val openFileLauncher = rememberLauncherForActivityResult(contract = OpenDocument()) {
        if (it != null) {
            fileViewModel.setUri(it, context)
            navController.navigate(BeauTyXTScreens.FileEdit.name)
        }
    }

    val createFileLauncher = rememberLauncherForActivityResult(contract = CreateDocument("text/plain")) {
        if (it != null) {
            fileViewModel.setUri(it, context)
            navController.navigate(BeauTyXTScreens.FileEdit.name)
        }
    }

    val infoShown = remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BeauTyXTAppBar(
                currentScreen = currentScreen,
                canNavigateBack = navController.previousBackStackEntry != null,
                navigateUp = { navController.navigateUp() },
                infoShown = infoShown.value,
                onInfoDismissRequest = { infoShown.value = false },
                onInfoButtonClicked = { infoShown.value = !infoShown.value },
                infoDialogContent = {
                    InfoDialogItem(info = stringResource(id = R.string.name), value = fileUiState.name.value)
                    InfoDialogItem(info = stringResource(id = R.string.size), value = fileUiState.size.value.toString() + " " + stringResource(id = R.string.bytes))
                    fileUiState.mimeType.value?.let {
                        InfoDialogItem(info = stringResource(id = R.string.mime_type),
                            it
                        )
                    }
                },
                modifier = modifier
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (preferencesUiState.acceptedPrivacyPolicyAndLicense.second.value) {BeauTyXTScreens.Start.name} else {BeauTyXTScreens.ReviewPrivacyPolicyAndLicense.name},
            modifier = modifier.padding(innerPadding),
        ) {
            composable(route = BeauTyXTScreens.ReviewPrivacyPolicyAndLicense.name) {
                ReviewPrivacyPolicyAndLicense(preferencesViewModel = preferencesViewModel)
            }
            composable(route = BeauTyXTScreens.Start.name) {
                StartupScreen(
                    modifier = modifier,
                    onOpenTxtButtonClicked = {
                        openFileLauncher.launch(
                            arrayOf(
                                "text/plain"
                            ),
                            ActivityOptionsCompat.makeBasic(),
                        )
                    },
                    onOpenAnyButtonClicked = {
                        openFileLauncher.launch(
                            arrayOf("*/*"),
                            ActivityOptionsCompat.makeBasic(),
                        )
                    },
                    onCreateTxtButtonClicked = {
                        createFileLauncher.launch(
                            // Make default file name the current LocalDateTime, and for devices
                            // which don't support LocalDateTime, make it blank.
                            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                LocalDateTime.now().toString()
                            } else {
                                ""
                            }),
                            ActivityOptionsCompat.makeBasic()
                        )
                    },
                    onSettingsButtonClicked = {
                        navController.navigate(BeauTyXTScreens.Settings.name)
                    }
                )
            }
            composable(
                route = BeauTyXTScreens.FileEdit.name,
                deepLinks = listOf(
                    navDeepLink {
                        mimeType = "text/plain"
                    },
                    navDeepLink {
                        mimeType = "application/json"
                    },
                    navDeepLink {
                        mimeType = "application/xml"
                    }
                ),
            ) {
                FileEditScreen(
                    name = fileUiState.name.value,
                    onContentChanged = {
                        fileViewModel.updateContent(it)
                        fileViewModel.setContentToUri(uri = fileUiState.uri, context = context)
                        fileViewModel.getSizeFromUri(uri = fileUiState.uri, context = context)
                    },
                    content = fileUiState.content.value,
                )
            }
            composable(route = BeauTyXTScreens.Settings.name) {
                SettingsScreen(
                    onLicenseIconButtonClicked = {
                        navController.navigate(BeauTyXTScreens.License.name)
                    },
                    onPrivacyPolicyIconButtonClicked = {
                        navController.navigate(BeauTyXTScreens.PrivacyPolicy.name)
                    },
                    onCreditsIconButtonClicked = {
                        navController.navigate(BeauTyXTScreens.Credits.name)
                    },
                    preferencesViewModel = preferencesViewModel
                )
            }
            composable(route = BeauTyXTScreens.License.name) {
                LicenseScreen()
            }
            composable(route = BeauTyXTScreens.PrivacyPolicy.name) {
                PrivacyPolicyScreen()
            }
            composable(route = BeauTyXTScreens.Credits.name) {
                CreditsScreen()
            }
        }
    }
}



