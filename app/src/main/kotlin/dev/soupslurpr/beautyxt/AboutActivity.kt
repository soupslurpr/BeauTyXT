package dev.soupslurpr.beautyxt

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.soupslurpr.beautyxt.ui.AboutScreen
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme

/** Hosts About while retaining Home and its state underneath it. */
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val packageInfo = packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(0)
        )
        val versionName = packageInfo.versionName ?: packageInfo.longVersionCode.toString()
        setContent {
            BeauTyXTTheme {
                AboutScreen(
                    versionName = versionName,
                    onBack = ::finish,
                    onOpenThirdPartyNotices = {
                        startActivity(Intent(this, ThirdPartyNoticesActivity::class.java))
                    }
                )
            }
        }
    }
}
