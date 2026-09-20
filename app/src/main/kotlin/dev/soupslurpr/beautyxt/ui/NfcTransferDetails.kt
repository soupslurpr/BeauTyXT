package dev.soupslurpr.beautyxt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.transfer.client.NfcTextSource
import dev.soupslurpr.beautyxt.transfer.client.ReceivedNfcMetadata

private val NfcDetailsSpacing = 8.dp
private val NfcDetailsPadding = 12.dp
private const val NFC_POSTER_TITLE_PREVIEW_UTF16_UNITS = 512

/** Displays inert NFC source metadata without treating hardware data as identity. */
@Composable
internal fun NfcTransferDetails(metadata: ReceivedNfcMetadata, modifier: Modifier = Modifier) {
    var technicalDetailsVisible by
        rememberSaveable(metadata.reportedTagId) { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NfcDetailsSpacing)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(NfcDetailsPadding),
                verticalArrangement = Arrangement.spacedBy(NfcDetailsSpacing)
            ) {
                Text(
                    text = metadata.source.displayName,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge
                )
                metadata.source.reviewDisclosure?.let { disclosure ->
                    Text(
                        text = disclosure,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                metadata.smartPosterTitle?.let { title ->
                    val titleWasShortened = title.length > NFC_POSTER_TITLE_PREVIEW_UTF16_UNITS
                    SelectionContainer {
                        Text(
                            text = stringResource(
                                R.string.nfc_poster_title,
                                nfcPosterTitlePreview(title)
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (titleWasShortened) {
                        Text(
                            text = stringResource(R.string.nfc_poster_shortened),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        metadata.tagLabel?.let { label ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(NfcDetailsPadding),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.nfc_tag_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = label,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
        metadata.reportedTagId?.let { identifier ->
            TextButton(
                onClick = { technicalDetailsVisible = !technicalDetailsVisible },
                modifier = Modifier.align(Alignment.Start)
            ) {
                Text(
                    if (technicalDetailsVisible) {
                        stringResource(
                            R.string.nfc_hide_details
                        )
                    } else {
                        stringResource(R.string.nfc_details)
                    }
                )
            }
            if (technicalDetailsVisible) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(NfcDetailsPadding),
                        verticalArrangement = Arrangement.spacedBy(NfcDetailsSpacing)
                    ) {
                        Text(
                            stringResource(R.string.nfc_reported_id),
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge
                        )
                        SelectionContainer {
                            Text(
                                text = identifier,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            stringResource(R.string.nfc_id_disclosure),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

/** Returns the factual source label shown during explicit NFC review. */
private val NfcTextSource.displayName: String
    @Composable get() = when (this) {
        NfcTextSource.BeauTyXT -> stringResource(R.string.nfc_source_beautyxt)
        NfcTextSource.Text -> stringResource(R.string.nfc_source_text)
        NfcTextSource.Uri -> stringResource(R.string.nfc_source_uri)
        NfcTextSource.PlainTextMime -> stringResource(R.string.nfc_source_plain_mime)
        NfcTextSource.MarkdownMime -> stringResource(R.string.nfc_source_markdown)
        NfcTextSource.HtmlMime -> stringResource(R.string.nfc_source_html)
        NfcTextSource.SmartPoster -> stringResource(R.string.nfc_source_poster)
    }

/** Returns any source-specific safety disclosure needed during review. */
private val NfcTextSource.reviewDisclosure: String?
    @Composable get() = when (this) {
        NfcTextSource.Uri,
        NfcTextSource.SmartPoster ->
            stringResource(R.string.nfc_uri_disclosure)

        NfcTextSource.HtmlMime ->
            stringResource(R.string.nfc_html_disclosure)

        NfcTextSource.BeauTyXT,
        NfcTextSource.Text,
        NfcTextSource.PlainTextMime,
        NfcTextSource.MarkdownMime -> null
    }

/** Returns one scalar-safe bounded Smart Poster title for Compose review. */
private fun nfcPosterTitlePreview(title: String): String {
    var end = minOf(title.length, NFC_POSTER_TITLE_PREVIEW_UTF16_UNITS)
    if (end < title.length && end > 0 && title[end - 1].isHighSurrogate()) {
        end -= 1
    }
    return title.substring(startIndex = 0, endIndex = end)
}
