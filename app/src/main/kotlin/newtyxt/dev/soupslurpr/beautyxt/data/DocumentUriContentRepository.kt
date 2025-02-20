package dev.soupslurpr.beautyxt.newtyxt.dev.soupslurpr.beautyxt.data

import android.net.Uri
import javax.inject.Inject

class DocumentUriContentRepository @Inject constructor(
    private val documentUriContentDataSource: DocumentUriContentDataSource
) {
    fun documentUriContentFlow(uri: Uri) =
        documentUriContentDataSource.documentUriContentFlow(originalUri = uri)
}