package dev.soupslurpr.beautyxt

import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.EditorDocument
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderer
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import dev.soupslurpr.beautyxt.testing.TestEditorDocumentSnapshot
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentState
import dev.soupslurpr.beautyxt.ui.editor.FileIdentityProbeResult
import dev.soupslurpr.beautyxt.ui.editor.SourceIdentitySnapshot
import dev.soupslurpr.beautyxt.ui.editor.TRANSIENT_DOCUMENT_MAX_UTF16_UNITS
import dev.soupslurpr.beautyxt.ui.editor.WritableDocumentRegistry
import dev.soupslurpr.beautyxt.ui.editor.combineCloseables
import dev.soupslurpr.beautyxt.ui.editor.parseAuthorityUser
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Step7ProductivityTest {

    @Test
    fun writableRegistryPreventsDualWriteAndSupportsReloadTransfer() {
        val registry = WritableDocumentRegistry()
        val uri = "content://org.example.docs/document/42"

        val first = registry.tryAcquire(uri)
        assertNotNull(first)
        assertTrue(registry.isOwned(uri))

        // Second window opening the same URI degrades to view-only (null lease).
        assertNull(registry.tryAcquire(uri))

        // Reloading the SAME owner coexists until publish; a failed reload preserves original ownership.
        val failedReplacement = registry.tryAcquire(uri, replacesOwnerId = first!!.ownerId)
        assertNotNull(failedReplacement)
        failedReplacement!!.close()
        assertTrue(registry.isOwned(uri))
        assertNull(registry.tryAcquire(uri))

        // Successful reload hands ownership cleanly when the original lease closes.
        val replacement = registry.tryAcquire(uri, replacesOwnerId = first.ownerId)
        assertNotNull(replacement)

        // Closing the superseded lease must NOT drop the replacement's ownership.
        first.close()
        assertTrue(registry.isOwned(uri))
        assertNull(registry.tryAcquire(uri))

        // Closing the active replacement releases the URI for future windows.
        replacement!!.close()
        assertFalse(registry.isOwned(uri))
        val reacquired = registry.tryAcquire(uri)
        assertNotNull(reacquired)
        reacquired!!.close()

        // Cross-provider SAF aliases sharing a regular kernel inode block dual write leases.
        val downloadsLease = registry.tryAcquire(
            setOf("doc:com.android.providers.downloads.documents:msf:42", "ino:98:65646")
        )
        assertNotNull(downloadsLease)
        assertNull(
            registry.tryAcquire(
                setOf("doc:com.android.externalstorage.documents:primary:Download/notes.txt", "ino:98:65646")
            )
        )
        // Failed multi-key acquisition must not leak partial ownership of its non-conflicting key.
        assertFalse(registry.isOwned("doc:com.android.externalstorage.documents:primary:Download/notes.txt"))
        downloadsLease!!.close()
        assertFalse(registry.isOwned("ino:98:65646"))

        // Active write leases can dynamically claim a newly opened save descriptor inode and
        // reject inodes owned by another live writer.
        val primaryLease = registry.tryAcquire("doc:provider:primary")
        val otherLease = registry.tryAcquire(setOf("doc:provider:other", "ino:98:77777"))
        assertNotNull(primaryLease)
        assertNotNull(otherLease)
        assertTrue(primaryLease!!.claimKey("ino:98:12345"))
        assertTrue(registry.isOwned("ino:98:12345"))
        assertFalse(primaryLease.claimKey("ino:98:77777"))
        assertNull(registry.tryAcquire(setOf("doc:alt:file", "ino:98:12345")))
        primaryLease.close()
        assertFalse(registry.isOwned("ino:98:12345"))
        assertFalse(primaryLease.claimKey("ino:98:99999"))
        otherLease!!.close()

        // Cross-provider atomic file replacement: refreshing active leases observes the new inode
        // on Window 1, releases the moved backup's obsolete inode, catches concurrent registrations,
        // and cleans up leases if cancelled across dispatcher handoff.
        runBlocking {
            var onDiskInode: FileIdentityProbeResult = FileIdentityProbeResult.Regular("ino:98:100")
            val window1 = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:com.android.providers.downloads.documents:msf:1", "ino:98:100"),
                liveProbe = { onDiskInode }
            )
            assertNotNull(window1)
            // External app moves original file to backup (ino:98:100) and replaces path with ino:98:200.
            onDiskInode = FileIdentityProbeResult.Regular("ino:98:200")
            val window2 = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:com.android.externalstorage.documents:primary:Download/notes.txt", "ino:98:200")
            )
            assertNull(window2)
            assertTrue(registry.isOwned("ino:98:200"))
            // Obsolete moved backup inode must be released so the backup can open writable.
            assertFalse(registry.isOwned("ino:98:100"))
            val backupLease = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:com.android.externalstorage.documents:primary:Download/notes.bak", "ino:98:100")
            )
            assertNotNull(backupLease)
            backupLease!!.close()

            // Concurrent lease registered while a refresh probe is suspended is also refreshed.
            var concurrentProbe: FileIdentityProbeResult = FileIdentityProbeResult.Regular("ino:98:500")
            var concurrentLease: dev.soupslurpr.beautyxt.ui.editor.WritableDocumentLease? = null
            onDiskInode = FileIdentityProbeResult.Regular("ino:98:200")
            val window1ProbeHook = registry.tryAcquire(
                documentKeys = setOf("doc:probe:hook", "ino:98:400"),
                liveProbe = {
                    // Register a brand-new writer mid-refresh whose live inode just changed to ino:98:600.
                    if (concurrentLease == null) {
                        concurrentProbe = FileIdentityProbeResult.Regular("ino:98:600")
                        concurrentLease = registry.tryAcquire(
                            documentKeys = setOf("doc:concurrent:win", "ino:98:500"),
                            liveProbe = { concurrentProbe }
                        )
                    }
                    FileIdentityProbeResult.Regular("ino:98:400")
                }
            )
            assertNotNull(window1ProbeHook)
            assertNull(
                registry.tryAcquireRefreshing(
                    documentKeys = setOf("doc:export:alias", "ino:98:600")
                )
            )
            assertTrue(registry.isOwned("ino:98:600"))
            concurrentLease?.close()
            window1ProbeHook!!.close()

            // Indeterminate probe on an active regular-file lease fails closed for regular-file destinations.
            onDiskInode = FileIdentityProbeResult.Indeterminate
            assertNull(
                registry.tryAcquireRefreshing(
                    documentKeys = setOf("doc:other:doc", "ino:98:300")
                )
            )
            // Confirmed non-regular read probe (e.g. provider returns a pipe for `r` while `rw`
            // holds a regular file) must preserve Window 1's active `rw` inode claim while still
            // allowing unrelated destinations.
            onDiskInode = FileIdentityProbeResult.NonRegularOrAbsent
            assertNull(
                registry.tryAcquireRefreshing(
                    documentKeys = setOf("doc:alias:pipe", "ino:98:200")
                )
            )
            // When Window 1's writable liveProbe returns the real `rw` inode (ino:98:200), a
            // separate read-only snapshot inode (ino:98:999) can never replace or drop ino:98:200.
            onDiskInode = FileIdentityProbeResult.Regular("ino:98:200")
            assertNull(
                registry.tryAcquireRefreshing(
                    documentKeys = setOf("doc:alias:copy", "ino:98:200")
                )
            )
            val unrelated = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:other:doc", "ino:98:300")
            )
            assertNotNull(unrelated)
            unrelated!!.close()

            // In-flight write descriptor pin keeps old inode reserved across a concurrent atomic
            // rename refresh, then releases it automatically once the write finishes.
            val inFlightPin = window1!!.pinInodeKey("ino:98:200")
            assertNotNull(inFlightPin)
            onDiskInode = FileIdentityProbeResult.Regular("ino:98:250")
            assertNull(
                registry.tryAcquireRefreshing(
                    documentKeys = setOf("doc:alt:new", "ino:98:250")
                )
            )
            // Old inode stays pinned while the write is still in flight.
            assertTrue(registry.isOwned("ino:98:200"))
            assertNull(
                registry.tryAcquireRefreshing(
                    documentKeys = setOf("doc:alt:old", "ino:98:200")
                )
            )
            inFlightPin!!.close()
            // Once the in-flight write finishes, the superseded old inode is released immediately.
            assertFalse(registry.isOwned("ino:98:200"))
            assertTrue(registry.isOwned("ino:98:250"))

            window1.close()
            assertFalse(registry.isOwned("ino:98:250"))

            // Cross-window inode move (A: 1->2, B: 2->3) and swap (A: 2->3, B: 3->2): two-phase
            // atomic refresh releases stale claims before binding newly observed inodes so Save a
            // copy cannot slip in and overwrite another window's active document.
            var probeA: FileIdentityProbeResult = FileIdentityProbeResult.Regular("ino:99:1")
            var probeB: FileIdentityProbeResult = FileIdentityProbeResult.Regular("ino:99:2")
            val leaseA = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:win:a", "ino:99:1"),
                liveProbe = { probeA }
            )
            val leaseB = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:win:b", "ino:99:2"),
                liveProbe = { probeB }
            )
            assertNotNull(leaseA)
            assertNotNull(leaseB)
            // File B moves to ino:99:3 and File A takes over ino:99:2.
            probeA = FileIdentityProbeResult.Regular("ino:99:2")
            probeB = FileIdentityProbeResult.Regular("ino:99:3")
            assertNull(
                registry.tryAcquireRefreshing(
                    documentKeys = setOf("doc:export:copy", "ino:99:2")
                )
            )
            assertTrue(registry.isOwned("ino:99:2"))
            assertTrue(registry.isOwned("ino:99:3"))
            assertFalse(registry.isOwned("ino:99:1"))
            // Full inode swap between A and B: both stay protected.
            probeA = FileIdentityProbeResult.Regular("ino:99:3")
            probeB = FileIdentityProbeResult.Regular("ino:99:2")
            assertNull(registry.tryAcquireRefreshing(setOf("doc:export:a", "ino:99:3")))
            assertNull(registry.tryAcquireRefreshing(setOf("doc:export:b", "ino:99:2")))
            leaseA!!.close()
            leaseB!!.close()
            assertFalse(registry.isOwned("ino:99:2"))
            assertFalse(registry.isOwned("ino:99:3"))

            // Two windows must NEVER pin the same inode concurrently even when refresh observes
            // B's URI converging onto A's currently pinned in-flight write inode.
            var pinProbeA: FileIdentityProbeResult = FileIdentityProbeResult.Regular("ino:100:1")
            var pinProbeB: FileIdentityProbeResult = FileIdentityProbeResult.Regular("ino:100:2")
            val pinLeaseA = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:pin:a", "ino:100:1"),
                liveProbe = { pinProbeA }
            )!!
            val pinLeaseB = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:pin:b", "ino:100:2"),
                liveProbe = { pinProbeB }
            )!!
            val activePinA = pinLeaseA.pinInodeKey("ino:100:1")
            assertNotNull(activePinA)
            // Externally, A moves to ino:100:3 while B moves to A's still-pinned ino:100:1.
            pinProbeA = FileIdentityProbeResult.Regular("ino:100:3")
            pinProbeB = FileIdentityProbeResult.Regular("ino:100:1")
            // Trigger refresh via a third-party export check on ino:100:1 (blocked).
            assertNull(registry.tryAcquireRefreshing(setOf("doc:pin:copy", "ino:100:1")))
            // While A still holds its in-flight pin on ino:100:1, B MUST NOT pin ino:100:1!
            assertNull(pinLeaseB.pinInodeKey("ino:100:1"))
            // Once A finishes its write (closing activePinA), A drops superseded ino:100:1 and
            // B now holds exclusive ownership so B's pin succeeds cleanly.
            activePinA!!.close()
            val activePinB = pinLeaseB.pinInodeKey("ino:100:1")
            assertNotNull(activePinB)
            activePinB!!.close()
            pinLeaseA.close()
            pinLeaseB.close()
            assertFalse(registry.isOwned("ino:100:1"))
            assertFalse(registry.isOwned("ino:100:3"))

            // True inode convergence (A stays on ino:101:1, B's URI moves from ino:101:2 to
            // ino:101:1): incumbent A keeps exclusive write ownership so A is NOT locked out,
            // colliding B cannot write or clobber A, and third-party exports are still blocked.
            var convProbeA: FileIdentityProbeResult = FileIdentityProbeResult.Regular("ino:101:1")
            var convProbeB: FileIdentityProbeResult = FileIdentityProbeResult.Regular("ino:101:2")
            val convLeaseA = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:conv:a", "ino:101:1"),
                liveProbe = { convProbeA }
            )!!
            val convLeaseB = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:conv:b", "ino:101:2"),
                liveProbe = { convProbeB }
            )!!
            convProbeB = FileIdentityProbeResult.Regular("ino:101:1")
            assertNull(registry.tryAcquireRefreshing(setOf("doc:conv:copy", "ino:101:1")))
            val pinA = convLeaseA.pinInodeKey("ino:101:1")
            assertNotNull("Incumbent owner A must remain writable after convergence", pinA)
            assertNull("Colliding lease B must not write concurrently onto A's inode", convLeaseB.pinInodeKey("ino:101:1"))
            pinA!!.close()
            // After A closes, B can acquire/pin ino:101:1 cleanly.
            convLeaseA.close()
            val pinBAfter = convLeaseB.pinInodeKey("ino:101:1")
            assertNotNull(pinBAfter)
            pinBAfter!!.close()
            convLeaseB.close()
            assertFalse(registry.isOwned("ino:101:1"))

            // Indeterminate probe on an unavailable/deleted source still blocks overwriting an
            // existing non-empty file, but allows rescuing edits to a brand-new empty file.
            val deletedLease = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:deleted:src", "ino:102:1"),
                liveProbe = { FileIdentityProbeResult.Indeterminate }
            )!!
            assertNull(
                registry.tryAcquireRefreshing(
                    documentKeys = setOf("doc:existing:target", "ino:102:2"),
                    destinationIsEmpty = false
                )
            )
            val rescueLease = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:rescue:new", "ino:102:2"),
                destinationIsEmpty = true
            )
            assertNotNull("Rescue save to brand-new empty file must succeed when source is unavailable", rescueLease)
            rescueLease!!.close()
            deletedLease.close()

            // Stale refresh probe must never overwrite a newer direct `rw` autosave inode claim
            // made while the probe was suspended.
            var staleLeaseRef: dev.soupslurpr.beautyxt.ui.editor.WritableDocumentLease? = null
            val staleRaceLease = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:stale:win", "ino:103:1"),
                liveProbe = {
                    // Simulate autosave observing and pinning newer inode ino:103:2, then finishing
                    // its write, while this older refresh probe returns stale ino:103:1.
                    val pin = staleLeaseRef!!.pinInodeKey("ino:103:2")
                    assertNotNull(pin)
                    pin!!.close()
                    FileIdentityProbeResult.Regular("ino:103:1")
                }
            )!!
            staleLeaseRef = staleRaceLease
            assertNull(
                "Stale probe must not drop live writer's newer ino:103:2 claim",
                registry.tryAcquireRefreshing(setOf("doc:stale:export", "ino:103:2"))
            )
            assertTrue(registry.isOwned("ino:103:2"))
            staleRaceLease.close()
            assertFalse(registry.isOwned("ino:103:2"))

            // A lease that closes while its Indeterminate probe is suspended must not block an
            // unrelated file from acquiring a write lease.
            var closingLeaseRef: dev.soupslurpr.beautyxt.ui.editor.WritableDocumentLease? = null
            val closingLease = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:closing:win", "ino:104:1"),
                liveProbe = {
                    closingLeaseRef!!.close()
                    FileIdentityProbeResult.Indeterminate
                }
            )!!
            closingLeaseRef = closingLease
            val unrelatedAfterClose = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:unrelated:win", "ino:104:2"),
                destinationIsEmpty = false
            )
            assertNotNull("Closed window's indeterminate probe must not force unrelated file view-only", unrelatedAfterClose)
            unrelatedAfterClose!!.close()

            // Cancellation right as acquireRefreshingWithCancelCleanup returns must never leak the lease.
            val cancelKey = "doc:cancel:test"
            val parentJob = kotlinx.coroutines.Job()
            val cancelledScope = kotlinx.coroutines.CoroutineScope(parentJob)
            val acquiredKey = registry.tryAcquire(
                documentKeys = setOf("doc:cancel:trigger", "ino:98:700"),
                liveProbe = {
                    parentJob.cancel()
                    FileIdentityProbeResult.Regular("ino:98:700")
                }
            )
            assertNotNull(acquiredKey)
            val job = cancelledScope.launch {
                registry.acquireRefreshingWithCancelCleanup(setOf(cancelKey))
            }
            job.join()
            acquiredKey!!.close()
            assertFalse(registry.isOwned(cancelKey))

            // An active in-flight pin holds BOTH the opened inode AND the lease's canonical SAF
            // document key independently: reload is blocked while parent + pin are both active,
            // closing the parent lease keeps both keys reserved, and ownerIdFor never returns a
            // standalone pin ID as a replaceable lease owner.
            val pinnedParent = registry.tryAcquire(setOf("doc:pin:parent", "ino:105:1"))!!
            val parentOwnerId = pinnedParent.ownerId
            val standalonePin = pinnedParent.pinInodeKey("ino:105:1")
            assertNotNull(standalonePin)
            assertNull(
                "Reload must not bypass an active in-flight pin while parent lease is still open",
                registry.tryAcquire(
                    documentKeys = setOf("doc:pin:parent", "ino:105:1"),
                    replacesOwnerId = parentOwnerId
                )
            )
            pinnedParent.close()
            assertTrue(registry.isOwned("ino:105:1"))
            assertTrue(registry.isOwned("doc:pin:parent"))
            assertNull(
                "ownerIdFor must never return a standalone pin as a replaceable owner",
                registry.ownerIdFor(setOf("doc:pin:parent", "ino:105:1"))
            )
            assertNull(
                "Another window must not acquire the SAF document key on a different inode while pin is active",
                registry.tryAcquire(setOf("doc:pin:parent", "ino:105:2"))
            )
            assertNull(
                "Alias must not acquire inode while standalone pin is still open after parent lease closes",
                registry.tryAcquire(setOf("doc:pin:alias", "ino:105:1"))
            )
            assertNull(
                "Reload replacing parentOwnerId must not bypass active standalone pin",
                registry.tryAcquire(
                    documentKeys = setOf("doc:pin:parent", "ino:105:1"),
                    replacesOwnerId = parentOwnerId
                )
            )
            standalonePin!!.close()
            assertFalse(registry.isOwned("ino:105:1"))
            assertFalse(registry.isOwned("doc:pin:parent"))
            val afterPinRelease = registry.tryAcquire(setOf("doc:pin:parent", "ino:105:1"))
            assertNotNull(afterPinRelease)
            afterPinRelease!!.close()

            // Round 33 regression: Window A opens inode I; external atomic rename moves A's URI to
            // inode J; Window B calls tryAcquireRefreshing(J), whose probe of A observes J while A
            // concurrently pins its already-open descriptor for I. Refresh MUST re-probe A instead
            // of discarding J, rejecting B and keeping BOTH pinned I and live J owned by A.
            var windowARef: dev.soupslurpr.beautyxt.ui.editor.WritableDocumentLease? = null
            var pinOfOldI: AutoCloseable? = null
            var probeCalls = 0
            val windowALease = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:rename:a", "ino:106:1"),
                liveProbe = {
                    probeCalls += 1
                    if (probeCalls == 1) {
                        pinOfOldI = windowARef!!.pinInodeKey("ino:106:1")
                        assertNotNull(pinOfOldI)
                    }
                    FileIdentityProbeResult.Regular("ino:106:2")
                }
            )!!
            windowARef = windowALease
            assertNull(
                "Window B must not acquire live inode J after A pinned older descriptor I during refresh",
                registry.tryAcquireRefreshing(setOf("doc:rename:aliasB", "ino:106:2"))
            )
            assertTrue("Re-probe must have run after generation change", probeCalls >= 2)
            assertTrue(registry.isOwned("ino:106:1"))
            assertTrue(registry.isOwned("ino:106:2"))
            pinOfOldI!!.close()
            assertFalse(registry.isOwned("ino:106:1"))
            assertTrue(registry.isOwned("ino:106:2"))
            windowALease.close()
            assertFalse(registry.isOwned("ino:106:2"))

            // Round 35 regression: when two open windows converge onto the same inode (Window B is
            // incumbent on ino:107:1 and Window A's URI moves onto ino:107:1), Window A loses live
            // ownership and its removal guard MUST be rejected so Trash/Delete cannot delete B's file.
            val incumbentB = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:conv:b", "ino:107:1"),
                liveProbe = { FileIdentityProbeResult.Regular("ino:107:1") }
            )!!
            val collidingA = registry.tryAcquireRefreshing(
                documentKeys = setOf("doc:conv:a", "ino:107:2"),
                liveProbe = { FileIdentityProbeResult.Regular("ino:107:1") }
            )!!
            assertNull(
                "Window A must not obtain a removal guard for an inode owned by incumbent Window B",
                collidingA.acquireRemovalGuard(setOf("doc:conv:a", "ino:107:1"))
            )
            val validBRemoval = incumbentB.acquireRemovalGuard(setOf("doc:conv:b", "ino:107:1"))
            assertNotNull("Incumbent Window B should still obtain its own removal guard", validBRemoval)
            validBRemoval!!.close()
            collidingA.close()
            incumbentB.close()
            assertFalse(registry.isOwned("ino:107:1"))

            // Round 36 regression: post-capability-query identity verification rejects mismatching
            // or indeterminate live targets so URI-based Trash/Delete cannot remove a swapped file.
            assertTrue(
                dev.soupslurpr.beautyxt.ui.editor.matchesGuardedRemovalIdentity(
                    expectedInodeKey = "ino:108:1",
                    observed = dev.soupslurpr.beautyxt.importing.client.DescriptorFileIdentity.Regular("ino:108:1")
                )
            )
            assertFalse(
                dev.soupslurpr.beautyxt.ui.editor.matchesGuardedRemovalIdentity(
                    expectedInodeKey = "ino:108:1",
                    observed = dev.soupslurpr.beautyxt.importing.client.DescriptorFileIdentity.Regular("ino:108:2")
                )
            )
            assertFalse(
                dev.soupslurpr.beautyxt.ui.editor.matchesGuardedRemovalIdentity(
                    expectedInodeKey = "ino:108:1",
                    observed = dev.soupslurpr.beautyxt.importing.client.DescriptorFileIdentity.Indeterminate
                )
            )
            assertFalse(
                dev.soupslurpr.beautyxt.ui.editor.matchesGuardedRemovalIdentity(
                    expectedInodeKey = "ino:108:1",
                    observed = dev.soupslurpr.beautyxt.importing.client.DescriptorFileIdentity.NonRegular
                )
            )
            assertTrue(
                dev.soupslurpr.beautyxt.ui.editor.matchesGuardedRemovalIdentity(
                    expectedInodeKey = null,
                    observed = dev.soupslurpr.beautyxt.importing.client.DescriptorFileIdentity.NonRegular
                )
            )
        }

        // SourceIdentitySnapshot fails closed on indeterminate live probes for regular destinations.
        val indeterminateSource = SourceIdentitySnapshot(
            canonicalKeys = setOf("doc:a:1", "ino:98:100"),
            liveProbeIndeterminate = true
        )
        assertTrue(indeterminateSource.conflictsWith(setOf("doc:b:2", "ino:98:200"), destinationHasRegularInode = true))
        assertFalse(indeterminateSource.conflictsWith(setOf("doc:b:2", "ino:98:200"), destinationHasRegularInode = true, destinationIsEmpty = true))
        assertTrue(indeterminateSource.conflictsWith(setOf("doc:a:1", "ino:98:200"), destinationHasRegularInode = true, destinationIsEmpty = true))
        assertFalse(indeterminateSource.conflictsWith(setOf("doc:b:2"), destinationHasRegularInode = false))

        // Authority user-qualifier parsing matches AOSP ContentProvider (signed int, lastIndexOf('@')).
        val userZero = parseAuthorityUser("0@com.example.docs")
        assertEquals(0, userZero.userId)
        assertEquals("com.example.docs", userZero.providerAuthority)
        val signedPlusZero = parseAuthorityUser("+0@com.example.docs")
        assertEquals(0, signedPlusZero.userId)
        assertEquals("com.example.docs", signedPlusZero.providerAuthority)
        val userCurrent = parseAuthorityUser("-2@com.example.docs")
        assertEquals(-2, userCurrent.userId)
        assertEquals("com.example.docs", userCurrent.providerAuthority)
        val userTen = parseAuthorityUser("10@com.example.docs")
        assertEquals(10, userTen.userId)
        assertEquals("com.example.docs", userTen.providerAuthority)
        val unqualified = parseAuthorityUser("com.example.docs")
        assertNull(unqualified.userId)
        assertEquals("com.example.docs", unqualified.providerAuthority)
    }

    @Test
    fun combineCloseablesClosesEveryLeaseExactlyOnce() {
        val closedCount = AtomicInteger(0)
        val a = AutoCloseable { closedCount.incrementAndGet() }
        val b = AutoCloseable { closedCount.incrementAndGet() }
        val combined = combineCloseables(null, a, b, null)
        assertNotNull(combined)
        combined!!.close()
        combined.close()
        assertEquals(2, closedCount.get())
    }

    @Test
    fun linePreservingPatchesKeepInteriorNewlinesUntouched() {
        val minimal = dev.soupslurpr.beautyxt.ui.editor.findUtf16MinimalDiff(
            "first\nsecond\nthird\n",
            "  first\n  second\nthird\n"
        )
        assertNotNull(minimal)
        val removed = "first\nsecond\nthird\n".substring(
            minimal!!.oldRange.start.toInt(),
            minimal.oldRange.end.toInt()
        )
        val patches = dev.soupslurpr.beautyxt.ui.editor.buildLinePreservingPatches(
            oldText = removed,
            newText = minimal.replacement,
            baseGlobalOffset = minimal.oldRange.start
        )
        assertNotNull(patches)
        assertEquals(2, patches!!.size)
        assertEquals(Utf16Range(0L, 0L), patches[0].range)
        assertEquals("  ", patches[0].inserted)
        assertEquals(Utf16Range(6L, 6L), patches[1].range)
        assertEquals("  ", patches[1].inserted)
        // Changing line count falls back to null (single replace).
        assertNull(
            dev.soupslurpr.beautyxt.ui.editor.buildLinePreservingPatches(
                oldText = "a\nb",
                newText = "a\nb\nc",
                baseGlobalOffset = 0L
            )
        )
        // 4,097 short lines (fits in one edit window) must still split cleanly without falling back.
        val manyLinesOld = "a\n".repeat(4_097)
        val manyLinesNew = "  a\n".repeat(4_097)
        val manyPatches = dev.soupslurpr.beautyxt.ui.editor.buildLinePreservingPatches(
            oldText = manyLinesOld,
            newText = manyLinesNew,
            baseGlobalOffset = 0L
        )
        assertNotNull(manyPatches)
        assertEquals(4_097, manyPatches!!.size)
    }

    @Test
    fun clipboardPasteValidationBoundsNormalizesAndDetectsFormat() {
        assertEquals(ClipboardPasteResult.Empty, validateClipboardPasteText(null))
        assertEquals(ClipboardPasteResult.Empty, validateClipboardPasteText(""))
        assertEquals(ClipboardPasteResult.Empty, validateClipboardPasteText("\uD800"))
        assertEquals(
            ClipboardPasteResult.TooLarge,
            validateClipboardPasteText("a".repeat(TRANSIENT_DOCUMENT_MAX_UTF16_UNITS + 1))
        )

        val plain = validateClipboardPasteText("Hello\r\nworld") as ClipboardPasteResult.Ready
        assertEquals("Hello\nworld", plain.text)
        assertEquals(DocumentFormat.PlainText, plain.format)

        val mdHeading = validateClipboardPasteText("# Title\r\nBody") as ClipboardPasteResult.Ready
        assertEquals("# Title\nBody", mdHeading.text)
        assertEquals(DocumentFormat.Markdown, mdHeading.format)

        val mdTask = validateClipboardPasteText("- [ ] Ship Release 86") as ClipboardPasteResult.Ready
        assertEquals(DocumentFormat.Markdown, mdTask.format)

        val mdFence = validateClipboardPasteText("```rust\nfn main() {}\n```") as ClipboardPasteResult.Ready
        assertEquals(DocumentFormat.Markdown, mdFence.format)

        val mdFrontmatter = validateClipboardPasteText("---\ntitle: Note\n---\nBody") as ClipboardPasteResult.Ready
        assertEquals(DocumentFormat.Markdown, mdFrontmatter.format)
    }

    @Test
    fun splitPreviewOverlayRendersUncommittedDraftWithoutMutatingRevision() = runBlocking {
        val backing = TestEditorDocument("Hello world")
        val overlayDoc = object : EditorDocument by backing {
            override fun capturePreviewSnapshot(
                expectedRevision: Long,
                range: Utf16Range,
                replacement: String
            ): EditorDocumentSnapshot {
                val overlaid = backing.text.replaceRange(
                    range.start.toInt(),
                    range.end.toInt(),
                    replacement
                )
                return TestEditorDocumentSnapshot(revision = expectedRevision, text = overlaid)
            }
        }
        val state = EditorDocumentState(overlayDoc)
        state.loadInitialViewport()
        assertEquals(0L, state.metrics?.revision)

        var renderedText = ""
        val fakeRenderer = MarkdownRenderer { snapshot, expectedBytes ->
            val testSnapshot = snapshot as TestEditorDocumentSnapshot
            assertEquals(
                testSnapshot.text.toByteArray(Charsets.UTF_8).size.toLong(),
                expectedBytes
            )
            renderedText = testSnapshot.text
            MarkdownPreviewDocument(
                inputByteLength = expectedBytes,
                blocks = listOf(
                    MarkdownRenderBlock(
                        kind = MarkdownBlockKind.Paragraph,
                        continuesPrevious = false,
                        isOrderedListItem = false,
                        isTaskChecked = false,
                        isTaskUnchecked = false,
                        isTableHeader = false,
                        containsRawHtml = false,
                        headingLevel = 0,
                        quoteDepth = 0,
                        listDepth = 0,
                        listNumber = 0L,
                        text = renderedText,
                        metadata = "",
                        spans = emptyList()
                    )
                ),
                spanCount = 0,
                containsRawHtml = false
            )
        }

        val rendered = state.renderMarkdownForSplitPreview(
            renderer = fakeRenderer,
            draftRange = Utf16Range(6L, 11L),
            draftText = "Split View!"
        )
        assertNotNull(rendered)
        assertEquals(0L, rendered!!.revision)
        assertEquals("Hello Split View!", renderedText)
        // Document revision, content, and replace history remain completely untouched.
        assertEquals(0L, state.metrics?.revision)
        assertEquals("Hello world", backing.text)
        assertTrue(backing.replaceCalls.isEmpty())
        state.close()
    }
}
