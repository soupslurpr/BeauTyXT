package dev.soupslurpr.beautyxt.ui

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import dev.soupslurpr.beautyxt.data.FileUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.InputStreamReader

class FileViewModel : ViewModel() {

    /**
     * File state for this file
     */
    private val _uiState = MutableStateFlow(FileUiState())
    val uiState: StateFlow<FileUiState> = _uiState.asStateFlow()

    private val options = MutableDataSet()
        .set(TablesExtension.COLUMN_SPANS, false)
        .set(TablesExtension.APPEND_MISSING_COLUMNS, true)
        .set(TablesExtension.DISCARD_EXTRA_COLUMNS, true)
        .set(TablesExtension.HEADER_SEPARATOR_COLUMN_MATCH, true)
        .set(Parser.EXTENSIONS, listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .toImmutable()

    /**
     * Set the uri for this file and update the content
     */
    fun setUri(uri: Uri, context: Context) {
        _uiState.update { currentState ->
            currentState.copy(
                uri = mutableStateOf(uri),
                name = getNameFromUri(uri = uri, context),
                content = getContentFromUri(uri = uri, context),
                mimeType = getMimeTypeFromUri(uri = uri, context),
                size = getSizeFromUri(uri = uri, context),
                readOnly = uiState.value.readOnly,
            )
        }
    }

    private fun getNameFromUri(uri: Uri, context: Context): MutableState<String> {
        var name = ""
        val contentResolver = context.contentResolver
        // The query, because it only applies to a single document, returns only
        // one row. There's no need to filter, sort, or select fields,
        // because we want all fields for one document.
        val cursor: Cursor? = contentResolver.query(
            uri, null, null, null, null, null)

        cursor?.use {
            // moveToFirst() returns false if the cursor has 0 rows. Very handy for
            // "if there's anything to look at, look at it" conditionals.
            if (it.moveToFirst()) {

                // This is provider-specific, and might not necessarily be the file name.
                name = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
        }
        return mutableStateOf(name)
    }

    private fun getContentFromUri(uri: Uri, context: Context): MutableState<String> {
        val stringBuilder = StringBuilder()
        val contentResolver = context.contentResolver
        contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    stringBuilder.append(line)
                    stringBuilder.append("\n")
                    line = reader.readLine()
                }
            }
        }
        return mutableStateOf(stringBuilder.toString())
    }

    private fun getMimeTypeFromUri(uri: Uri, context: Context): MutableState<String?> {
        return mutableStateOf(context.contentResolver.getType(uri))
    }

    fun getSizeFromUri(uri: Uri, context: Context): MutableState<Long> {
        var size = 0L
        val contentResolver = context.contentResolver
        // The query, because it only applies to a single document, returns only
        // one row. There's no need to filter, sort, or select fields,
        // because we want all fields for one document.
        val cursor: Cursor? = contentResolver.query(
            uri, null, null, null, null, null)

        cursor?.use {
            // moveToFirst() returns false if the cursor has 0 rows. Very handy for
            // "if there's anything to look at, look at it" conditionals.
            if (it.moveToFirst()) {
                size = it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE))
            }
        }
        uiState.value.size.value = size
        return mutableStateOf(size)
    }

    fun setContentToUri(uri: Uri, context: Context) {
        try {
            val contentResolver = context.contentResolver
            contentResolver.openFileDescriptor(uri, "wt")?.use {
                FileOutputStream(it.fileDescriptor).use {
                    it.write(
                        (uiState.value.content.value)
                            .toByteArray()
                    )
                }
            }
        } catch (e: UnsupportedOperationException) {
            setReadOnly(true)
        } catch (e: SecurityException) {
            setReadOnly(true)
        }
        // TODO: Handle more exceptions
//        } catch (e: IOException) {
//            e.printStackTrace()
//        }
    }

    fun updateContent(content: String) {
        uiState.value.content.value = content
    }

    fun getMarkdownToHtml(): MutableState<String> {
        val parser = Parser.builder(options).build()
        val renderer = HtmlRenderer.builder(options).build()

        val document = parser.parse(uiState.value.content.value)

        uiState.value.contentConvertedToHtml.value = renderer.render(document)
        return mutableStateOf(renderer.render(document))
    }

    fun setReadOnly(readOnly: Boolean) {
        uiState.value.readOnly.value = readOnly
    }

    /** Set uiState to default values */
    fun clearUiState() {
        uiState.value.content.value = ""
        uiState.value.contentConvertedToHtml.value = ""
        uiState.value.size.value = 0L
        uiState.value.mimeType.value = ""
        uiState.value.name.value = ""
        uiState.value.uri.value = Uri.EMPTY
        uiState.value.readOnly.value = true
    }

    fun saveAsHtml(uri: Uri, context: Context) {
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
                                            table, th, td {
                                                border: thin solid;
                                            }
                                            body {
                                                background: #ffffff;
                                                color: #000000;
                                            }
                                            @media (prefers-color-scheme: dark) {
                                              body {
                                                  background: #121212;
                                                  color: #FFFFFF;
                                              }
                                            }
                                        </style>
                                    </head>
                                    <body>
                                        ${getMarkdownToHtml().value}
                                    </body>
                                </html>
                                """.trimIndent()
        try {
            val contentResolver = context.contentResolver
            contentResolver.openFileDescriptor(uri, "wt")?.use {
                FileOutputStream(it.fileDescriptor).use {
                    it.write(
                        (html)
                            .toByteArray()
                    )
                }
            }
        } finally {

        }
//        } catch (e: UnsupportedOperationException) {
//            e.printStackTrace()
//        } catch (e: SecurityException) {
//            e.printStackTrace()
//        }
        // TODO: Handle more exceptions
//        } catch (e: IOException) {
//            e.printStackTrace()
//        }
    }
}