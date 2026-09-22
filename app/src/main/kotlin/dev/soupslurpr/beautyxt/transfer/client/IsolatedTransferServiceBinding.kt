package dev.soupslurpr.beautyxt.transfer.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import dev.soupslurpr.beautyxt.ipc.NativeServiceNames
import dev.soupslurpr.beautyxt.ipc.newIsolatedServiceInstanceName
import dev.soupslurpr.beautyxt.transfer.ITransferService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred

/** Owns one explicit transfer-service binding and its death observation. */
internal class IsolatedTransferServiceBinding(context: Context) :
    ServiceConnection,
    IBinder.DeathRecipient,
    AutoCloseable {
    private val applicationContext = context.applicationContext
    private val instanceName = newIsolatedServiceInstanceName()
    private val connectedService = CompletableDeferred<ITransferService>()
    private val service = AtomicReference<ITransferService?>()
    private val serviceBinder = AtomicReference<IBinder?>()
    private val operationCompletion =
        AtomicReference<CompletableDeferred<TransferTerminalStatus>?>()
    private val connectionFailure = AtomicReference<TransferException?>()
    private val closed = AtomicBoolean(false)
    private val bound = AtomicBoolean(false)

    /** Starts one explicit bind from the caller's background thread. */
    fun bind() {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "isolated transfer binding must start off the main thread"
        }
        check(!closed.get()) { "isolated transfer binding is closed" }
        val intent = Intent().setClassName(applicationContext, NativeServiceNames.TRANSFER)
        val accepted =
            try {
                applicationContext.bindIsolatedService(
                    intent,
                    Context.BIND_AUTO_CREATE,
                    instanceName,
                    applicationContext.mainExecutor,
                    this
                )
            } catch (failure: Exception) {
                throw TransferException(TransferFailure.ServiceUnavailable, failure)
            }
        if (!accepted) {
            throw TransferException(TransferFailure.ServiceUnavailable)
        }
        bound.set(true)
        if (closed.get()) {
            unbindOnce()
            throw TransferException(TransferFailure.ServiceUnavailable)
        }
    }

    /** Waits cancellably for the first live service proxy. */
    suspend fun awaitService(): ITransferService = connectedService.await()

    /** Routes terminal callbacks and service death through one completion gate. */
    fun attachOperation(completion: CompletableDeferred<TransferTerminalStatus>) {
        check(operationCompletion.compareAndSet(null, completion)) {
            "isolated transfer operation is already attached"
        }
        connectionFailure.get()?.let(completion::completeExceptionally)
    }

    /** Requests best-effort cancellation without waiting for acknowledgement. */
    fun cancelTransfer(jobId: Long) {
        try {
            service.get()?.cancelTransfer(jobId)
        } catch (_: RemoteException) {
            // Binding teardown remains authoritative when the service is unavailable.
        } catch (_: RuntimeException) {
            // Binding teardown remains authoritative when the service is unavailable.
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        if (closed.get() || connectionFailure.get() != null) {
            return
        }
        try {
            binder.linkToDeath(this, 0)
            if (closed.get() || connectionFailure.get() != null) {
                binder.unlinkToDeath(this, 0)
                return
            }
            val transferService = ITransferService.Stub.asInterface(binder)
            if (!serviceBinder.compareAndSet(null, binder)) {
                binder.unlinkToDeath(this, 0)
                return
            }
            service.set(transferService)
            if (!connectedService.complete(transferService)) {
                service.compareAndSet(transferService, null)
                serviceBinder.compareAndSet(binder, null)
                binder.unlinkToDeath(this, 0)
            }
        } catch (failure: RemoteException) {
            signalFailure(failure)
        } catch (failure: RuntimeException) {
            signalFailure(failure)
        }
    }

    override fun onNullBinding(name: ComponentName) = signalFailure()

    override fun onBindingDied(name: ComponentName) = signalFailure()

    override fun onServiceDisconnected(name: ComponentName) = signalFailure()

    override fun binderDied() = signalFailure()

    /** Unlinks death observation and releases the explicit binding once. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        serviceBinder.getAndSet(null)?.let { binder ->
            try {
                binder.unlinkToDeath(this, 0)
            } catch (_: RuntimeException) {
                // The remote binder is already dead or unlinked.
            }
        }
        service.set(null)
        val failure = TransferException(TransferFailure.ServiceUnavailable)
        connectedService.completeExceptionally(failure)
        operationCompletion.getAndSet(null)?.completeExceptionally(failure)
        unbindOnce()
    }

    /** Releases a successfully registered connection exactly once. */
    private fun unbindOnce() {
        if (bound.compareAndSet(true, false)) {
            try {
                applicationContext.unbindService(this)
            } catch (_: RuntimeException) {
                // The connection is already absent from the application context.
            }
        }
    }

    /** Fails connection and operation waits exactly once. */
    private fun signalFailure(cause: Throwable? = null) {
        val failure = TransferException(TransferFailure.ServiceUnavailable, cause)
        if (!connectionFailure.compareAndSet(null, failure)) {
            return
        }
        service.set(null)
        connectedService.completeExceptionally(failure)
        operationCompletion.get()?.completeExceptionally(failure)
    }
}
