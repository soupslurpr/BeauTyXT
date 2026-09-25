package dev.soupslurpr.beautyxt.ui.transfer

import androidx.camera.core.ImageProxy
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.testing.QueuedSessionTestDispatcher
import dev.soupslurpr.beautyxt.transfer.client.QrCodeGrid
import dev.soupslurpr.beautyxt.transfer.client.QrFrameDecoder
import dev.soupslurpr.beautyxt.transfer.client.QrLuminanceFrame
import dev.soupslurpr.beautyxt.transfer.client.QrTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.ReceivedTransferText
import dev.soupslurpr.beautyxt.transfer.client.TransferException
import dev.soupslurpr.beautyxt.transfer.client.TransferFailure
import dev.soupslurpr.beautyxt.ui.UiText
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises frame freshness, bounded backpressure, and scanner cancellation. */
class QrFrameAnalyzerTest {
    @Test
    fun startsTheDecoderBeforeTakingAFreshFrame() {
        Fixture(ready = false).use { fixture ->
            assertEquals(1, fixture.processor.opened)
            val stale = fixture.offer(1)
            assertEquals(1, stale.closes)
            assertEquals(0, stale.planeReads)

            fixture.processor.ready.complete(Unit)
            fixture.drain()
            val fresh = fixture.offer(2)
            fixture.drain()

            assertEquals(listOf(2), fixture.processor.decoded)
            assertEquals(listOf("2"), fixture.received.map { it.text })
            assertEquals(1, fresh.closes)
            assertEquals(1, fixture.processor.closed)
        }
    }

    @Test
    fun missesReuseOneSessionAndBusyFramesAreDiscarded() {
        Fixture().use { fixture ->
            val gate = CompletableDeferred<Unit>()
            fixture.processor.decode = { frame ->
                if (frame.bytes[0].toInt() == 1) {
                    gate.await()
                    throw TransferException(TransferFailure.NoQrCode)
                }
                received(frame)
            }
            fixture.offer(1)
            fixture.drain()
            val busy = fixture.offer(2)
            assertEquals(1, busy.closes)
            assertEquals(0, busy.planeReads)
            gate.complete(Unit)
            fixture.drain()
            fixture.offer(3)
            fixture.drain()

            assertEquals(listOf(1, 3), fixture.processor.decoded)
            assertEquals(listOf("3"), fixture.received.map { it.text })
            assertTrue(fixture.failures.isEmpty())
            assertEquals(1, fixture.processor.opened)
            assertEquals(1, fixture.processor.closed)
            val afterSuccess = fixture.offer(4)
            assertEquals(0, afterSuccess.planeReads)
            assertEquals(1, afterSuccess.closes)
        }
    }

    @Test
    fun unrelatedAndAmbiguousCodesDoNotReplaceTheSession() {
        Fixture().use { fixture ->
            fixture.processor.decode = { frame ->
                when (frame.bytes[0].toInt()) {
                    1 -> throw TransferException(TransferFailure.Unsupported)
                    2 -> throw TransferException(TransferFailure.AmbiguousQr)
                    else -> received(frame)
                }
            }
            for (value in 1..3) {
                fixture.offer(value)
                fixture.drain()
            }
            assertEquals(2, fixture.failures.size)
            assertEquals(listOf("3"), fixture.received.map { it.text })
            assertEquals(1, fixture.processor.opened)
        }
    }

    @Test
    fun closingDuringPreparationOrAnIdleWaitReleasesTheSession() {
        for (ready in listOf(false, true)) {
            Fixture(ready).use { fixture ->
                fixture.analyzer.close()
                fixture.drain()
                fixture.processor.ready.complete(Unit)
                val late = fixture.offer(1)
                fixture.drain()
                assertEquals(1, fixture.processor.closed)
                assertEquals(1, late.closes)
                assertEquals(0, late.planeReads)
                assertTrue(fixture.processor.decoded.isEmpty())
                assertTrue(fixture.received.isEmpty())
            }
        }
    }

    @Test
    fun closingWithAQueuedFrameDoesNotStartItsDecode() {
        Fixture().use { fixture ->
            val queued = fixture.offer(1)
            fixture.analyzer.close()
            fixture.drain()
            assertEquals(1, queued.closes)
            assertEquals(1, fixture.processor.closed)
            assertTrue(fixture.processor.decoded.isEmpty())
            assertTrue(fixture.received.isEmpty())
        }
    }

    @Test
    fun closingDuringDecodeSuppressesTheLateResult() {
        Fixture().use { fixture ->
            val gate = CompletableDeferred<Unit>()
            fixture.processor.decode = { frame ->
                gate.await()
                received(frame)
            }
            fixture.offer(1)
            fixture.drain()
            fixture.analyzer.close()
            fixture.drain()
            gate.complete(Unit)
            fixture.drain()
            assertEquals(1, fixture.processor.closed)
            assertTrue(fixture.received.isEmpty())
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun invalidCameraStorageDoesNotStrandThePreparedDecoder() {
        Fixture().use { fixture ->
            val invalid = CameraFrame(1, truncated = true)
            fixture.analyzer.analyze(invalid.image)
            fixture.drain()
            assertEquals(1, invalid.closes)
            assertEquals(1, fixture.failures.size)
            fixture.offer(2)
            fixture.drain()
            assertEquals(listOf("2"), fixture.received.map { it.text })
            assertEquals(1, fixture.processor.opened)
        }
    }

    private class Fixture(ready: Boolean = true) : AutoCloseable {
        private val dispatcher = QueuedSessionTestDispatcher()
        val processor = Processor().also { if (ready) it.ready.complete(Unit) }
        val received = mutableListOf<ReceivedTransferText>()
        val failures = mutableListOf<UiText>()
        val analyzer = QrFrameAnalyzer(processor, failures::add, received::add, dispatcher)

        init { drain() }

        fun drain() = dispatcher.runAll()

        fun offer(value: Int): CameraFrame = CameraFrame(value).also {
            analyzer.analyze(it.image)
        }

        override fun close() {
            analyzer.close()
            drain()
        }
    }

    private class Processor : QrTransferProcessor {
        val ready = CompletableDeferred<Unit>()
        val decoded = mutableListOf<Int>()
        var opened = 0
        var closed = 0
        var decode: suspend (QrLuminanceFrame) -> ReceivedTransferText = ::received

        override suspend fun <T> withQrDecoder(operation: suspend (QrFrameDecoder) -> T): T {
            opened++
            try {
                ready.await()
                return operation(QrFrameDecoder { frame ->
                    decoded += frame.bytes[0].toInt()
                    decode(frame)
                })
            } finally {
                closed++
            }
        }

        override suspend fun decodeQr(frame: QrLuminanceFrame): ReceivedTransferText =
            error("The scanner must use its session decoder")

        override suspend fun encodeQr(
            snapshot: EditorDocumentSnapshot,
            expectedBytes: Long,
            format: DocumentFormat
        ): QrCodeGrid = error("Scanning never encodes documents")
    }

    private class CameraFrame(value: Int, truncated: Boolean = false) {
        var closes = 0
        var planeReads = 0
        private val bytes = ByteArray(if (truncated) 1 else 48 * 48) { value.toByte() }
        private val plane = proxy(ImageProxy.PlaneProxy::class.java) { method ->
            when (method) {
                "getBuffer" -> ByteBuffer.wrap(bytes)
                "getRowStride" -> 48
                "getPixelStride" -> 1
                else -> error("Unexpected plane access: $method")
            }
        }
        val image = proxy(ImageProxy::class.java) { method ->
            when (method) {
                "getWidth", "getHeight" -> 48
                "getPlanes" -> { planeReads++; arrayOf(plane) }
                "close" -> { closes++; null }
                else -> error("Unexpected camera access: $method")
            }
        }
    }

    companion object {
        private fun received(frame: QrLuminanceFrame) = ReceivedTransferText(
            text = frame.bytes[0].toString(),
            format = DocumentFormat.PlainText
        )

        private fun <T> proxy(type: Class<T>, invoke: (String) -> Any?): T =
            checkNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                invoke(method.name)
            }))
    }
}
