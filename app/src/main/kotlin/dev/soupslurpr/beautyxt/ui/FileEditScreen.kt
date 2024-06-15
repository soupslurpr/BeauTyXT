package dev.soupslurpr.beautyxt.ui

import android.content.res.Configuration
import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.constants.mimeTypeMarkdown
import dev.soupslurpr.beautyxt.data.FileUiState
import dev.soupslurpr.beautyxt.settings.PreferencesUiState

/**
 * Composable for editing a file in plain text.
 */
@Composable
fun FileEditScreen(
    name: String,
    onContentChanged: (String) -> Unit,
    content: String,
    mimeType: String,
    contentConvertedToHtml: String,
    readOnly: Boolean,
    preferencesUiState: PreferencesUiState,
    fileViewModel: FileViewModel,
    fileUiState: FileUiState,
    previewMarkdownRenderedToHtmlFullscreen: Boolean,
    navigateUp: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val textColor = colorScheme.onBackground
    val renderedMarkdownVerticalScrollState = rememberScrollState()
    val textFieldVerticalScrollState = rememberScrollState()
    val textStyle = typography.bodyLarge
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    LaunchedEffect(key1 = previewMarkdownRenderedToHtmlFullscreen) {
        /** This is so the markdown render updates when disabling render markdown, making a change,
         * and then turning it on again. Or else it only updates after a character gets typed.
         * Its also for updating the html when previewing using the fullscreen markdown preview
         * experimental feature while having render markdown at the bottom half of the screen off.*/
        if (
            (preferencesUiState.renderMarkdown.second.value or previewMarkdownRenderedToHtmlFullscreen)
            && mimeType == mimeTypeMarkdown
        ) {
            if (fileViewModel.rustService != null) {
                fileViewModel.setMarkdownToHtml()
            }
        }
    }

    /** This is needed in the event that the FileViewModel or FileUiState is destroyed or cleared so that it
     * automatically goes to the last screen or start screen instead of viewing a blank "read only" non-existent "file"
     */
    LaunchedEffect(fileUiState.uri.value) {
        if (fileUiState.uri.value == Uri.EMPTY) {
            navigateUp()
        }
    }

    if (isPortrait) {
        Column(
            modifier = Modifier.imePadding()
        ) {
            // TODO: Move to functions to reduce unnecessary 2x code duplication. Also its very annoying. See the
            //  TypstProjectScreen.kt for an example of how to do it properly.
            if (readOnly) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(
                            if (previewMarkdownRenderedToHtmlFullscreen) {
                                0.00000001f
                            } else {
                                1f
                            }
                        )
                        .padding(TextFieldDefaults.contentPaddingWithLabel())
                        .verticalScroll(textFieldVerticalScrollState),
                ) {
                    if (previewMarkdownRenderedToHtmlFullscreen) {
                        // if we don't do this then when the preview is fullscreen the label appears
                    } else {
                        SelectionContainer {
                            Text(
                                text = name,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = typography.bodySmall.fontSize,
                                fontStyle = typography.bodySmall.fontStyle,
                                fontFamily = typography.bodySmall.fontFamily,
                            )
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = content
                        )
                    }
                }
            } else {
                TextField(
                    /** We cannot use .verticalScroll when editing is possible as the TextField currently
                     * does not scroll to the next line when pressing enter automatically with .verticalScroll.
                     * It does scroll to the next line when pressing enter without it having .verticalScroll.
                     */
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(
                            if (previewMarkdownRenderedToHtmlFullscreen) {
                                0.00000001f
                            } else {
                                1f
                            }
                        ),
                    value = content,
                    onValueChange = {
                        onContentChanged(it)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = textColor,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    label = {
                        if (previewMarkdownRenderedToHtmlFullscreen) {
                            // if we don't do this then when the preview is fullscreen the label appears
                        } else {
                            SelectionContainer {
                                Text(
                                    text = name,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    },
                    textStyle = textStyle,
                )
            }
            when (mimeType) {
                "text/markdown" -> {
                    if (preferencesUiState.renderMarkdown.second.value or previewMarkdownRenderedToHtmlFullscreen) {
                        if (previewMarkdownRenderedToHtmlFullscreen) {
                            Spacer(modifier = Modifier.padding(4.dp))
                        }
                        Text(
                            text = stringResource(R.string.rendered_markdown),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
                            style = typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                                .padding(8.dp)
                                .verticalScroll(renderedMarkdownVerticalScrollState)
                        ) {
                            AndroidView(
                                factory = { context ->
                                    WebView(context).apply {
                                        settings.javaScriptEnabled = false // disable JavaScript for security.
                                        settings.setSupportZoom(false)
                                        settings.builtInZoomControls = false
                                        settings.displayZoomControls = false
                                        setBackgroundColor(colorScheme.background.toArgb()) // set WebView background color to current colorScheme's background color.
                                    }
                                },
                                update = { view ->
                                    /**
                                     * The markdown which is converted to HTML is inserted into the body of this.
                                     * The default text color is set to the current colorScheme's onBackground color
                                     * to match the TextField's text color.
                                     */
//                                    fileViewModel.setMarkdownToHtml()
                                    val html = """
                                <!DOCTYPE html>
                                <html>
                                    <head>
                                        <meta charset="utf-8"/>
                                        <meta name="viewport" content="width=device-width, initial-scale=1"/>
                                        <style>
                                            html {
                                                overflow-wrap: anywhere;
                                            }
                                            body {
                                                color: ${textColor.toCssColor()};  
                                            }
                                            table, th, td {
                                                border: thin solid;
                                            }
                                            a:link {
                                                color: ${colorScheme.primary.toCssColor()}
                                            }
                                            a:visited {
                                                color: ${colorScheme.secondary.toCssColor()}
                                            }
                                            a:hover {
                                                color: ${colorScheme.tertiary.toCssColor()}
                                            }
                                        </style>
                                    </head>
                                    <body>
                                        ${
                                        if (contentConvertedToHtml == "") {
                                            fileUiState.contentConvertedToHtml.value
                                        } else {
                                            contentConvertedToHtml
                                        }
                                    }
                                    </body>
                                </html>
                                """.trimIndent()
                                    view.loadData(html, "text/html", "UTF-8")
                                }
                            )
                        }
                    }
                }
            }
        }
    } else {
        Row(
            modifier = Modifier.imePadding()
        ) {
            if (readOnly) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(
                            if (previewMarkdownRenderedToHtmlFullscreen) {
                                0.00000001f
                            } else {
                                1f
                            }
                        )
                        .padding(TextFieldDefaults.contentPaddingWithLabel())
                        .verticalScroll(textFieldVerticalScrollState),
                ) {
                    if (previewMarkdownRenderedToHtmlFullscreen) {
                        // if we don't do this then when the preview is fullscreen the label appears
                    } else {
                        SelectionContainer {
                            Text(
                                text = name,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = typography.bodySmall.fontSize,
                                fontStyle = typography.bodySmall.fontStyle,
                                fontFamily = typography.bodySmall.fontFamily,
                            )
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = content
                        )
                    }
                }
            } else {
                TextField(
                    /** We cannot use .verticalScroll when editing is possible as the TextField currently
                     * does not scroll to the next line when pressing enter automatically with .verticalScroll.
                     * It does scroll to the next line when pressing enter without it having .verticalScroll.
                     */
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(
                            if (previewMarkdownRenderedToHtmlFullscreen) {
                                0.00000001f
                            } else {
                                1f
                            }
                        ),
                    value = content,
                    onValueChange = {
                        onContentChanged(it)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = textColor,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    label = {
                        if (previewMarkdownRenderedToHtmlFullscreen) {
                            // if we don't do this then when the preview is fullscreen the label appears
                        } else {
                            SelectionContainer {
                                Text(
                                    text = name,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    },
                    textStyle = textStyle,
                )
            }
            when (mimeType) {
                "text/markdown" -> {
                    if (preferencesUiState.renderMarkdown.second.value or previewMarkdownRenderedToHtmlFullscreen) {
                        if (previewMarkdownRenderedToHtmlFullscreen) {
                            Spacer(modifier = Modifier.padding(4.dp))
                        }
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.rendered_markdown),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                                style = typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 8.dp, end = 8.dp)
                                    .verticalScroll(renderedMarkdownVerticalScrollState)
                            ) {
                                AndroidView(
                                    factory = { context ->
                                        WebView(context).apply {
                                            settings.javaScriptEnabled = false // disable JavaScript for security.
                                            settings.setSupportZoom(false)
                                            settings.builtInZoomControls = false
                                            settings.displayZoomControls = false
                                            setBackgroundColor(colorScheme.background.toArgb()) // set WebView background color to current colorScheme's background color.
                                        }
                                    },
                                    update = { view ->
                                        /**
                                         * The markdown which is converted to HTML is inserted into the body of this.
                                         * The default text color is set to the current colorScheme's onBackground color
                                         * to match the TextField's text color.
                                         */
                                        fileViewModel.setMarkdownToHtml()
                                        val html = """
                                <!DOCTYPE html>
                                <html>
                                    <head>
                                        <meta charset="utf-8"/>
                                        <meta name="viewport" content="width=device-width, initial-scale=1"/>
                                        <style>
                                            html {
                                                overflow-wrap: anywhere;
                                            }
                                            body {
                                                color: ${textColor.toCssColor()};  
                                            }
                                            table, th, td {
                                                border: thin solid;
                                            }
                                            a:link {
                                                color: ${colorScheme.primary.toCssColor()}
                                            }
                                            a:visited {
                                                color: ${colorScheme.secondary.toCssColor()}
                                            }
                                            a:hover {
                                                color: ${colorScheme.tertiary.toCssColor()}
                                            }
                                        </style>
                                    </head>
                                    <body>
                                        ${if (contentConvertedToHtml == "") {
                                            fileUiState.contentConvertedToHtml.value
                                        } else {
                                            contentConvertedToHtml
                                        }
                                        }
                                    </body>
                                </html>
                                """.trimIndent()
                                        view.loadData(html, "text/html", "UTF-8")
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun Color.toCssColor(): String {
    return "rgb(${red*255}, ${green*255}, ${blue*255})"
}
