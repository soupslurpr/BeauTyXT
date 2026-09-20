package dev.soupslurpr.beautyxt.exporting

private const val EXPORT_JNI_LIBRARY = "beautyxt_export_jni"

/** Exposes the native bounded export worker. */
internal object NativeExportWorker {
    init {
        System.loadLibrary(EXPORT_JNI_LIBRARY)
    }

    /** Creates an export job and returns its native handle. */
    @JvmStatic
    external fun createJob(): Long

    /** Runs one exact descriptor-based export and fills its byte counts. */
    @JvmStatic
    external fun runJob(
        jobHandle: Long,
        inputFileDescriptor: Int,
        outputFileDescriptor: Int,
        expectedBytes: Long,
        timeoutMillis: Long,
        resultValues: LongArray
    ): Int

    /** Runs one conditional source replacement and returns its verified digest. */
    @JvmStatic
    external fun runConditionalSourceSaveJob(
        jobHandle: Long,
        packageFileDescriptor: Int,
        sourceBackingFileDescriptor: Int,
        sourceFileDescriptor: Int,
        expectedBytes: Long,
        expectedSourceBytes: Long,
        expectedSourceSha256: ByteArray,
        timeoutMillis: Long,
        resultValues: LongArray,
        resultSha256: ByteArray
    ): Int

    /** Verifies one source version without mutating its descriptor. */
    @JvmStatic
    external fun runSourceVerificationJob(
        jobHandle: Long,
        sourceFileDescriptor: Int,
        expectedSourceBytes: Long,
        expectedSourceSha256: ByteArray,
        timeoutMillis: Long,
        resultValues: LongArray
    ): Int

    /** Inspects one bounded source and returns its exact version without mutation. */
    @JvmStatic
    external fun runSourceInspectionJob(
        jobHandle: Long,
        sourceFileDescriptor: Int,
        maximumSourceBytes: Long,
        timeoutMillis: Long,
        resultValues: LongArray,
        resultSha256: ByteArray
    ): Int

    /** Requests cancellation of a running export job. */
    @JvmStatic
    external fun cancelJob(jobHandle: Long)

    /** Destroys a native export job handle. */
    @JvmStatic
    external fun destroyJob(jobHandle: Long)
}
