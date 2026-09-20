package dev.soupslurpr.beautyxt.importing.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import dev.soupslurpr.beautyxt.importing.IImportService
import dev.soupslurpr.beautyxt.importing.IsolatedImportService
import dev.soupslurpr.beautyxt.ipc.newIsolatedServiceInstanceName
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred

/** Owns one explicit isolated-service binding and its death observation. */
internal class IsolatedImportServiceBinding(context: Context) :
    ServiceConnection,
    IBinder.DeathRecipient,
    AutoCloseable {
    private val applicationContext = context.applicationContext
    private val instanceName = newIsolatedServiceInstanceName()
    private val connectedService = CompletableDeferred<IImportService>()
    private val service = AtomicReference<IImportService?>()
    private val serviceBinder = AtomicReference<IBinder?>()
    private val operationCompletion =
        AtomicReference<CompletableDeferred<ImportTerminalStatus>?>()
    private val connectionFailure = AtomicReference<DocumentImportException?>()
    private val closed = AtomicBoolean(false)
    private val bound = AtomicBoolean(false)

    /** Starts an explicit bind from the caller's background thread. */
    fun bind() {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "isolated import binding must start off the main thread"
        }
        check(!closed.get()) { "isolated import binding is closed" }
        val intent = Intent(applicationContext, IsolatedImportService::class.java)
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
                throw DocumentImportException(
                    DocumentImportFailure.SERVICE_UNAVAILABLE,
                    failure
                )
            }
        if (!accepted) {
            throw DocumentImportException(DocumentImportFailure.SERVICE_UNAVAILABLE)
        }
        bound.set(true)
        if (closed.get()) {
            unbindOnce()
            throw DocumentImportException(DocumentImportFailure.SERVICE_UNAVAILABLE)
        }
    }

    /** Waits cancellably for the first live service proxy. */
    suspend fun awaitService(): IImportService = connectedService.await()

    /** Routes terminal callbacks and service death through one completion gate. */
    fun attachOperation(completion: CompletableDeferred<ImportTerminalStatus>) {
        check(operationCompletion.compareAndSet(null, completion)) {
            "isolated import operation is already attached"
        }
        connectionFailure.get()?.let(completion::completeExceptionally)
    }

    /** Requests best-effort cancellation without waiting for an acknowledgement. */
    fun cancelImport(jobId: Long) {
        try {
            service.get()?.cancelImport(jobId)
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
            val importService = IImportService.Stub.asInterface(binder)
            if (!serviceBinder.compareAndSet(null, binder)) {
                binder.unlinkToDeath(this, 0)
                return
            }
            if (closed.get() || connectionFailure.get() != null) {
                serviceBinder.compareAndSet(binder, null)
                binder.unlinkToDeath(this, 0)
                return
            }
            service.set(importService)
            if (closed.get() || connectionFailure.get() != null) {
                service.compareAndSet(importService, null)
                serviceBinder.compareAndSet(binder, null)
                binder.unlinkToDeath(this, 0)
                return
            }
            if (!connectedService.complete(importService)) {
                service.compareAndSet(importService, null)
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
                // The remote binder is already dead or unlinked.
            }
        }
        service.set(null)
        val failure = DocumentImportException(DocumentImportFailure.SERVICE_UNAVAILABLE)
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
            DocumentImportException(
                failure = DocumentImportFailure.SERVICE_UNAVAILABLE,
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
