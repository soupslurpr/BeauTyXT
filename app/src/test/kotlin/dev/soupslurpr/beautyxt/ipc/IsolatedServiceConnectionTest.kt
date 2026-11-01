package dev.soupslurpr.beautyxt.ipc

import android.os.IBinder
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies shared wrapper ownership at controlled callback/close boundaries. */
class IsolatedServiceConnectionTest {
    private val recipient = IBinder.DeathRecipient {}

    @Test
    fun lateDecodedProxyCannotPublishAfterClose(): Unit = runBlocking {
        val state = connection()
        val binder = BinderFixture()
        state.connect(binder.proxy, recipient) {
            state.close(recipient)
            "late proxy"
        }
        assertNull(state.remote)
        assertEquals(1, binder.links)
        assertEquals(1, binder.unlinks)
        assertThrows(IllegalStateException::class.java) { runBlocking { state.awaitService() } }
    }

    @Test
    fun failureDuringDecodeCannotPublishAProxy() {
        val state = connection()
        val binder = BinderFixture()
        state.connect(binder.proxy, recipient) {
            state.fail()
            "failed proxy"
        }
        assertNull(state.remote)
        assertEquals(1, binder.unlinks)
    }

    @Test
    fun operationOfferedAfterCloseFailsImmediately() {
        val state = connection()
        state.close(recipient)
        val result = CompletableDeferred<Unit>()
        state.attach(result)
        assertTrue(result.isCancelled)
    }

    @Test
    fun healthyProxyAndRepeatedOperationsRemainAvailableUntilClose(): Unit = runBlocking {
        val state = connection()
        val binder = BinderFixture()
        state.connect(binder.proxy, recipient) { "live proxy" }
        assertEquals("live proxy", state.awaitService())
        val first = CompletableDeferred<Unit>()
        state.attach(first)
        first.complete(Unit)
        state.detach(first)
        val next = CompletableDeferred<Unit>()
        state.attach(next)
        state.detach(first)
        assertFalse(next.isCompleted)
        state.close(recipient)
        state.close(recipient)
        assertTrue(next.isCancelled)
        assertNull(state.remote)
        assertEquals(1, binder.unlinks)
        assertThrows(IllegalStateException::class.java) { runBlocking { state.awaitService() } }
    }

    private fun connection() = IsolatedServiceConnection<String> { cause ->
        IllegalStateException("synthetic unavailable service", cause)
    }

    private class BinderFixture {
        var links = 0
        var unlinks = 0
        val proxy: IBinder = Proxy.newProxyInstance(
            IBinder::class.java.classLoader, arrayOf(IBinder::class.java)
        ) { instance, method, arguments ->
            when (method.name) {
                "linkToDeath" -> { links++; null }
                "unlinkToDeath" -> { unlinks++; true }
                "equals" -> instance === arguments?.get(0)
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "synthetic Binder"
                else -> null
            }
        } as IBinder
    }
}
