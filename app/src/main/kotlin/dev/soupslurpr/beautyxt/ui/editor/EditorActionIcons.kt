package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Draws a backward history arrow that follows the reading direction. */
internal val EditorUndoIcon = editorHistoryIcon("Undo", redo = false)

/** Draws a forward history arrow that follows the reading direction. */
internal val EditorRedoIcon = editorHistoryIcon("Redo", redo = true)

internal val EditorZoomInIcon = editorZoomIcon("Diagram text size", enlarge = true)
internal val EditorZoomOutIcon = editorZoomIcon("Fit diagram", enlarge = false)

/** Original magnifier geometry, matching the weight of the other editor actions. */
private fun editorZoomIcon(name: String, enlarge: Boolean): ImageVector = editorOutlineIcon(name) {
    moveTo(16f, 9.5f)
    arcTo(6.5f, 6.5f, 0f, true, true, 3f, 9.5f)
    arcTo(6.5f, 6.5f, 0f, true, true, 16f, 9.5f)
    close()
    moveTo(14.2f, 14.2f)
    lineTo(21f, 21f)
    moveTo(6.5f, 9.5f)
    horizontalLineTo(12.5f)
    if (enlarge) {
        moveTo(9.5f, 6.5f)
        verticalLineTo(12.5f)
    }
}

/** Draws a hierarchy of text sections without external assets. */
internal val EditorContentsIcon = editorOutlineIcon("Document contents", autoMirror = true) {
    moveTo(4f, 5f)
    horizontalLineTo(20f)
    moveTo(8f, 10f)
    horizontalLineTo(20f)
    moveTo(8f, 15f)
    horizontalLineTo(17f)
    moveTo(4f, 20f)
    horizontalLineTo(20f)
}

/** Draws a document page with distinct title and body lines. */
internal val EditorPreviewIcon = editorOutlineIcon("Markdown preview") {
    moveTo(6f, 3f)
    horizontalLineTo(18f)
    quadTo(20f, 3f, 20f, 5f)
    verticalLineTo(19f)
    quadTo(20f, 21f, 18f, 21f)
    horizontalLineTo(6f)
    quadTo(4f, 21f, 4f, 19f)
    verticalLineTo(5f)
    quadTo(4f, 3f, 6f, 3f)
    close()
    moveTo(8f, 8f)
    horizontalLineTo(16f)
    moveTo(8f, 12f)
    horizontalLineTo(16f)
    moveTo(8f, 16f)
    horizontalLineTo(13f)
}

/** Constructs one outlined editing glyph without external vector assets. */
private fun editorOutlineIcon(
    name: String,
    autoMirror: Boolean = false,
    path: PathBuilder.() -> Unit
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = autoMirror
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = path
    )
}.build()

/** Constructs either direction of the curved history arrow. */
private fun editorHistoryIcon(name: String, redo: Boolean): ImageVector =
    editorOutlineIcon(name, autoMirror = true) {
        if (redo) {
            moveTo(15f, 4f)
            lineTo(20f, 9f)
            lineTo(15f, 14f)
            moveTo(20f, 9f)
            horizontalLineTo(10f)
            curveTo(2f, 9f, 2f, 20f, 10f, 20f)
            horizontalLineTo(15f)
        } else {
            moveTo(9f, 4f)
            lineTo(4f, 9f)
            lineTo(9f, 14f)
            moveTo(4f, 9f)
            horizontalLineTo(14f)
            curveTo(22f, 9f, 22f, 20f, 14f, 20f)
            horizontalLineTo(9f)
        }
    }

/** Draws the traditional floppy-disk symbol used by explicit Save actions. */
internal val EditorSaveIcon: ImageVector =
    ImageVector.Builder(
        name = "Editor save",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(5f, 3f)
            horizontalLineTo(17f)
            lineTo(21f, 7f)
            verticalLineTo(21f)
            horizontalLineTo(3f)
            verticalLineTo(5f)
            quadTo(3f, 3f, 5f, 3f)
            close()
            moveTo(7f, 3f)
            verticalLineTo(9f)
            horizontalLineTo(16f)
            verticalLineTo(3f)
            moveTo(7f, 21f)
            verticalLineTo(14f)
            horizontalLineTo(17f)
            verticalLineTo(21f)
        }
    }.build()

/** Draws the original lightweight printer symbol used by the editor menu. */
internal val EditorPrintIcon: ImageVector =
    ImageVector.Builder(
        name = "Editor print",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(7f, 8f)
            verticalLineTo(3f)
            horizontalLineTo(17f)
            verticalLineTo(8f)
            moveTo(7f, 18f)
            horizontalLineTo(5f)
            quadTo(3f, 18f, 3f, 16f)
            verticalLineTo(10f)
            quadTo(3f, 8f, 5f, 8f)
            horizontalLineTo(19f)
            quadTo(21f, 8f, 21f, 10f)
            verticalLineTo(16f)
            quadTo(21f, 18f, 19f, 18f)
            horizontalLineTo(17f)
            moveTo(7f, 14f)
            horizontalLineTo(17f)
            verticalLineTo(21f)
            horizontalLineTo(7f)
            close()
            moveTo(9.5f, 17.5f)
            horizontalLineTo(14.5f)
            moveTo(18f, 11f)
            horizontalLineTo(18.01f)
        }
    }.build()
