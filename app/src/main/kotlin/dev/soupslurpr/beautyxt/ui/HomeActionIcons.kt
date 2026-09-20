package dev.soupslurpr.beautyxt.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private const val ICON_VIEWPORT = 24f
private const val ICON_STROKE_WIDTH = 1.8f

/** Draws the standard Material file-open symbol used by home actions. */
internal val HomeOpenIcon: ImageVector =
    outlineIcon("Open file") {
        moveTo(6f, 3f)
        horizontalLineTo(14f)
        lineTo(20f, 9f)
        verticalLineTo(14f)
        moveTo(14f, 3f)
        verticalLineTo(9f)
        horizontalLineTo(20f)
        moveTo(6f, 3f)
        verticalLineTo(21f)
        horizontalLineTo(15f)
        moveTo(17f, 17f)
        horizontalLineTo(22f)
        verticalLineTo(22f)
        moveTo(22f, 17f)
        lineTo(17f, 22f)
    }

/** Draws the new-document symbol used by home actions. */
internal val HomeNewIcon: ImageVector =
    outlineIcon("New document") {
        moveTo(6f, 3f)
        horizontalLineTo(14f)
        lineTo(19f, 8f)
        verticalLineTo(12f)
        moveTo(14f, 3f)
        verticalLineTo(8f)
        horizontalLineTo(19f)
        moveTo(6f, 3f)
        verticalLineTo(21f)
        horizontalLineTo(12f)
        moveTo(17f, 14f)
        verticalLineTo(22f)
        moveTo(13f, 18f)
        horizontalLineTo(21f)
    }

/** Draws the QR-frame symbol used by home actions. */
internal val HomeQrIcon: ImageVector =
    outlineIcon("QR code") {
        moveTo(3f, 3f)
        horizontalLineTo(9f)
        verticalLineTo(9f)
        horizontalLineTo(3f)
        close()
        moveTo(15f, 3f)
        horizontalLineTo(21f)
        verticalLineTo(9f)
        horizontalLineTo(15f)
        close()
        moveTo(3f, 15f)
        horizontalLineTo(9f)
        verticalLineTo(21f)
        horizontalLineTo(3f)
        close()
        moveTo(13f, 13f)
        horizontalLineTo(17f)
        verticalLineTo(17f)
        horizontalLineTo(13f)
        close()
        moveTo(19f, 13f)
        horizontalLineTo(21f)
        verticalLineTo(15f)
        moveTo(19f, 19f)
        horizontalLineTo(21f)
        verticalLineTo(21f)
        moveTo(13f, 19f)
        verticalLineTo(21f)
        horizontalLineTo(16f)
    }

/** Draws the standard Android NFC symbol used by home actions. */
internal val HomeNfcIcon: ImageVector =
    outlineIcon("NFC") {
        moveTo(4f, 3f)
        horizontalLineTo(20f)
        verticalLineTo(21f)
        horizontalLineTo(4f)
        close()
        moveTo(7f, 7f)
        horizontalLineTo(10f)
        verticalLineTo(9f)
        horizontalLineTo(9f)
        verticalLineTo(17f)
        horizontalLineTo(17f)
        verticalLineTo(7f)
        horizontalLineTo(13f)
        verticalLineTo(10.2f)
        moveTo(12f, 10.2f)
        curveTo(10.9f, 10.2f, 10f, 11.1f, 10f, 12.2f)
        curveTo(10f, 13.3f, 10.9f, 14.2f, 12f, 14.2f)
        curveTo(13.1f, 14.2f, 14f, 13.3f, 14f, 12.2f)
        curveTo(14f, 11.1f, 13.1f, 10.2f, 12f, 10.2f)
    }

/** Builds one consistent 24 dp outline icon. */
private fun outlineIcon(
    name: String,
    draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = ICON_VIEWPORT,
    viewportHeight = ICON_VIEWPORT
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = ICON_STROKE_WIDTH,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = draw
    )
}.build()
