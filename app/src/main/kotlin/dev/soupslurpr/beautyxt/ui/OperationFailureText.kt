package dev.soupslurpr.beautyxt.ui

import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportFailure
import dev.soupslurpr.beautyxt.importing.client.DocumentImportFailure

/** Maps typed importing failures to presentation text without localizing diagnostics. */
internal val DocumentImportFailure.userMessage: UiText
    get() = when (this) {
        DocumentImportFailure.SOURCE_UNAVAILABLE -> UiText.Resource(
            R.string.import_source_unavailable
        )

        DocumentImportFailure.BUFFER_UNAVAILABLE -> UiText.Resource(
            R.string.import_buffer_unavailable
        )

        DocumentImportFailure.SERVICE_UNAVAILABLE -> UiText.Resource(
            R.string.import_service_unavailable
        )

        DocumentImportFailure.SERVICE_BUSY -> UiText.Resource(R.string.import_service_busy)

        DocumentImportFailure.INVALID_UTF8 -> UiText.Resource(R.string.import_invalid_utf8)

        DocumentImportFailure.UNSUPPORTED_ENCODING -> UiText.Resource(
            R.string.import_unsupported_encoding
        )

        DocumentImportFailure.TOO_LARGE -> UiText.Resource(R.string.import_too_large)

        DocumentImportFailure.TIMED_OUT -> UiText.Resource(R.string.import_timed_out)

        DocumentImportFailure.CANCELLED -> UiText.Resource(R.string.import_cancelled)

        DocumentImportFailure.OPEN_FAILED -> UiText.Resource(R.string.import_open_failed)

        DocumentImportFailure.INVALID_RESPONSE -> UiText.Resource(R.string.import_invalid_response)
    }

/** Maps typed exporting failures to presentation text without localizing diagnostics. */
internal val DocumentExportFailure.userMessage: UiText
    get() = when (this) {
        DocumentExportFailure.DESTINATION_UNAVAILABLE -> UiText.Resource(
            R.string.export_destination_unavailable
        )

        DocumentExportFailure.PIPE_UNAVAILABLE -> UiText.Resource(R.string.export_pipe_unavailable)

        DocumentExportFailure.SERVICE_UNAVAILABLE -> UiText.Resource(
            R.string.export_service_unavailable
        )

        DocumentExportFailure.SERVICE_BUSY -> UiText.Resource(R.string.export_service_busy)

        DocumentExportFailure.TOO_LARGE -> UiText.Resource(R.string.export_too_large)

        DocumentExportFailure.TIMED_OUT -> UiText.Resource(R.string.export_timed_out)

        DocumentExportFailure.CANCELLED -> UiText.Resource(R.string.export_cancelled)

        DocumentExportFailure.SNAPSHOT_FAILED -> UiText.Resource(R.string.export_snapshot_failed)

        DocumentExportFailure.WRITE_FAILED -> UiText.Resource(R.string.export_write_failed)

        DocumentExportFailure.SOURCE_CONFLICT -> UiText.Resource(R.string.export_source_conflict)

        DocumentExportFailure.SOURCE_UNCERTAIN -> UiText.Resource(R.string.export_source_uncertain)

        DocumentExportFailure.INVALID_RESPONSE -> UiText.Resource(R.string.export_invalid_response)
    }
