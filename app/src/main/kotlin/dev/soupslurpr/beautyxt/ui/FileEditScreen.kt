package dev.soupslurpr.beautyxt.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.DataSet
import com.vladsch.flexmark.util.data.MutableDataSet
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
    preferencesUiState: PreferencesUiState,
) {
    val colorScheme = MaterialTheme.colorScheme
    val textColor = colorScheme.onBackground
    val renderedMarkdownVerticalScrollState = rememberScrollState()
    val options = MutableDataSet()
        .set(TablesExtension.COLUMN_SPANS, false)
        .set(TablesExtension.APPEND_MISSING_COLUMNS, true)
        .set(TablesExtension.DISCARD_EXTRA_COLUMNS, true)
        .set(TablesExtension.HEADER_SEPARATOR_COLUMN_MATCH, true)
        .set(Parser.EXTENSIONS, listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .toImmutable()
    val textStyle = typography.bodyLarge

    Column(
        modifier = Modifier
    ) {
        TextField(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
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
            textStyle = textStyle
        )
        when (mimeType) {
            "text/markdown" -> {
                if (preferencesUiState.renderMarkdown.second.value) {
                    Text(
                        text = "Rendered markdown",
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
                                        ${markdownToHtml(content, options)}
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

fun markdownToHtml(markdown: String, options: DataSet): String {
    val parser = Parser.builder(options).build()
    val renderer = HtmlRenderer.builder(options).build()

    val document = parser.parse(markdown)
    return renderer.render(document)
}