package dev.soupslurpr.beautyxt

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import dev.soupslurpr.beautyxt.settings.SettingsViewModel
import dev.soupslurpr.beautyxt.ui.CreditsScreen
import dev.soupslurpr.beautyxt.ui.FileEditScreen
import dev.soupslurpr.beautyxt.ui.FileViewModel
import dev.soupslurpr.beautyxt.ui.LicenseScreen
import dev.soupslurpr.beautyxt.ui.PrivacyPolicyScreen
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeauTyXTAppBar(
    currentScreen: BeauTyXTScreens,
    canNavigateBack: Boolean,
    navigateUp: () -> Unit,
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
        }
    )
}

@Composable
fun BeauTyXTApp(
    fileViewModel: FileViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
    modifier: Modifier,
) {
    val navController = rememberNavController()

    val backStackEntry by navController.currentBackStackEntryAsState()

    val currentScreen = BeauTyXTScreens.valueOf(
        backStackEntry?.destination?.route ?: BeauTyXTScreens.Start.name
    )

    val context = LocalContext.current

    val uiState by fileViewModel.uiState.collectAsState()

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

    Scaffold(
        topBar = {
            BeauTyXTAppBar(
                currentScreen = currentScreen,
                canNavigateBack = navController.previousBackStackEntry != null,
                navigateUp = { navController.navigateUp() },
                modifier = modifier
            )
        }
    ) { innerPadding ->

        NavHost(
            navController = navController,
            startDestination = BeauTyXTScreens.Start.name,
            modifier = modifier.padding(innerPadding)
        ) {
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
                    name = uiState.name.value,
                    onContentChanged = {
                        fileViewModel.updateContent(it)
                        fileViewModel.setContentToUri(uri = uiState.uri, context = context)
                    },
                    content = uiState.content.value,
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
                    settingsViewModel = settingsViewModel
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



