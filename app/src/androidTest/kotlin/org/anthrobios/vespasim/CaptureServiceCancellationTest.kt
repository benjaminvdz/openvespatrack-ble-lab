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
import org.anthrobios.vespasim.service.CaptureService
import org.anthrobios.vespasim.service.RadioSessionGate
import org.anthrobios.vespasim.ui.CaptureActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Pending discovery cancellation must happen before permission/hardware checks and radio acquisition. */
@RunWith(AndroidJUnit4::class)
class CaptureServiceCancellationTest {
    @Test fun startThenStopWhileWorkerQueuedNeverChecksHardwareOrChangesRadioOwner() {
        val app = ApplicationProvider.getApplicationContext<VespaApplication>()
        awaitCondition("archive initialization") { app.initializationComplete }
        val beforeCaptures = app.captureRepository.page(1).total
        val initialOwner = RadioSessionGate.owner()
        assertNull("Previous test left the radio gate occupied", initialOwner)
        val testOwner = "qa-pending-discovery:${UUID.randomUUID()}"
        assertTrue(RadioSessionGate.tryAcquire(testOwner))
        try {
            ActivityScenario.launch(CaptureActivity::class.java).use {
                val connected = CountDownLatch(1)
                val reference = AtomicReference<CaptureService?>()
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        reference.set((binder as CaptureService.LocalBinder).service)
                        connected.countDown()
                    }
                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                }
                var bound = false
                var worker: Handler? = null
                val release = CountDownLatch(1)
                val boundaryError = AtomicReference<Throwable?>()
                try {
                    onMain { bound = app.bindService(Intent(app, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE) }
                    assertTrue(bound)
                    await(connected, "capture service binding")
                    val service = requireNotNull(reference.get())
                    val writer = CaptureService::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(service) as Handler
                    worker = writer
                    drain(writer)
                    assertNull(service.snapshot().error)
                    val entered = CountDownLatch(1)
                    check(writer.post {
                        entered.countDown()
                        try { check(release.await(15, TimeUnit.SECONDS)) { "Test did not release discovery boundary" } }
                        catch (failure: Throwable) { boundaryError.set(failure) }
                    })
                    await(entered, "capture worker entered pending boundary")
                    onMain {
                        service.startDiscovery()
                        service.stopDiscovery()
                    }
                    release.countDown()
                    drain(writer)
                    boundaryError.get()?.let { throw AssertionError("Discovery boundary failed", it) }
                    val state = service.snapshot()
                    assertNull("Cancelled queued discovery must not reach scan preflight", state.error)
                    assertFalse(state.discovering)
                    assertFalse(state.recording)
                    assertFalse(state.finishing)
                    assertEquals(testOwner, RadioSessionGate.owner())
                    assertEquals(beforeCaptures, app.captureRepository.page(1).total)
                    // The forced competing owner prevents any real scan even if this regression returns.
                    // On hardware with scan permissions the old code fails at the busy gate; on an
                    // emulator it fails preflight. Both incorrectly leave an error after cancellation.
                } finally {
                    release.countDown()
                    reference.get()?.let { service -> onMain { service.stopDiscovery() } }
                    worker?.let(::drain)
                    if (bound) onMain { app.unbindService(connection) }
                }
            }
        } finally {
            RadioSessionGate.release(testOwner)
            assertEquals(initialOwner, RadioSessionGate.owner())
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun await(latch: CountDownLatch, label: String) = assertTrue("Timeout: $label", latch.await(15, TimeUnit.SECONDS))
    private fun drain(worker: Handler) {
        repeat(2) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val reached = CountDownLatch(1)
            check(worker.post { reached.countDown() })
            await(reached, "capture worker queue barrier")
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
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
