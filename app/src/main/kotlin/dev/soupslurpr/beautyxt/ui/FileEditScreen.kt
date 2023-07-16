package dev.soupslurpr.beautyxt.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.soupslurpr.beautyxt.settings.PreferencesUiState

/**
 * Composable for editing text in a TextField,
 * it also has icons for opening, and creating new files.
 */
@Composable
fun FileEditScreen(
    name: String,
    onContentChanged: (String) -> Unit = {},
    content: String,
    mimeType: String,
    preferencesUiState: PreferencesUiState,
) {
    Column(
        modifier = Modifier
    ) {
        TextField(
            modifier = Modifier.fillMaxSize(),
            value = content,
            onValueChange = {
                onContentChanged(it)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent
            ),
            label = {
                Text(
                    text = name
                )
            },
        )
    }
}