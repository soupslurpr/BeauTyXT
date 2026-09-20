package dev.soupslurpr.beautyxt.transfer

private const val TRANSFER_JNI_LIBRARY = "beautyxt_transfer_jni"

/** Exposes the native bounded local-transfer worker. */
internal object NativeTransferWorker {
    init {
        System.loadLibrary(TRANSFER_JNI_LIBRARY)
    }

    /** Creates a transfer job and returns its native handle. */
    @JvmStatic
    external fun createJob(): Long

    /** Runs one descriptor-based QR or NFC transformation. */
    @JvmStatic
    external fun runJob(
        jobHandle: Long,
        operation: Int,
        inputFileDescriptor: Int,
        outputFileDescriptor: Int,
        expectedInputBytes: Long,
        argumentZero: Long,
        argumentOne: Long,
        timeoutMillis: Long,
        resultValues: LongArray
    ): Int

    /** Requests cancellation of a running transfer job. */
    @JvmStatic
    external fun cancelJob(jobHandle: Long)

    /** Destroys a native transfer job handle. */
    @JvmStatic
    external fun destroyJob(jobHandle: Long)
}
