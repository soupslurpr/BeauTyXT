package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.printing.PrintContentMode
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceButtons
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceOption

/** Explains the selected output consistently in the summary and detailed preview. */
@Composable
internal fun ExportFormatChoices(controller: ExcerptExportController) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Refresh releases the capture temporarily; keep the disabled selection visible.
        SingleChoiceButtons((controller.formats + controller.format).distinct().map { format ->
            SingleChoiceOption(
                exportFormatLabel(controller, format),
                controller.format == format,
                { controller.selectFormat(format) },
                controller.canConfigure
            )
        })
        controller.capture?.let { capture ->
            val description = when (controller.format) {
                ExcerptFormat.Text -> when {
                    !capture.exactSource -> R.string.export_format_reading_text
                    controller.exportScope == ExportScope.Selection -> R.string.export_format_selected_source
                    else -> R.string.export_format_source
                }
                ExcerptFormat.Markdown -> if (controller.exportScope == ExportScope.Document) {
                    R.string.export_format_markdown_document
                } else {
                    R.string.export_format_markdown_selection
                }
                ExcerptFormat.ReadingText -> R.string.export_format_reading_text
                ExcerptFormat.Pdf -> when {
                    controller.printDraft.contentMode != PrintContentMode.Source -> R.string.export_format_pdf_formatted
                    capture.exactSource -> R.string.export_format_pdf_source
                    else -> R.string.export_format_pdf_reading_text
                }
            }
            Text(
                stringResource(description),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
