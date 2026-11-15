package org.anthrobios.vespasim.service

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import kotlin.test.*

class CaptureLifecycleTest {
    @Test fun stopClosesAdmissionButDrainsEveryAcceptedCallbackInOrder() {
        val queue = CaptureInbox<Int>(4)
        assertEquals(CaptureInbox.Offer.ACCEPTED, queue.offer(12))
        assertEquals(CaptureInbox.Offer.ACCEPTED, queue.offer(13))
        queue.close()
        assertEquals(CaptureInbox.Offer.CLOSED, queue.offer(14))
        assertEquals(12, queue.poll())
        assertEquals(13, queue.poll())
        assertNull(queue.poll())
        assertEquals(0, queue.overflowCount)
    }

    @Test fun overflowingQueueClosesOnceAndNeverEvictsEarlierEvidence() {
        val queue = CaptureInbox<Int>(2)
        queue.offer(1); queue.offer(2)
        assertEquals(CaptureInbox.Offer.FULL, queue.offer(3))
        assertEquals(CaptureInbox.Offer.CLOSED, queue.offer(4))
        assertEquals(1, queue.overflowCount)
        assertEquals(listOf(1, 2), generateSequence(queue::poll).toList())
        assertFalse(queue.isOpen())
    }

    @Test fun observationLimitAcceptsTheLastRecordWithoutDiscardingIt() {
        val queue = CaptureInbox<Int>(2, maximumAccepted = 3)
        queue.offer(1); assertEquals(1, queue.poll())
        queue.offer(2); assertEquals(2, queue.poll())
        assertEquals(CaptureInbox.Offer.ACCEPTED_LIMIT, queue.offer(3))
        assertEquals(CaptureInbox.Offer.CLOSED, queue.offer(4))
        assertEquals(3, queue.poll())
        assertEquals(0, queue.overflowCount)
    }

    @Test fun callbacksHoldingAnOldSessionCannotWriteIntoANewSession() {
        val previous = CaptureInbox<String>(2)
        previous.offer("accepted before stop")
        previous.close()
        val current = CaptureInbox<String>(2)
        assertEquals(CaptureInbox.Offer.CLOSED, previous.offer("late old callback"))
        current.offer("new callback")
        assertEquals("accepted before stop", previous.poll())
        assertNull(previous.poll())
        assertEquals("new callback", current.poll())
        assertNull(current.poll())
    }

    @Test fun concurrentProducersAndStopDrainExactlyTheAcceptedSet() {
        val queue = CaptureInbox<Int>(2_000)
        val accepted = Collections.synchronizedSet(mutableSetOf<Int>())
        val workers = Executors.newFixedThreadPool(5)
        val ready = CountDownLatch(5)
        val go = CountDownLatch(1)
        try {
            val jobs = (0..3).map { producer -> workers.submit {
                ready.countDown(); go.await()
                repeat(250) { index ->
                    val value = producer * 250 + index
                    if (queue.offer(value) == CaptureInbox.Offer.ACCEPTED) accepted.add(value)
                }
            } }
            val stop = workers.submit { ready.countDown(); go.await(); queue.close() }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }; stop.get(5, TimeUnit.SECONDS)
            val persisted = generateSequence(queue::poll).toList()
            assertEquals(accepted, persisted.toSet())
            assertEquals(accepted.size, persisted.size)
            assertEquals(CaptureInbox.Offer.CLOSED, queue.offer(1_001))
        } finally { workers.shutdownNow() }
    }

    @Test fun actualCallbackTimeDefinesFreshnessIncludingBoundariesAndClockAnomalies() {
        assertTrue(CaptureSelection.isFresh(1_000, 11_000))
        assertFalse(CaptureSelection.isFresh(1_000, 11_001))
        assertFalse(CaptureSelection.isFresh(1_000, 999))
        assertFalse(CaptureSelection.isFresh(-1, 999))
        assertFalse(CaptureSelection.isFresh(0, Long.MAX_VALUE))
    }

    @Test fun radioGateIsIdempotentOnlyForItsOwnerAndWrongReleaseCannotUnlockIt() {
        val owner = "capture-test"
        assertTrue(RadioSessionGate.tryAcquire(owner))
        try {
            assertTrue(RadioSessionGate.tryAcquire(owner))
            assertTrue(RadioSessionGate.isBusyFor("transmitter"))
            assertFalse(RadioSessionGate.tryAcquire("transmitter"))
            RadioSessionGate.release("transmitter")
            assertEquals(owner, RadioSessionGate.owner())
        } finally { RadioSessionGate.release(owner) }
        assertNull(RadioSessionGate.owner())
    }

    @Test fun concurrentScanAndTransmitCannotBothAcquireRadio() {
        val workers = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val attempted = CountDownLatch(2)
        val acquired = AtomicInteger()
        try {
            val tasks = listOf("capture-race", "transmit-race").map { owner -> workers.submit {
                ready.countDown(); go.await()
                val won = RadioSessionGate.tryAcquire(owner)
                if (won) acquired.incrementAndGet()
                attempted.countDown()
                assertTrue(attempted.await(5, TimeUnit.SECONDS))
                if (won) RadioSessionGate.release(owner)
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, acquired.get())
            assertNull(RadioSessionGate.owner())
        } finally {
            workers.shutdownNow()
            RadioSessionGate.release("capture-race"); RadioSessionGate.release("transmit-race")
        }
    }

    @Test fun uncertainScannerStopSurvivesServiceRecreationUntilExplicitSuccessfulRetry() {
        val previousOwner = "old-capture-service"
        var attempts = 0
        assertTrue(RadioSessionGate.tryAcquire(previousOwner))
        CaptureCleanupRecovery.remember(previousOwner, { attempts++; if (attempts == 1) "permission revoked" else null }, "permission revoked")
        assertFalse(RadioSessionGate.tryAcquire("new-capture-service"))
        assertEquals("permission revoked", CaptureCleanupRecovery.retry())
        assertEquals(previousOwner, RadioSessionGate.owner())
        assertEquals(1, attempts)
        assertNull(CaptureCleanupRecovery.retry())
        assertNull(CaptureCleanupRecovery.problem())
        assertNull(RadioSessionGate.owner())
        assertEquals(2, attempts)
        assertTrue(RadioSessionGate.tryAcquire("new-capture-service"))
        RadioSessionGate.release("new-capture-service")
    }
}
