package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import dev.soupslurpr.beautyxt.removeLegacyPrivateState
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

private const val LEGACY_CLEANUP_FILE_COUNT = 4_096
private const val LEGACY_CLEANUP_FILE_CONTENT = "synthetic legacy cache"

/** Verifies cleanup never traverses a legacy leaf or ancestor symbolic link. */
internal fun Instrumentation.verifyLegacyCleanupLinkBoundaries() {
    val fixture = Files.createTempDirectory(targetContext.cacheDir.toPath(), "cleanup-links-")
    try {
        val data = Files.createDirectory(fixture.resolve("app"))
        val outside = Files.createDirectory(fixture.resolve("unrelated"))
        val preserved = Files.createDirectories(outside.resolve("datastore")).resolve("keep")
        Files.writeString(preserved, LEGACY_CLEANUP_FILE_CONTENT)
        val leaf = data.resolve("app_webview")
        Files.createSymbolicLink(leaf, outside)
        removeLegacyPrivateState(data.toFile())
        check(!Files.exists(leaf, LinkOption.NOFOLLOW_LINKS)) { "legacy symlink was retained" }
        check(Files.readString(preserved) == LEGACY_CLEANUP_FILE_CONTENT) {
            "legacy cleanup followed its leaf symlink"
        }

        Files.createSymbolicLink(data.resolve("files"), outside)
        var rejected = false
        try {
            removeLegacyPrivateState(data.toFile())
        } catch (_: IOException) {
            rejected = true
        }
        check(rejected) { "legacy cleanup accepted a symlinked ancestor" }
        check(Files.readString(preserved) == LEGACY_CLEANUP_FILE_CONTENT) {
            "legacy cleanup followed an ancestor symlink"
        }
    } finally {
        check(fixture.toFile().deleteRecursively()) { "cleanup link fixture could not be removed" }
    }
}

/** Measures a synthetic legacy cache traversal without imposing a device-specific threshold. */
internal fun Instrumentation.measureLegacyCleanupTraversal() {
    val fixture = Files.createTempDirectory(targetContext.cacheDir.toPath(), "cleanup-timing-")
    try {
        val cache = Files.createDirectories(fixture.resolve("app_webview/Default/Cache"))
        repeat(LEGACY_CLEANUP_FILE_COUNT) { entry ->
            Files.writeString(cache.resolve("entry-$entry"), LEGACY_CLEANUP_FILE_CONTENT)
        }
        val started = SystemClock.elapsedRealtime()
        removeLegacyPrivateState(fixture.toFile())
        val elapsed = SystemClock.elapsedRealtime() - started
        check(!Files.exists(cache)) { "legacy cleanup left the synthetic cache" }
        sendStatus(
            0,
            Bundle().apply {
                putString(
                    Instrumentation.REPORT_KEY_STREAMRESULT,
                    "legacy cleanup: $LEGACY_CLEANUP_FILE_COUNT files in $elapsed ms\n"
                )
            }
        )
    } finally {
        check(fixture.toFile().deleteRecursively()) {
            "cleanup timing fixture could not be removed"
        }
    }
}
