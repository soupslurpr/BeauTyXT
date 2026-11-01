package dev.soupslurpr.beautyxt.exporting.client

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import dev.soupslurpr.beautyxt.exporting.IExportService
import android.content.Intent
import dev.soupslurpr.beautyxt.ipc.NativeServiceNames
import dev.soupslurpr.beautyxt.ipc.IsolatedServiceConnection
import dev.soupslurpr.beautyxt.ipc.IsolatedServiceRegistration
import dev.soupslurpr.beautyxt.ipc.newIsolatedServiceInstanceName
import kotlinx.coroutines.CompletableDeferred

/** Owns one explicit export binding and serializes its publication against close. */
internal class IsolatedExportServiceBinding(context: Context) :
    ServiceConnection,
    IBinder.DeathRecipient,
    AutoCloseable {
    private val applicationContext = context.applicationContext
    private val instanceName = newIsolatedServiceInstanceName()
    private val state = IsolatedServiceConnection<IExportService> { cause ->
        DocumentExportException(DocumentExportFailure.SERVICE_UNAVAILABLE, cause)
    }
    private val registration = IsolatedServiceRegistration {
        applicationContext.unbindService(this)
    }

    /** Starts an explicit bind without holding the connection gate across Android calls. */
    fun bind() {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "isolated export binding must start off the main thread"
        }
        check(!state.isClosed) { "isolated export binding is closed" }
        try {
            val accepted = registration.bind {
                applicationContext.bindIsolatedService(
                    Intent().setClassName(applicationContext, NativeServiceNames.EXPORT),
                    Context.BIND_AUTO_CREATE,
                    instanceName,
                    applicationContext.mainExecutor,
                    this
                )
            }
            if (!accepted || state.isClosed) {
                registration.close()
                throw DocumentExportException(DocumentExportFailure.SERVICE_UNAVAILABLE)
            }
        } catch (failure: DocumentExportException) {
            throw failure
        } catch (failure: Exception) {
            throw DocumentExportException(DocumentExportFailure.SERVICE_UNAVAILABLE, failure)
        }
    }

    suspend fun awaitService(): IExportService = state.awaitService()

    /** Routes operation attachment through the same closed-state gate as publication. */
    fun attachOperation(completion: CompletableDeferred<*>) {
        state.attach(completion)
    }

    /** Requests best-effort cancellation without waiting for acknowledgement. */
    fun cancelExport(jobId: Long) {
        try {
            state.remote?.cancelExport(jobId)
        } catch (_: RemoteException) {
            // Binding teardown remains authoritative when the service is unavailable.
        } catch (_: RuntimeException) {
            // Binding teardown remains authoritative when the service is unavailable.
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        state.connect(binder, this, IExportService.Stub::asInterface)
    }

    override fun onNullBinding(name: ComponentName) = state.fail()
    override fun onBindingDied(name: ComponentName) = state.fail()
    override fun onServiceDisconnected(name: ComponentName) = state.fail()
    override fun binderDied() = state.fail()

    /** Closes publication first and releases registration after any in-flight bind. */
    override fun close() {
        state.close(this)
        registration.close()
    }
}
