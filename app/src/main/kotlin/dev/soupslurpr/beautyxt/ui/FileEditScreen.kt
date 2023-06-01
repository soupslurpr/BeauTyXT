package dev.soupslurpr.beautyxt.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

/**
 * Composable for editing text in a TextField,
 * it also has icons for opening, and creating new files.
 */
@Composable
fun FileEditScreen(
    content: String,
    uri: Uri,
    name: String,
    onContentChanged: (String) -> Unit = {}
) {
    // TODO: try to use LiveData and observeAsState() instead if its a good idea (it might not be)
    var content by rememberSaveable { mutableStateOf(content) }
    Column(
        modifier = Modifier
    ) {
        TextField(
            modifier = Modifier.fillMaxSize(),
            value = content,
            onValueChange = {
                content = it
                onContentChanged(content)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent
            ),
            label = {
                Text(
                    text = name
                )
            }
        )
    }
}

@Preview
@Composable
fun FileEditPreview() {
    FileEditScreen(content = "test", Uri.EMPTY, name = "test.txt")
}