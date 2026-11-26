package dev.soupslurpr.beautyxt.illustration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** LRU retention is independent of document length; pinned, higher-priority results cannot churn. */
internal class IllustrationCache(
    private val byteLimit: Int = MAX_DOCUMENT_ILLUSTRATION_BYTES,
    private val entryLimit: Int = MAX_DOCUMENT_ILLUSTRATIONS
) {
    private val lock = Any()
    private val entries = LinkedHashMap<IllustrationRequest, IllustrationResult>(16, 0.75f, true)

    @Volatile
    private var currentSnapshot: Map<IllustrationRequest, IllustrationResult> = emptyMap()

    @Volatile
    var retainedBytes: Int = 0
        private set

    @Volatile
    var version: Long = 0
        private set

    operator fun get(request: IllustrationRequest): IllustrationResult? =
        synchronized(lock) { entries[request] }

    /** Returns an immutable point-in-time snapshot safe to iterate from any thread. */
    fun snapshot(): Map<IllustrationRequest, IllustrationResult> = currentSnapshot

    /** Returns a consistent `(version, snapshot)` pair captured atomically under the cache lock. */
    fun snapshotWithVersion(): Pair<Long, Map<IllustrationRequest, IllustrationResult>> =
        synchronized(lock) { version to currentSnapshot }

    fun put(
        request: IllustrationRequest,
        result: IllustrationResult,
        pinned: Set<IllustrationRequest>
    ): Boolean {
        check(result !is IllustrationResult.Pending)
        val bytes = (result as? IllustrationResult.Rendered)?.drawing?.packetBytes ?: 0
        if (bytes > byteLimit) return false
        synchronized(lock) {
            // Plan evictions without calling `entries[request]` (which mutates access-ordered LRU
            // state) so a rejected insertion/replacement leaves entries, LRU order, retainedBytes,
            // version, and currentSnapshot completely unchanged.
            val existing = entries.entries.firstOrNull { it.key == request }?.value
            var projectedCount = entries.size - if (existing != null) 1 else 0
            var projectedBytes = retainedBytes - (existing?.let(::packetBytes) ?: 0)
            val victims = ArrayList<IllustrationRequest>()
            if (projectedCount >= entryLimit || projectedBytes > byteLimit - bytes) {
                for ((key, value) in entries) {
                    if (key == request || key in pinned) continue
                    victims.add(key)
                    projectedCount -= 1
                    projectedBytes -= packetBytes(value)
                    if (projectedCount < entryLimit && projectedBytes <= byteLimit - bytes) {
                        break
                    }
                }
            }
            if (projectedCount >= entryLimit || projectedBytes > byteLimit - bytes) {
                return false
            }
            entries.remove(request)?.let { retainedBytes -= packetBytes(it) }
            for (victim in victims) {
                retainedBytes -= packetBytes(checkNotNull(entries.remove(victim)))
            }
            entries[request] = result
            retainedBytes += bytes
            version++
            currentSnapshot = LinkedHashMap(entries)
            return true
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            retainedBytes = 0
            version++
            currentSnapshot = emptyMap()
        }
    }

    private fun packetBytes(result: IllustrationResult) =
        (result as? IllustrationResult.Rendered)?.drawing?.packetBytes ?: 0
}

/**
 * Only the latest viewport is queued. A scroll never kills a healthy in-flight renderer; it finishes
 * its one bounded request, then the newest visible content wins. No document-wide lifetime quota.
 */
internal class ProgressiveIllustrations(
    scope: CoroutineScope,
    private val render: suspend (IllustrationRequest) -> IllustrationResult,
    private val release: () -> Unit,
    private val publish: suspend (() -> Unit) -> Unit = { apply -> apply() },
    private val nanoTime: () -> Long = System::nanoTime,
    private val cache: IllustrationCache = IllustrationCache(),
    private val clearCacheOnClose: Boolean = true
) : AutoCloseable {
    private val viewport = MutableStateFlow<List<IllustrationRequest>>(emptyList())
    private val queue = MutableStateFlow(IllustrationViewport(0, emptyList()))
    val requested: StateFlow<List<IllustrationRequest>> get() = viewport
    private val mutableResults = MutableStateFlow(cache.snapshot())
    val results: StateFlow<Map<IllustrationRequest, IllustrationResult>> get() = mutableResults
    private var closed = false
    private val job: Job = scope.launch {
        queue.collect { batch ->
            val requests = batch.requests
            var spentNanos = 0L
            val pinned = HashSet<IllustrationRequest>()
            val deferred = LinkedHashMap<IllustrationRequest, IllustrationResult>()
            for (request in requests) {
                currentCoroutineContext().ensureActive()
                if (queue.value != batch || closed) break
                val cached = cache[request]
                if (cached != null) {
                    pinned += request
                    continue
                }
                val result = if (spentNanos / 1_000_000 >= 12_000) {
                    IllustrationResult.Fallback(IllustrationFailure.Budget, request.kind)
                } else {
                    val started = nanoTime()
                    render(request).forKind(request.kind).also {
                        spentNanos += nanoTime() - started
                    }
                }
                currentCoroutineContext().ensureActive()
                if (closed) break
                if ((
                        result is IllustrationResult.Fallback &&
                            result.reason in setOf(
                                IllustrationFailure.Budget,
                                IllustrationFailure.Unavailable
                            )
                        ) ||
                    !cache.put(request, result, pinned)
                ) {
                    // Queue exhaustion is not a property of this formula; a new viewport can retry.
                    deferred[request] = if (result is IllustrationResult.Fallback) {
                        result
                    } else {
                        IllustrationResult.Fallback(IllustrationFailure.Budget, request.kind)
                    }
                } else {
                    pinned += request
                }
                if (queue.value != batch) break
                val snapshot = cache.snapshot() + deferred
                if (mutableResults.value != snapshot) {
                    publish { if (!closed) mutableResults.value = snapshot }
                }
            }
            if (!closed && queue.value == batch) {
                val snapshot = cache.snapshot() + deferred
                if (mutableResults.value != snapshot) {
                    publish { if (!closed) mutableResults.value = snapshot }
                }
            }
        }
    }

    fun updateViewport(requests: List<IllustrationRequest>) {
        if (closed) return
        val bounded = requests.distinct().take(MAX_DOCUMENT_ILLUSTRATIONS)
        if (viewport.value == bounded) return
        viewport.value = bounded
        // Preserve a quick stop/start even when the in-flight worker finishes after both. Lists
        // alone would conflate A -> empty -> A and keep the released worker's failure on screen.
        queue.value = IllustrationViewport(Math.incrementExact(queue.value.generation), bounded)
    }

    override fun close() {
        if (closed) return
        closed = true
        job.cancel()
        release()
        if (clearCacheOnClose) cache.clear()
        mutableResults.value = emptyMap()
        viewport.value = emptyList()
    }
}

private data class IllustrationViewport(
    val generation: Long,
    val requests: List<IllustrationRequest>
)
