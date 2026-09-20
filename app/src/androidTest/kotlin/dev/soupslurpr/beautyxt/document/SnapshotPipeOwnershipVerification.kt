/* Verifies descriptor lifetimes across interrupted snapshot production. */
package dev.soupslurpr.beautyxt.document

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import dev.soupslurpr.beautyxt.ipc.ReliableSnapshotPipe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val SNAPSHOT_OWNERSHIP_TIMEOUT_SECONDS = 5L
private const val SNAPSHOT_TIMEOUT_MILLIS = 30_000L

/** Keeps borrowed native descriptors alive through failure and owner teardown. */
internal fun verifySnapshotPipeDescriptorOwnership() {
    for (closeOwner in listOf(false, true)) {
        ReliableSnapshotPipe.create(SNAPSHOT_TIMEOUT_MILLIS).use { pipe ->
            pipe.takeReader().use {
                val entered = CountDownLatch(1)
                val interrupted = CountDownLatch(1)
                val executor = Executors.newSingleThreadExecutor()
                val snapshot = object : EditorDocumentSnapshot {
                    override fun writeSnapshot(
                        outputRawFileDescriptor: Int,
                        cancellationRawFileDescriptor: Int,
                        timeoutMillis: Long
                    ): Long {
                        entered.countDown()
                        check(
                            interrupted.await(SNAPSHOT_OWNERSHIP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        ) {
                            "snapshot interruption was not released"
                        }
                        // Check both capabilities after teardown wins the JNI entry race.
                        for (descriptor in intArrayOf(
                            outputRawFileDescriptor,
                            cancellationRawFileDescriptor
                        )) {
                            ParcelFileDescriptor.fromFd(descriptor).use { duplicate ->
                                check(
                                    OsConstants.S_ISFIFO(Os.fstat(duplicate.fileDescriptor).st_mode)
                                ) {
                                    "borrowed snapshot descriptor changed its kernel object"
                                }
                            }
                        }
                        return 0L
                    }

                    override fun close() = Unit
                }
                val producer = executor.submit<Long> { pipe.writeSnapshot(snapshot) }
                try {
                    check(entered.await(SNAPSHOT_OWNERSHIP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "snapshot producer did not start"
                    }
                    if (closeOwner) pipe.close() else pipe.failWriter()
                    interrupted.countDown()
                    check(
                        producer.get(SNAPSHOT_OWNERSHIP_TIMEOUT_SECONDS, TimeUnit.SECONDS) == 0L
                    ) {
                        "snapshot probe returned an unexpected length"
                    }
                } finally {
                    interrupted.countDown()
                    executor.shutdownNow()
                    check(
                        executor.awaitTermination(
                            SNAPSHOT_OWNERSHIP_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                        )
                    ) {
                        "snapshot producer did not terminate"
                    }
                }
            }
        }
    }
}
