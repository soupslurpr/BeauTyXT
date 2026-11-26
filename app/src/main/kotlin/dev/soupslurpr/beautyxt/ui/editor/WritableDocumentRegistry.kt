/* Coordinates single-writer ownership across multi-window sessions in this process. */
package dev.soupslurpr.beautyxt.ui.editor

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import dev.soupslurpr.beautyxt.importing.client.DescriptorFileIdentity
import dev.soupslurpr.beautyxt.importing.client.probeDescriptorFileIdentityBounded
import dev.soupslurpr.beautyxt.ipc.acquireResourceWithTimeout
import dev.soupslurpr.beautyxt.ipc.openProviderDescriptor
import dev.soupslurpr.beautyxt.normalizedProviderAuthority
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val PER_USER_UID_RANGE = 100_000
private const val USER_CURRENT = -2
private const val USER_CURRENT_OR_SELF = -3
private const val IDENTITY_PROBE_TIMEOUT_MILLIS = 5_000L

internal data class ParsedAuthorityUser(
    val userId: Int?,
    val providerAuthority: String
)

/** Matches Android `ContentProvider.getUserIdFromAuthority` (`lastIndexOf('@')` + signed int). */
internal fun parseAuthorityUser(rawAuthority: String): ParsedAuthorityUser {
    val atIndex = rawAuthority.lastIndexOf('@')
    if (atIndex > 0) {
        val userPart = rawAuthority.substring(0, atIndex)
        val providerPart = rawAuthority.substring(atIndex + 1)
        val parsedUser = userPart.toIntOrNull()
        if (parsedUser != null && providerPart.isNotBlank()) {
            return ParsedAuthorityUser(userId = parsedUser, providerAuthority = providerPart)
        }
    }
    return ParsedAuthorityUser(userId = null, providerAuthority = rawAuthority)
}

private fun isSameUserAlias(userId: Int?, currentUserId: Int): Boolean =
    userId == null || userId == currentUserId || userId == USER_CURRENT || userId == USER_CURRENT_OR_SELF

private fun currentProcessUserId(): Int =
    try {
        Process.myUid() / PER_USER_UID_RANGE
    } catch (_: Throwable) {
        0
    }

/** Matches AOSP `DocumentsContract.getDocumentId`, accepting extra trailing path segments. */
internal fun safDocumentIdFromPath(uri: Uri): String? {
    val segments = uri.pathSegments ?: return null
    return when {
        segments.size >= 2 && segments[0] == "document" && segments[1].isNotEmpty() -> segments[1]
        segments.size >= 4 &&
            segments[0] == "tree" &&
            segments[1].isNotEmpty() &&
            segments[2] == "document" &&
            segments[3].isNotEmpty() -> segments[3]
        else -> null
    }
}

private fun isKnownDocumentsProvider(context: Context, rawAuthority: String?, docId: String): Boolean {
    if (rawAuthority.isNullOrBlank() || docId.isEmpty()) return false
    return try {
        val probe = DocumentsContract.buildDocumentUri(rawAuthority, docId)
        DocumentsContract.isDocumentUri(context, probe)
    } catch (_: Exception) {
        false
    }
}

/** Strips any leading `<userId>@` authority prefix so `DocumentsContract` recognizes the provider. */
internal fun stripUserQualifierFromContentUri(uri: Uri): Uri {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return uri
    val rawAuthority = uri.authority ?: return uri
    val parsed = parseAuthorityUser(rawAuthority)
    if (parsed.userId == null) return uri
    return uri.buildUpon().authority(parsed.providerAuthority).build()
}

/** Canonicalizes SAF document/tree-document and same-user authority aliases to one lock key. */
internal fun writableDocumentKey(
    context: Context?,
    uri: Uri,
    currentUserId: Int = currentProcessUserId()
): String {
    if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
        val parsed = uri.authority?.let(::parseAuthorityUser)
        val sameUser = isSameUserAlias(parsed?.userId, currentUserId)
        val normalizedUri = if (parsed != null && parsed.userId != null && sameUser) {
            uri.buildUpon().authority(parsed.providerAuthority).build()
        } else {
            uri
        }
        val authority = normalizedProviderAuthority(normalizedUri)?.lowercase(Locale.ROOT)
        if (!authority.isNullOrBlank()) {
            val lookupUri = stripUserQualifierFromContentUri(normalizedUri)
            val foreignUserId = parsed?.userId?.takeIf { !sameUser }
            val docId = try {
                when {
                    context != null && DocumentsContract.isDocumentUri(context, lookupUri) ->
                        DocumentsContract.getDocumentId(lookupUri)
                    context != null -> {
                        val pathDocId = safDocumentIdFromPath(lookupUri)
                        when {
                            pathDocId == null -> null
                            foreignUserId != null -> pathDocId
                            // `DocumentsProvider.openFile` extracts the ID via `getDocumentId` even
                            // when extra trailing path segments make `isDocumentUri` return false.
                            isKnownDocumentsProvider(context, lookupUri.authority, pathDocId) -> pathDocId
                            else -> null
                        }
                    }
                    foreignUserId != null -> safDocumentIdFromPath(lookupUri)
                    else -> null
                }
            } catch (_: Exception) {
                if (foreignUserId != null) safDocumentIdFromPath(lookupUri) else null
            }
            if (!docId.isNullOrEmpty()) {
                return if (foreignUserId != null) {
                    "doc:u$foreignUserId:$authority:$docId"
                } else {
                    "doc:$authority:$docId"
                }
            }
        }
        return normalizedUri.toString()
    }
    return uri.toString()
}

/** Outcome of probing a `content://` URI's live regular-file kernel `(st_dev, st_ino)` identity. */
internal sealed interface FileIdentityProbeResult {
    /** The URI currently resolves to a regular filesystem inode [key]. */
    data class Regular(val key: String) : FileIdentityProbeResult

    /** The URI is non-content or confirmed non-regular (pipe/virtual stream) via a live open. */
    data object NonRegularOrAbsent : FileIdentityProbeResult

    /** The probe failed or timed out without ruling out a live regular file alias. */
    data object Indeterminate : FileIdentityProbeResult
}

/** Snapshot of an open source's canonical keys plus whether its live probe was indeterminate. */
internal data class SourceIdentitySnapshot(
    val canonicalKeys: Set<String> = emptySet(),
    val liveProbeIndeterminate: Boolean = false
) {
    /**
     * Returns whether writing to [destinationKeys] would risk overwriting this source.
     *
     * Always blocks any match against [canonicalKeys]. When [liveProbeIndeterminate] is true
     * (for example, because the source was deleted or its grant was revoked), fails closed for
     * existing non-empty regular files while still allowing rescue exports to a brand-new empty
     * file ([destinationIsEmpty] = `true`) that does not match any known source key.
     */
    fun conflictsWith(
        destinationKeys: Set<String>,
        destinationHasRegularInode: Boolean,
        destinationIsEmpty: Boolean = false
    ): Boolean =
        canonicalKeys.any { it in destinationKeys } ||
            (liveProbeIndeterminate && destinationHasRegularInode && !destinationIsEmpty)
}

/** Captures the open source's URI, last observed regular-file inode, and write capability. */
internal data class CapturedSourceIdentity(
    val encodedUri: String,
    val fileIdentityKey: String?,
    val isWritable: Boolean
)

/**
 * Probes a regular file's kernel `(st_dev, st_ino)` identity via a non-truncating open ([mode] is
 * `"r"` for read-only sources or `"rw"` for active writable leases), treating any provider error
 * (including `FileNotFoundException`, which Android `ContentResolver` can throw on provider IPC
 * failure) as [FileIdentityProbeResult.Indeterminate].
 */
internal suspend fun probeRegularFileIdentity(
    context: Context,
    uri: Uri,
    mode: String = "r"
): FileIdentityProbeResult {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
        return FileIdentityProbeResult.NonRegularOrAbsent
    }
    val resolver = context.applicationContext.contentResolver
    return try {
        acquireResourceWithTimeout(IDENTITY_PROBE_TIMEOUT_MILLIS) {
            openProviderDescriptor(
                resolver = resolver,
                uri = uri,
                mode = mode,
                closeUnclaimed = ::closeProbeDescriptorQuietly
            )
        }.use { descriptor ->
            when (
                val identity = probeDescriptorFileIdentityBounded(
                    descriptor = descriptor,
                    timeoutMillis = IDENTITY_PROBE_TIMEOUT_MILLIS
                )
            ) {
                is DescriptorFileIdentity.Regular -> FileIdentityProbeResult.Regular(identity.key)
                DescriptorFileIdentity.NonRegular -> FileIdentityProbeResult.NonRegularOrAbsent
                DescriptorFileIdentity.Indeterminate -> FileIdentityProbeResult.Indeterminate
            }
        }
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        FileIdentityProbeResult.Indeterminate
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        FileIdentityProbeResult.Indeterminate
    } catch (_: LinkageError) {
        FileIdentityProbeResult.Indeterminate
    }
}

/**
 * Probes a writable source's live kernel `(st_dev, st_ino)` identity via a non-truncating `"rw"`
 * open so providers that return a distinct regular snapshot for `"r"` never overwrite the active
 * write inode during lease refresh.
 */
internal suspend fun probeWritableFileIdentity(
    context: Context,
    uri: Uri
): FileIdentityProbeResult = probeRegularFileIdentity(context = context, uri = uri, mode = "rw")

/**
 * Probes a regular file's kernel `(st_dev, st_ino)` identity via a non-truncating open.
 *
 * This unifies cross-provider SAF aliases (such as `DownloadStorageProvider` and
 * `ExternalStorageProvider` pointing to the same underlying file in `Download/`).
 */
internal suspend fun probeRegularFileIdentityKey(
    context: Context,
    uri: Uri,
    mode: String = "r"
): String? =
    (probeRegularFileIdentity(context = context, uri = uri, mode = mode) as? FileIdentityProbeResult.Regular)?.key

/**
 * Resolves a fail-closed [SourceIdentitySnapshot] combining the source's normalized SAF/URI key,
 * any cached regular-file inode, and a fresh live probe of [uri].
 */
internal suspend fun resolveSourceIdentitySnapshot(
    context: Context?,
    uri: Uri,
    knownFileIdentityKey: String? = null,
    preferWriteProbe: Boolean = false
): SourceIdentitySnapshot {
    val uriKey = writableDocumentKey(context, uri)
    val probeMode = if (preferWriteProbe) "rw" else "r"
    val probeResult = context?.let { probeRegularFileIdentity(it, uri, mode = probeMode) }
        ?: FileIdentityProbeResult.NonRegularOrAbsent
    val keys = buildSet {
        add(uriKey)
        knownFileIdentityKey?.takeIf { it.isNotBlank() }?.let(::add)
        if (probeResult is FileIdentityProbeResult.Regular) {
            add(probeResult.key)
        }
    }
    return SourceIdentitySnapshot(
        canonicalKeys = keys,
        liveProbeIndeterminate = probeResult == FileIdentityProbeResult.Indeterminate
    )
}

private fun closeProbeDescriptorQuietly(descriptor: ParcelFileDescriptor) {
    try {
        descriptor.close()
    } catch (_: IOException) {
        // Ignore close error on discarded probe descriptor.
    }
}

/**
 * Resolves all canonical ownership keys for [uri]: its normalized SAF/URI key plus, when backed
 * by a regular filesystem inode, its kernel `(st_dev, st_ino)` identity key.
 *
 * Pass [includeLiveProbe] = `true` when checking whether an export destination aliases the live
 * source so an external atomic file replacement on disk cannot hide behind a cached inode.
 */
internal suspend fun writableDocumentKeys(
    context: Context?,
    uri: Uri,
    knownFileIdentityKey: String? = null,
    includeLiveProbe: Boolean = false,
    preferWriteProbe: Boolean = false
): Set<String> {
    val uriKey = writableDocumentKey(context, uri)
    val probeMode = if (preferWriteProbe) "rw" else "r"
    val liveKey = if (includeLiveProbe || knownFileIdentityKey.isNullOrBlank()) {
        context?.let { probeRegularFileIdentityKey(it, uri, mode = probeMode) }
    } else {
        null
    }
    return buildSet {
        add(uriKey)
        knownFileIdentityKey?.takeIf { it.isNotBlank() }?.let(::add)
        liveKey?.takeIf { it.isNotBlank() }?.let(::add)
    }
}

/**
 * Builds canonical ownership keys from [uri] and an already-opened descriptor's [descriptorIdentityKey]
 * without opening a second provider descriptor.
 */
internal fun writableDocumentKeysForDescriptor(
    context: Context?,
    uri: Uri,
    descriptorIdentityKey: String?
): Set<String> {
    val uriKey = writableDocumentKey(context, uri)
    return if (!descriptorIdentityKey.isNullOrBlank()) {
        setOf(uriKey, descriptorIdentityKey)
    } else {
        setOf(uriKey)
    }
}

/** Holds an in-memory exclusive write lease across all canonical keys for one document. */
internal class WritableDocumentLease internal constructor(
    initialKeys: Set<String>,
    val ownerId: UUID,
    internal val replacesOwnerId: UUID?,
    internal val liveProbe: (suspend () -> FileIdentityProbeResult)?,
    private val claimKeyAction: (WritableDocumentLease, String) -> Boolean,
    private val replaceInodeKeyAction: (WritableDocumentLease, String) -> Boolean,
    private val pinInodeKeyAction: (WritableDocumentLease, String?) -> AutoCloseable?,
    private val acquireRemovalGuardAction: suspend (WritableDocumentLease, Set<String>) -> WritableDocumentLease?,
    private val releaseAction: (WritableDocumentLease) -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    internal val trackedKeys: MutableSet<String> = LinkedHashSet(initialKeys)
    internal val pinnedInodeCounts: MutableMap<String, Int> = HashMap()
    internal var currentInodeKey: String? = initialKeys.lastOrNull { it.startsWith(INODE_KEY_PREFIX) }
    internal var inodeMutationGeneration: Long = 0L

    @Volatile
    internal var tracksRegularInode: Boolean = initialKeys.any { it.startsWith(INODE_KEY_PREFIX) }

    val isClosed: Boolean
        get() = closed.get()

    /**
     * Atomically binds an additional canonical key to this active lease, returning `false` if
     * another live writer owns [key] or this lease has already been closed.
     */
    fun claimKey(key: String): Boolean {
        if (closed.get()) return false
        if (key.isBlank()) return true
        return claimKeyAction(this, key)
    }

    /**
     * Atomically updates this lease's regular-file inode key to [inodeKey] while releasing any
     * unpinned obsolete inode claims, returning `false` if another live writer owns [inodeKey] or
     * this lease has already been closed.
     */
    fun replaceInodeKey(inodeKey: String): Boolean {
        if (closed.get()) return false
        if (inodeKey.isBlank()) return true
        return replaceInodeKeyAction(this, inodeKey)
    }

    /**
     * Claims and pins [inodeKey] (when non-null) together with all canonical SAF/URI keys of this
     * lease for the duration of an in-flight write descriptor so neither concurrent refreshes nor
     * closing/replacing the parent lease can release those keys until the returned handle closes.
     *
     * Returns `null` if another live writer or active pin owns any required key or this lease is
     * closed.
     */
    fun pinInodeKey(inodeKey: String?): AutoCloseable? {
        if (closed.get()) return null
        val normalizedInode = inodeKey?.takeIf { it.isNotBlank() }
        if (inodeKey != null && normalizedInode == null) return null
        return pinInodeKeyAction(this, normalizedInode)
    }

    /**
     * Refreshes all other active leases and acquires an exclusive removal guard across
     * [documentKeys] (the live SAF URI key plus live regular inode), returning `null` if another
     * window currently owns or has an indeterminate live probe on that target or this lease is
     * closed.
     */
    suspend fun acquireRemovalGuard(documentKeys: Set<String>): WritableDocumentLease? {
        if (closed.get()) return null
        return acquireRemovalGuardAction(this, documentKeys)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            releaseAction(this)
        }
    }
}

private const val INODE_KEY_PREFIX = "ino:"

/**
 * Tracks active writable document keys in memory across windows in the current process.
 *
 * When a second window opens a document that another live session already owns for writing
 * (via tree/direct SAF aliases, user-qualified authorities, or distinct SAF providers backed by
 * the same kernel inode), acquiring a write lease returns `null` so the second window degrades
 * cleanly to view-only instead of racing autosaves.
 */
internal class WritableDocumentRegistry {
    private val lock = Any()
    private val acquisitionMutex = Mutex()
    private val owners = HashMap<String, MutableList<UUID>>()
    private val activeLeases = LinkedHashSet<WritableDocumentLease>()

    /** Attempts to claim exclusive in-process write ownership for a single [documentKey]. */
    fun tryAcquire(
        documentKey: String,
        replacesOwnerId: UUID? = null,
        liveProbe: (suspend () -> FileIdentityProbeResult)? = null
    ): WritableDocumentLease? = tryAcquire(setOf(documentKey), replacesOwnerId, liveProbe)

    /**
     * Runs [tryAcquireRefreshing] on [dispatcher] and guarantees the acquired lease is closed if
     * the caller's coroutine is cancelled across the dispatcher handoff.
     */
    suspend fun acquireRefreshingWithCancelCleanup(
        documentKeys: Set<String>,
        replacesOwnerId: UUID? = null,
        liveProbe: (suspend () -> FileIdentityProbeResult)? = null,
        destinationIsEmpty: Boolean = false,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): WritableDocumentLease? {
        val acquired = AtomicReference<WritableDocumentLease?>(null)
        try {
            withContext(dispatcher) {
                val lease = tryAcquireRefreshing(
                    documentKeys = documentKeys,
                    replacesOwnerId = replacesOwnerId,
                    liveProbe = liveProbe,
                    destinationIsEmpty = destinationIsEmpty
                )
                acquired.set(lease)
            }
            return acquired.getAndSet(null)
        } finally {
            acquired.getAndSet(null)?.close()
        }
    }

    /**
     * Serializes refresh-and-acquire so concurrent writers cannot slip past live probes, refreshes
     * every other active lease's live kernel inode (replacing obsolete inode claims after atomic
     * renames), and atomically claims [documentKeys].
     *
     * Fails closed if another active regular-file lease's live probe is indeterminate while
     * [documentKeys] targets a non-empty regular file ([destinationIsEmpty] = `false`).
     */
    suspend fun tryAcquireRefreshing(
        documentKeys: Set<String>,
        replacesOwnerId: UUID? = null,
        liveProbe: (suspend () -> FileIdentityProbeResult)? = null,
        destinationIsEmpty: Boolean = false
    ): WritableDocumentLease? = acquisitionMutex.withLock {
        val candidateRequestsRegularInode = documentKeys.any { it.startsWith(INODE_KEY_PREFIX) }
        val probedResults = LinkedHashMap<WritableDocumentLease, Pair<Long, FileIdentityProbeResult>>()
        val maxRefreshPasses = 4
        var attempt = 0
        while (attempt < maxRefreshPasses) {
            attempt += 1
            val probedThisPass = HashSet<UUID>()
            while (true) {
                val leasesToRefresh = synchronized(lock) {
                    activeLeases.mapNotNull { lease ->
                        if (
                            !lease.isClosed &&
                            lease.liveProbe != null &&
                            lease.ownerId != replacesOwnerId &&
                            lease.ownerId !in probedThisPass
                        ) {
                            val existing = probedResults[lease]
                            if (existing == null || existing.first != lease.inodeMutationGeneration) {
                                lease to lease.inodeMutationGeneration
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }
                }
                if (leasesToRefresh.isEmpty()) break
                for ((lease, generationBefore) in leasesToRefresh) {
                    probedThisPass.add(lease.ownerId)
                    val probe = lease.liveProbe ?: continue
                    val result = probe()
                    probedResults[lease] = generationBefore to result
                }
            }
            var needsRetry = false
            val acquired = synchronized(lock) {
                val freshProbes = LinkedHashMap<WritableDocumentLease, FileIdentityProbeResult>(probedResults.size)
                var hasStaleLiveLease = false
                for (lease in activeLeases) {
                    if (lease.isClosed || lease.liveProbe == null || lease.ownerId == replacesOwnerId) continue
                    val entry = probedResults[lease]
                    if (entry == null || entry.first != lease.inodeMutationGeneration) {
                        hasStaleLiveLease = true
                        break
                    }
                }
                if (hasStaleLiveLease) {
                    if (attempt < maxRefreshPasses) {
                        needsRetry = true
                        return@synchronized null
                    }
                    // Fail closed if a live regular-file lease kept mutating across all refresh
                    // retries while the candidate targets a non-empty regular file.
                    val blocksCandidate =
                        candidateRequestsRegularInode &&
                            !destinationIsEmpty &&
                            activeLeases.any { lease ->
                                !lease.isClosed &&
                                    lease.liveProbe != null &&
                                    lease.ownerId != replacesOwnerId &&
                                    lease.tracksRegularInode &&
                                    probedResults[lease]?.first != lease.inodeMutationGeneration
                            }
                    if (blocksCandidate) {
                        return@synchronized null
                    }
                }
                for ((lease, entry) in probedResults) {
                    val (generationBefore, result) = entry
                    if (lease.isClosed || lease.inodeMutationGeneration != generationBefore) continue
                    if (
                        result == FileIdentityProbeResult.Indeterminate &&
                        candidateRequestsRegularInode &&
                        lease.tracksRegularInode &&
                        !destinationIsEmpty
                    ) {
                        return@synchronized null
                    }
                    freshProbes[lease] = result
                }
                val priorCurrentInodes = HashMap<WritableDocumentLease, String?>(freshProbes.size)
                for ((lease, result) in freshProbes) {
                    if (result !is FileIdentityProbeResult.Regular) continue
                    priorCurrentInodes[lease] = lease.currentInodeKey
                }
                // Phase 1: release every unpinned stale inode across all refreshed leases first so
                // cross-lease inode swaps/moves cannot fail against another lease's stale claim.
                for ((lease, result) in freshProbes) {
                    if (result !is FileIdentityProbeResult.Regular) continue
                    releaseStaleUnpinnedInodesLocked(lease, result.key)
                }
                // Phase 2: bind each refreshed lease to its observed live inode without granting a
                // second live owner when two leases converge onto the same inode (incumbent keeps
                // exclusive write ownership; a departing lease with only a temporary pin yields
                // live ownership to the incoming lease once its pin closes).
                val liveInodeHolders = HashMap<String, UUID>()
                for (active in activeLeases) {
                    if (active.isClosed) continue
                    if (active in priorCurrentInodes) continue
                    active.currentInodeKey?.let { key -> liveInodeHolders[key] = active.ownerId }
                }
                // Prioritize leases whose observed inode did not change (incumbents keep ownership).
                val orderedRefreshes = freshProbes.entries.sortedBy { (lease, result) ->
                    if (result is FileIdentityProbeResult.Regular && priorCurrentInodes[lease] == result.key) 0 else 1
                }
                for ((lease, result) in orderedRefreshes) {
                    if (result !is FileIdentityProbeResult.Regular) continue
                    val existingLiveOwner = liveInodeHolders[result.key]
                    val canClaimLive = existingLiveOwner == null ||
                        existingLiveOwner == lease.ownerId ||
                        existingLiveOwner == lease.replacesOwnerId
                    if (canClaimLive) {
                        liveInodeHolders[result.key] = lease.ownerId
                        bindObservedInodeLocked(lease, result.key)
                    } else {
                        // Another active lease already holds live exclusive ownership of `result.key`;
                        // clear this colliding lease's current inode claim without polluting `owners`.
                        lease.currentInodeKey = null
                    }
                }
                // Phase 3: check and acquire the candidate lease in the same critical section.
                tryAcquireLocked(
                    documentKeys = documentKeys,
                    replacesOwnerId = replacesOwnerId,
                    liveProbe = liveProbe
                )
            }
            if (!needsRetry) {
                return@withLock acquired
            }
        }
        null
    }

    /**
     * Atomically claims exclusive in-process write ownership across every key in [documentKeys].
     *
     * Pass [replacesOwnerId] only when an existing owner is reloading its own source: the
     * replacement lease coexists with [replacesOwnerId] until either the original editor closes
     * on publish (handing ownership cleanly to the replacement) or the failed replacement closes
     * (preserving the original owner's lease intact).
     */
    fun tryAcquire(
        documentKeys: Set<String>,
        replacesOwnerId: UUID? = null,
        liveProbe: (suspend () -> FileIdentityProbeResult)? = null
    ): WritableDocumentLease? = synchronized(lock) {
        tryAcquireLocked(documentKeys, replacesOwnerId, liveProbe)
    }

    private fun tryAcquireLocked(
        documentKeys: Set<String>,
        replacesOwnerId: UUID? = null,
        liveProbe: (suspend () -> FileIdentityProbeResult)? = null
    ): WritableDocumentLease? {
        val validKeys = documentKeys.filterTo(LinkedHashSet()) { it.isNotBlank() }
        if (validKeys.isEmpty()) return null
        for (key in validKeys) {
            val active = owners[key]
            val allowed = when {
                active.isNullOrEmpty() -> true
                replacesOwnerId != null -> active.all { it == replacesOwnerId }
                else -> false
            }
            if (!allowed) return null
        }
        val candidateOwner = UUID.randomUUID()
        val lease = WritableDocumentLease(
            initialKeys = validKeys,
            ownerId = candidateOwner,
            replacesOwnerId = replacesOwnerId,
            liveProbe = liveProbe,
            claimKeyAction = ::claimAdditionalKey,
            replaceInodeKeyAction = ::replaceInodeKey,
            pinInodeKeyAction = ::pinInodeKey,
            acquireRemovalGuardAction = { activeLease, keys ->
                if (activeLease.isClosed) {
                    null
                } else {
                    acquireRefreshingWithCancelCleanup(
                        documentKeys = keys,
                        replacesOwnerId = activeLease.ownerId,
                        destinationIsEmpty = false
                    )
                }
            },
            releaseAction = ::release
        )
        for (key in validKeys) {
            owners.getOrPut(key) { ArrayList(2) }.add(candidateOwner)
        }
        activeLeases.add(lease)
        return lease
    }

    private fun releaseStaleUnpinnedInodesLocked(lease: WritableDocumentLease, keepInodeKey: String) {
        val staleInodes = lease.trackedKeys.filter { key ->
            key.startsWith(INODE_KEY_PREFIX) &&
                key != keepInodeKey &&
                (lease.pinnedInodeCounts[key] ?: 0) == 0
        }
        for (stale in staleInodes) {
            lease.trackedKeys.remove(stale)
            val active = owners[stale] ?: continue
            active.remove(lease.ownerId)
            if (active.isEmpty()) {
                owners.remove(stale)
            }
        }
    }

    private fun bindObservedInodeLocked(lease: WritableDocumentLease, inodeKey: String) {
        val ownerList = owners.getOrPut(inodeKey) { ArrayList(2) }
        if (!ownerList.contains(lease.ownerId)) {
            ownerList.add(lease.ownerId)
        }
        lease.trackedKeys.add(inodeKey)
        lease.currentInodeKey = inodeKey
        lease.tracksRegularInode = true
    }

    private fun claimAdditionalKey(lease: WritableDocumentLease, key: String): Boolean {
        synchronized(lock) {
            if (lease.isClosed) return false
            val active = owners[key]
            val allowed = when {
                active.isNullOrEmpty() -> true
                active.all { it == lease.ownerId || it == lease.replacesOwnerId } -> true
                else -> false
            }
            if (!allowed) return false
            if (key in lease.trackedKeys) return true
            val ownerList = owners.getOrPut(key) { ArrayList(2) }
            if (!ownerList.contains(lease.ownerId)) {
                ownerList.add(lease.ownerId)
            }
            lease.trackedKeys.add(key)
            if (key.startsWith(INODE_KEY_PREFIX)) {
                lease.currentInodeKey = key
                lease.tracksRegularInode = true
                lease.inodeMutationGeneration += 1L
            }
            return true
        }
    }

    private fun replaceInodeKeyLocked(lease: WritableDocumentLease, inodeKey: String): Boolean {
        if (lease.isClosed) return false
        val active = owners[inodeKey]
        val allowed = when {
            active.isNullOrEmpty() -> true
            active.all { it == lease.ownerId || it == lease.replacesOwnerId } -> true
            else -> false
        }
        if (!allowed) return false
        val staleInodes = lease.trackedKeys.filter { key ->
            key.startsWith(INODE_KEY_PREFIX) &&
                key != inodeKey &&
                (lease.pinnedInodeCounts[key] ?: 0) == 0
        }
        for (stale in staleInodes) {
            lease.trackedKeys.remove(stale)
            val active = owners[stale] ?: continue
            active.remove(lease.ownerId)
            if (active.isEmpty()) {
                owners.remove(stale)
            }
        }
        val ownerList = owners.getOrPut(inodeKey) { ArrayList(2) }
        if (!ownerList.contains(lease.ownerId)) {
            ownerList.add(lease.ownerId)
        }
        lease.trackedKeys.add(inodeKey)
        lease.currentInodeKey = inodeKey
        lease.tracksRegularInode = true
        lease.inodeMutationGeneration += 1L
        return true
    }

    private fun replaceInodeKey(lease: WritableDocumentLease, inodeKey: String): Boolean =
        synchronized(lock) { replaceInodeKeyLocked(lease, inodeKey) }

    private fun pinInodeKey(lease: WritableDocumentLease, inodeKey: String?): AutoCloseable? {
        val pinOwnerId = UUID.randomUUID()
        val pinnedKeys = LinkedHashSet<String>()
        synchronized(lock) {
            if (lease.isClosed) return null
            if (inodeKey != null) {
                if (!replaceInodeKeyLocked(lease, inodeKey)) return null
                pinnedKeys.add(inodeKey)
            }
            for (key in lease.trackedKeys) {
                if (!key.startsWith(INODE_KEY_PREFIX)) {
                    pinnedKeys.add(key)
                }
            }
            if (pinnedKeys.isEmpty()) return null
            for (key in pinnedKeys) {
                val active = owners[key]
                if (active != null && !active.all { it == lease.ownerId || it == lease.replacesOwnerId }) {
                    return null
                }
            }
            if (inodeKey != null) {
                lease.pinnedInodeCounts[inodeKey] = (lease.pinnedInodeCounts[inodeKey] ?: 0) + 1
            }
            for (key in pinnedKeys) {
                owners.getOrPut(key) { ArrayList(2) }.add(pinOwnerId)
            }
        }
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (!released.compareAndSet(false, true)) return@AutoCloseable
            synchronized(lock) {
                for (key in pinnedKeys) {
                    val activeForPin = owners[key]
                    if (activeForPin != null) {
                        activeForPin.remove(pinOwnerId)
                        if (activeForPin.isEmpty()) {
                            owners.remove(key)
                        }
                    }
                }
                if (inodeKey != null) {
                    val remaining = (lease.pinnedInodeCounts[inodeKey] ?: 0) - 1
                    if (remaining > 0) {
                        lease.pinnedInodeCounts[inodeKey] = remaining
                    } else {
                        lease.pinnedInodeCounts.remove(inodeKey)
                        // If a concurrent refresh superseded this inode while the write was in flight,
                        // release the now-obsolete inode immediately now that the write has finished.
                        if (!lease.isClosed && lease.currentInodeKey != inodeKey) {
                            lease.trackedKeys.remove(inodeKey)
                            val active = owners[inodeKey]
                            if (active != null) {
                                active.remove(lease.ownerId)
                                if (active.isEmpty()) {
                                    owners.remove(inodeKey)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun release(lease: WritableDocumentLease) {
        synchronized(lock) {
            activeLeases.remove(lease)
            for (key in lease.trackedKeys) {
                val active = owners[key] ?: continue
                active.remove(lease.ownerId)
                if (active.isEmpty()) {
                    owners.remove(key)
                }
            }
            lease.trackedKeys.clear()
            lease.pinnedInodeCounts.clear()
            lease.currentInodeKey = null
        }
    }

    /** Returns whether [documentKey] currently has a live writable owner in this process. */
    fun isOwned(documentKey: String): Boolean =
        documentKey.isNotBlank() && synchronized(lock) { !owners[documentKey].isNullOrEmpty() }

    /** Returns the current primary owner identifier for [documentKey], if any. */
    fun ownerIdFor(documentKey: String): UUID? = ownerIdFor(setOf(documentKey))

    /** Returns the current live lease owner identifier matching any key in [documentKeys], never a standalone pin. */
    fun ownerIdFor(documentKeys: Set<String>): UUID? =
        synchronized(lock) {
            val liveLeaseOwnerIds = activeLeases.mapTo(HashSet()) { it.ownerId }
            for (key in documentKeys) {
                if (key.isBlank()) continue
                val owner = owners[key]?.firstOrNull { it in liveLeaseOwnerIds }
                if (owner != null) return@synchronized owner
            }
            null
        }

    companion object {
        /** Process-wide registry shared by all windows and activities. */
        val shared = WritableDocumentRegistry()
    }
}

/** Combines multiple optional [AutoCloseable] leases so closing releases every lease. */
internal fun combineCloseables(vararg closeables: AutoCloseable?): AutoCloseable? {
    val active = closeables.filterNotNull()
    if (active.isEmpty()) return null
    val closed = AtomicBoolean(false)
    return AutoCloseable {
        if (!closed.compareAndSet(false, true)) return@AutoCloseable
        var failure: Throwable? = null
        for (item in active) {
            try {
                item.close()
            } catch (closeFailure: Throwable) {
                if (failure == null) {
                    failure = closeFailure
                } else {
                    failure.addSuppressed(closeFailure)
                }
            }
        }
        failure?.let { throw it }
    }
}
