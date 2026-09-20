package dev.soupslurpr.beautyxt.importing.client

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Supplies deterministic seekable and streaming sources to import instrumentation. */
class StatelessImportTestProvider : ContentProvider() {
    private val pausedSession = AtomicReference<PausedPipeSession?>()

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        requireTargetCaller()
        if (mode != READ_MODE) {
            throw FileNotFoundException("test provider supports read-only descriptors")
        }
        val path = uri.pathSegments.singleOrNull()
            ?: throw FileNotFoundException("unknown test provider source")
        val operationToken = parseOperationToken(uri)
        return try {
            when (path) {
                PATH_SEEKABLE -> createSeekableSource(parseSourceBytes(uri), operationToken)
                PATH_PIPE -> createStreamingSource(parseSourceBytes(uri), operationToken)
                PATH_PAUSED_PIPE -> createPausedSource(operationToken)
                PATH_FAILED_PIPE -> createFailedSource(operationToken)
                else -> throw FileNotFoundException("unknown test provider source")
            }
        } catch (exception: ErrnoException) {
            throw sourceCreationFailure(exception)
        } catch (exception: IOException) {
            throw sourceCreationFailure(exception)
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val callerPackage = requireTargetCaller()
        val operationToken = parseOperationToken(extras)
        return when (method) {
            METHOD_RESET_PAUSED_PIPE -> {
                pausedSession.getAndSet(PausedPipeSession(operationToken))?.close()
                Bundle.EMPTY
            }
            METHOD_PAUSED_PIPE_STATUS -> {
                val session = requirePausedSession(operationToken)
                Bundle().apply {
                    putBoolean(STATUS_OPENED_KEY, session.opened.get())
                    putBoolean(STATUS_PREFIX_DELIVERED_KEY, session.prefixDelivered.count == 0L)
                    putBoolean(STATUS_WRITE_FAILED_KEY, session.writeFailed.get())
                    putBoolean(STATUS_TERMINATED_KEY, session.terminated.count == 0L)
                }
            }
            METHOD_RELEASE_PAUSED_PIPE -> {
                requirePausedSession(operationToken).release()
                Bundle.EMPTY
            }
            METHOD_OFFER_PERSISTABLE_READ_GRANT -> {
                val request = parseGrantRequest(extras, callerPackage, operationToken)
                requireContext().grantUriPermission(
                    request.targetPackage,
                    request.uri,
                    PERSISTABLE_READ_GRANT_FLAGS
                )
                Bundle.EMPTY
            }
            METHOD_REVOKE_READ_GRANT -> {
                val request = parseGrantRequest(extras, callerPackage, operationToken)
                requireContext().revokeUriPermission(
                    request.targetPackage,
                    request.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                Bundle.EMPTY
            }
            else -> throw IllegalArgumentException("unknown test provider method")
        }
    }

    override fun getType(uri: Uri): String = "text/plain"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    /** Creates one anonymous seekable descriptor containing a fixed byte count. */
    private fun createSeekableSource(
        sourceBytes: Long,
        operationToken: String
    ): ParcelFileDescriptor {
        val descriptor = Os.memfd_create("beautyxt-test-source", OsConstants.MFD_CLOEXEC)
        try {
            writeSourceBytes(descriptor, sourceBytes, operationToken)
            Os.lseek(descriptor, 0L, OsConstants.SEEK_SET)
            return ParcelFileDescriptor.dup(descriptor)
        } finally {
            closeRawDescriptor(descriptor)
        }
    }

    /** Creates one reliable nonseekable descriptor containing a fixed byte count. */
    private fun createStreamingSource(
        sourceBytes: Long,
        operationToken: String
    ): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val input = pipe[0]
        val provider = pipe[1]
        startProviderThread {
            try {
                writeSourceBytes(provider.fileDescriptor, sourceBytes, operationToken)
            } catch (_: ErrnoException) {
                // The importing reader may have closed before the provider writer.
            } finally {
                closeDescriptorQuietly(provider)
            }
        }
        return input
    }

    /** Creates a pipe that pauses after the importer consumes a bounded prefix. */
    private fun createPausedSource(operationToken: String): ParcelFileDescriptor {
        val session = requirePausedSession(operationToken)
        check(session.opened.compareAndSet(false, true)) {
            "paused test provider source was opened more than once"
        }
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val input = pipe[0]
        val provider = pipe[1]
        session.attachProvider(provider)
        startProviderThread {
            try {
                writeSourceBytes(provider.fileDescriptor, PAUSED_PREFIX_BYTES, operationToken)
                session.prefixDelivered.countDown()
                session.awaitRelease()
                writeTestBytes(provider.fileDescriptor, PAUSED_TERMINAL_PROBE_BYTES)
            } catch (_: ErrnoException) {
                session.writeFailed.set(true)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                session.writeFailed.set(true)
            } finally {
                session.prefixDelivered.countDown()
                session.closeProvider()
                session.terminated.countDown()
            }
        }
        return input
    }

    /** Creates a reliable pipe that reports an error after a valid prefix. */
    private fun createFailedSource(operationToken: String): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val input = pipe[0]
        val provider = pipe[1]
        startProviderThread {
            try {
                writeSourceBytes(
                    provider.fileDescriptor,
                    STREAM_CHUNK_BYTES.toLong(),
                    operationToken
                )
                provider.closeWithError(PROVIDER_ERROR_MESSAGE)
            } catch (_: ErrnoException) {
                closeDescriptorQuietly(provider)
            } catch (_: IOException) {
                closeDescriptorQuietly(provider)
            }
        }
        return input
    }

    private fun sourceCreationFailure(cause: Exception): FileNotFoundException =
        FileNotFoundException("test provider could not create source").apply { initCause(cause) }

    /** Parses and bounds the requested deterministic source length. */
    private fun parseSourceBytes(uri: Uri): Long {
        val sourceBytes = uri.getQueryParameter(SIZE_QUERY_PARAMETER)?.toLongOrNull()
            ?: throw FileNotFoundException("test source byte count is unavailable")
        if (sourceBytes !in MIN_SOURCE_BYTES..MAX_SOURCE_BYTES) {
            throw FileNotFoundException("test source byte count is outside supported limits")
        }
        return sourceBytes
    }

    /** Parses one validated operation token from a source URI. */
    private fun parseOperationToken(uri: Uri): String = try {
        validateOperationToken(uri.getQueryParameter(OPERATION_TOKEN_QUERY_PARAMETER))
    } catch (exception: IllegalArgumentException) {
        throw FileNotFoundException("test source operation token is unavailable").apply {
            initCause(exception)
        }
    }

    /** Parses one validated operation token from provider control extras. */
    private fun parseOperationToken(extras: Bundle?): String = validateOperationToken(
        requireNotNull(extras) { "operation token extras are unavailable" }
            .getString(OPERATION_TOKEN_KEY)
    )

    /** Validates one lowercase hexadecimal operation token. */
    private fun validateOperationToken(operationToken: String?): String {
        require(operationToken != null && operationToken.length == OPERATION_TOKEN_HEX_CHARS) {
            "operation token has an invalid length"
        }
        require(operationToken.all { it in '0'..'9' || it in 'a'..'f' }) {
            "operation token must be lowercase hexadecimal"
        }
        return operationToken
    }

    /** Returns the configured paused session for one operation. */
    private fun requirePausedSession(operationToken: String): PausedPipeSession {
        val session = checkNotNull(pausedSession.get()) {
            "paused test provider session is unavailable"
        }
        check(session.operationToken == operationToken) {
            "paused test provider operation token changed"
        }
        return session
    }

    /** Parses one provider-owned URI grant request. */
    private fun parseGrantRequest(
        extras: Bundle?,
        callerPackage: String,
        operationToken: String
    ): GrantRequest {
        requireNotNull(extras) { "grant request extras are unavailable" }
        val targetPackage = extras.getString(GRANT_TARGET_PACKAGE_KEY)
        val uriValue = extras.getString(GRANT_URI_KEY)
        require(!targetPackage.isNullOrBlank() && uriValue != null) {
            "grant request is incomplete"
        }
        if (targetPackage != callerPackage) {
            throw SecurityException("grant target does not match the test caller")
        }
        val uri = Uri.parse(uriValue)
        val expectedAuthority = requireContext().packageName + ".stateless-import"
        require(uri.scheme == "content" && uri.authority == expectedAuthority) {
            "grant request uri is outside the test provider"
        }
        val grantOperationToken =
            validateOperationToken(uri.getQueryParameter(OPERATION_TOKEN_QUERY_PARAMETER))
        require(operationToken == grantOperationToken) { "grant request operation token changed" }
        return GrantRequest(targetPackage, uri)
    }

    /** Returns the only target package permitted to use this test provider. */
    private fun requireTargetCaller(): String {
        val providerPackage = requireContext().packageName
        check(providerPackage.endsWith(TEST_PACKAGE_SUFFIX)) {
            "test provider package suffix is unavailable"
        }
        val targetPackage = providerPackage.removeSuffix(TEST_PACKAGE_SUFFIX)
        val callerPackage = callingPackage
        if (targetPackage != callerPackage) {
            throw SecurityException("test provider caller is not the target package")
        }
        return callerPackage
    }

    /** Starts one daemon writer whose lifetime is bounded by its descriptor. */
    private fun startProviderThread(action: Runnable) {
        Thread(action, PROVIDER_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    /** Coordinates one deliberately paused provider stream across Binder calls. */
    private class PausedPipeSession(val operationToken: String) : AutoCloseable {
        val opened = AtomicBoolean(false)
        val prefixDelivered = CountDownLatch(1)
        val writeFailed = AtomicBoolean(false)
        val terminated = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val provider = AtomicReference<ParcelFileDescriptor?>()

        /** Attaches the only provider-side descriptor before its writer starts. */
        fun attachProvider(descriptor: ParcelFileDescriptor) {
            check(provider.compareAndSet(null, descriptor)) {
                "paused provider descriptor is already attached"
            }
        }

        /** Waits until instrumentation permits the provider to report clean EOF. */
        fun awaitRelease() = release.await()

        /** Permits the provider writer to close normally. */
        fun release() = release.countDown()

        /** Closes the provider descriptor after its writer exits. */
        fun closeProvider() = closeDescriptorQuietly(provider.getAndSet(null))

        /** Releases and closes a session abandoned by instrumentation. */
        override fun close() {
            release()
            closeProvider()
        }
    }

    /** Stores one validated provider-owned URI grant request. */
    private class GrantRequest(val targetPackage: String, val uri: Uri)

    companion object {
        private const val READ_MODE = "r"
        private const val TEST_PACKAGE_SUFFIX = ".test.providers"
        private const val STREAM_CHUNK_BYTES = 64 * 1024
        private const val PAUSED_PREFIX_BYTES = STREAM_CHUNK_BYTES * 3L
        private const val PAUSED_TERMINAL_PROBE_BYTES = STREAM_CHUNK_BYTES * 4L
        private const val PERSISTABLE_READ_GRANT_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        private const val SOURCE_PREFIX_SEPARATOR: Byte = 0x0a
        private const val TEST_BYTE: Byte = 0x78
        private const val PROVIDER_ERROR_MESSAGE = "synthetic provider failure"
        private const val PROVIDER_THREAD_NAME = "BeauTyXT test provider"

        /** Writes one token-prefixed sequence without allocating document-sized memory. */
        private fun writeSourceBytes(
            descriptor: FileDescriptor,
            byteCount: Long,
            operationToken: String
        ) {
            val operationTokenBytes = operationToken.toByteArray(Charsets.US_ASCII)
            val minimumBytes = operationTokenBytes.size + 1L
            require(byteCount >= minimumBytes) { "test source cannot contain its operation token" }
            writeBytes(descriptor, operationTokenBytes, operationTokenBytes.size)
            val separator = byteArrayOf(SOURCE_PREFIX_SEPARATOR)
            writeBytes(descriptor, separator, separator.size)
            writeTestBytes(descriptor, byteCount - minimumBytes)
        }

        /** Writes deterministic filler without allocating document-sized memory. */
        private fun writeTestBytes(descriptor: FileDescriptor, byteCount: Long) {
            require(byteCount >= 0L) { "test byte count must be nonnegative" }
            val buffer = ByteArray(STREAM_CHUNK_BYTES) { TEST_BYTE }
            var remainingBytes = byteCount
            while (remainingBytes > 0L) {
                val requestedBytes = minOf(remainingBytes, buffer.size.toLong()).toInt()
                writeBytes(descriptor, buffer, requestedBytes)
                remainingBytes -= requestedBytes
            }
        }

        /** Writes an exact prefix of one buffer to a provider descriptor. */
        private fun writeBytes(descriptor: FileDescriptor, buffer: ByteArray, requestedBytes: Int) {
            require(requestedBytes in 0..buffer.size) { "requested test write exceeds its buffer" }
            var bufferOffset = 0
            while (bufferOffset < requestedBytes) {
                val remainingBytes = requestedBytes - bufferOffset
                val writtenBytes = try {
                    Os.write(descriptor, buffer, bufferOffset, remainingBytes)
                } catch (exception: InterruptedIOException) {
                    val transferredBytes = exception.bytesTransferred
                    if (transferredBytes !in 0..remainingBytes) {
                        throw IllegalStateException(
                            "interrupted test write reported an invalid byte count",
                            exception
                        )
                    }
                    bufferOffset += transferredBytes
                    continue
                }
                check(writtenBytes > 0) { "test provider made no write progress" }
                bufferOffset += writtenBytes
            }
        }

        /** Closes one raw descriptor without masking provider teardown. */
        private fun closeRawDescriptor(descriptor: FileDescriptor) {
            try {
                Os.close(descriptor)
            } catch (_: ErrnoException) {
                // Descriptor ownership has already ended.
            }
        }

        /** Closes one parcel descriptor without masking provider teardown. */
        private fun closeDescriptorQuietly(descriptor: ParcelFileDescriptor?) {
            try {
                descriptor?.close()
            } catch (_: IOException) {
                // Descriptor ownership has already ended.
            }
        }
    }
}
