package dev.soupslurpr.beautyxt.exporting.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import dev.soupslurpr.beautyxt.exporting.IExportService
import dev.soupslurpr.beautyxt.exporting.IsolatedExportService
import dev.soupslurpr.beautyxt.ipc.newIsolatedServiceInstanceName
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred

/** Owns one explicit isolated export binding and its death observation. */
internal class IsolatedExportServiceBinding(context: Context) :
    ServiceConnection,
    IBinder.DeathRecipient,
    AutoCloseable {
    private val applicationContext = context.applicationContext
    private val instanceName = newIsolatedServiceInstanceName()
    private val connectedService = CompletableDeferred<IExportService>()
    private val service = AtomicReference<IExportService?>()
    private val serviceBinder = AtomicReference<IBinder?>()
    private val operationCompletion =
        AtomicReference<CompletableDeferred<*>?>()
    private val connectionFailure = AtomicReference<DocumentExportException?>()
    private val closed = AtomicBoolean(false)
    private val bound = AtomicBoolean(false)

    /** Starts an explicit bind from the caller's background thread. */
    fun bind() {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "isolated export binding must start off the main thread"
        }
        check(!closed.get()) { "isolated export binding is closed" }
        val intent = Intent(applicationContext, IsolatedExportService::class.java)
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
                throw DocumentExportException(
                    DocumentExportFailure.SERVICE_UNAVAILABLE,
                    failure
                )
            }
        if (!accepted) {
            throw DocumentExportException(DocumentExportFailure.SERVICE_UNAVAILABLE)
        }
        bound.set(true)
        if (closed.get()) {
            unbindOnce()
            throw DocumentExportException(DocumentExportFailure.SERVICE_UNAVAILABLE)
        }
    }

    /** Waits cancellably for the first live service proxy. */
    suspend fun awaitService(): IExportService = connectedService.await()

    /** Routes terminal callbacks and service death through one completion gate. */
    fun attachOperation(completion: CompletableDeferred<*>) {
        check(operationCompletion.compareAndSet(null, completion)) {
            "isolated export operation is already attached"
        }
        connectionFailure.get()?.let(completion::completeExceptionally)
    }

    /** Requests best-effort cancellation without waiting for acknowledgement. */
    fun cancelExport(jobId: Long) {
        try {
            service.get()?.cancelExport(jobId)
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
            val exportService = IExportService.Stub.asInterface(binder)
            if (!serviceBinder.compareAndSet(null, binder)) {
                binder.unlinkToDeath(this, 0)
                return
            }
            service.set(exportService)
            if (closed.get() || connectionFailure.get() != null) {
                service.compareAndSet(exportService, null)
                serviceBinder.compareAndSet(binder, null)
                binder.unlinkToDeath(this, 0)
                return
            }
            if (!connectedService.complete(exportService)) {
                service.compareAndSet(exportService, null)
                serviceBinder.compareAndSet(binder, null)
                binder.unlinkToDeath(this, 0)
            }
        } catch (failure: RemoteException) {
            signalFailure(failure)
        } catch (failure: RuntimeException) {
            signalFailure(failure)
        }
    }

    override fun onNullBinding(name: ComponentName) {
        signalFailure()
    }

    override fun onBindingDied(name: ComponentName) {
        signalFailure()
    }

    override fun onServiceDisconnected(name: ComponentName) {
        signalFailure()
    }

    override fun binderDied() {
        signalFailure()
    }

    /** Unlinks death observation and releases the explicit binding once. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        serviceBinder.getAndSet(null)?.let { binder ->
            try {
                binder.unlinkToDeath(this, 0)
            } catch (_: RuntimeException) {
                // The remote Binder is already dead or unlinked.
            }
        }
        service.set(null)
        val failure = DocumentExportException(DocumentExportFailure.SERVICE_UNAVAILABLE)
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
        val failure =
            DocumentExportException(
                failure = DocumentExportFailure.SERVICE_UNAVAILABLE,
                cause = cause
            )
        if (!connectionFailure.compareAndSet(null, failure)) {
            return
        }
        service.set(null)
        connectedService.completeExceptionally(failure)
        operationCompletion.get()?.completeExceptionally(failure)
    }
}
