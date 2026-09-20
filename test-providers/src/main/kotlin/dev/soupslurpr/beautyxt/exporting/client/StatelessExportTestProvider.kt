package dev.soupslurpr.beautyxt.exporting.client

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Receives deterministic export streams without retaining complete output. */
class StatelessExportTestProvider : ContentProvider() {
    private val activeSession = AtomicReference<SinkSession?>()

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        requireTargetCaller()
        if (mode != READ_WRITE_MODE && mode != WRITE_TRUNCATE_MODE) {
            throw FileNotFoundException("test provider supports output descriptors only")
        }
        val behavior = when (uri.pathSegments.singleOrNull()) {
            PATH_SINK -> SinkBehavior.NORMAL
            PATH_PAUSED_SINK -> SinkBehavior.PAUSED
            PATH_BLOCKED_SINK -> SinkBehavior.BLOCKED
            PATH_FAILED_SINK -> SinkBehavior.FAIL_AFTER_PREFIX
            PATH_DRAINED_FAILED_SINK -> SinkBehavior.FAIL_AFTER_COMPLETE_DRAIN
            else -> throw FileNotFoundException("unknown test provider sink")
        }
        val session = requireSession(parseOperationToken(uri))
        if (!session.opened.compareAndSet(false, true)) {
            throw FileNotFoundException("test provider sink was opened more than once")
        }
        val pipe = try {
            ParcelFileDescriptor.createReliablePipe()
        } catch (exception: IOException) {
            session.finish(true)
            throw FileNotFoundException("test provider could not create a sink").apply {
                initCause(exception)
            }
        }
        if (pipe.size != RELIABLE_PIPE_DESCRIPTOR_COUNT) {
            pipe.forEach(::closeDescriptorQuietly)
            session.finish(true)
            throw FileNotFoundException("reliable pipe returned an invalid descriptor pair")
        }
        val reader = pipe[RELIABLE_PIPE_READER_INDEX]
        val output = pipe[RELIABLE_PIPE_WRITER_INDEX]
        try {
            session.attachReader(reader)
            if (behavior == SinkBehavior.BLOCKED) {
                Os.fcntlInt(output.fileDescriptor, F_SETPIPE_SZ, BLOCKED_PIPE_BYTES)
            }
            startProviderThread { consumeSink(session, behavior) }
        } catch (exception: ErrnoException) {
            session.close()
            closeDescriptorQuietly(output)
            throw sinkStartupFailure(exception)
        } catch (exception: RuntimeException) {
            session.close()
            closeDescriptorQuietly(output)
            throw sinkStartupFailure(exception)
        }
        return output
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        requireTargetCaller()
        val operationToken = parseOperationToken(extras)
        return when (method) {
            METHOD_RESET_SINK -> {
                activeSession.getAndSet(SinkSession(operationToken))?.close()
                Bundle.EMPTY
            }
            METHOD_SINK_STATUS -> requireSession(operationToken).snapshot().toBundle()
            METHOD_RELEASE_PAUSED_SINK -> {
                requireSession(operationToken).releasePaused()
                Bundle.EMPTY
            }
            else -> throw IllegalArgumentException("unknown test provider method")
        }
    }

    override fun getType(uri: Uri): String = CONTENT_MIME_TYPE

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

    private fun sinkStartupFailure(cause: Exception): FileNotFoundException =
        FileNotFoundException("test provider could not start a sink").apply { initCause(cause) }

    /** Consumes one sink according to its deterministic test behavior. */
    private fun consumeSink(session: SinkSession, behavior: SinkBehavior) {
        var hasError = true
        try {
            when (behavior) {
                SinkBehavior.NORMAL -> consumeToEnd(session)
                SinkBehavior.PAUSED -> {
                    consumeExactPrefix(session, PAUSED_PREFIX_BYTES)
                    session.awaitPausedRelease()
                    consumeToEnd(session)
                }
                SinkBehavior.BLOCKED -> {
                    consumeExactPrefix(session, BLOCKED_PREFIX_BYTES)
                    session.awaitPausedRelease()
                    consumeToEnd(session)
                }
                SinkBehavior.FAIL_AFTER_PREFIX -> {
                    consumeExactPrefix(session, FAILED_PREFIX_BYTES)
                    session.failReader(PROVIDER_ERROR_MESSAGE)
                }
                SinkBehavior.FAIL_AFTER_COMPLETE_DRAIN -> {
                    session.signalReaderErrorPreservingData(PROVIDER_ERROR_MESSAGE)
                    consumeExactPrefix(session, DRAINED_FAILURE_BYTES)
                }
            }
            hasError = behavior == SinkBehavior.FAIL_AFTER_PREFIX ||
                behavior == SinkBehavior.FAIL_AFTER_COMPLETE_DRAIN
        } catch (_: ErrnoException) {
            hasError = true
        } catch (_: IOException) {
            hasError = true
        } catch (_: RuntimeException) {
            hasError = true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            hasError = true
        } finally {
            session.clearPaused()
            session.closeReader()
            session.finish(hasError)
        }
    }

    /** Reads one normal sink until reliable clean EOF. */
    private fun consumeToEnd(session: SinkSession) {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            val bytesRead = readBytes(session.readerDescriptor(), buffer, buffer.size)
            if (bytesRead == 0) {
                session.checkReaderError()
                return
            }
            session.record(buffer, bytesRead)
        }
    }

    /** Reads exactly one bounded prefix without consuming its following byte. */
    private fun consumeExactPrefix(session: SinkSession, byteCount: Long) {
        require(byteCount >= 0L) { "test sink prefix length must be nonnegative" }
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var remainingBytes = byteCount
        while (remainingBytes > 0L) {
            val requestedBytes = minOf(remainingBytes, buffer.size.toLong()).toInt()
            val bytesRead = readBytes(session.readerDescriptor(), buffer, requestedBytes)
            if (bytesRead == 0) {
                session.checkReaderError()
                throw IOException("test sink ended before its deterministic prefix")
            }
            session.record(buffer, bytesRead)
            remainingBytes -= bytesRead
        }
    }

    /** Reads one bounded chunk while preserving interrupted transfer progress. */
    private fun readBytes(descriptor: FileDescriptor, buffer: ByteArray, requestedBytes: Int): Int {
        require(requestedBytes in 0..buffer.size) { "requested test read exceeds its buffer" }
        while (true) {
            try {
                return Os.read(descriptor, buffer, 0, requestedBytes)
            } catch (exception: InterruptedIOException) {
                val transferredBytes = exception.bytesTransferred
                if (transferredBytes !in 0..requestedBytes) {
                    throw IllegalStateException(
                        "interrupted test read reported an invalid byte count",
                        exception
                    )
                }
                if (transferredBytes > 0) return transferredBytes
            }
        }
    }

    /** Parses one validated operation token from a sink URI. */
    private fun parseOperationToken(uri: Uri): String = try {
        validateOperationToken(uri.getQueryParameter(OPERATION_TOKEN_QUERY_PARAMETER))
    } catch (exception: IllegalArgumentException) {
        throw FileNotFoundException("test sink operation token is unavailable").apply {
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

    /** Returns the configured sink session for one operation. */
    private fun requireSession(operationToken: String): SinkSession {
        val session = checkNotNull(activeSession.get()) {
            "test provider sink session is unavailable"
        }
        check(session.operationToken == operationToken) {
            "test provider sink operation token changed"
        }
        return session
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

    /** Starts one daemon reader whose lifetime is bounded by its descriptor. */
    private fun startProviderThread(action: Runnable) {
        Thread(action, PROVIDER_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    private enum class SinkBehavior {
        NORMAL,
        PAUSED,
        BLOCKED,
        FAIL_AFTER_PREFIX,
        FAIL_AFTER_COMPLETE_DRAIN
    }

    /** Coordinates one descriptor reader and its bounded observations. */
    private class SinkSession(val operationToken: String) : AutoCloseable {
        val opened = AtomicBoolean(false)
        private val terminal = AtomicBoolean(false)
        private val error = AtomicBoolean(false)
        private val paused = AtomicBoolean(false)
        private val release = CountDownLatch(1)
        private val reader = AtomicReference<ParcelFileDescriptor?>()
        private val digest = createDigest()
        private val prefix = ByteArray(MAX_STATUS_SAMPLE_BYTES)
        private val suffix = ByteArray(MAX_STATUS_SAMPLE_BYTES)
        private var byteCount = 0L
        private var prefixLength = 0
        private var suffixLength = 0
        private var suffixStart = 0
        private var digestHex: String? = null

        /** Attaches the only reader descriptor before its worker starts. */
        fun attachReader(descriptor: ParcelFileDescriptor) {
            check(reader.compareAndSet(null, descriptor)) { "test sink reader is already attached" }
        }

        /** Returns the currently owned raw reader descriptor. */
        fun readerDescriptor(): FileDescriptor =
            checkNotNull(reader.get()) { "test sink reader is unavailable" }.fileDescriptor

        /** Checks whether a reliable writer reported a terminal error. */
        fun checkReaderError() {
            val descriptor = checkNotNull(reader.get()) { "test sink reader is unavailable" }
            if (descriptor.canDetectErrors()) descriptor.checkError()
        }

        /** Records one bounded chunk into counters, digest, and edge samples. */
        @Synchronized
        fun record(buffer: ByteArray, bytesRead: Int) {
            require(bytesRead in 1..buffer.size) {
                "recorded test sink bytes exceed the source buffer"
            }
            check(!terminal.get()) { "test sink session is already terminal" }
            val resultingByteCount = Math.addExact(byteCount, bytesRead.toLong())
            check(resultingByteCount <= MAX_SINK_BYTES) {
                "test sink exceeded its bounded byte count"
            }
            digest.update(buffer, 0, bytesRead)
            val prefixBytes = minOf(bytesRead, prefix.size - prefixLength)
            if (prefixBytes > 0) {
                buffer.copyInto(prefix, prefixLength, 0, prefixBytes)
                prefixLength += prefixBytes
            }
            appendSuffix(buffer, bytesRead)
            byteCount = resultingByteCount
        }

        /** Waits after publishing that the deterministic prefix was consumed. */
        fun awaitPausedRelease() {
            paused.set(true)
            try {
                release.await()
            } finally {
                paused.set(false)
            }
        }

        fun releasePaused() = release.countDown()

        fun clearPaused() = paused.set(false)

        /** Closes the reader with a bounded reliable-pipe error. */
        fun failReader(message: String) {
            val descriptor = reader.getAndSet(null) ?: return
            try {
                descriptor.closeWithError(message)
            } catch (_: IOException) {
                // Writer teardown still prevents further output.
            }
        }

        /** Reports failure while retaining a raw duplicate that can drain data. */
        fun signalReaderErrorPreservingData(message: String) {
            val reliableReader = checkNotNull(reader.get()) { "test sink reader is unavailable" }
            val drainingReader = ParcelFileDescriptor.dup(reliableReader.fileDescriptor)
            if (!reader.compareAndSet(reliableReader, drainingReader)) {
                closeDescriptorQuietly(drainingReader)
                error("test sink reader changed during failure setup")
            }
            try {
                reliableReader.closeWithError(message)
            } catch (exception: IOException) {
                closeReader()
                throw exception
            }
        }

        /** Closes the reader descriptor exactly once. */
        fun closeReader() = closeDescriptorQuietly(reader.getAndSet(null))

        /** Publishes one terminal digest and immutable status. */
        @Synchronized
        fun finish(hasError: Boolean) {
            if (!terminal.compareAndSet(false, true)) return
            error.set(hasError)
            digestHex = toLowercaseHex(digest.digest())
        }

        /** Returns one bounded, immutable metadata snapshot. */
        @Synchronized
        fun snapshot(): SinkSnapshot = SinkSnapshot(
            opened = opened.get(),
            terminal = terminal.get(),
            hasError = error.get(),
            paused = paused.get(),
            byteCount = byteCount,
            sha256Hex = digestHex,
            prefix = prefix.copyOf(prefixLength),
            suffix = orderedSuffix()
        )

        /** Releases and closes a session abandoned by instrumentation. */
        override fun close() {
            releasePaused()
            closeReader()
        }

        /** Appends one chunk to the bounded suffix ring without per-byte work. */
        private fun appendSuffix(buffer: ByteArray, bytesRead: Int) {
            if (bytesRead >= suffix.size) {
                buffer.copyInto(suffix, 0, bytesRead - suffix.size, bytesRead)
                suffixLength = suffix.size
                suffixStart = 0
                return
            }
            var sourceOffset = 0
            var remainingBytes = bytesRead
            if (suffixLength < suffix.size) {
                val appendedBytes = minOf(remainingBytes, suffix.size - suffixLength)
                val destinationOffset = (suffixStart + suffixLength) % suffix.size
                copyIntoSuffixRing(buffer, sourceOffset, destinationOffset, appendedBytes)
                suffixLength += appendedBytes
                sourceOffset += appendedBytes
                remainingBytes -= appendedBytes
            }
            if (remainingBytes > 0) {
                copyIntoSuffixRing(buffer, sourceOffset, suffixStart, remainingBytes)
                suffixStart = (suffixStart + remainingBytes) % suffix.size
            }
        }

        /** Copies one contiguous source range into the wrapping suffix ring. */
        private fun copyIntoSuffixRing(
            source: ByteArray,
            sourceOffset: Int,
            destinationOffset: Int,
            byteCount: Int
        ) {
            val firstCopyBytes = minOf(byteCount, suffix.size - destinationOffset)
            source.copyInto(suffix, destinationOffset, sourceOffset, sourceOffset + firstCopyBytes)
            val remainingBytes = byteCount - firstCopyBytes
            if (remainingBytes > 0) {
                source.copyInto(
                    suffix,
                    0,
                    sourceOffset + firstCopyBytes,
                    sourceOffset + firstCopyBytes + remainingBytes
                )
            }
        }

        /** Returns the suffix ring in logical byte order. */
        private fun orderedSuffix(): ByteArray {
            val ordered = ByteArray(suffixLength)
            val firstCopyBytes = minOf(suffixLength, suffix.size - suffixStart)
            suffix.copyInto(ordered, 0, suffixStart, suffixStart + firstCopyBytes)
            if (firstCopyBytes < suffixLength) {
                suffix.copyInto(ordered, firstCopyBytes, 0, suffixLength - firstCopyBytes)
            }
            return ordered
        }
    }

    /** Contains one bounded externally observable sink state. */
    private class SinkSnapshot(
        val opened: Boolean,
        val terminal: Boolean,
        val hasError: Boolean,
        val paused: Boolean,
        val byteCount: Long,
        val sha256Hex: String?,
        val prefix: ByteArray,
        val suffix: ByteArray
    ) {
        /** Encodes only bounded metadata for one provider control response. */
        fun toBundle(): Bundle = Bundle().apply {
            putBoolean(STATUS_OPENED_KEY, opened)
            putBoolean(STATUS_TERMINAL_KEY, terminal)
            putBoolean(STATUS_ERROR_KEY, hasError)
            putBoolean(STATUS_PAUSED_KEY, paused)
            putLong(STATUS_BYTE_COUNT_KEY, byteCount)
            putString(STATUS_SHA256_KEY, sha256Hex)
            putByteArray(STATUS_PREFIX_KEY, prefix)
            putByteArray(STATUS_SUFFIX_KEY, suffix)
        }
    }

    companion object {
        private const val READ_WRITE_MODE = "rw"
        private const val WRITE_TRUNCATE_MODE = "wt"
        private const val F_SETPIPE_SZ = 1031
        private const val BLOCKED_PREFIX_BYTES = 1L
        private const val TEST_PACKAGE_SUFFIX = ".test.providers"
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private const val PAUSED_PREFIX_BYTES = STREAM_BUFFER_BYTES.toLong()
        private const val FAILED_PREFIX_BYTES = STREAM_BUFFER_BYTES.toLong()
        private const val SHA256_ALGORITHM = "SHA-256"
        private const val SHA256_HEX_CHARS = 64
        private const val LOWERCASE_HEX_DIGITS = "0123456789abcdef"
        private const val PROVIDER_ERROR_MESSAGE = "synthetic export sink failure"
        private const val PROVIDER_THREAD_NAME = "BeauTyXT export test sink"
        private const val CONTENT_MIME_TYPE = "text/plain"
        private const val RELIABLE_PIPE_DESCRIPTOR_COUNT = 2
        private const val RELIABLE_PIPE_READER_INDEX = 0
        private const val RELIABLE_PIPE_WRITER_INDEX = 1

        /** Creates one SHA-256 digest required by the Android runtime. */
        private fun createDigest(): MessageDigest = try {
            MessageDigest.getInstance(SHA256_ALGORITHM)
        } catch (exception: NoSuchAlgorithmException) {
            throw AssertionError("sha-256 digest is unavailable", exception)
        }

        /** Encodes one digest with fixed lowercase hexadecimal digits. */
        private fun toLowercaseHex(digest: ByteArray): String {
            val encoded = CharArray(digest.size * 2)
            for (byteIndex in digest.indices) {
                val unsignedValue = digest[byteIndex].toInt() and 0xff
                encoded[byteIndex * 2] = LOWERCASE_HEX_DIGITS[unsignedValue ushr 4]
                encoded[byteIndex * 2 + 1] = LOWERCASE_HEX_DIGITS[unsignedValue and 0x0f]
            }
            check(encoded.size == SHA256_HEX_CHARS) { "sha-256 digest has an invalid length" }
            return encoded.concatToString()
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
