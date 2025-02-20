package oldtyxt.dev.soupslurpr.beautyxt

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat.startActivity
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.soupslurpr.beautyxt.R
import oldtyxt.dev.soupslurpr.beautyxt.constants.mimeTypeDocx
import oldtyxt.dev.soupslurpr.beautyxt.constants.mimeTypeHtml
import oldtyxt.dev.soupslurpr.beautyxt.constants.mimeTypeMarkdown
import oldtyxt.dev.soupslurpr.beautyxt.constants.mimeTypePdf
import oldtyxt.dev.soupslurpr.beautyxt.constants.mimeTypePlainText
import oldtyxt.dev.soupslurpr.beautyxt.settings.PreferencesUiState
import oldtyxt.dev.soupslurpr.beautyxt.settings.PreferencesViewModel
import oldtyxt.dev.soupslurpr.beautyxt.ui.CreditsScreen
import oldtyxt.dev.soupslurpr.beautyxt.ui.DonationScreen
import oldtyxt.dev.soupslurpr.beautyxt.ui.FileEditScreen
import oldtyxt.dev.soupslurpr.beautyxt.ui.FileViewModel
import oldtyxt.dev.soupslurpr.beautyxt.ui.LicenseScreen
import oldtyxt.dev.soupslurpr.beautyxt.ui.PlainTextAndMarkdownRustLibraryCreditsScreen
import oldtyxt.dev.soupslurpr.beautyxt.ui.PrivacyPolicyScreen
import oldtyxt.dev.soupslurpr.beautyxt.ui.SettingsScreen
import oldtyxt.dev.soupslurpr.beautyxt.ui.StartupScreen
import oldtyxt.dev.soupslurpr.beautyxt.ui.TypstProjectScreen
import oldtyxt.dev.soupslurpr.beautyxt.ui.TypstProjectViewModel
import oldtyxt.dev.soupslurpr.beautyxt.ui.TypstRustLibraryCreditsScreen
import java.time.LocalDateTime
import kotlin.random.Random

enum class BeauTyXTScreens(@StringRes val title: Int) {
    Start(title = R.string.oldtyxt_app_name),
    FileEdit(title = R.string.oldtyxt_file_editor),
    TypstProject(title = R.string.oldtyxt_typst_project),
    Settings(title = R.string.oldtyxt_settings),
    License(title = R.string.oldtyxt_license),
    PrivacyPolicy(title = R.string.oldtyxt_privacy_policy),
    Credits(title = R.string.oldtyxt_credits),
    PlainTextAndMarkdownRustLibraryCredits(title = R.string.oldtyxt_plain_text_and_markdown_rust_library_credits),
    TypstRustLibraryCredits(title = R.string.oldtyxt_typst_rust_library_credits),
    Donation(title = R.string.oldtyxt_donation),
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

    printOptionsDialogShown: Boolean,
    onPrintOptionsDialogDismissRequest: () -> Unit,
    onPrintOptionsExportDropdownMenuItemClicked: () -> Unit,
    printOptionsDialogContent: @Composable () -> Unit,
    printOptionsDialogConfirmButton: @Composable () -> Unit,
    printOptionsDialogDismissButton: @Composable () -> Unit,

    exportAsDialogShown: Boolean,
    onExportAsDialogDismissRequest: () -> Unit,
    onExportAsExportDropdownMenuItemClicked: () -> Unit,
    exportAsDialogContent: @Composable () -> Unit,
    exportAsDialogConfirmButton: @Composable () -> Unit,
    exportAsDialogDismissButton: @Composable () -> Unit,

    onShareExportDropdownMenuItemClicked: () -> Unit,

    shareAsDialogShown: Boolean,
    onShareAsDialogDismissRequest: () -> Unit,
    onShareAsExportDropdownMenuItemClicked: () -> Unit,
    shareAsDialogContent: @Composable () -> Unit,
    shareAsDialogConfirmButton: @Composable () -> Unit,
    shareAsDialogDismissButton: @Composable () -> Unit,

    deleteFileDialogShown: Boolean,
    onDeleteFileDialogDismissRequest: () -> Unit,
    onDeleteFileDropdownMenuItemClicked: () -> Unit,
    deleteFileDialogContent: @Composable () -> Unit,
    deleteFileDialogConfirmButton: @Composable () -> Unit,
    deleteFileDialogDismissButton: @Composable () -> Unit,

    onTypstProjectOpenAnotherFileInTheProjectButtonClicked: () -> Unit,
    onTypstProjectCreateAndOpenAnotherFileInTheProjectButtonClicked: () -> Unit,

    readOnly: Boolean,
    mimeType: String,
    onPreviewMarkdownRenderedToFullscreenButtonClicked: () -> Unit,
    onPreviewTypstProjectRenderedToFullscreenButtonClicked: () -> Unit,
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
                IconButton(onClick = navigateUp) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.oldtyxt_back_button)
                    )
                }
            }
        },
        actions = {
            val isCurrentScreenTypstProject = currentScreen==BeauTyXTScreens.TypstProject
            if (currentScreen==BeauTyXTScreens.FileEdit || isCurrentScreenTypstProject) {
                // Read only checking not implemented yet for Typst projects
                if (!isCurrentScreenTypstProject && readOnly) {
                    Text(text = stringResource(R.string.oldtyxt_read_only))
                }
                if (mimeType==mimeTypeMarkdown) {
                    if (preferencesUiState.experimentalFeaturePreviewRenderedMarkdownInFullscreen.second.value) {
                        IconButton(
                            onClick = onPreviewMarkdownRenderedToFullscreenButtonClicked,
                            content = {
                                Icon(
                                    painter = painterResource(R.drawable.baseline_preview_24),
                                    contentDescription = stringResource(R.string.oldtyxt_preview_markdown_rendered_to_html)
                                )
                            }
                        )
                    }
                }
                if (isCurrentScreenTypstProject) {
                    if (preferencesUiState.experimentalFeaturePreviewRenderedTypstProjectInFullscreen.second.value) {
                        IconButton(
                            onClick = onPreviewTypstProjectRenderedToFullscreenButtonClicked,
                            content = {
                                Icon(
                                    painter = painterResource(R.drawable.baseline_preview_24),
                                    contentDescription = stringResource(R.string.oldtyxt_toggle_previewing_typst_project_in_fullscreen)
                                )
                            }
                        )
                    }
                    IconButton(
                        onClick = onTypstProjectCreateAndOpenAnotherFileInTheProjectButtonClicked,
                        content = {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.oldtyxt_create_and_edit_another_file_in_the_typst_project)
                            )
                        }
                    )
                    IconButton(
                        onClick = onTypstProjectOpenAnotherFileInTheProjectButtonClicked,
                        content = {
                            Icon(
                                painter = painterResource(R.drawable.baseline_file_open_24),
                                contentDescription = stringResource(R.string.oldtyxt_edit_another_file_in_the_typst_project),
                            )
                        }
                    )
                }
                IconButton(
                    onClick = onDropDownMenuButtonClicked,
                    content = {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            stringResource(R.string.oldtyxt_options_dropdown_menu)
                        )
                    }
                )
                DropdownMenu(
                    expanded = dropDownMenuShown,
                    onDismissRequest = { onDropDownMenuDismissRequest() },
                    modifier = Modifier.width(225.dp)
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.oldtyxt_file_info),
                                style = dropDownMenuItemTextStyle
                            )
                        },
                        onClick = { onFileInfoDropdownMenuItemClicked() },
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.Info, contentDescription = null)
                        },
                        enabled = !isCurrentScreenTypstProject
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.oldtyxt_export),
                                style = dropDownMenuItemTextStyle
                            )
                        },
                        onClick = { onExportDropdownMenuItemClicked() },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.baseline_export_notes_24),
                                contentDescription = null
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.oldtyxt_delete_file),
                                style = dropDownMenuItemTextStyle
                            )
                        },
                        onClick = { onDeleteFileDropdownMenuItemClicked() },
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.Delete, contentDescription = null)
                        },
                        enabled = if (isCurrentScreenTypstProject) {
                            false
                        } else {
                            !readOnly
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.oldtyxt_settings),
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
                    modifier = Modifier.width(225.dp)
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.oldtyxt_print),
                                style = dropDownMenuItemTextStyle
                            )
                        },
                        onClick = { onPrintOptionsExportDropdownMenuItemClicked() },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.baseline_print_24),
                                contentDescription = null
                            )
                        },
                        enabled = !isCurrentScreenTypstProject,
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.oldtyxt_export_as),
                                style = dropDownMenuItemTextStyle
                            )
                        },
                        onClick = { onExportAsExportDropdownMenuItemClicked() },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.baseline_save_as_24),
                                contentDescription = null
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.oldtyxt_share),
                                style = dropDownMenuItemTextStyle
                            )
                        },
                        onClick = { onShareExportDropdownMenuItemClicked() },
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.Share, contentDescription = null)
                        },
                        enabled = !isCurrentScreenTypstProject,
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.oldtyxt_share_as),
                                style = dropDownMenuItemTextStyle
                            )
                        },
                        onClick = { onShareAsExportDropdownMenuItemClicked() },
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.Share, contentDescription = null)
                        },
                        enabled = !isCurrentScreenTypstProject,
                    )
                }
                if (fileInfoShown) {
                    AlertDialog(
                        onDismissRequest = onFileInfoDialogDismissRequest,
                        confirmButton = fileInfoDialogConfirmButton,
                        title = {
                            Text(
                                text = stringResource(R.string.oldtyxt_file_info),
                                style = typography.headlineSmall,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                        },
                        text = {
                            fileInfoDialogContent()
                        }
                    )
                }
                if (printOptionsDialogShown) {
                    AlertDialog(
                        onDismissRequest = onPrintOptionsDialogDismissRequest,
                        confirmButton = printOptionsDialogConfirmButton,
                        dismissButton = printOptionsDialogDismissButton,
                        title = {
                            Text(
                                text = stringResource(R.string.oldtyxt_print_options),
                                style = typography.headlineSmall,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                        },
                        text = {
                            printOptionsDialogContent()
                        }
                    )
                }
                if (exportAsDialogShown) {
                    AlertDialog(
                        onDismissRequest = onExportAsDialogDismissRequest,
                        confirmButton = exportAsDialogConfirmButton,
                        dismissButton = exportAsDialogDismissButton,
                        title = {
                            Text(
                                text = stringResource(R.string.oldtyxt_export_as),
                                style = typography.headlineSmall,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                        },
                        text = {
                            exportAsDialogContent()
                        }
                    )
                }
                if (shareAsDialogShown) {
                    AlertDialog(
                        onDismissRequest = onShareAsDialogDismissRequest,
                        confirmButton = shareAsDialogConfirmButton,
                        dismissButton = shareAsDialogDismissButton,
                        title = {
                            Text(
                                text = stringResource(R.string.oldtyxt_share_as),
                                style = typography.headlineSmall,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                        },
                        text = {
                            shareAsDialogContent()
                        }
                    )
                }
                if (deleteFileDialogShown) {
                    AlertDialog(
                        onDismissRequest = onDeleteFileDialogDismissRequest,
                        confirmButton = deleteFileDialogConfirmButton,
                        dismissButton = deleteFileDialogDismissButton,
                        title = {
                            Text(
                                text = stringResource(R.string.oldtyxt_delete_file),
                                style = typography.headlineSmall,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                        },
                        text = {
                            deleteFileDialogContent()
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
fun FileTypeSelectionDialogItem(
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
    modifier: Modifier,
    fileViewModel: FileViewModel,
    typstProjectViewModel: TypstProjectViewModel,
    preferencesViewModel: PreferencesViewModel,
    isActionViewOrEdit: Boolean,
    isActionSend: Boolean,
) {
    val navController = rememberNavController()

    val backStackEntry by navController.currentBackStackEntryAsState()

    val currentScreen = BeauTyXTScreens.valueOf(
        backStackEntry?.destination?.route ?: BeauTyXTScreens.Start.name
    )

    val isCurrentScreenTypstProject = currentScreen==BeauTyXTScreens.TypstProject

    val context = LocalContext.current

    val fileUiState by fileViewModel.uiState.collectAsState()

    val typstProjectUiState by typstProjectViewModel.uiState.collectAsState()

    val preferencesUiState by preferencesViewModel.uiState.collectAsState()

    var previewMarkdownRenderedToFullscreen by rememberSaveable { mutableStateOf(false) }

    val openFileLauncher = rememberLauncherForActivityResult(contract = OpenDocument()) {
        if (it!=null) {
            fileViewModel.bindIsolatedService(
                it,
                preferencesUiState.renderMarkdown.second.value,
                previewMarkdownRenderedToFullscreen
            )
            fileViewModel.setReadOnly(false)
            fileViewModel.setUri(it, context)
            navController.navigate(BeauTyXTScreens.FileEdit.name)
        }
    }

    val createTxtFileLauncher =
        rememberLauncherForActivityResult(contract = CreateDocument(mimeTypePlainText)) {
            if (it!=null) {
                fileViewModel.bindIsolatedService(
                    it,
                    preferencesUiState.renderMarkdown.second.value,
                    previewMarkdownRenderedToFullscreen
                )
                fileViewModel.setReadOnly(false)
                fileViewModel.setUri(it, context)
                navController.navigate(BeauTyXTScreens.FileEdit.name)
            }
        }

    val createMdFileLauncher =
        rememberLauncherForActivityResult(contract = CreateDocument(mimeTypeMarkdown)) {
            if (it!=null) {
                fileViewModel.bindIsolatedService(
                    it,
                    preferencesUiState.renderMarkdown.second.value,
                    previewMarkdownRenderedToFullscreen
                )
                fileViewModel.setReadOnly(false)
                fileViewModel.setUri(it, context)
                navController.navigate(BeauTyXTScreens.FileEdit.name)
            }
        }

    val openTypstProjectLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts
            .OpenDocumentTree()
    ) { projectFolderUri ->
        if (projectFolderUri!=null) {
            typstProjectViewModel.bindService(projectFolderUri)
            navController.navigate(BeauTyXTScreens.TypstProject.name)
        }
    }

    val createTypstProjectLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts
            .OpenDocumentTree()
    ) { projectFolderUri ->
        if (projectFolderUri!=null) {
            typstProjectViewModel.bindService(projectFolderUri)
            navController.navigate(BeauTyXTScreens.TypstProject.name)
        }
    }

    var fileInfoDialogShown by rememberSaveable { mutableStateOf(false) }

    var dropDownMenuShown by rememberSaveable { mutableStateOf(false) }

    var exportDropdownMenuShown by rememberSaveable { mutableStateOf(false) }

    var printOptionsDialogShown by rememberSaveable { mutableStateOf(false) }

    var exportAsDialogShown by rememberSaveable { mutableStateOf(false) }

    var shareAsDialogShown by rememberSaveable { mutableStateOf(false) }

    var deleteFileDialogShown by rememberSaveable { mutableStateOf(false) }

    val exportAsHtmlFileLauncher =
        rememberLauncherForActivityResult(contract = CreateDocument(mimeTypeHtml)) {
            if (it!=null) {
                fileViewModel.exportAsHtml(it, context)
            }
        }

    val exportAsDocxFileLauncher =
        rememberLauncherForActivityResult(contract = CreateDocument(mimeTypeDocx)) {
            if (it!=null) {
                fileViewModel.exportAsDocx(it, context)
            }
        }

    val exportTypstProjectAsPdfFileLauncher = rememberLauncherForActivityResult(
        contract = CreateDocument(
            mimeTypePdf
        )
    ) {
        if (it!=null) {
            typstProjectViewModel.exportDocumentToPdf(it, context)
        }
    }

    val setTypstCurrentOpenedPathLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts
            .OpenDocument
                ()
    ) {
        if (it!=null) {
            typstProjectViewModel.refreshProjectFiles(context)
            typstProjectViewModel.setCurrentOpenedPath(it, context.contentResolver)
            typstProjectViewModel.setTypstProjectFileText(
                typstProjectUiState.currentOpenedPath.value
            )
        }
    }

    val createNewTypstProjectFileAndSetCurrentOpenedPathLauncher =
        rememberLauncherForActivityResult(
            contract =
            // .typ files don't have an official MIME type as far as I know as of 11/5/2023
            CreateDocument("typst/application")
        ) {
            if (it!=null) {
                typstProjectViewModel.refreshProjectFiles(context)
                typstProjectViewModel.setCurrentOpenedPath(it, context.contentResolver)
                typstProjectViewModel.refreshProjectFiles(context)
                typstProjectViewModel.setTypstProjectFileText(
                    typstProjectUiState.currentOpenedPath.value
                )
            }
        }

    var previewTypstProjectRenderedToFullscreen by rememberSaveable { mutableStateOf(false) }

    val randomValue = Random.nextInt(0, 11)
    val splashMessage = rememberSaveable {
        when (randomValue) {
            0 -> "Text, but beautiful."
            1 -> "Textacular!"
            2 -> "In Rust We Trust."
            3, 4, 5 -> "Supremely sandboxed!"
            6, 7 -> "Cares about your security."
            8 -> "OldTyXT (deprecated)"
            else -> "Text, but beautiful."
        }
    }

    Scaffold(
        topBar = {
            var exportAsSelectedFileType by rememberSaveable { mutableStateOf("") }

            var shareAsSelectedFileType by rememberSaveable { mutableStateOf("") }

            var marginLeft by rememberSaveable { mutableStateOf("1") }
            val isLeftMarginError = marginLeft.toFloatOrNull()==null

            var marginRight by rememberSaveable { mutableStateOf("1") }
            val isRightMarginError = marginRight.toFloatOrNull()==null

            var marginTop by rememberSaveable { mutableStateOf("1") }
            val isTopMarginError = marginTop.toFloatOrNull()==null

            var marginBottom by rememberSaveable { mutableStateOf("1") }
            val isBottomMarginError = marginBottom.toFloatOrNull()==null

            val isPrintOptionsConfirmButtonEnabled =
                !isLeftMarginError and !isRightMarginError and !isTopMarginError
                    .and(!isBottomMarginError)

            BeauTyXTAppBar(
                currentScreen = currentScreen,
                canNavigateBack = navController.previousBackStackEntry!=null,
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
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        FileInfoDialogItem(
                            info = stringResource(id = R.string.oldtyxt_name),
                            value = fileUiState.name.value
                        )
                        FileInfoDialogItem(
                            info = stringResource(id = R.string.oldtyxt_size),
                            value = fileUiState.size.value.toString() + " " + stringResource(id = R.string.oldtyxt_bytes)
                        )
                        fileUiState.mimeType.value.let {
                            FileInfoDialogItem(
                                info = stringResource(id = R.string.oldtyxt_mime_type),
                                it
                            )
                        }
                        FileInfoDialogItem(
                            info = stringResource(R.string.oldtyxt_words),
                            value = fileUiState.wordCount.value.toString()
                        )
                        FileInfoDialogItem(
                            info = stringResource(R.string.oldtyxt_characters),
                            value = fileUiState.characterCount.value.toString()
                        )
                    }
                },
                fileInfoDialogConfirmButton = {
                    TextButton(
                        onClick = {
                            fileInfoDialogShown = false
                        },
                        content = {
                            Text(
                                text = stringResource(R.string.oldtyxt_confirm_)
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

                printOptionsDialogShown = printOptionsDialogShown,
                onPrintOptionsDialogDismissRequest = { printOptionsDialogShown = false },
                onPrintOptionsExportDropdownMenuItemClicked = {
                    printOptionsDialogShown = !printOptionsDialogShown
                    dropDownMenuShown = false
                    exportDropdownMenuShown = false
                },
                printOptionsDialogContent = {
                    Column(
                        modifier = Modifier
                            .selectableGroup()
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = marginLeft,
                            onValueChange = {
                                marginLeft = it.trim()
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.oldtyxt_left_margin) + if (isLeftMarginError) {
                                        " " + stringResource(R.string.oldtyxt_invalid_size)
                                    } else {
                                        ""
                                    }
                                )
                            },
                            prefix = {
                                Text(
                                    text = stringResource(R.string.oldtyxt_inches) + ":"
                                )
                            },
                            isError = isLeftMarginError,
                            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            value = marginRight,
                            onValueChange = {
                                marginRight = it.trim()
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.oldtyxt_right_margin) + if (isRightMarginError) {
                                        " " + stringResource(R.string.oldtyxt_invalid_size)
                                    } else {
                                        ""
                                    }
                                )
                            },
                            prefix = {
                                Text(
                                    text = stringResource(R.string.oldtyxt_inches) + ":"
                                )
                            },
                            isError = isRightMarginError,
                            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = marginTop,
                            onValueChange = {
                                marginTop = it.trim()
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.oldtyxt_top_margin) + if (isTopMarginError) {
                                        " " + stringResource(R.string.oldtyxt_invalid_size)
                                    } else {
                                        ""
                                    }
                                )
                            },
                            prefix = {
                                Text(
                                    text = stringResource(R.string.oldtyxt_inches) + ":"
                                )
                            },
                            isError = isTopMarginError,
                            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = marginBottom,
                            onValueChange = {
                                marginBottom = it.trim()
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.oldtyxt_bottom_margin) + if (isBottomMarginError) {
                                        " " + stringResource(R.string.oldtyxt_invalid_size)
                                    } else {
                                        ""
                                    }
                                )
                            },
                            prefix = {
                                Text(
                                    text = stringResource(R.string.oldtyxt_inches) + ":"
                                )
                            },
                            isError = isBottomMarginError,
                            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
                        )
                    }
                },
                printOptionsDialogConfirmButton = {
                    TextButton(
                        onClick = {
                            var mWebView: WebView? = null

                            // Create a WebView object specifically for printing
                            val webView = WebView(context)
                            webView.webViewClient = object : WebViewClient() {

                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ) =
                                    false

                                override fun onPageFinished(view: WebView, url: String) {
                                    createWebPrintJob(view)
                                    mWebView = null
                                }

                                fun createWebPrintJob(webView: WebView) {

                                    // Get a PrintManager instance
                                    (context.getSystemService(Context.PRINT_SERVICE) as? PrintManager)?.let { printManager ->

                                        val jobName =
                                            fileUiState.name.value.substringBeforeLast(".")

                                        // Get a print adapter instance
                                        val printAdapter =
                                            webView.createPrintDocumentAdapter(jobName)

                                        // Create a print job with name and adapter instance
                                        printManager.print(
                                            jobName,
                                            printAdapter,
                                            PrintAttributes.Builder().build()
                                        )
                                    }
                                }
                            }

                            when (fileUiState.mimeType.value) {
                                mimeTypeMarkdown -> {
                                    fileViewModel.setMarkdownToHtml()
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
                                            @page {
                                                margin-left: ${marginLeft}in;
                                                margin-right: ${marginRight}in;
                                                margin-top: ${marginTop}in;
                                                margin-bottom: ${marginBottom}in;
                                            }
                                        </style>
                                    </head>
                                    <body>
                                        ${fileUiState.contentConvertedToHtml.value}
                                    </body>
                                </html>
                            """.trimIndent()
                                    webView.loadData(htmlDocument, mimeTypeHtml, "UTF-8")
                                }

                                else -> {
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
                                            @page {
                                                margin-left: ${marginLeft}in;
                                                margin-right: ${marginRight}in;
                                                margin-top: ${marginTop}in;
                                                margin-bottom: ${marginBottom}in;
                                            }
                                        </style>
                                    </head>
                                    <body>
${
                                        fileUiState.content.value
                                            .replace("&", "&amp;")
                                            .replace("<", "&lt;")
                                            .replace(">", "&gt;")
                                            .replace("%", "%25")
                                            .replace("#", "%23")
                                            .replace("\n", "<br>")
                                    }
                                    </body>
                                </html>
                            """.trimIndent()
                                    webView.loadData(htmlDocument, mimeTypeHtml, "UTF-8")
                                }
                            }

                            // Keep a reference to WebView object until you pass the PrintDocumentAdapter
                            // to the PrintManager
                            mWebView = webView
                            printOptionsDialogShown = false
                        },
                        enabled = isPrintOptionsConfirmButtonEnabled,
                        content = {
                            Text(
                                text = stringResource(R.string.oldtyxt_confirm_)
                            )
                        }
                    )
                },
                printOptionsDialogDismissButton = {
                    TextButton(
                        onClick = {
                            printOptionsDialogShown = false
                        },
                        content = {
                            Text(
                                text = stringResource(R.string.oldtyxt_cancel)
                            )
                        }
                    )
                },

                exportAsDialogShown = exportAsDialogShown,
                onExportAsDialogDismissRequest = {
                    exportAsDialogShown = false
                    exportAsSelectedFileType = ""
                },
                onExportAsExportDropdownMenuItemClicked = {
                    exportAsDialogShown = !exportAsDialogShown
                    dropDownMenuShown = false
                    exportDropdownMenuShown = false
                },
                exportAsDialogContent = {
                    Column(
                        modifier = Modifier
                            .selectableGroup()
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        if (fileUiState.mimeType.value==mimeTypeMarkdown) {
                            FileTypeSelectionDialogItem(
                                fileTypeText = stringResource(R.string.oldtyxt_html),
                                selected = exportAsSelectedFileType==mimeTypeHtml,
                                onClickRadioButton = {
                                    exportAsSelectedFileType = mimeTypeHtml
                                }
                            )
                        }
                        if ((preferencesUiState.experimentalFeatureExportMarkdownToDocx.second.value and
                                    (fileUiState.mimeType.value==mimeTypeMarkdown)) or
                            (fileUiState.mimeType.value!=mimeTypeMarkdown && !isCurrentScreenTypstProject)
                        ) {
                            FileTypeSelectionDialogItem(
                                fileTypeText = stringResource(R.string.oldtyxt_docx),
                                selected = exportAsSelectedFileType==mimeTypeDocx,
                                onClickRadioButton = {
                                    exportAsSelectedFileType = mimeTypeDocx
                                }
                            )
                        }
                        if (isCurrentScreenTypstProject) {
                            FileTypeSelectionDialogItem(
                                fileTypeText = stringResource(R.string.oldtyxt_pdf),
                                selected = exportAsSelectedFileType==mimeTypePdf,
                                onClickRadioButton = {
                                    exportAsSelectedFileType = mimeTypePdf
                                }
                            )
                        }
                    }
                },
                exportAsDialogConfirmButton = {
                    TextButton(
                        onClick = {
                            when (exportAsSelectedFileType) {
                                mimeTypeHtml -> exportAsHtmlFileLauncher.launch(
                                    fileUiState.name.value.substringBeforeLast(".")
                                )

                                mimeTypeDocx -> exportAsDocxFileLauncher.launch(
                                    fileUiState.name.value.substringBeforeLast(".")
                                )

                                mimeTypePdf -> exportTypstProjectAsPdfFileLauncher.launch(
                                    if (isCurrentScreenTypstProject) {
                                        "main.pdf"
                                    } else {
                                        fileUiState.name.value.substringBeforeLast(".")
                                    }
                                )
                            }
                            exportAsDialogShown = false
                            exportAsSelectedFileType = ""
                        },
                        enabled = exportAsSelectedFileType!="",
                        content = {
                            Text(
                                text = stringResource(R.string.oldtyxt_confirm_)
                            )
                        }
                    )
                },
                exportAsDialogDismissButton = {
                    TextButton(
                        onClick = {
                            exportAsDialogShown = false
                            exportAsSelectedFileType = ""
                        },
                        content = {
                            Text(
                                text = stringResource(R.string.oldtyxt_cancel)
                            )
                        }
                    )
                },

                onShareExportDropdownMenuItemClicked = {
                    var sendIntent = Intent()

                    sendIntent = when (fileUiState.mimeType.value) {
                        mimeTypePlainText -> {
                            sendIntent.apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, fileUiState.content.value)
                                type = mimeTypePlainText
                            }
                        }

                        mimeTypeMarkdown -> {
                            sendIntent.apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, fileUiState.content.value)
                                fileViewModel.setMarkdownToHtml()
                                putExtra(
                                    Intent.EXTRA_HTML_TEXT,
                                    fileUiState.contentConvertedToHtml.value
                                )
                                type = mimeTypeMarkdown
                            }
                        }

                        else -> {
                            sendIntent.apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, fileUiState.content.value)
                                type = fileUiState.mimeType.value
                            }
                        }
                    }

                    val shareIntent = Intent.createChooser(sendIntent, null)
                    context.startActivity(shareIntent, ActivityOptionsCompat.makeBasic().toBundle())

                    dropDownMenuShown = false
                    exportDropdownMenuShown = false
                },

                shareAsDialogShown = shareAsDialogShown,
                onShareAsDialogDismissRequest = {
                    shareAsDialogShown = false
                    shareAsSelectedFileType = ""
                },
                onShareAsExportDropdownMenuItemClicked = {
                    shareAsDialogShown = !shareAsDialogShown
                    dropDownMenuShown = false
                    exportDropdownMenuShown = false
                },
                shareAsDialogContent = {
                    Column(
                        modifier = Modifier
                            .selectableGroup()
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        if (isCurrentScreenTypstProject) {
                            FileTypeSelectionDialogItem(
                                fileTypeText = stringResource(R.string.oldtyxt_pdf),
                                selected = shareAsSelectedFileType==mimeTypePdf,
                                onClickRadioButton = {
                                    shareAsSelectedFileType = mimeTypePdf
                                }
                            )
                        } else when (fileUiState.mimeType.value) {
                            mimeTypePlainText -> {
                                FileTypeSelectionDialogItem(
                                    fileTypeText = stringResource(R.string.oldtyxt_txt),
                                    selected = shareAsSelectedFileType==mimeTypePlainText,
                                    onClickRadioButton = {
                                        shareAsSelectedFileType = mimeTypePlainText
                                    }
                                )
                            }

                            mimeTypeMarkdown -> {
                                FileTypeSelectionDialogItem(
                                    fileTypeText = stringResource(R.string.oldtyxt_md),
                                    selected = shareAsSelectedFileType==mimeTypeMarkdown,
                                    onClickRadioButton = {
                                        shareAsSelectedFileType = mimeTypeMarkdown
                                    }
                                )
                            }

                            else -> {
                                FileTypeSelectionDialogItem(
                                    fileTypeText = fileUiState.mimeType.value,
                                    selected = shareAsSelectedFileType==fileUiState.mimeType.value,
                                    onClickRadioButton = {
                                        shareAsSelectedFileType = fileUiState.mimeType.value
                                    }
                                )
                            }

                        }
                    }
                },
                shareAsDialogConfirmButton = {
                    TextButton(
                        onClick = {
                            var sendIntent = Intent()

                            sendIntent = when (fileUiState.mimeType.value) {
                                mimeTypePlainText -> {
                                    sendIntent.apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_STREAM, fileUiState.uri.value)
                                        type = mimeTypePlainText
                                    }
                                }

                                mimeTypeMarkdown -> {
                                    sendIntent.apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_STREAM, fileUiState.uri.value)
                                        type = mimeTypeMarkdown
                                    }
                                }

                                else -> {
                                    sendIntent.apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_STREAM, fileUiState.uri.value)
                                        type = fileUiState.mimeType.value
                                    }
                                }
                            }

                            val shareIntent = Intent.createChooser(sendIntent, null)
                            context.startActivity(
                                shareIntent,
                                ActivityOptionsCompat.makeBasic().toBundle()
                            )

                            shareAsDialogShown = false
                            shareAsSelectedFileType = ""
                        },
                        enabled = shareAsSelectedFileType!="",
                        content = {
                            Text(
                                text = stringResource(R.string.oldtyxt_confirm_)
                            )
                        }
                    )
                },
                shareAsDialogDismissButton = {
                    TextButton(
                        onClick = {
                            shareAsDialogShown = false
                            shareAsSelectedFileType = ""
                        },
                        content = {
                            Text(
                                text = stringResource(R.string.oldtyxt_cancel)
                            )
                        }
                    )
                },

                deleteFileDialogShown = deleteFileDialogShown,
                onDeleteFileDialogDismissRequest = { deleteFileDialogShown = false },
                onDeleteFileDropdownMenuItemClicked = {
                    deleteFileDialogShown = !deleteFileDialogShown
                    dropDownMenuShown = false
                    exportDropdownMenuShown = false
                },
                deleteFileDialogContent = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = stringResource(R.string.oldtyxt_delete_file_dialog_text),
                            fontWeight = FontWeight.Black
                        )
                    }
                },
                deleteFileDialogConfirmButton = {
                    TextButton(
                        onClick = {
                            fileViewModel.deleteFile(context = context)
                            deleteFileDialogShown = false
                        },
                        content = {
                            Text(
                                text = stringResource(R.string.oldtyxt_confirm_)
                            )
                        }
                    )
                },
                deleteFileDialogDismissButton = {
                    TextButton(
                        onClick = {
                            deleteFileDialogShown = false
                        },
                        content = {
                            Text(
                                text = stringResource(R.string.oldtyxt_cancel)
                            )
                        }
                    )
                },

                onTypstProjectOpenAnotherFileInTheProjectButtonClicked = {
                    setTypstCurrentOpenedPathLauncher.launch(arrayOf("*/*"))
                },
                onTypstProjectCreateAndOpenAnotherFileInTheProjectButtonClicked = {
                    createNewTypstProjectFileAndSetCurrentOpenedPathLauncher.launch("enter-a-file-name.typ")
                },

                readOnly = fileUiState.readOnly.value,

                mimeType = fileUiState.mimeType.value,

                onPreviewMarkdownRenderedToFullscreenButtonClicked = {
                    previewMarkdownRenderedToFullscreen = !previewMarkdownRenderedToFullscreen
                },

                onPreviewTypstProjectRenderedToFullscreenButtonClicked = {
                    previewTypstProjectRenderedToFullscreen =
                        !previewTypstProjectRenderedToFullscreen
                },

                modifier = modifier
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            // Use this instead of deeplinks so that when back is pressed
            // it goes to the app this app was opened by. Deeplinks not doing
            // that is intentional, see figure 4 at
            // https://developer.android.com/guide/navigation/principles#deep-link
            startDestination = if (isActionViewOrEdit || isActionSend) {
                BeauTyXTScreens.FileEdit.name
            } else {
                BeauTyXTScreens.Start.name
            },
            modifier = modifier.padding(
                innerPadding.calculateStartPadding(LayoutDirection.Ltr),
                innerPadding.calculateTopPadding(),
                innerPadding.calculateEndPadding(LayoutDirection.Ltr)
            ),
        ) {
            composableWithDefaultSlideTransitions(route = BeauTyXTScreens.Start) {
                StartupScreen(
                    modifier = modifier,
                    splashMessage = splashMessage,
                    onOpenTxtButtonClicked = {
                        openFileLauncher.launch(
                            arrayOf(mimeTypePlainText),
                            ActivityOptionsCompat.makeBasic(),
                        )
                    },
                    onOpenMdButtonClicked = {
                        openFileLauncher.launch(
                            arrayOf(mimeTypeMarkdown),
                            ActivityOptionsCompat.makeBasic(),
                        )
                    },
                    onOpenTypstProjectButtonClicked = {
                        openTypstProjectLauncher.launch(Uri.EMPTY)
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
                            LocalDateTime.now().toString(),
                            ActivityOptionsCompat.makeBasic()
                        )
                    },
                    onCreateMdButtonClicked = {
                        createMdFileLauncher.launch(
                            // Make default file name the current LocalDateTime, and for devices
                            // which don't support LocalDateTime, make it blank.
                            LocalDateTime.now().toString(),
                            ActivityOptionsCompat.makeBasic()
                        )
                    },
                    onCreateTypstProjectButtonClicked = {
                        createTypstProjectLauncher.launch(Uri.EMPTY)
                    },
                    onSettingsButtonClicked = {
                        navController.navigate(BeauTyXTScreens.Settings.name)
                    },
                    fileViewModel = fileViewModel,
                    preferencesUiState = preferencesUiState,
                    typstProjectViewModel = typstProjectViewModel,
                )
            }
            composableWithDefaultSlideTransitions(route = BeauTyXTScreens.FileEdit) {
                FileEditScreen(
                    name = fileUiState.name.value,
                    onContentChanged = { content ->
                        fileViewModel.updateContent(content)
                        fileViewModel.setContentToUri(
                            uri = fileUiState.uri.value,
                            context = context
                        )
                        when (fileUiState.mimeType.value) {
                            mimeTypeMarkdown -> if (preferencesUiState.renderMarkdown.second.value) {
                                fileViewModel.setMarkdownToHtml()
                            }
                        }
                        fileViewModel.setSizeFromUri(uri = fileUiState.uri.value, context = context)
                        fileViewModel.setWordCount()
                        fileViewModel.setCharacterCount()
                    },
                    content = fileUiState.content.value,
                    mimeType = fileUiState.mimeType.value,
                    contentConvertedToHtml = fileUiState.contentConvertedToHtml.value,
                    readOnly = fileUiState.readOnly.value,
                    preferencesUiState = preferencesUiState,
                    fileViewModel = fileViewModel,
                    fileUiState = fileUiState,
                    previewMarkdownRenderedToHtmlFullscreen = previewMarkdownRenderedToFullscreen,
                    navigateUp = { navController.navigateUp() }
                )
            }
            composableWithDefaultSlideTransitions(route = BeauTyXTScreens.TypstProject) {
                TypstProjectScreen(
                    typstProjectViewModel = typstProjectViewModel,
                    preferencesUiState = preferencesUiState,
                    navigateUp = { navController.navigateUp() },
                    previewTypstProjectRenderedToFullscreen = previewTypstProjectRenderedToFullscreen
                )
            }
            composableWithDefaultSlideTransitions(route = BeauTyXTScreens.Settings) {
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
                    preferencesViewModel = preferencesViewModel,
                    onDonationSettingsItemClicked = {
                        navController.navigate(BeauTyXTScreens.Donation.name)
                    }
                )
            }
            composableWithDefaultSlideTransitions(route = BeauTyXTScreens.License) {
                LicenseScreen()
            }
            composableWithDefaultSlideTransitions(route = BeauTyXTScreens.PrivacyPolicy) {
                PrivacyPolicyScreen()
            }
            composableWithDefaultSlideTransitions(route = BeauTyXTScreens.Credits) {
                CreditsScreen(
                    onPlainTextAndMarkdownRustLibraryCreditsButtonClicked = {
                        navController.navigate(BeauTyXTScreens.PlainTextAndMarkdownRustLibraryCredits.name)
                    },
                    onTypstRustLibraryCreditsButtonClicked = {
                        navController.navigate(BeauTyXTScreens.TypstRustLibraryCredits.name)
                    }
                )
            }
            composableWithDefaultSlideTransitions(route = BeauTyXTScreens.PlainTextAndMarkdownRustLibraryCredits) {
                PlainTextAndMarkdownRustLibraryCreditsScreen()
            }
            composableWithDefaultSlideTransitions(route = BeauTyXTScreens.TypstRustLibraryCredits) {
                TypstRustLibraryCreditsScreen()
            }
            composableWithDefaultSlideTransitions(route = BeauTyXTScreens.Donation) {
                DonationScreen()
            }
        }
    }
}

fun NavGraphBuilder.composableWithDefaultSlideTransitions(
    route: BeauTyXTScreens,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    enterTransition: @JvmSuppressWildcards (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = null,
    exitTransition: @JvmSuppressWildcards (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = null,
    popEnterTransition: @JvmSuppressWildcards (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = enterTransition,
    popExitTransition: @JvmSuppressWildcards (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = exitTransition,
    sizeTransform: @JvmSuppressWildcards (AnimatedContentTransitionScope<NavBackStackEntry>.() -> SizeTransform?)? = null,
    content: @Composable (AnimatedContentScope.(NavBackStackEntry) -> Unit),
) {
    composable(route.name, arguments, deepLinks, if (enterTransition==null) {
        {
            getEnterTransition(initialState, targetState)
        }
    } else {
        enterTransition
    }, if (exitTransition==null) {
        {
            getExitTransition(initialState, targetState)
        }
    } else {
        exitTransition
    }, if (popEnterTransition==null) {
        {
            getEnterTransition(initialState, targetState)
        }
    } else {
        popEnterTransition
    }, if (popExitTransition==null) {
        {
            getExitTransition(initialState, targetState)
        }
    } else {
        popExitTransition
    }, sizeTransform, content)
}

fun getEnterTransition(
    initialState: NavBackStackEntry,
    targetState: NavBackStackEntry,
): EnterTransition {
    val initialNavRoute = getStateNavRoute(initialState)
    val targetNavRoute = getStateNavRoute(targetState)
    val isNavAnimationVertical = false

    return if ((initialNavRoute!=null) && (targetNavRoute!=null)) {
        val isInitialNavRouteGreaterThanTarget =
            initialNavRoute.ordinal > targetNavRoute.ordinal

        slideIn {
            if (isNavAnimationVertical) {
                IntOffset(
                    0,
                    if (isInitialNavRouteGreaterThanTarget) {
                        -it.height
                    } else {
                        it.height
                    }
                )
            } else {
                IntOffset(
                    if (isInitialNavRouteGreaterThanTarget) {
                        -it.width
                    } else {
                        it.width
                    },
                    0
                )
            }
        } + fadeIn()
    } else {
        EnterTransition.None
    }
}

fun getExitTransition(
    initialState: NavBackStackEntry,
    targetState: NavBackStackEntry,
): ExitTransition {
    val initialNavRoute = getStateNavRoute(initialState)
    val targetNavRoute = getStateNavRoute(targetState)
    val isNavAnimationVertical = false

    return if ((initialNavRoute!=null) && (targetNavRoute!=null)) {
        val isInitialNavRouteGreaterThanTarget =
            initialNavRoute.ordinal > targetNavRoute.ordinal

        slideOut {
            if (isNavAnimationVertical) {
                IntOffset(
                    0,
                    if (isInitialNavRouteGreaterThanTarget) {
                        it.height
                    } else {
                        -it.height
                    }
                )
            } else {
                IntOffset(
                    if (isInitialNavRouteGreaterThanTarget) {
                        it.width
                    } else {
                        -it.width
                    },
                    0
                )
            }
        } + fadeOut()
    } else {
        ExitTransition.None
    }
}

fun getStateNavRoute(state: NavBackStackEntry): BeauTyXTScreens? {
    state.destination.route?.let { return BeauTyXTScreens.valueOf(it) }
    return null
}
