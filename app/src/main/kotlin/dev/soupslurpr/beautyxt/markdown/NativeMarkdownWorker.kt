package dev.soupslurpr.beautyxt.markdown

private const val MARKDOWN_JNI_LIBRARY = "beautyxt_markdown_jni"

/** Exposes the native bounded Markdown renderer. */
internal object NativeMarkdownWorker {
    init {
        System.loadLibrary(MARKDOWN_JNI_LIBRARY)
    }

    /** Creates a Markdown job and returns its native handle. */
    @JvmStatic
    external fun createJob(): Long

    /** Parses one exact input snapshot into a bounded render packet. */
    @JvmStatic
    external fun runJob(
        jobHandle: Long,
        inputFileDescriptor: Int,
        outputFileDescriptor: Int,
        expectedInputBytes: Long,
        maxPacketBytes: Long,
        timeoutMillis: Long,
        resultValues: LongArray
    ): Int

    /** Requests cancellation of a running job. */
    @JvmStatic
    external fun cancelJob(jobHandle: Long)

    /** Destroys a native job handle. */
    @JvmStatic
    external fun destroyJob(jobHandle: Long)
}
