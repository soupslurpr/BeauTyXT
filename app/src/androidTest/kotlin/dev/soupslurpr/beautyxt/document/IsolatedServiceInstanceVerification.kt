package dev.soupslurpr.beautyxt.document

import android.content.Context
import android.os.IBinder
import dev.soupslurpr.beautyxt.exporting.client.IsolatedExportServiceBinding
import dev.soupslurpr.beautyxt.importing.client.IsolatedImportServiceBinding
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownServiceBinding
import dev.soupslurpr.beautyxt.transfer.client.IsolatedTransferServiceBinding
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Exercises production bindings with different document clients alive together. */
internal fun verifyUniqueServiceInstances(context: Context) = runBlocking {
    val factories: List<suspend () -> BoundTestWorker> = listOf(
        {
            val binding = IsolatedImportServiceBinding(context)
            connectTestWorker(binding) {
                binding.bind()
                binding.awaitService().asBinder()
            }
        },
        {
            val binding = IsolatedExportServiceBinding(context)
            connectTestWorker(binding) {
                binding.bind()
                binding.awaitService().asBinder()
            }
        },
        {
            val binding = IsolatedMarkdownServiceBinding(context)
            connectTestWorker(binding) {
                binding.bind()
                binding.awaitService().asBinder()
            }
        },
        {
            val binding = IsolatedTransferServiceBinding(context)
            connectTestWorker(binding) {
                binding.bind()
                binding.awaitService().asBinder()
            }
        }
    )
    factories.forEachIndexed { index, factory ->
        withTimeout(20_000L) {
            factory().use { first ->
                factory().use { second ->
                    check(first.binder != second.binder) {
                        "worker type $index shared a service instance between documents"
                    }
                    first.close()
                    check(second.binder.pingBinder()) {
                        "closing one document disconnected the other worker of type $index"
                    }
                    factory().use { third ->
                        check(third.binder != first.binder && third.binder != second.binder) {
                            "worker type $index reused an earlier document's service instance"
                        }
                        check(second.binder.pingBinder() && third.binder.pingBinder())
                    }
                }
            }
        }
    }
}

private class BoundTestWorker(val binder: IBinder, private val binding: AutoCloseable) :
    AutoCloseable {
    override fun close() = binding.close()
}

private suspend fun connectTestWorker(
    binding: AutoCloseable,
    connect: suspend () -> IBinder
): BoundTestWorker = try {
    BoundTestWorker(connect(), binding)
} catch (failure: Throwable) {
    binding.close()
    throw failure
}
