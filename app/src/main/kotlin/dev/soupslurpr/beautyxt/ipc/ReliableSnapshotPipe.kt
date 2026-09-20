/* Owns cancellation-safe streaming capabilities shared by snapshot consumers. */
package dev.soupslurpr.beautyxt.ipc

import android.os.ParcelFileDescriptor
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import java.io.IOException

private const val SNAPSHOT_PIPE_ERROR = "document snapshot unavailable"

/** Owns one reliable source pipe without allocating a document-sized buffer. */
internal class ReliableSnapshotPipe private constructor(
    private val timeoutMillis: Long,
    private var reader: ParcelFileDescriptor?,
    private var writer: ParcelFileDescriptor?,
    private var cancellationReader: ParcelFileDescriptor?,
    private var cancellationWriter: ParcelFileDescriptor?
) : AutoCloseable {
    /** Transfers the read capability for one synchronous Binder call. */
    @Synchronized
    fun takeReader(): ParcelFileDescriptor =
        checkNotNull(reader) { "snapshot pipe reader is unavailable" }.also {
            reader = null
        }

    /** Streams one immutable native snapshot and closes the writer terminally. */
    fun writeSnapshot(snapshot: EditorDocumentSnapshot): Long = try {
        duplicateProducerDescriptors().use { descriptors ->
            snapshot.writeSnapshot(
                outputRawFileDescriptor = descriptors.output.fd,
                cancellationRawFileDescriptor = descriptors.cancellation.fd,
                timeoutMillis = timeoutMillis
            )
        }
    } catch (failure: Exception) {
        failWriter()
        throw failure
    } catch (failure: LinkageError) {
        failWriter()
        throw failure
    } finally {
        finishProducer()
    }

    /** Duplicates producer capabilities before cancellation can close their owners. */
    @Synchronized
    private fun duplicateProducerDescriptors(): SnapshotProducerDescriptors {
        val output = checkNotNull(writer) { "snapshot pipe writer is unavailable" }
        val cancellation = checkNotNull(cancellationReader) {
            "snapshot cancellation reader is unavailable"
        }
        val ownedOutput = ParcelFileDescriptor.dup(output.fileDescriptor)
        return try {
            SnapshotProducerDescriptors(
                output = ownedOutput,
                cancellation = ParcelFileDescriptor.dup(cancellation.fileDescriptor)
            )
        } catch (failure: Throwable) {
            closeQuietly(ownedOutput)
            throw failure
        }
    }

    /** Closes the source with a content-free reliable-pipe failure. */
    @Synchronized
    fun failWriter() {
        closeQuietly(cancellationWriter)
        cancellationWriter = null
        val descriptor = writer ?: return
        writer = null
        try {
            descriptor.closeWithError(SNAPSHOT_PIPE_ERROR)
        } catch (_: IOException) {
            // Reader teardown still interrupts the duplicated native writer.
        }
    }

    /** Closes every locally owned pipe capability exactly once. */
    @Synchronized
    override fun close() {
        closeQuietly(cancellationWriter)
        closeQuietly(reader)
        closeQuietly(writer)
        closeQuietly(cancellationReader)
        cancellationWriter = null
        reader = null
        writer = null
        cancellationReader = null
    }

    /** Releases producer descriptors after normal completion or interruption. */
    @Synchronized
    private fun finishProducer() {
        closeQuietly(writer)
        closeQuietly(cancellationReader)
        closeQuietly(cancellationWriter)
        writer = null
        cancellationReader = null
        cancellationWriter = null
    }

    companion object {
        /** Creates one reliable pipe whose failures cross the process boundary. */
        fun create(timeoutMillis: Long): ReliableSnapshotPipe {
            require(timeoutMillis > 0L) { "snapshot timeout must be positive" }
            val snapshotDescriptors = ParcelFileDescriptor.createReliablePipe()
            check(snapshotDescriptors.size == 2) {
                "reliable pipe returned an invalid descriptor pair"
            }
            val cancellationDescriptors =
                try {
                    ParcelFileDescriptor.createPipe()
                } catch (failure: Exception) {
                    closeQuietly(snapshotDescriptors[0])
                    closeQuietly(snapshotDescriptors[1])
                    throw failure
                }
            check(cancellationDescriptors.size == 2) {
                "cancellation pipe returned an invalid descriptor pair"
            }
            return ReliableSnapshotPipe(
                timeoutMillis = timeoutMillis,
                reader = snapshotDescriptors[0],
                writer = snapshotDescriptors[1],
                cancellationReader = cancellationDescriptors[0],
                cancellationWriter = cancellationDescriptors[1]
            )
        }
    }
}

/** Owns stable producer capabilities for one synchronous snapshot call. */
private data class SnapshotProducerDescriptors(
    val output: ParcelFileDescriptor,
    val cancellation: ParcelFileDescriptor
) : AutoCloseable {
    /** Releases both descriptors after the native borrower has returned. */
    override fun close() {
        closeQuietly(output)
        closeQuietly(cancellation)
    }
}

/** Closes one descriptor without masking the active snapshot outcome. */
private fun closeQuietly(descriptor: ParcelFileDescriptor?) {
    try {
        descriptor?.close()
    } catch (_: Exception) {
        // Preserve the primary snapshot outcome.
    }
}
