package dev.soupslurpr.beautyxt.newtyxt.dev.soupslurpr.beautyxt.data

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

class DocumentUriContentDataSource @Inject constructor(
    private val contentResolver: ContentResolver
) {
    // REMINDER that unfortunately, we might not be notified of changes if apps don't call
    // contentResolver.notifyChange() when they finish writing
    fun documentUriContentFlow(originalUri: Uri): Flow<String> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun deliverSelfNotifications(): Boolean {
                return false
            }

            override fun onChange(selfChange: Boolean) {
                this.onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val chosenUri = uri ?: originalUri
                val cancellationSignal = CancellationSignal()
                val stringBuilder = StringBuilder()

                try {
                    ParcelFileDescriptor.AutoCloseInputStream(
                        this@DocumentUriContentDataSource.contentResolver.openFileDescriptor(
                            chosenUri, "r", cancellationSignal
                        )
                    ).use { inputStream ->
                        inputStream.bufferedReader(charset = Charsets.UTF_8).use { reader ->
                            var line: String? = reader.readLine()
                            while (line != null) {
                                stringBuilder.appendLine(line)
                                line = reader.readLine()
                            }
                        }
                    }
                    trySendBlocking(stringBuilder.toString())
                } catch (e: Exception) {
                    throw e
//                    close(e)
                }
            }
        }

        this@DocumentUriContentDataSource.contentResolver.registerContentObserver(
            originalUri, false, observer
        )

        // TODO: maybe don't do this
        observer.onChange(true)

        awaitClose {
            this@DocumentUriContentDataSource.contentResolver.unregisterContentObserver(observer)
        }
    }

    // TODO: check Document.COLUMN_FLAGS to see if we can write to the document
    // TODO: also call contentResolver.notifyChange() when finished writing
}