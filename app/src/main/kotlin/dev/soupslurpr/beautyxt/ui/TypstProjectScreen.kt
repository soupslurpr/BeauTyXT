package dev.soupslurpr.beautyxt.ui

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import dev.soupslurpr.beautyxt.CustomSourceDiagnostic
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.settings.PreferencesUiState
import kotlinx.coroutines.Dispatchers

@Composable
fun TypstProjectScreen(
    typstProjectViewModel: TypstProjectViewModel,
    preferencesUiState: PreferencesUiState,
    navigateUp: () -> Unit,
    previewTypstProjectRenderedToFullscreen: Boolean,
) {
    val context = LocalContext.current

    val typstProjectUiState by typstProjectViewModel.uiState.collectAsState()

    val content = typstProjectUiState.currentOpenedContent.value

    val imageRequest = ImageRequest.Builder(context = context)
        .decoderFactory(SvgDecoder.Factory())
        .data(typstProjectUiState.renderedProjectSvg.value)
        // Can't use IO because it blinks if we do.
        .dispatcher(Dispatchers.Main)
        .build()

    val svgPreviewVerticalScroll = rememberScrollState()

    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    /** This is needed in the event that the TypstProjectViewModel or TypstProjectUiState is destroyed or cleared so
     * that it automatically goes to the last screen or start screen instead of being in an invalid project.
     */
    LaunchedEffect(typstProjectUiState.projectFolderUri.value) {
        if (typstProjectUiState.projectFolderUri.value == Uri.EMPTY) {
            navigateUp()
        }
    }

    if (isPortrait) {
        Column(
            modifier = Modifier.imePadding()
        ) {
            TypstProjectTextField(
                modifier = Modifier.fillMaxSize().weight(
                    if (previewTypstProjectRenderedToFullscreen) {
                        0.00000001f
                    } else {
                        1f
                    }
                ),
                content = content,
                displayName = typstProjectUiState.currentOpenedDisplayName.value,
            ) {
                val currentOpenedPath = typstProjectUiState.currentOpenedPath.value

                typstProjectViewModel.updateProjectFileWithNewText(it, currentOpenedPath)

                typstProjectViewModel.renderProjectToSvgs()
            }

            Column(
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.rendered_typst_project_preview),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = typography.bodySmall,
                )

                ScrollableSvgDocumentPreview(
                    modifier = Modifier.verticalScroll(svgPreviewVerticalScroll).fillMaxWidth().background(Color.White)
                        .weight(1f),
                    imageRequest = imageRequest,
                )

                if (preferencesUiState.typstProjectShowWarningsAndErrors.second.value && typstProjectUiState
                        .sourceDiagnostics
                        .isNotEmpty()
                ) {
                    WarningsAndErrors(
                        modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().weight(0.25f),
                        sourceDiagnostics = typstProjectUiState.sourceDiagnostics.toList()
                    )
                }
            }
        }
    } else {
        Row(
            modifier = Modifier.imePadding()
        ) {
            TypstProjectTextField(
                modifier = Modifier.fillMaxSize().weight(
                    if (previewTypstProjectRenderedToFullscreen) {
                        0.00000001f
                    } else {
                        1f
                    }
                ),
                content = content,
                displayName = typstProjectUiState.currentOpenedDisplayName.value,
            ) {
                val currentOpenedPath = typstProjectUiState.currentOpenedPath.value

                typstProjectViewModel.updateProjectFileWithNewText(it, currentOpenedPath)

                typstProjectViewModel.renderProjectToSvgs()
            }

            Column(
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.rendered_typst_project_preview),
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = typography.bodySmall,
                )

                ScrollableSvgDocumentPreview(
                    modifier = Modifier.verticalScroll(svgPreviewVerticalScroll).fillMaxWidth().background(Color.White)
                        .weight(1f),
                    imageRequest = imageRequest,
                )

                if (preferencesUiState.typstProjectShowWarningsAndErrors.second.value && typstProjectUiState
                        .sourceDiagnostics.isNotEmpty()
                ) {
                    WarningsAndErrors(
                        modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().weight(0.25f),
                        sourceDiagnostics = typstProjectUiState.sourceDiagnostics.toList()
                    )
                }
            }
        }
    }
}

@Composable
fun TypstProjectTextField(
    modifier: Modifier = Modifier,
    content: String,
    displayName: String, // The display name might not necessarily be the file name!
    onValueChanged: (String) -> Unit
) {
    TextField(
        modifier = modifier,
        value = content,
        onValueChange = {
            onValueChanged(it)
        },
        label = {
            Text(
                text = displayName,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent
        ),
    )
}

@Composable
fun ScrollableSvgDocumentPreview(
    modifier: Modifier = Modifier,
    imageRequest: ImageRequest,
) {
    AsyncImage(
        modifier = modifier,
        model = imageRequest,
        contentDescription = null,
    )
}

@Composable
fun WarningsAndErrors(
    modifier: Modifier = Modifier,
    sourceDiagnostics: List<CustomSourceDiagnostic>,
) {
    Column(
        modifier = modifier
    ) {
        sourceDiagnostics.forEach {
            Text(it.message)
        }
    }
}