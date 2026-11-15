package org.anthrobios.vespasim

import android.text.Editable
import android.text.TextWatcher
import android.content.Context
import android.os.Handler
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.anthrobios.vespasim.ui.BeaconActivity
import org.anthrobios.vespasim.service.BeaconService
import org.anthrobios.vespasim.service.BeaconSessionStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File

/** Opening/recreating the real screen must not require permission or start Bluetooth. */
@RunWith(AndroidJUnit4::class)
class BeaconActivityTest {
    @Test fun openingAndRecreatingKeepsTheSameSyntheticIdentityWithoutStarting() {
        ActivityScenario.launch(BeaconActivity::class.java).use { scenario ->
            val first = awaitIdentity(scenario)
            assertTrue(first.matches(Regex("OVT-[0-9A-F]{16}")))
            assertIdle(scenario)
            scenario.recreate()
            assertEquals(first, awaitIdentity(scenario))
            assertIdle(scenario)
        }
    }

    @Test fun persistedInterruptionIsShownOnServiceRecreationWithoutAutomaticallyStarting() {
        // Drain an actual bound-only service before setting up the persisted process-loss state.
        lateinit var previousWorker: Thread
        ActivityScenario.launch(BeaconActivity::class.java).use { scenario ->
            awaitIdentity(scenario)
            assertIdle(scenario)
            previousWorker = serviceWorker(scenario)
        }
        previousWorker.join(15_000)
        assertFalse("Previous service fully destroyed", previousWorker.isAlive)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.noBackupFilesDir, BeaconSessionStore.FILE_NAME)
        val saved = path.takeIf { it.exists() }?.readBytes()
        val store = BeaconSessionStore(path)
        lateinit var recreatedWorker: Thread
        try {
            store.markRunning()
            ActivityScenario.launch(BeaconActivity::class.java).use { scenario ->
                awaitIdentity(scenario)
                recreatedWorker = serviceWorker(scenario)
                scenario.onActivity { activity ->
                    val content = activity.findViewById<ViewGroup>(android.R.id.content)
                    assertEquals(activity.getString(R.string.beacon_error_interrupted),
                        content.findViewWithTag<TextView>("beacon_status").text.toString())
                    assertTrue("A fresh explicit start is required", content.findViewWithTag<Button>("beacon_start").isEnabled)
                    assertFalse("No new radio session was started", content.findViewWithTag<Button>("beacon_stop").isEnabled)
                }
                assertTrue("Merely reopening does not clear the unfinished session marker", store.wasRunning())
            }
            recreatedWorker.join(15_000)
            assertFalse("Recreated service fully destroyed", recreatedWorker.isAlive)
        } finally {
            if (saved == null) store.clearAfterConfirmedStop() else path.writeBytes(saved)
        }
    }

    private fun serviceWorker(scenario: ActivityScenario<BeaconActivity>): Thread {
        lateinit var thread: Thread
        scenario.onActivity { activity ->
            val service = BeaconActivity::class.java.getDeclaredField("service").apply { isAccessible = true }.get(activity) as BeaconService
            thread = (BeaconService::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(service) as Handler).looper.thread
        }
        return thread
    }

    private fun assertIdle(scenario: ActivityScenario<BeaconActivity>) {
        scenario.onActivity { activity ->
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            assertTrue(content.findViewWithTag<Button>("beacon_start").isEnabled)
            assertFalse(content.findViewWithTag<Button>("beacon_stop").isEnabled)
            assertEquals(activity.getString(R.string.beacon_ready), content.findViewWithTag<TextView>("beacon_status").text.toString())
            assertEquals(org.anthrobios.vespasim.ui.Appearance.colors(activity).text,
                content.findViewWithTag<TextView>("beacon_identity").currentTextColor)
        }
    }

    private fun awaitIdentity(scenario: ActivityScenario<BeaconActivity>): String {
        val ready = CountDownLatch(1)
        var value = ""
        scenario.onActivity { activity ->
            val label = activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<TextView>("beacon_identity")
            fun inspect() {
                val text = label.text.toString()
                if (text.startsWith("OVT-")) { value = text; ready.countDown() }
            }
            label.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { inspect() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            inspect()
        }
        assertTrue("Identity loaded without starting a beacon", ready.await(15, TimeUnit.SECONDS))
        return value
    }
}
