package dev.soupslurpr.beautyxt.illustration

import android.os.Process
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/** Debug-only fixture for worker hangs/crashes, without depending on an upstream parser bug. */
class IsolatedIllustrationProbeService : IsolatedIllustrationService() {
    override val maximumInputBytes = 16
    override val timeoutMillis = 250L

    override fun renderNative(source: ByteArray, display: Boolean): ByteArray {
        when (source.toString(Charsets.UTF_8)) {
            "hang" -> CountDownLatch(1).await()
            "crash" -> Process.killProcess(Process.myPid())
        }
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array()
    }
}
