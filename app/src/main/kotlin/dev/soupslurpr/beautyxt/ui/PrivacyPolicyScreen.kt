package dev.soupslurpr.beautyxt.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PrivacyPolicyScreen() {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 15.dp),
            text = "BeauTyXT privacy policy\n" +
                    "\n" +
                    "This app does not use any sensitive permissions, makes no internet connections," +
                    " and does not store any data in itself other than preferences. This app uses the " +
                    "Android Storage Access Framework (SAF) to interact with files you choose to access, " +
                    "which can be stored in various locations of your choice. You are responsible " +
                    "for where you choose to store your files. This app does not access, collect, " +
                    "or transmit data from these locations beyond this purpose."
        )
    }
}