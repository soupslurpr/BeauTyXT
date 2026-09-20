package dev.soupslurpr.beautyxt

import android.app.Application
import android.os.Process
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

private val LEGACY_PRIVATE_PATHS =
    listOf(
        "app_webview",
        "databases",
        "files/datastore",
        "shared_prefs"
    )

/** Removes private state that production BeauTyXT versions could leave during an update. */
internal fun removeLegacyPrivateState(dataDirectory: File) {
    val dataPath = dataDirectory.toPath().toAbsolutePath().normalize()
    LEGACY_PRIVATE_PATHS.forEach { relativePath ->
        val legacyPath = dataPath.resolve(relativePath).normalize()
        check(legacyPath.startsWith(dataPath)) {
            "legacy private path escaped the app data directory"
        }
        var parent = legacyPath.parent
        while (parent != dataPath) {
            check(parent != null && parent.startsWith(dataPath)) {
                "legacy private ancestor escaped the app data directory"
            }
            if (Files.isSymbolicLink(parent)) {
                throw IOException("legacy private path has a symbolic-link ancestor")
            }
            parent = parent.parent
        }
        deleteTreeWithoutFollowingLinks(legacyPath)
    }
}

/** Deletes one private tree without traversing symbolic links. */
private fun deleteTreeWithoutFollowingLinks(root: Path) {
    if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
        return
    }
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exception: IOException): FileVisitResult =
                throw exception

            override fun postVisitDirectory(
                directory: Path,
                exception: IOException?
            ): FileVisitResult {
                if (exception != null) {
                    throw exception
                }
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        }
    )
}

/** Removes legacy private state before BeauTyXT exposes any document workflow. */
class BeauTyXTApplication : Application() {
    internal lateinit var legacyCleanup: LegacyCleanup
        private set

    override fun onCreate() {
        super.onCreate()
        if (Process.myUid() != applicationInfo.uid) {
            return
        }
        legacyCleanup = LegacyCleanup { removeLegacyPrivateState(File(applicationInfo.dataDir)) }
        legacyCleanup.start()
    }
}
