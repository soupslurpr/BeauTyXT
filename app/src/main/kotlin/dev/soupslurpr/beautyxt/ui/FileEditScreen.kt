package dev.soupslurpr.beautyxt.ui

import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.settings.PreferencesUiState

/**
 * Composable for editing a file in plain text.
 */
@Composable
fun FileEditScreen(
    name: String,
    onContentChanged: (String) -> Unit = {},
    content: String,
    mimeType: String,
    contentConvertedToHtml: String,
    readOnly: Boolean,
    preferencesUiState: PreferencesUiState,
    fileViewModel: FileViewModel,
    navController: NavController,
) {
    val colorScheme = MaterialTheme.colorScheme
    val textColor = colorScheme.onBackground
    val renderedMarkdownVerticalScrollState = rememberScrollState()
    val textFieldVerticalScrollState = rememberScrollState()
    val textStyle = typography.bodyLarge

    LaunchedEffect(key1 = Unit) {
        /** Detect if fileViewModel was cleared (uri == Uri.EMPTY) and if so, go back to the
         * previous screen.
         */
        if (fileViewModel.uiState.value.uri.value == Uri.EMPTY) {
            navController.navigateUp()
        }
        /** This is so the markdown render updates when disabling render markdown, making a change,
         * and then turning it on again. Or else it only updates after a character gets typed. */
        if (preferencesUiState.renderMarkdown.second.value) {
            fileViewModel.getMarkdownToHtml()
        }
    }

    Column(
        modifier = Modifier
    ) {
        TextField(
            /** We cannot use .verticalScroll when editing is possible as the TextField currently
             * does not scroll to the next line when pressing enter automatically with .verticalScroll.
             * It does scroll to the next line when pressing enter without it having .verticalScroll.
             */
            modifier = if (readOnly) {
                Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .verticalScroll(textFieldVerticalScrollState)
            } else {
                Modifier
                    .fillMaxSize()
                    .weight(1f)
            },
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
                Text(
                    text = name,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            },
            textStyle = textStyle,
            enabled = !readOnly
        )
        when (mimeType) {
            "text/markdown" -> {
                if (preferencesUiState.renderMarkdown.second.value) {
                    Text(
                        text = stringResource(R.string.rendered_markdown),
                        textAlign = TextAlign.Center,
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
                                        </style>
                                    </head>
                                    <body>
                                        ${if (contentConvertedToHtml == "") {
                                            fileViewModel.getMarkdownToHtml().value

                                        } else {
                                            contentConvertedToHtml
                                        }}
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

fun Color.toCssColor(): String {
    return "rgb(${red*255}, ${green*255}, ${blue*255})"
}
