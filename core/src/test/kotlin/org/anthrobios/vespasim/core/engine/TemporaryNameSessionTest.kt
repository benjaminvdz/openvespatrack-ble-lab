package org.anthrobios.vespasim.core.engine

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class TemporaryNameSessionTest {
    private class Clock : EngineScheduler {
        data class Job(val at: Long, val order: Int, val work: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        private var order = 0
        private val jobs = mutableListOf<Job>()
        override fun nowMs() = now
        override fun schedule(delayMs: Long, task: () -> Unit): Cancellation {
            val job = Job(now + delayMs, order++, task)
            jobs += job
            return Cancellation { job.cancelled = true }
        }
        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val job = jobs.filter { !it.cancelled && it.at <= until }.minWithOrNull(compareBy<Job> { it.at }.thenBy { it.order }) ?: break
                jobs.remove(job); now = job.at; job.work()
            }
            now = until
        }
    }

    private class Store : TemporaryNameStore {
        var journal: TemporaryNameJournal? = null
        var readFailure = false
        var writeFailure = false
        var clearFailure = false
        var writes = 0
        var clears = 0
        override fun read(): TemporaryNameJournal? { check(!readFailure) { "corrupt journal" }; return journal }
        override fun write(journal: TemporaryNameJournal) { check(!writeFailure) { "disk full" }; writes++; this.journal = journal }
        override fun clear() { check(!clearFailure) { "clear failed" }; clears++; journal = null }
    }

    private class Adapter(val store: Store) : TemporaryNameAdapter {
        var name = "Phone"
        var readFailure = false
        var requestFailure = false
        var accepts = true
        var immediate = false
        var reads = 0
        val requests = mutableListOf<String>()
        override fun currentName(): String { reads++; check(!readFailure) { "Bluetooth permission or power unavailable" }; return name }
        override fun requestName(name: String): Boolean {
            assertNotNull("Journal must exist before any request", store.journal)
            check(!requestFailure) { "binder failed" }
            requests += name
            if (accepts && immediate) this.name = name
            return accepts
        }
    }

    private class Lab(val observerThrows: Boolean = false) {
        val clock = Clock()
        val store = Store()
        val adapter = Adapter(store)
        val events = mutableListOf<String>()
        val session = TemporaryNameSession(adapter, store, clock, { kind, _ ->
            events += kind
            if (observerThrows) error("observer failed")
        }, timeoutMs = 500, pollMs = 50)
        val prepared = mutableListOf<Result<Unit>>()
        val restored = mutableListOf<Result<Unit>>()
        fun prepare(name: String? = "vn0CqrvM3e7/", owner: String = "run-1") = session.prepare(name, owner) { prepared += it }
        fun restore() = session.restore { restored += it }
        fun active() { prepare(); adapter.name = "vn0CqrvM3e7/"; clock.advance(50); assertTrue(prepared.single().isSuccess) }
    }

    @Test fun waitsForConfirmedNameAndPersistsBeforeAllowingRadio() {
        val l = Lab(); l.prepare()
        assertTrue(l.prepared.isEmpty()); assertTrue(l.session.isBusy)
        assertEquals(TemporaryNamePhase.APPLYING, l.store.journal!!.phase)
        assertEquals("Phone", l.store.journal!!.originalName)
        l.adapter.name = "vn0CqrvM3e7/"; l.clock.advance(50)
        assertTrue(l.prepared.single().isSuccess); assertFalse(l.session.isBusy)
        assertEquals(TemporaryNamePhase.ACTIVE, l.store.journal!!.phase)
    }

    @Test fun restoresOnlyAfterOriginalNameIsReadBack() {
        val l = Lab(); l.active(); l.restore()
        assertTrue(l.restored.isEmpty()); assertEquals(TemporaryNamePhase.RESTORING, l.store.journal!!.phase)
        assertEquals(listOf("vn0CqrvM3e7/", "Phone"), l.adapter.requests)
        l.adapter.name = "Phone"; l.clock.advance(50)
        assertTrue(l.restored.single().isSuccess); assertNull(l.store.journal)
    }

    @Test fun immediatePlatformChangesCompleteWithoutPolling() {
        val l = Lab(); l.adapter.immediate = true; l.prepare(); l.restore()
        assertTrue(l.prepared.single().isSuccess); assertTrue(l.restored.single().isSuccess)
        assertNull(l.store.journal); assertEquals("Phone", l.adapter.name)
    }

    @Test fun noNameNeverTouchesAdapterOrExistingJournal() {
        val l = Lab(); val existing = TemporaryNameJournal("Original", "Temporary", "old")
        l.store.journal = existing; l.adapter.readFailure = true; l.prepare(null)
        assertTrue(l.prepared.single().isSuccess); assertEquals(0, l.adapter.reads)
        assertEquals(existing, l.store.journal); assertTrue(l.adapter.requests.isEmpty())
    }

    @Test fun alreadyMatchingNameDoesNotTakeOwnership() {
        val l = Lab(); l.adapter.name = "vn0CqrvM3e7/"; l.prepare(); l.restore()
        assertTrue(l.prepared.single().isSuccess); assertTrue(l.restored.single().isSuccess)
        assertEquals(0, l.store.writes); assertTrue(l.adapter.requests.isEmpty())
    }

    @Test fun pendingJournalBlocksAnotherNamedRun() {
        val l = Lab(); l.store.journal = TemporaryNameJournal("A", "B", "previous"); l.prepare()
        assertTrue(l.prepared.single().isFailure); assertTrue(l.adapter.requests.isEmpty())
        assertEquals("previous", l.store.journal!!.ownerRunId)
    }

    @Test fun overlappingPrepareDoesNotReplaceOwnerOrCallback() {
        val l = Lab(); l.prepare(); l.prepare("Other", "run-2")
        assertEquals(1, l.prepared.size); assertTrue(l.prepared.single().isFailure)
        assertEquals("run-1", l.store.journal!!.ownerRunId)
        l.adapter.name = "vn0CqrvM3e7/"; l.clock.advance(50)
        assertTrue(l.prepared.last().isSuccess); assertEquals(1, l.adapter.requests.size)
    }

    @Test fun permissionOrPowerFailureBeforeRequestDoesNotCreateJournal() {
        val l = Lab(); l.adapter.readFailure = true; l.prepare()
        assertTrue(l.prepared.single().isFailure); assertNull(l.store.journal); assertTrue(l.adapter.requests.isEmpty())
    }

    @Test fun corruptedJournalPreventsAnyChange() {
        val l = Lab(); l.store.readFailure = true; l.prepare(); l.restore()
        assertTrue(l.prepared.single().isFailure); assertTrue(l.restored.single().isFailure)
        assertTrue(l.adapter.requests.isEmpty())
    }

    @Test fun persistenceFailureBeforeRequestPreventsNameMutation() {
        val l = Lab(); l.store.writeFailure = true; l.prepare()
        assertTrue(l.prepared.single().isFailure); assertNull(l.store.journal); assertTrue(l.adapter.requests.isEmpty())
    }

    @Test fun explicitRejectedPrepareClearsUnusedJournal() {
        val l = Lab(); l.adapter.accepts = false; l.prepare()
        assertTrue(l.prepared.single().isFailure); assertNull(l.store.journal)
    }

    @Test fun uncertainPlatformExceptionRetainsJournal() {
        val l = Lab(); l.adapter.requestFailure = true; l.prepare()
        assertTrue(l.prepared.single().isFailure); assertEquals(TemporaryNamePhase.APPLYING, l.store.journal!!.phase)
    }

    @Test fun timeoutDoesNotForgetPotentialLateNameChange() {
        val l = Lab(); l.prepare(); l.clock.advance(500)
        assertTrue(l.prepared.single().isFailure); assertNotNull(l.store.journal)
        l.clock.advance(5_000); assertEquals(1, l.prepared.size)
        l.adapter.name = "vn0CqrvM3e7/"; l.adapter.immediate = true; l.restore()
        assertTrue(l.restored.single().isSuccess); assertEquals("Phone", l.adapter.name); assertNull(l.store.journal)
    }

    @Test fun cancellationWhileApplyingNeverCompletesPrepareSuccessfullyAndCleansLateChange() {
        val l = Lab(); l.prepare(); l.restore()
        assertTrue(l.prepared.single().exceptionOrNull() is CancellationException)
        assertTrue(l.restored.isEmpty()); assertEquals(1, l.adapter.requests.size)
        l.adapter.name = "vn0CqrvM3e7/"; l.adapter.immediate = true; l.clock.advance(50)
        assertTrue(l.restored.single().isSuccess); assertEquals(1, l.prepared.size)
        assertNull(l.store.journal); assertEquals("Phone", l.adapter.name)
    }

    @Test fun cancelledUnconfirmedRequestStillAtOriginalKeepsRecoveryRecord() {
        val l = Lab(); l.prepare(); l.restore(); l.clock.advance(500)
        assertTrue(l.restored.single().isFailure); assertEquals("Phone", l.adapter.name)
        assertEquals(TemporaryNamePhase.APPLYING, l.store.journal!!.phase)
    }

    @Test fun externallyChangedConfirmedNameIsPreservedAndOwnershipReleased() {
        val l = Lab(); l.active(); l.adapter.name = "My new phone name"; l.restore()
        assertTrue(l.restored.single().isSuccess); assertNull(l.store.journal)
        assertEquals(listOf("vn0CqrvM3e7/"), l.adapter.requests)
        assertTrue("name_external_preserved" in l.events)
    }

    @Test fun externalChangeDuringPreparationFailsAndKeepsAmbiguousJournal() {
        val l = Lab(); l.prepare(); l.adapter.name = "External"; l.clock.advance(50); l.restore()
        assertTrue(l.prepared.single().isFailure); assertTrue(l.restored.single().isFailure)
        assertNotNull(l.store.journal); assertEquals("External", l.adapter.name); assertEquals(1, l.adapter.requests.size)
    }

    @Test fun restorationCanRecoverFromNewProcessUsingDurableActiveJournal() {
        val l = Lab(); l.store.journal = TemporaryNameJournal("Original", "Temporary", "crashed", TemporaryNamePhase.ACTIVE)
        l.adapter.name = "Temporary"; l.adapter.immediate = true; l.restore()
        assertTrue(l.restored.single().isSuccess); assertEquals("Original", l.adapter.name); assertNull(l.store.journal)
    }

    @Test fun restorationAfterProcessDeathAcknowledgesAlreadyRestoredName() {
        val l = Lab(); l.store.journal = TemporaryNameJournal("Phone", "Temporary", "crashed", TemporaryNamePhase.RESTORING)
        l.restore(); assertTrue(l.restored.single().isSuccess); assertNull(l.store.journal); assertTrue(l.adapter.requests.isEmpty())
    }

    @Test fun revokedPermissionDuringPreparationFailsWithoutForgettingJournal() {
        val l = Lab(); l.prepare(); l.adapter.readFailure = true; l.clock.advance(50)
        assertTrue(l.prepared.single().isFailure); assertNotNull(l.store.journal)
    }

    @Test fun powerOrPermissionLossDuringRestoreIsRetryable() {
        val l = Lab(); l.active(); l.adapter.readFailure = true; l.restore()
        assertTrue(l.restored.single().isFailure); assertNotNull(l.store.journal)
        l.adapter.readFailure = false; l.adapter.immediate = true; l.restore()
        assertTrue(l.restored.last().isSuccess); assertNull(l.store.journal)
    }

    @Test fun rejectedRestorationKeepsRecordForRetry() {
        val l = Lab(); l.active(); l.adapter.accepts = false; l.restore()
        assertTrue(l.restored.single().isFailure); assertNotNull(l.store.journal)
        l.adapter.accepts = true; l.adapter.immediate = true; l.restore()
        assertTrue(l.restored.last().isSuccess); assertNull(l.store.journal)
    }

    @Test fun restoreTimeoutKeepsRecordAndAcknowledgesLateSuccess() {
        val l = Lab(); l.active(); l.restore(); l.clock.advance(500)
        assertTrue(l.restored.single().isFailure); assertNotNull(l.store.journal)
        l.adapter.name = "Phone"; l.restore()
        assertTrue(l.restored.last().isSuccess); assertNull(l.store.journal)
    }

    @Test fun failedClearDoesNotPretendRecoveryIsFinished() {
        val l = Lab(); l.active(); l.adapter.immediate = true; l.store.clearFailure = true; l.restore()
        assertTrue(l.restored.single().isFailure); assertNotNull(l.store.journal)
        l.store.clearFailure = false; l.restore()
        assertTrue(l.restored.last().isSuccess); assertNull(l.store.journal)
    }

    @Test fun activeJournalWriteFailureDoesNotAllowRadioStart() {
        val l = Lab(); l.prepare(); l.store.writeFailure = true; l.adapter.name = "vn0CqrvM3e7/"; l.clock.advance(50)
        assertTrue(l.prepared.single().isFailure); assertEquals(TemporaryNamePhase.APPLYING, l.store.journal!!.phase)
        l.store.writeFailure = false; l.adapter.immediate = true; l.restore(); assertTrue(l.restored.single().isSuccess)
    }

    @Test fun observerExceptionsCannotInterruptDurableCleanup() {
        val l = Lab(observerThrows = true); l.adapter.immediate = true; l.prepare(); l.restore()
        assertTrue(l.prepared.single().isSuccess); assertTrue(l.restored.single().isSuccess); assertNull(l.store.journal)
    }

    @Test fun externalRenameWhileRestoringIsNotOverwrittenAgain() {
        val l = Lab(); l.active(); l.restore(); l.adapter.name = "External"; l.clock.advance(50)
        assertTrue(l.restored.single().isFailure); assertNotNull(l.store.journal)
        assertEquals(listOf("vn0CqrvM3e7/", "Phone"), l.adapter.requests)
    }

    @Test fun overlappingRestoreDoesNotCancelFirstRestore() {
        val l = Lab(); l.active(); l.restore(); l.restore()
        assertTrue(l.restored.single().isFailure); assertTrue(l.session.isBusy)
        l.adapter.name = "Phone"; l.clock.advance(50)
        assertTrue(l.restored.last().isSuccess); assertNull(l.store.journal)
    }

    @Test fun cancellingPreparationMarksRestoreBusyBeforeCallbackCanReenter() {
        val l = Lab(); var reentrant: Result<Unit>? = null
        l.session.prepare("Temporary", "run") { if (it.isFailure) l.session.prepare("Other", "new") { r -> reentrant = r } }
        l.restore(); assertTrue(reentrant!!.isFailure); assertEquals(listOf("Temporary"), l.adapter.requests)
    }

    @Test fun invalidPersistedJournalNeverRequestsAName() {
        for (record in listOf(
            TemporaryNameJournal("A", "B", "run", version = 2),
            TemporaryNameJournal("A", "A", "run"),
            TemporaryNameJournal("A", "B", ""),
            TemporaryNameJournal("A", "é".repeat(125), "run"),
        )) {
            val l = Lab(); l.store.journal = record; l.restore()
            assertTrue(l.restored.single().isFailure); assertEquals(record, l.store.journal)
            assertTrue(l.adapter.requests.isEmpty())
        }
    }

    @Test fun failureToPersistRestoringPreventsRestoreRequest() {
        val l = Lab(); l.active(); l.store.writeFailure = true; l.restore()
        assertTrue(l.restored.single().isFailure); assertEquals(TemporaryNamePhase.ACTIVE, l.store.journal!!.phase)
        assertEquals(listOf("vn0CqrvM3e7/"), l.adapter.requests)
    }

    @Test fun nextRunKeepsItsOwnOriginalNameAfterSuccessfulCleanup() {
        val l = Lab(); l.adapter.immediate = true; l.prepare(); l.restore()
        l.adapter.name = "Different user name"; l.prepare("New tag", "run-2")
        assertEquals("Different user name", l.store.journal!!.originalName)
        assertEquals("run-2", l.store.journal!!.ownerRunId)
        l.restore(); assertEquals("Different user name", l.adapter.name); assertNull(l.store.journal)
    }

    @Test fun invalidRequestedNameFailsBeforeStorageOrPlatformCall() {
        for (name in listOf("", "  ", "é".repeat(125))) {
            val l = Lab(); l.prepare(name)
            assertTrue(l.prepared.single().isFailure); assertNull(l.store.journal); assertTrue(l.adapter.requests.isEmpty())
        }
    }

    @Test fun throwingCancellationCallbackCannotPreventRestoration() {
        val l = Lab()
        l.session.prepare("Temporary", "run") { if (it.isFailure) error("caller failed") }
        l.restore(); l.adapter.name = "Temporary"; l.adapter.immediate = true; l.clock.advance(50)
        assertTrue(l.restored.single().isSuccess); assertNull(l.store.journal); assertEquals("Phone", l.adapter.name)
    }

    @Test fun failedClearAfterExplicitRejectionCanBeRetriedWithoutBluetoothAccess() {
        val l = Lab(); l.adapter.accepts = false; l.store.clearFailure = true; l.prepare()
        assertTrue(l.prepared.single().isFailure); assertEquals(TemporaryNamePhase.REJECTED, l.store.journal!!.phase)
        l.store.clearFailure = false; l.adapter.readFailure = true; l.restore()
        assertTrue(l.restored.single().isSuccess); assertNull(l.store.journal)
        assertEquals(listOf("vn0CqrvM3e7/"), l.adapter.requests)
    }
}
