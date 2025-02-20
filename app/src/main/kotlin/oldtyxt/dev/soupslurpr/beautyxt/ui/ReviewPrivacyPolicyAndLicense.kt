package oldtyxt.dev.soupslurpr.beautyxt.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import kotlinx.coroutines.launch
import oldtyxt.dev.soupslurpr.beautyxt.settings.PreferencesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewPrivacyPolicyAndLicense(
    preferencesViewModel: PreferencesViewModel,
) {
    val preferencesUiState by preferencesViewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var checked by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(text = stringResource(R.string.oldtyxt_review_privacy_policy_and_license))
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(innerPadding)
        ) {
            Text(
                text = stringResource(R.string.oldtyxt_full_privacy_policy) + "\n\n\n\n" + stringResource(
                    R.string.oldtyxt_full_license
                ) + "\n\n\n\n"
            )
            Text(
                text = stringResource(R.string.oldtyxt_privacy_policy_and_license_checkbox_text),
                fontWeight = FontWeight.Bold
            )
            Checkbox(
                checked = checked,
                onCheckedChange = {
                    checked = it
                }
            )
            Button(
                onClick = {
                    coroutineScope.launch {
                        preferencesViewModel.setPreference(
                            preferencesUiState.acceptedPrivacyPolicyAndLicense.first,
                            checked
                        )
                    }
                },
                enabled = checked
            ) {
                Text(text = stringResource(R.string.oldtyxt_continue_to_app))
            }

            Spacer(Modifier.padding(WindowInsets.navigationBars.asPaddingValues()))
        }
    }
}