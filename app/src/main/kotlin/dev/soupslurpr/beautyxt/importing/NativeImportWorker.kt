package dev.soupslurpr.beautyxt.importing

private const val IMPORT_JNI_LIBRARY = "beautyxt_import_jni"

/** Exposes the native byte-preserving UTF-8 import worker. */
internal object NativeImportWorker {
    init {
        System.loadLibrary(IMPORT_JNI_LIBRARY)
    }

    /** Creates an import job and returns its native handle. */
    @JvmStatic
    external fun createJob(): Long

    /** Validates and copies a bounded source while reporting bytes, flags, and SHA-256. */
    @JvmStatic
    external fun runJob(
        jobHandle: Long,
        inputFileDescriptor: Int,
        outputFileDescriptor: Int,
        maxInputBytes: Long,
        maxOutputBytes: Long,
        timeoutMillis: Long,
        resultValues: LongArray,
        resultSha256: ByteArray
    ): Int

    /** Requests cancellation of a running job. */
    @JvmStatic
    external fun cancelJob(jobHandle: Long)

    /** Destroys a native job handle. */
    @JvmStatic
    external fun destroyJob(jobHandle: Long)
}
