package org.anthrobios.vespasim.service

import org.junit.Assert.*
import org.junit.Test

/** Deterministic main/worker queue interleavings, with no sleeping or Bluetooth hardware. */
class BeaconRequestsTest {
    @Test fun queuedStartStopStartIgnoresOldWorkerCommandsAndForegroundCleanup() {
        val requests = BeaconRequests()
        var preparing = false
        var ownsRadio = false
        var foreground = true
        var starts = 0
        val first = requests.request({ true }, { preparing = true })!!
        val stoppedFirst = requests.cancel()
        val second = requests.request({ true }, { preparing = true })!!

        // A delayed Start A and Stop A are processed after the user has already requested B.
        requests.finishStart(first) { preparing = false }
        requests.forCommand(stoppedFirst.command) { preparing = false; foreground = false }
        assertFalse(requests.commitStart(first, { true }) { starts++; ownsRadio = true })
        if (requests.canCleanForeground(first) { !ownsRadio }) foreground = false
        assertTrue(preparing)
        assertTrue(foreground)
        assertEquals(0, starts)

        assertTrue(requests.commitStart(second, { true }) { preparing = false; ownsRadio = true; starts++ })
        if (requests.canCleanForeground(first) { !ownsRadio }) foreground = false
        assertTrue(foreground)
        assertTrue(ownsRadio)
        assertEquals(1, starts)
    }

    @Test fun cleanupQueuedBeforeNewStartCannotStopTheNewSessionEvenBeforeItsWorkerRuns() {
        val requests = BeaconRequests()
        val first = requests.request({ true }, {})!!
        assertTrue(requests.commitStart(first, { true }, {}))
        var removed = 0
        val deferredOldCleanup = { if (requests.canCleanForeground(first) { true }) removed++ }
        val second = requests.request({ true }, {})!!
        deferredOldCleanup()
        assertEquals(0, removed)
        assertTrue(requests.isCurrentStart(second))
        assertTrue(requests.commitStart(second, { true }, {}))
        deferredOldCleanup()
        assertEquals(0, removed)
    }

    @Test fun explicitStopStillAllowsCleanupOfItsSessionAlthoughCommandGenerationChanged() {
        val requests = BeaconRequests()
        val start = requests.request({ true }, {})!!
        assertTrue(requests.commitStart(start, { true }, {}))
        val stop = requests.cancel()
        assertNotEquals(start, stop.command)
        assertEquals(start, stop.session)
        var stopped = false
        requests.forCommand(stop.command) { stopped = true }
        assertTrue(stopped)
        assertFalse(requests.canCleanForeground(start) { false })
        assertTrue(requests.canCleanForeground(start) { true })
    }

    @Test fun backgroundCancellationDuringDurablePreparationNeverCommitsTheRadio() {
        val requests = BeaconRequests()
        val first = requests.request({ true }, {})!!
        val backgroundStop = requests.cancelPending()!!
        var starts = 0
        assertFalse(requests.commitStart(first, { true }) { starts++ })
        assertEquals(0, starts)
        assertTrue(requests.canCleanForeground(backgroundStop.session) { true })
        // Returning to the screen creates no request; another explicit tap must do that.
        assertNull(requests.cancelPending())
        assertFalse(requests.isCurrentStart(first))
    }

    @Test fun publishingStartingPreventsASecondRequestBeforePendingIsReleased() {
        val requests = BeaconRequests()
        var ownsRadio = false
        val start = requests.request({ !ownsRadio }, {})!!
        assertNull(requests.request({ true }, {}))
        assertTrue(requests.commitStart(start, { true }) { ownsRadio = true })
        assertNull(requests.request({ !ownsRadio }, {}))
        assertFalse(requests.canCleanForeground(start) { !ownsRadio })
    }

    @Test fun lateStorageFailureFromOldCleanupCannotOverwriteNewPreparation() {
        val requests = BeaconRequests()
        val first = requests.request({ true }, {})!!
        assertTrue(requests.commitStart(first, { true }, {}))
        var status = "stopped"
        val second = requests.request({ true }, { status = "preparing" })!!
        requests.forSession(first) { status = "old cleanup failed" }
        assertEquals("preparing", status)
        assertTrue(requests.isCurrentStart(second))
        requests.finishStart(second) { status = "new start failed" }
        assertEquals("new start failed", status)
        assertTrue(requests.canCleanForeground(second) { true })
    }
}
