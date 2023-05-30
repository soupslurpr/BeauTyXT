package dev.soupslurpr.beautyxt.ui

import android.net.Uri
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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