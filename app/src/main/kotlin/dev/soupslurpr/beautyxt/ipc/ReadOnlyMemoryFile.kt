package dev.soupslurpr.beautyxt.ipc

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Seekable read-only capabilities over immutable anonymous memory, with independent positions. */
internal object ReadOnlyMemoryFile {
    private const val MAX_READERS = 32
    private val readers = AtomicInteger()
    private val handler by lazy {
        Handler(HandlerThread("excerpt-readers").apply { start() }.looper)
    }

    fun open(context: Context, source: ParcelFileDescriptor, byteCount: Long): ParcelFileDescriptor {
        if (readers.incrementAndGet() > MAX_READERS) {
            readers.decrementAndGet()
            throw IOException("Too many open excerpt readers")
        }
        var owned: ParcelFileDescriptor? = null
        val released = AtomicBoolean()
        fun release() {
            if (!released.compareAndSet(false, true)) return
            try { owned?.close() } catch (_: IOException) {
                // Descriptor cleanup must not stop the shared reader thread.
            } finally { readers.decrementAndGet() }
        }
        try {
            val retained = ParcelFileDescriptor.dup(source.fileDescriptor).also { owned = it }
            val callback = object : ProxyFileDescriptorCallback() {
                override fun onGetSize() = byteCount
                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    if (offset < 0 || size < 0 || size > data.size) {
                        throw ErrnoException("excerpt read", OsConstants.EINVAL)
                    }
                    val wanted = minOf(size.toLong(), (byteCount - offset).coerceAtLeast(0)).toInt()
                    var read = 0
                    while (read < wanted) {
                        val count = Os.pread(retained.fileDescriptor, data, read, wanted - read, offset + read)
                        if (count <= 0) throw ErrnoException("excerpt read", OsConstants.EIO)
                        read += count
                    }
                    return read
                }
                override fun onRelease() {
                    release()
                }
            }
            return checkNotNull(context.getSystemService(StorageManager::class.java))
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, handler)
        } catch (failure: Throwable) {
            release()
            throw failure
        }
    }
}
