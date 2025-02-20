package dev.soupslurpr.beautyxt.newtyxt.dev.soupslurpr.beautyxt.ui

import android.net.Uri
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.soupslurpr.beautyxt.newtyxt.dev.soupslurpr.beautyxt.data.DocumentUriContentRepository
import dev.soupslurpr.beautyxt.newtyxt.dev.soupslurpr.beautyxt.ui.common.ScreenLazyColumn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditorScreenUiState(
    val fileName: String? = null, val text: String? = null, val uri: Uri? = null
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val documentUriContentRepository: DocumentUriContentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorScreenUiState())
    val uiState: StateFlow<EditorScreenUiState> = _uiState.asStateFlow()

    fun setUri(uri: Uri) {
        _uiState.update { currentState ->
            currentState.copy(
                uri = uri
            )
        }

        viewModelScope.launch {
            documentUriContentRepository.documentUriContentFlow(
                uri = uri
            ).collect { documentUriContent ->
                _uiState.update { currentState ->
                    currentState.copy(
                        text = documentUriContent
                    )
                }
            }
        }
    }
}

/**
 * The modular UI for editing text files.
 */
@Composable
fun EditorScreen(
    uri: Uri,
    viewModel: EditorViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // TODO: let's not do this. instead here's the idea:
    //  make repositories and data sources activity scoped, then in the activity start set a
    //  repository for intent information or something to the intent's information and then have
    //  that same repository as a parameter for the EditorViewModel and have hilt inject it ofc
    //  .
    //  i think a repository scoped to activity retained is the correct solution honestly.
    //  try it.
    LaunchedEffect(true) {
        if (uiState.uri == null) {
            viewModel.setUri(uri)
        }
    }

    ScreenLazyColumn {
        item {
            Text(text = "HELLO!?")
        }
        item {
            if (uiState.text == null) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = uiState.text!!
                )
            }
        }
    }
}