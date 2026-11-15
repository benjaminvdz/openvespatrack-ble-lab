package org.anthrobios.vespasim.core.beacon

import org.anthrobios.vespasim.core.engine.Cancellation
import org.anthrobios.vespasim.core.engine.EngineScheduler
import org.junit.Assert.*
import org.junit.Test
import java.util.PriorityQueue

class BeaconEngineTest {
    private val identity = BeaconIdentity("0123456789abcdef")

    @Test fun identityHasVersionedBytesAndFitsLegacyWithoutNameOrManufacturerData() {
        assertEquals("OVT-0123456789ABCDEF", identity.label)
        assertArrayEquals(byteArrayOf(1, 1, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte()), identity.serviceData())
        assertEquals(30, 3 + 2 + 16 + identity.serviceData().size)
        assertTrue(BeaconIdentity.LEGACY_BUDGET_BYTES <= 31)
        val returned = identity.serviceData(); returned.fill(0)
        assertEquals(1, identity.serviceData()[0].toInt())
    }

    @Test fun identityRejectsCapturedAddressesUnknownVersionsAndMalformedValues() {
        listOf("", "01:23:45:67:89:ab", "0123456789ABCDEF", "0123456789abcdeg", "0123456789abcdef00", "0123456789abcdef\n").forEach {
            try { BeaconIdentity(it); fail("Accepted invalid identity: $it") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun continuousModeHasNoDeadlineOrRepeatedAdvertisingRequests() {
        val f = Fixture(); f.engine.start(); f.radio.accept()
        f.clock.advance(12 * 60 * 60 * 1_000L)
        assertEquals(BeaconPhase.ACTIVE, f.engine.state.phase)
        assertEquals(1, f.radio.starts)
        assertEquals(0, f.radio.stops)
        assertEquals(0, f.cleaned)
        assertEquals(0, f.clock.pending())
    }

    @Test fun explicitStopWaitsForControllerCleanup() {
        val f = Fixture(); f.engine.start(); f.radio.accept(); f.engine.stop()
        assertEquals(BeaconPhase.STOPPING, f.engine.state.phase)
        assertTrue(f.engine.state.ownsRadio)
        assertEquals(0, f.cleaned)
        f.radio.confirmStop()
        assertEquals(BeaconPhase.STOPPED, f.engine.state.phase)
        assertFalse(f.engine.state.ownsRadio)
        assertEquals(1, f.cleaned)
    }

    @Test fun repeatedStartAndStopDoNotQueueMoreRadioWork() {
        val f = Fixture(); repeat(8) { f.engine.start() }; f.radio.accept()
        repeat(8) { f.engine.start(); f.engine.stop() }
        assertEquals(1, f.radio.starts); assertEquals(1, f.radio.stops)
        f.radio.confirmStop(); f.radio.confirmStop(); f.engine.start(); f.engine.stop()
        assertEquals(1, f.cleaned); assertEquals(1, f.radio.starts)
    }

    @Test fun stopDuringPendingStartCannotBeUndoneByLateStartSuccess() {
        val f = Fixture(); f.engine.start(); f.engine.stop(); f.radio.accept()
        assertEquals(BeaconPhase.STOPPING, f.engine.state.phase)
        assertNull(f.engine.state.acceptedAtMs)
        f.radio.confirmStop()
        assertEquals(BeaconPhase.STOPPED, f.engine.state.phase)
        f.radio.accept()
        assertEquals(BeaconPhase.STOPPED, f.engine.state.phase)
    }

    @Test fun startTimeoutRequestsCleanupAndNeverAutomaticallyRestarts() {
        val f = Fixture(); f.engine.start(); f.clock.advance(5_000)
        assertEquals(BeaconPhase.STOPPING, f.engine.state.phase)
        assertEquals(BeaconIssue.START_TIMEOUT, f.engine.state.issue)
        f.radio.confirmStop(); f.clock.advance(10_000_000)
        assertEquals(BeaconPhase.FAILED, f.engine.state.phase)
        assertEquals(1, f.radio.starts)
    }

    @Test fun missingStopConfirmationRetainsRadioOwnershipAndAcceptsLateConfirmation() {
        val f = Fixture(); f.engine.start(); f.radio.accept(); f.engine.stop(); f.clock.advance(5_000)
        assertEquals(BeaconPhase.STOP_UNCONFIRMED, f.engine.state.phase)
        assertTrue(f.engine.state.ownsRadio); assertEquals(0, f.cleaned)
        f.radio.confirmStop()
        assertEquals(BeaconPhase.STOPPED, f.engine.state.phase)
        assertEquals(1, f.cleaned)
    }

    @Test fun stopRetryStillAcceptsAnEarlierGenuineCleanupConfirmation() {
        val f = Fixture(); f.engine.start(); f.radio.accept(); f.engine.stop()
        val earlier = f.radio.stopCallbacks.single()
        f.clock.advance(5_000); f.engine.stop()
        assertEquals(2, f.radio.stops)
        earlier(BeaconResult(true)); f.radio.confirmStop(); f.clock.advance(5_000)
        assertEquals(BeaconPhase.STOPPED, f.engine.state.phase)
        assertEquals(1, f.cleaned)
    }

    @Test fun permissionAndUnsupportedFailuresNeverBecomeActive() {
        listOf(BeaconIssue.PERMISSION, BeaconIssue.UNSUPPORTED, BeaconIssue.BLUETOOTH_OFF).forEach { issue ->
            val f = Fixture(); f.engine.start(); f.radio.startCallback!!(BeaconResult(false, issue)); f.radio.confirmStop()
            assertEquals(BeaconPhase.FAILED, f.engine.state.phase)
            assertEquals(issue, f.engine.state.issue)
            assertFalse(f.states.any { it.phase == BeaconPhase.ACTIVE })
        }
    }

    @Test fun unexpectedControllerInterruptionStopsAndPreservesItsCause() {
        val f = Fixture(); f.engine.start(); f.radio.accept(); f.engine.fail(BeaconIssue.INTERRUPTED)
        f.radio.confirmStop()
        assertEquals(BeaconPhase.FAILED, f.engine.state.phase)
        assertEquals(BeaconIssue.INTERRUPTED, f.engine.state.issue)
        assertEquals(1, f.radio.starts)
    }

    @Test fun controllerCodeAndActualPowerAreMetadataNotReceptionEvidence() {
        val f = Fixture(); f.engine.start(); f.radio.accept(-4)
        assertEquals(-4, f.engine.state.actualTxPowerDbm)
        assertEquals(0L, f.engine.state.acceptedAtMs)
        f.engine.fail(BeaconIssue.CONTROLLER, 3); f.radio.confirmStop()
        assertEquals(3, f.engine.state.platformCode)
    }

    @Test fun stopPermissionFailureDoesNotClaimRadioCleanup() {
        val f = Fixture(); f.engine.start(); f.radio.accept(); f.engine.stop()
        f.radio.stopCallbacks.last()(BeaconResult(false, BeaconIssue.PERMISSION))
        assertEquals(BeaconPhase.STOP_UNCONFIRMED, f.engine.state.phase)
        assertEquals(0, f.cleaned)
        f.engine.stop(); f.radio.confirmStop()
        assertEquals(BeaconPhase.STOPPED, f.engine.state.phase)
    }

    @Test fun synchronousDriverExceptionsRequestCleanup() {
        val clock = FakeClock(); var stops = 0; var cleaned = 0
        val engine = BeaconEngine(identity, object : BeaconRadio {
            override fun start(identity: BeaconIdentity, complete: (BeaconResult) -> Unit) { throw SecurityException() }
            override fun stop(complete: (BeaconResult) -> Unit) { stops++; complete(BeaconResult(true)) }
        }, clock, {}, { cleaned++ })
        engine.start()
        assertEquals(1, stops); assertEquals(1, cleaned)
        assertEquals(BeaconPhase.FAILED, engine.state.phase)
        assertEquals(0, clock.pending())
    }

    @Test fun stopBeforeStartNeverCallsTheRadio() {
        val f = Fixture(); f.engine.stop(); f.engine.start()
        assertEquals(0, f.radio.starts); assertEquals(0, f.radio.stops)
        assertEquals(BeaconPhase.STOPPED, f.engine.state.phase)
    }

    private inner class Fixture {
        val clock = FakeClock()
        val radio = FakeRadio()
        val states = mutableListOf<BeaconState>()
        var cleaned = 0
        val engine = BeaconEngine(identity, radio, clock, { states += it }, { cleaned++ })
    }

    private class FakeRadio : BeaconRadio {
        var starts = 0; var stops = 0
        var startCallback: ((BeaconResult) -> Unit)? = null
        val stopCallbacks = mutableListOf<(BeaconResult) -> Unit>()
        override fun start(identity: BeaconIdentity, complete: (BeaconResult) -> Unit) { starts++; startCallback = complete }
        override fun stop(complete: (BeaconResult) -> Unit) { stops++; stopCallbacks += complete }
        fun accept(power: Int = -7) { startCallback!!(BeaconResult(true, txPowerDbm = power)) }
        fun confirmStop() { stopCallbacks.last()(BeaconResult(true)) }
    }

    private class FakeClock : EngineScheduler {
        private data class Task(val at: Long, val sequence: Long, val run: () -> Unit, var cancelled: Boolean = false)
        private var now = 0L; private var sequence = 0L
        private val queue = PriorityQueue<Task>(compareBy<Task> { it.at }.thenBy { it.sequence })
        override fun nowMs() = now
        override fun schedule(delayMs: Long, task: () -> Unit): Cancellation {
            val entry = Task(now + delayMs, sequence++, task); queue += entry
            return Cancellation { entry.cancelled = true }
        }
        fun pending() = queue.count { !it.cancelled }
        fun advance(duration: Long) {
            val target = now + duration
            while (queue.isNotEmpty() && queue.peek().at <= target) {
                val entry = queue.remove(); now = entry.at
                if (!entry.cancelled) entry.run()
            }
            now = target
        }
    }
}
