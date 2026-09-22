package dev.soupslurpr.beautyxt.ui.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R

private const val APP_MARK_FOREGROUND_SCALE = 1.4f

/** Displays the production icon on its launcher background, consistently in Home and About. */
@Composable
internal fun AppMark(size: Dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(percent = 30),
        color = colorResource(R.color.launcher_background),
        tonalElevation = 3.dp
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground_color),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(APP_MARK_FOREGROUND_SCALE),
            contentScale = ContentScale.Fit
        )
    }
}
