package dev.soupslurpr.beautyxt.ui

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import dev.soupslurpr.beautyxt.data.FileUiState
import dev.soupslurpr.beautyxt.markdownToDocx
import dev.soupslurpr.beautyxt.markdownToHtml
import dev.soupslurpr.beautyxt.plainTextToDocx
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

    /**
     * Set the uri for this file and update the content
     */
    fun setUri(uri: Uri, context: Context) {
        getContentFromUri(uri = uri, context)
        _uiState.update { currentState ->
            currentState.copy(
                uri = mutableStateOf(uri),
                name = getNameFromUri(uri = uri, context),
                content = uiState.value.content,
                mimeType = getMimeTypeFromUri(uri = uri, context),
                size = run {
                    setSizeFromUri(uri = uri, context)
                    uiState.value.size
                },
                readOnly = uiState.value.readOnly,
                wordCount = run {
                    setWordCount()
                    uiState.value.wordCount
                },
                characterCount = run {
                    setCharacterCount()
                    uiState.value.characterCount
                }
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
        val content = stringBuilder.toString()
        _uiState.value.content.value = content
        return mutableStateOf(content)
    }

    private fun getMimeTypeFromUri(uri: Uri, context: Context): MutableState<String?> {
        return mutableStateOf(context.contentResolver.getType(uri))
    }

    fun setSizeFromUri(uri: Uri, context: Context) {
        var size = 0L
        val contentResolver = context.contentResolver
        val cursor: Cursor? = contentResolver.query(
            uri,
            null,
            null,
            null,
            null,
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                size = it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE))
            }
        }
        _uiState.value.size.value = size
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
        _uiState.value.content.value = content
    }

    fun setMarkdownToHtml() {
        _uiState.value.contentConvertedToHtml.value = markdownToHtml(uiState.value.content.value)
    }

    fun setReadOnly(readOnly: Boolean) {
        _uiState.value.readOnly.value = readOnly
    }

    fun setWordCount() {
        val wordCount = uiState.value.content.value.split("\\s+".toRegex()).filter { it.isNotEmpty() }.size.toLong()
        _uiState.value.wordCount.value = wordCount

    }

    fun setCharacterCount() {
        val characterCount = uiState.value.content.value.count().toLong()
        _uiState.value.characterCount.value = characterCount
    }

    /** Set uiState to default values */
    fun clearUiState() {
        _uiState.value = FileUiState()
    }

    fun saveAsHtml(uri: Uri, context: Context) {
        setMarkdownToHtml()
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
                                        ${uiState.value.contentConvertedToHtml.value}
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
        // TODO: Handle exceptions
//        } catch (e: UnsupportedOperationException) {
//            e.printStackTrace()
//        } catch (e: SecurityException) {
//            e.printStackTrace()
//        }
//        } catch (e: IOException) {
//            e.printStackTrace()
//        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun saveAsDocx(uri: Uri, context: Context) {
        val docx = when (uiState.value.mimeType.value) {
            "text/markdown" -> {
                markdownToDocx(uiState.value.content.value)
            }

            else -> {
                plainTextToDocx(uiState.value.content.value)
            }
        }

        try {
            val contentResolver = context.contentResolver
            contentResolver.openFileDescriptor(uri, "wt")?.use {
                FileOutputStream(it.fileDescriptor).use {
                    it.write(docx.toUByteArray().toByteArray())
                }
            }
        } finally {

        }
        // TODO: Handle exceptions
    }

    override fun onCleared() {
        super.onCleared()
        clearUiState()
    }
}