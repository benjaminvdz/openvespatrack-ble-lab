package org.anthrobios.vespasim

import android.content.Context
import android.os.SystemClock
import android.os.Handler
import androidx.test.platform.app.InstrumentationRegistry
import org.anthrobios.vespasim.service.SimulationService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import android.view.ViewGroup
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.anthrobios.vespasim.core.RunMode
import org.anthrobios.vespasim.core.RunStatus
import org.anthrobios.vespasim.core.SimulationProfile
import org.anthrobios.vespasim.core.TagTrack
import org.anthrobios.vespasim.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses the real Activity/service lifecycle, without an extra test binding masking onUnbind. */
@RunWith(AndroidJUnit4::class)
class ActivityLifecycleTest {
    @Test
    fun repeatedPendingStartClicksThenImmediateBackgroundCancelBeforeCreatingRun() {
        val application = ApplicationProvider.getApplicationContext<VespaApplication>()
        waitUntil("Archive initialization before lifecycle setup") { application.initializationComplete }
        val repository = application.repository
        val profileId = "qa-start-background-${UUID.randomUUID()}"
        repository.saveProfile(SimulationProfile(
            id = profileId, name = "QA immediate background", durationMs = 120_000,
            slotMs = 250, tracks = listOf(TagTrack(id = "qa-tag")),
        ))
        val preferences = application.getSharedPreferences("ui", Context.MODE_PRIVATE)
        val previousSelection = preferences.getString("profile", null)
        assertTrue(preferences.edit().putString("profile", profileId).commit())
        val previousRunIds = repository.runs().map { it.id }.toSet()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitUntil("Service ready for immediate-background test") {
                    var ready = false
                    scenario.onActivity { activity ->
                        ready = activity.findViewById<ViewGroup>(android.R.id.content)
                            .findViewWithTag<Button>("start_run")?.isEnabled == true
                    }
                    ready
                }
                lateinit var service: SimulationService
                scenario.onActivity { activity ->
                    service = MainActivity::class.java.getDeclaredField("service").apply { isAccessible = true }.get(activity) as SimulationService
                }
                val worker = SimulationService::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(service) as Handler
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val boundaryError = AtomicReference<Throwable?>()
                check(worker.post {
                    entered.countDown()
                    try { check(release.await(15, TimeUnit.SECONDS)) { "Test did not release pending-start boundary" } }
                    catch (failure: Throwable) { boundaryError.set(failure) }
                })
                assertTrue(entered.await(15, TimeUnit.SECONDS))
                try {
                    scenario.onActivity { activity ->
                        val start = requireNotNull(activity.findViewById<ViewGroup>(android.R.id.content)
                            .findViewWithTag<Button>("start_run"))
                        assertTrue(start.performClick())
                        assertTrue(start.performClick())
                    }
                    scenario.moveToState(Lifecycle.State.CREATED)
                    val bound = SimulationService::class.java.getDeclaredField("boundClient").apply { isAccessible = true }
                    val unbound = CountDownLatch(1)
                    val observer = Executors.newSingleThreadScheduledExecutor()
                    try {
                        observer.scheduleAtFixedRate({ if (!bound.getBoolean(service)) unbound.countDown() }, 0, 5, TimeUnit.MILLISECONDS)
                        assertTrue("Real Activity onUnbind cancelled pending request", unbound.await(15, TimeUnit.SECONDS))
                    } finally { observer.shutdownNow() }
                } finally { release.countDown() }
                // No extra test binding masks onUnbind. If Android destroyed this bound-only service,
                // a joined worker is equivalent to a queue barrier; otherwise drain queued callbacks.
                repeat(2) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    val drained = CountDownLatch(1)
                    if (worker.post { drained.countDown() }) assertTrue(drained.await(15, TimeUnit.SECONDS))
                    else { worker.looper.thread.join(15_000); assertFalse(worker.looper.thread.isAlive) }
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                boundaryError.get()?.let { throw AssertionError("Worker boundary failed", it) }
                assertFalse(service.snapshot().running)
                val queued = SimulationService::class.java.getDeclaredField("requestQueued").apply { isAccessible = true }
                assertFalse(queued.getBoolean(service))
                assertEquals("Cancelled pending requests must never create a run later", previousRunIds, repository.runs().map { it.id }.toSet())

            }
        } finally {
            val editor = preferences.edit()
            if (previousSelection == null) editor.remove("profile") else editor.putString("profile", previousSelection)
            editor.commit()
        }
    }

    @Test
    fun dryRunSurvivesRecreationAndStopsWhenAppIsNoLongerVisible() {
        val application = ApplicationProvider.getApplicationContext<VespaApplication>()
        waitUntil("Archive initialization before lifecycle setup") { application.initializationComplete }
        val repository = application.repository
        val profileId = "qa-lifecycle-${UUID.randomUUID()}"
        val profile = SimulationProfile(
            id = profileId,
            name = "QA lifecycle dry-run",
            durationMs = 120_000,
            slotMs = 250,
            tracks = listOf(TagTrack(id = "qa-tag")),
        )
        repository.saveProfile(profile)
        val preferences = application.getSharedPreferences("ui", Context.MODE_PRIVATE)
        val previousSelection = preferences.getString("profile", null)
        assertTrue(preferences.edit().putString("profile", profileId).commit())
        val previousRunIds = repository.runs().map { it.id }.toSet()
        var selectedRunId: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitUntil("Start button enabled after service binding") {
                    var enabled = false
                    scenario.onActivity { activity ->
                        enabled = activity.findViewById<ViewGroup>(android.R.id.content)
                            .findViewWithTag<Button>("start_run")?.isEnabled == true
                    }
                    enabled
                }
                scenario.onActivity { activity ->
                    val start = requireNotNull(activity.findViewById<ViewGroup>(android.R.id.content)
                        .findViewWithTag<Button>("start_run")) { "Stable Start button tag" }
                    assertTrue(start.performClick())
                }
                waitUntil("One new running dry-run") {
                    val newRuns = repository.runs().filter { it.id !in previousRunIds }
                    require(newRuns.size <= 1) { "One click started more than one run" }
                    val running = newRuns.singleOrNull()
                    if (running?.status == RunStatus.RUNNING) {
                        selectedRunId = running.id
                        true
                    } else false
                }
                val runId = requireNotNull(selectedRunId)
                val original = requireNotNull(repository.run(runId))
                assertEquals(RunMode.DRY_RUN, original.mode)
                assertEquals(profileId, original.profile.id)
                waitUntil("First simulated frame durably logged") { repository.summary(runId).simulatedFrames > 0 }
                val eventCountBeforeRotation = repository.summary(runId).eventCount

                scenario.recreate()
                waitUntil("Same run continues after Activity recreation") {
                    repository.run(runId)?.status == RunStatus.RUNNING &&
                        repository.summary(runId).eventCount > eventCountBeforeRotation
                }
                assertEquals("Recreation must not create a second run", 1,
                    repository.runs().count { it.id !in previousRunIds })

                // No extra test client is bound. This really exercises the service's background lease.
                scenario.moveToState(Lifecycle.State.CREATED)
                waitUntil("Foreground-only dry-run stops after Activity leaves foreground") {
                    repository.run(runId)?.status == RunStatus.STOPPED
                }
                val finished = requireNotNull(repository.run(runId))
                assertNotNull(finished.endedAtMs)
                assertTrue(repository.summary(runId).simulatedFrames > 0)
                assertEquals(0L, repository.summary(runId).controllerAccepted)

                scenario.moveToState(Lifecycle.State.RESUMED)
                waitUntil("Start becomes available after return") {
                    var enabled = false
                    scenario.onActivity { activity ->
                        enabled = activity.findViewById<ViewGroup>(android.R.id.content)
                            .findViewWithTag<Button>("start_run")?.isEnabled == true
                    }
                    enabled
                }
                assertEquals("Returning must not automatically restart the scenario", 1,
                    repository.runs().count { it.id !in previousRunIds })
                assertEquals(RunStatus.STOPPED, repository.run(runId)?.status)
            }
        } finally {
            val editor = preferences.edit()
            if (previousSelection == null) editor.remove("profile") else editor.putString("profile", previousSelection)
            editor.commit()
        }
    }

    private fun waitUntil(label: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(40)
        }
        fail("Timed out: $label")
    }
}
