package dev.soupslurpr.beautyxt

import android.content.Context
import android.os.Build
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import dev.soupslurpr.beautyxt.settings.PreferencesUiState
import dev.soupslurpr.beautyxt.settings.PreferencesViewModel
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

    dropDownMenuShown: Boolean,
    onDropDownMenuButtonClicked: () -> Unit,
    onDropDownMenuDismissRequest: () -> Unit,

    preferencesUiState: PreferencesUiState,
    fileInfoShown: Boolean,
    onFileInfoDialogDismissRequest: () -> Unit,
    onFileInfoDropdownMenuItemClicked: () -> Unit,
    fileInfoDialogContent: @Composable () -> Unit,
    fileInfoDialogConfirmButton: @Composable () -> Unit,

    onSettingsDropdownMenuItemClicked: () -> Unit,

    exportDropdownMenuShown: Boolean,
    onExportDropdownMenuItemClicked: () -> Unit,
    onExportDropdownMenuDismissRequest: () -> Unit,

    saveAsShown: Boolean,
    onSaveAsDialogDismissRequest: () -> Unit,
    onSaveAsExportDropdownMenuItemClicked: () -> Unit,
    saveAsDialogContent: @Composable () -> Unit,
    saveAsDialogConfirmButton: @Composable () -> Unit,
    saveAsDialogDismissButton: @Composable () -> Unit,

    onPrintExportDropdownMenuItemClicked: () -> Unit,

    readOnly: Boolean,
    mimeType: String?,
    onPreviewMarkdownRenderedToFullscreenButtonClicked: () -> Unit,
    modifier: Modifier
) {
    val dropDownMenuItemTextStyle = typography.bodyLarge

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
                if (readOnly) {
                    Text(text = stringResource(R.string.read_only))
                }
                if (mimeType == "text/markdown") {
                    if (preferencesUiState.experimentalFeaturePreviewRenderedMarkdownInFullscreen.second.value) {
                        IconButton(
                            onClick = onPreviewMarkdownRenderedToFullscreenButtonClicked,
                            content = {
                                Icon(
                                    painter = painterResource(R.drawable.baseline_preview_24),
                                    contentDescription = stringResource(R.string.preview_markdown_rendered_to_html)
                                )
                            }
                        )
                    }
                }
                IconButton(
                    onClick = onDropDownMenuButtonClicked,
                    content = {
                        Icon(imageVector = Icons.Filled.MoreVert,
                            stringResource(R.string.options_dropdown_menu))
                    }
                )
                DropdownMenu(
                    expanded = dropDownMenuShown,
                    onDismissRequest = { onDropDownMenuDismissRequest() },
//                    scrollState = rememberScrollState(),
                    modifier = Modifier.width(225.dp)
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.file_info),
                                style = dropDownMenuItemTextStyle
                            )
                        },
                        onClick = { onFileInfoDropdownMenuItemClicked() },
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.Info, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.export),
                                style = dropDownMenuItemTextStyle
                            )
                        },
                        onClick = { onExportDropdownMenuItemClicked() },
                        leadingIcon = {
                            Icon(painter = painterResource(R.drawable.baseline_output_24), contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.settings),
                                style = dropDownMenuItemTextStyle
                            )
                        },
                        onClick = { onSettingsDropdownMenuItemClicked() },
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.Settings, contentDescription = null)
                        }
                    )
                }
                DropdownMenu(
                    expanded = exportDropdownMenuShown,
                    onDismissRequest = { onExportDropdownMenuDismissRequest() },
//                    scrollState = rememberScrollState(),
                    modifier = Modifier.width(225.dp)
                ) {
                    if (mimeType == "text/markdown") {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Print",
                                    style = dropDownMenuItemTextStyle
                                )
                            },
                            onClick = { onPrintExportDropdownMenuItemClicked() },
                            leadingIcon = {
                                Icon(painter = painterResource(R.drawable.baseline_print_24), contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.save_as),
                                    style = dropDownMenuItemTextStyle
                                )
                            },
                            onClick = { onSaveAsExportDropdownMenuItemClicked() },
                            leadingIcon = {
                                Icon(painter = painterResource(R.drawable.baseline_save_as_24), contentDescription = null)
                            }
                        )
                    }
                }
                if (saveAsShown) {
                    AlertDialog(
                        onDismissRequest = onSaveAsDialogDismissRequest,
                        confirmButton = saveAsDialogConfirmButton,
                        dismissButton = saveAsDialogDismissButton,
                        title = {
                            Text(
                                text = stringResource(R.string.save_as),
                                style = typography.headlineSmall,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                        },
                        text = {
                            saveAsDialogContent()
                        }
                    )
                }
                if (fileInfoShown) {
                    AlertDialog(
                        onDismissRequest = onFileInfoDialogDismissRequest,
                        confirmButton = fileInfoDialogConfirmButton,
                        title = {
                            Text(
                                text = stringResource(R.string.file_info),
                                style = typography.headlineSmall,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                        },
                        text = {
                            fileInfoDialogContent()
                        }
                    )
                }
            }
        }
    )
}

@Composable
fun FileInfoDialogItem(info: String, value: String) {
    Text(
        text = "$info:\n$value",
        style = typography.titleMedium
    )
}

@Composable
fun SaveAsDialogItem(
    fileTypeText: String,
    selected: Boolean,
    onClickRadioButton: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClickRadioButton
            )
            .fillMaxWidth()
    ) {
        RadioButton(
            selected = selected,
            onClick = null
        )
        Spacer(modifier = Modifier.padding(4.dp))
        Text(
            text = fileTypeText,
            style = typography.titleMedium
        )
    }
}

@Composable
fun BeauTyXTApp(
    preferencesViewModel: PreferencesViewModel,
    modifier: Modifier,
) {
    val fileViewModel: FileViewModel = viewModel()

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
            fileViewModel.setReadOnly(false)
            fileViewModel.setUri(it, context)
            navController.navigate(BeauTyXTScreens.FileEdit.name)
        }
    }

    val createTxtFileLauncher = rememberLauncherForActivityResult(contract = CreateDocument("text/plain")) {
        if (it != null) {
            fileViewModel.setReadOnly(false)
            fileViewModel.setUri(it, context)
            navController.navigate(BeauTyXTScreens.FileEdit.name)
        }
    }

    val createMdFileLauncher = rememberLauncherForActivityResult(contract = CreateDocument("text/markdown")) {
        if (it != null) {
            fileViewModel.setReadOnly(false)
            fileViewModel.setUri(it, context)
            navController.navigate(BeauTyXTScreens.FileEdit.name)
        }
    }

    var fileInfoDialogShown by remember { mutableStateOf(false) }

    var dropDownMenuShown by remember { mutableStateOf(false) }

    var exportDropdownMenuShown by remember { mutableStateOf(false) }

    var saveAsShown by remember { mutableStateOf(false) }

    val mimeTypeHtml = "text/html"

    val saveAsHtmlFileLauncher = rememberLauncherForActivityResult(contract = CreateDocument(mimeTypeHtml)) {
        if (it != null) {
            fileViewModel.saveAsHtml(it, context)
        }
    }

    var previewMarkdownRenderedToFullscreen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            var saveAsSelectedFileType by remember { mutableStateOf("") }
            BeauTyXTAppBar(
                currentScreen = currentScreen,
                canNavigateBack = navController.previousBackStackEntry != null,
                navigateUp = { navController.navigateUp() },
                preferencesUiState = preferencesUiState,

                dropDownMenuShown = dropDownMenuShown,
                onDropDownMenuButtonClicked = { dropDownMenuShown = !dropDownMenuShown },
                onDropDownMenuDismissRequest = { dropDownMenuShown = false },

                fileInfoShown = fileInfoDialogShown,
                onFileInfoDialogDismissRequest = { fileInfoDialogShown = false },
                onFileInfoDropdownMenuItemClicked = {
                    fileInfoDialogShown = !fileInfoDialogShown
                    dropDownMenuShown = false
                },
                fileInfoDialogContent = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        FileInfoDialogItem(info = stringResource(id = R.string.name), value = fileUiState.name.value)
                        FileInfoDialogItem(info = stringResource(id = R.string.size), value = fileUiState.size.value.toString() + " " + stringResource(id = R.string.bytes))
                        fileUiState.mimeType.value?.let {
                            FileInfoDialogItem(info = stringResource(id = R.string.mime_type),
                                it
                            )
                        }
                    }
                },
                fileInfoDialogConfirmButton = {
                    TextButton(
                        onClick = {
                            fileInfoDialogShown = false
                        },
                        content = {
                            Text(
                                text = stringResource(R.string.confirm_)
                            )
                        }
                    )
                },

                onSettingsDropdownMenuItemClicked = {
                    navController.navigate(BeauTyXTScreens.Settings.name)
                    dropDownMenuShown = false
                },

                exportDropdownMenuShown = exportDropdownMenuShown,
                onExportDropdownMenuItemClicked = {
                    dropDownMenuShown = !dropDownMenuShown
                    exportDropdownMenuShown = !exportDropdownMenuShown
                },
                onExportDropdownMenuDismissRequest = { exportDropdownMenuShown = false },

                saveAsShown = saveAsShown,
                onSaveAsDialogDismissRequest = { saveAsShown = false },
                onSaveAsExportDropdownMenuItemClicked = {
                    saveAsShown = !saveAsShown
                    dropDownMenuShown = false
                    exportDropdownMenuShown = false
                },
                saveAsDialogContent = {
                    Column(
                        modifier = Modifier
                            .selectableGroup()
                            .fillMaxWidth()
                    ) {
                        SaveAsDialogItem(
                            fileTypeText = stringResource(R.string.html),
                            selected = saveAsSelectedFileType == mimeTypeHtml,
                            onClickRadioButton = {
                                saveAsSelectedFileType = mimeTypeHtml
                            }
                        )
                    }

                },
                saveAsDialogConfirmButton = {
                    TextButton(
                        onClick = {
                            when (saveAsSelectedFileType) {
                                mimeTypeHtml -> saveAsHtmlFileLauncher.launch(
                                    fileUiState.name.value.substringBeforeLast(".")
                                )
                            }
                            saveAsShown = false
                        },
                        enabled = saveAsSelectedFileType != "",
                        content = {
                            Text(
                                text = stringResource(R.string.confirm_)
                            )
                        }
                    )
                },
                saveAsDialogDismissButton = {
                    TextButton(
                        onClick = {
                            saveAsShown = false
                        },
                        content = {
                            Text(
                                text = stringResource(R.string.cancel)
                            )
                        }
                    )
                },

                onPrintExportDropdownMenuItemClicked = {
                    exportDropdownMenuShown = false
                    var mWebView: WebView? = null

                    // Create a WebView object specifically for printing
                    val webView = WebView(context)
                    webView.webViewClient = object : WebViewClient() {

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

                        override fun onPageFinished(view: WebView, url: String) {
                            createWebPrintJob(view)
                            mWebView = null
                        }
                        fun createWebPrintJob(webView: WebView) {

                            // Get a PrintManager instance
                            (context.getSystemService(Context.PRINT_SERVICE) as? PrintManager)?.let { printManager ->

                                val jobName = fileUiState.name.value.substringBeforeLast(".")

                                // Get a print adapter instance
                                val printAdapter = webView.createPrintDocumentAdapter(jobName)

                                // Create a print job with name and adapter instance
                                printManager.print(
                                    jobName,
                                    printAdapter,
                                    PrintAttributes.Builder().build()
                                )
                            }
                        }
                    }

                    // Generate an HTML document on the fly:
                    val htmlDocument = """
                                <!DOCTYPE html>
                                <html>
                                    <head>
                                        <meta charset="utf-8"/>
                                        <meta name="viewport" content="width=device-width, initial-scale=1"/>
                                        <style>
                                            html {
                                                overflow-wrap: anywhere;
                                            }
                                            table, th, td {
                                                border: thin solid;
                                            }
                                        </style>
                                    </head>
                                    <body>
                                        ${fileViewModel.getMarkdownToHtml().value}
                                    </body>
                                </html>
                                """.trimIndent()
                    webView.loadData(htmlDocument, "text/html", "UTF-8")

                    // Keep a reference to WebView object until you pass the PrintDocumentAdapter
                    // to the PrintManager
                    mWebView = webView
                },

                readOnly = fileUiState.readOnly.value,

                mimeType = fileUiState.mimeType.value,

                onPreviewMarkdownRenderedToFullscreenButtonClicked = {
                    previewMarkdownRenderedToFullscreen = !previewMarkdownRenderedToFullscreen
                },

                modifier = modifier
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = BeauTyXTScreens.Start.name,
            modifier = modifier.padding(innerPadding),
        ) {
            composable(route = BeauTyXTScreens.Start.name) {
                StartupScreen(
                    modifier = modifier,
                    onOpenTxtButtonClicked = {
                        openFileLauncher.launch(
                            arrayOf("text/plain"),
                            ActivityOptionsCompat.makeBasic(),
                        )
                    },
                    onOpenMdButtonClicked = {
                        openFileLauncher.launch(
                            arrayOf("text/markdown"),
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
                        createTxtFileLauncher.launch(
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
                    onCreateMdButtonClicked = {
                        createMdFileLauncher.launch(
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
                    },
                    fileViewModel = fileViewModel,
                    preferencesUiState = preferencesUiState,
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
                    },
                    navDeepLink {
                        mimeType = "text/markdown"
                    }
                ),
            ) {
                FileEditScreen(
                    name = fileUiState.name.value,
                    onContentChanged = { content ->
                        fileViewModel.updateContent(content)
                        fileViewModel.setContentToUri(uri = fileUiState.uri.value, context = context)
                        when (fileUiState.mimeType.value) {
                            "text/markdown" -> if (preferencesUiState.renderMarkdown.second.value) {
                                fileViewModel.getMarkdownToHtml()
                            }
                        }
                        fileViewModel.getSizeFromUri(uri = fileUiState.uri.value, context = context)
                    },
                    content = fileUiState.content.value,
                    mimeType = fileUiState.mimeType.value!!,
                    contentConvertedToHtml = fileUiState.contentConvertedToHtml.value,
                    readOnly = fileUiState.readOnly.value,
                    preferencesUiState = preferencesUiState,
                    fileViewModel = fileViewModel,
                    previewMarkdownRenderedToHtmlFullscreen = previewMarkdownRenderedToFullscreen,
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



