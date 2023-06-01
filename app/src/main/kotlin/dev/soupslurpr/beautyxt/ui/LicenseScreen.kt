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
fun LicenseScreen() {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 15.dp),
            text = "ISC License\n" +
                    "\n" +
                    "Copyright (c) 2023 soupslurpr\n" +
                    "\n" +
                    "Permission to use, copy, modify, and/or distribute this software for any\n" +
                    "purpose with or without fee is hereby granted, provided that the above\n" +
                    "copyright notice and this permission notice appear in all copies.\n" +
                    "\n" +
                    "THE SOFTWARE IS PROVIDED \"AS IS\" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH\n" +
                    "REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY\n" +
                    "AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,\n" +
                    "INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM\n" +
                    "LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR\n" +
                    "OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR\n" +
                    "PERFORMANCE OF THIS SOFTWARE."
        )
    }
}