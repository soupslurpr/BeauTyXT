package dev.soupslurpr.beautyxt.document

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import dev.soupslurpr.beautyxt.importing.client.IsolatedImportServiceBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Measures fresh bound import processes; explicitly selected, never a timing assertion. */
internal fun profileImportService(
    context: Context,
    shell: (String) -> ParcelFileDescriptor
) = runBlocking {
    fun command(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(shell(command)).bufferedReader().use {
            it.readText()
        }

    val pattern = "^${context.packageName.replace(".", "[.]")}:import:"
    fun processIds(): Set<Int> = command("pgrep -f $pattern")
        .split(Regex("\\s+"))
        .mapNotNull(String::toIntOrNull)
        .toSet()

    repeat(7) { sample ->
        val previous = processIds()
        val binding = IsolatedImportServiceBinding(context)
        var processId: Int? = null
        try {
            val started = SystemClock.elapsedRealtimeNanos()
            binding.bind()
            withTimeout(15_000) { binding.awaitService() }
            val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            processId = withTimeout(5_000) {
                var found: Int? = null
                while (found == null) {
                    found = (processIds() - previous).singleOrNull()
                    if (found == null) delay(20)
                }
                found
            }
            val memory = command("dumpsys meminfo --local $processId")
            Log.i("BeauTyXTImportProfile", "sample=$sample pid=$processId bindMs=$elapsed\n$memory")
            check(memory.contains("TOTAL")) { "worker memory report unavailable" }
        } finally {
            binding.close()
            processId?.let { pid ->
                withTimeout(10_000) {
                    while (pid in processIds()) delay(20)
                }
            }
        }
    }
}
