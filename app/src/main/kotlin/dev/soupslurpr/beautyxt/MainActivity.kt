package dev.soupslurpr.beautyxt

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import dev.soupslurpr.beautyxt.BeauTyXTApp
import dev.soupslurpr.beautyxt.ui.theme.BeauTyXTTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BeauTyXTTheme {
                BeauTyXTApp(modifier = Modifier)
            }
        }
    }
}