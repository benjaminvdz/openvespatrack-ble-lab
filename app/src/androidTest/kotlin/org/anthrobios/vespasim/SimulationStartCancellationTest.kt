package org.anthrobios.vespasim

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.anthrobios.vespasim.core.RunMode
import org.anthrobios.vespasim.core.RunStatus
import org.anthrobios.vespasim.core.SimulationProfile
import org.anthrobios.vespasim.core.TagTrack
import org.anthrobios.vespasim.core.storage.LabRepository
import org.anthrobios.vespasim.service.LiveSnapshot
import org.anthrobios.vespasim.service.SimulationService
import org.anthrobios.vespasim.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Real bound service, no Bluetooth permission or radio invocation. Race boundaries use latches/locks. */
@RunWith(AndroidJUnit4::class)
class SimulationStartCancellationTest {
    @Test fun startThenStopBeforeWorkerEncodingCreatesNoRunAndCannotAutoStartLater() {
        withBoundService { app, service, worker ->
            val before = app.repository.runs().map { it.id }.toSet()
            val profile = profile("before-encode")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val workerError = AtomicReference<Throwable?>()
            val cancelled = CountDownLatch(1)
            val previousSnapshot = service.snapshot()
            val listener: (LiveSnapshot) -> Unit = {
                if (it !== previousSnapshot && !it.running && !it.preparingStart && it.message.contains("Startaanvraag geannuleerd")) cancelled.countDown()
            }
            service.addListener(listener)
            try {
                check(worker.post {
                    entered.countDown()
                    try { check(release.await(15, TimeUnit.SECONDS)) { "Test did not release encoder boundary" } }
                    catch (failure: Throwable) { workerError.set(failure) }
                })
                await(entered, "worker entered pre-encode boundary")
                onMain {
                    service.startRun(profile, RunMode.DRY_RUN)
                    service.stopRun() // Same main turn: Stop must invalidate this exact queued request.
                }
                release.countDown()
                await(cancelled, "real ACTION_STOP completion")
                drain(worker)
                workerError.get()?.let { throw AssertionError("Worker boundary failed", it) }
                assertFalse(service.snapshot().running)
                assertFalse(service.snapshot().preparingStart)
                assertEquals(before, app.repository.runs().map { it.id }.toSet())
            } finally {
                release.countDown()
                service.removeListener(listener)
            }
        }
    }

    @Test fun stopWhileDurableCreateWaitsPreservesStoppedEvidenceWithoutStartingEngine() {
        withBoundService { app, service, worker ->
            val before = app.repository.runs().map { it.id }.toSet()
            val profile = profile("during-create")
            val mutex = LabRepository::class.java.getDeclaredField("mutex").apply { isAccessible = true }.get(app.repository) as ReentrantLock
            val terminal = CountDownLatch(1)
            val listener: (LiveSnapshot) -> Unit = {
                if (it.run?.profile?.id == profile.id && !it.running && it.run?.status == RunStatus.STOPPED) terminal.countDown()
            }
            service.addListener(listener)
            try {
                mutex.lock()
                try {
                    onMain { service.startRun(profile, RunMode.DRY_RUN) }
                    // The lock stays held until we observe the actual service thread queued at it.
                    // Polling observes a stable lock state; it does not select the race by elapsed sleep.
                    awaitCondition("service blocked at durable create") { mutex.hasQueuedThread(worker.looper.thread) }
                    assertTrue(worker.looper.thread.stackTrace.any { it.className == LabRepository::class.java.name && it.methodName == "createRun" })
                    onMain { service.stopRun() }
                } finally { mutex.unlock() }
                await(terminal, "cancelled durable run finalized")
                drain(worker)
                val created = app.repository.runs().filter { it.id !in before }
                assertEquals(1, created.size)
                val run = created.single()
                assertEquals(profile, run.profile)
                assertEquals(RunStatus.STOPPED, run.status)
                assertNotNull(run.endedAtMs)
                val summary = app.repository.summary(run.id)
                assertEquals(0L, summary.simulatedFrames)
                assertEquals(0L, summary.controllerAccepted)
                assertTrue(app.repository.events(run.id).none { it.kind in setOf("run_started", "simulated_frame", "radio_request", "controller_accepted") })
                assertFalse(service.snapshot().running)
                assertFalse(service.snapshot().preparingStart)
            } finally {
                service.removeListener(listener)
            }
        }
    }

    private fun profile(label: String) = SimulationProfile(
        id = "cancel-${UUID.randomUUID()}", name = "Cancellation $label", durationMs = 120_000,
        slotMs = 250, tracks = listOf(TagTrack("test-tag")),
    )

    private fun withBoundService(test: (VespaApplication, SimulationService, Handler) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<VespaApplication>()
        awaitCondition("archive initialization") { app.initializationComplete }
        ActivityScenario.launch(MainActivity::class.java).use {
            val connected = CountDownLatch(1)
            val reference = AtomicReference<SimulationService?>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    reference.set((binder as SimulationService.LocalBinder).service)
                    connected.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
            var bound = false
            try {
                onMain { bound = app.bindService(Intent(app, SimulationService::class.java), connection, Context.BIND_AUTO_CREATE) }
                assertTrue(bound)
                await(connected, "service binding")
                val service = requireNotNull(reference.get())
                val worker = SimulationService::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(service) as Handler
                drain(worker)
                assertFalse("Previous test left an active scenario", service.snapshot().running)
                assertFalse("Previous test left a pending start", service.snapshot().preparingStart)
                test(app, service, worker)
            } finally {
                reference.get()?.let { service ->
                    val beforeCleanup = service.snapshot()
                    val stopped = CountDownLatch(1)
                    val listener: (LiveSnapshot) -> Unit = {
                        if (it !== beforeCleanup && !it.running && !it.preparingStart) stopped.countDown()
                    }
                    service.addListener(listener)
                    try {
                        onMain { service.stopRun() }
                        await(stopped, "cleanup ACTION_STOP completion")
                        val worker = SimulationService::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(service) as Handler
                        drain(worker)
                    } finally { service.removeListener(listener) }
                }
                if (bound) onMain { app.unbindService(connection) }
            }
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /** Drain both queues twice; worker results can enqueue main callbacks that enqueue more worker work. */
    private fun drain(worker: Handler) {
        repeat(2) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val reached = CountDownLatch(1)
            check(worker.post { reached.countDown() }) { "Service worker unexpectedly stopped" }
            await(reached, "service worker queue barrier")
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun await(latch: CountDownLatch, label: String) {
        assertTrue("Timeout: $label", latch.await(15, TimeUnit.SECONDS))
    }

    private fun awaitCondition(label: String, condition: () -> Boolean) {
        val reached = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val observer = Executors.newSingleThreadScheduledExecutor()
        try {
            observer.scheduleAtFixedRate({
                try { if (condition()) reached.countDown() }
                catch (failure: Throwable) { error.compareAndSet(null, failure); reached.countDown() }
            }, 0, 5, TimeUnit.MILLISECONDS)
            await(reached, label)
            error.get()?.let { throw AssertionError(label, it) }
        } finally { observer.shutdownNow() }
    }
}
