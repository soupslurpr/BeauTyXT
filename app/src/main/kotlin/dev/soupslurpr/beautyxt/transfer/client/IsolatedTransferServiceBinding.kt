package dev.soupslurpr.beautyxt.transfer.client

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import dev.soupslurpr.beautyxt.transfer.ITransferService
import android.content.Intent
import dev.soupslurpr.beautyxt.ipc.NativeServiceNames
import dev.soupslurpr.beautyxt.ipc.IsolatedServiceConnection
import dev.soupslurpr.beautyxt.ipc.IsolatedServiceRegistration
import dev.soupslurpr.beautyxt.ipc.newIsolatedServiceInstanceName
import kotlinx.coroutines.CompletableDeferred

/** Owns one explicit transfer binding and serializes its publication against close. */
internal class IsolatedTransferServiceBinding(context: Context) :
    ServiceConnection,
    IBinder.DeathRecipient,
    AutoCloseable {
    private val applicationContext = context.applicationContext
    private val instanceName = newIsolatedServiceInstanceName()
    private val state = IsolatedServiceConnection<ITransferService> { cause ->
        TransferException(TransferFailure.ServiceUnavailable, cause)
    }
    private val registration = IsolatedServiceRegistration {
        applicationContext.unbindService(this)
    }

    /** Starts an explicit bind without holding the connection gate across Android calls. */
    fun bind() {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "isolated transfer binding must start off the main thread"
        }
        check(!state.isClosed) { "isolated transfer binding is closed" }
        try {
            val accepted = registration.bind {
                applicationContext.bindIsolatedService(
                    Intent().setClassName(applicationContext, NativeServiceNames.TRANSFER),
                    Context.BIND_AUTO_CREATE,
                    instanceName,
                    applicationContext.mainExecutor,
                    this
                )
            }
            if (!accepted || state.isClosed) {
                registration.close()
                throw TransferException(TransferFailure.ServiceUnavailable)
            }
        } catch (failure: TransferException) {
            throw failure
        } catch (failure: Exception) {
            throw TransferException(TransferFailure.ServiceUnavailable, failure)
        }
    }

    suspend fun awaitService(): ITransferService = state.awaitService()

    /** Routes operation attachment through the same closed-state gate as publication. */
    fun attachOperation(completion: CompletableDeferred<TransferTerminalStatus>) {
        state.attach(completion)
    }

    /** Detaches only the completed job before another scanner frame can attach. */
    fun detachOperation(completion: CompletableDeferred<TransferTerminalStatus>) {
        state.detach(completion)
    }

    /** Requests best-effort cancellation without waiting for acknowledgement. */
    fun cancelTransfer(jobId: Long) {
        try {
            state.remote?.cancelTransfer(jobId)
        } catch (_: RemoteException) {
            // Binding teardown remains authoritative when the service is unavailable.
        } catch (_: RuntimeException) {
            // Binding teardown remains authoritative when the service is unavailable.
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        state.connect(binder, this, ITransferService.Stub::asInterface)
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
