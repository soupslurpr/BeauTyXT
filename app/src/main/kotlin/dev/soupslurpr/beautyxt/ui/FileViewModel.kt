package dev.soupslurpr.beautyxt.ui

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import dev.soupslurpr.beautyxt.data.FileUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
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
        _uiState.update { currentState ->
            currentState.copy(
                uri = uri,
                name = getNameFromUri(uri = uri, context),
                content = getContentFromUri(uri = uri, context),
                mimeType = getMimeTypeFromUri(uri = uri, context),
                size = getSizeFromUri(uri = uri, context)
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
        uiState.value.size = mutableLongStateOf(size)
        return mutableLongStateOf(size)
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
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun updateContent(content: String) {
        uiState.value.content.value = content
    }
}