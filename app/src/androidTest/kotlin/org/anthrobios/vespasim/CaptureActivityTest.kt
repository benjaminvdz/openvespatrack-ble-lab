package org.anthrobios.vespasim

import android.content.Intent
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.anthrobios.vespasim.core.RunStatus
import org.anthrobios.vespasim.core.capture.CaptureEvent
import org.anthrobios.vespasim.core.capture.CaptureObservation
import org.anthrobios.vespasim.core.capture.CaptureRecord
import org.anthrobios.vespasim.core.capture.CaptureReplay
import org.anthrobios.vespasim.ui.CaptureActivity
import org.anthrobios.vespasim.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Native lifecycle and durable synthetic evidence. No Bluetooth radio or permission grant needed. */
@RunWith(AndroidJUnit4::class)
class CaptureActivityTest {
    private val app get() = ApplicationProvider.getApplicationContext<VespaApplication>()
    @Test fun entryDefaultsToNanoAndRecreationNeverScansOrStarts() {
        ready()
        val before = app.captureRepository.page(200).total
        ActivityScenario.launch(CaptureActivity::class.java).use { scenario ->
            waitUntil("Capture picker ready") { exists(scenario, "capture_filter") }
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                assertEquals(0, root.findViewWithTag<Spinner>("capture_filter").selectedItemPosition)
                assertFalse(root.findViewWithTag<Button>("capture_start").isEnabled)
                assertEquals(activity.getString(R.string.capture_find_devices), root.findViewWithTag<Button>("capture_scan").text.toString())
            }
            scenario.recreate()
            waitUntil("Idle picker after rotation") { exists(scenario, "capture_start") }
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<Button>("capture_start").isEnabled)
            }
            assertEquals(before, app.captureRepository.page(200).total)
        }
    }
    @Test fun savedArchivePagesOriginalBytesAndOfflineReplayRemainRadioFreeAcrossRotation() {
        ready()
        val record = fixture(105)
        val beforeRuns = app.repository.runs().map { it.id }.toSet()
        try {
            ActivityScenario.launch<CaptureActivity>(Intent(app, CaptureActivity::class.java).putExtra("captureId", record.id)).use { scenario ->
                waitUntil("Original first page") { selectedSample(scenario, 1) }
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    assertEquals(100, root.findViewWithTag<Spinner>("capture_sample").count)
                    assertTrue(root.findViewWithTag<Button>("capture_offline_play").performClick())
                    assertTrue(root.findViewWithTag<Button>("capture_samples_next").performClick())
                }
                waitUntil("Second page starts with exact record 101") { selectedSample(scenario, 101) }
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    assertEquals(5, root.findViewWithTag<Spinner>("capture_sample").count)
                    assertFalse(root.findViewWithTag<Button>("capture_samples_next").isEnabled)
                    assertTrue(root.findViewWithTag<TextView>("capture_original_bytes").text.contains(RAW))
                }
                scenario.recreate()
                waitUntil("Page identity survives rotation") { selectedSample(scenario, 101) }
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    assertEquals(activity.getString(R.string.capture_play), root.findViewWithTag<Button>("capture_offline_play").text.toString())
                    assertTrue(root.findViewWithTag<Button>("capture_samples_previous").performClick())
                }
                waitUntil("Back to first evidence page") { selectedSample(scenario, 1) }
                assertEquals(beforeRuns, app.repository.runs().map { it.id }.toSet())
                assertEquals(105L, app.captureRepository.count(record.id))
                assertEquals(RunStatus.STOPPED, app.captureRepository.get(record.id)?.status)
            }
        } finally { remove(record.id) }
    }
    @Test fun trashAndRestoreKeepOriginalMeasurements() {
        ready(); val record = fixture(3)
        try {
            ActivityScenario.launch<CaptureActivity>(Intent(app, CaptureActivity::class.java).putExtra("captureId", record.id)).use { scenario ->
                waitUntil("Archive detail ready") { exists(scenario, "capture_trash") }
                click(scenario, "capture_trash")
                waitUntil("Trash persisted") { app.captureRepository.get(record.id) == null && app.captureRepository.get(record.id, true) != null }
                waitUntil("Archive reloaded") { !exists(scenario, "capture_trash") }
                scenario.onActivity { activity -> activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<CheckBox>("capture_trash_toggle").isChecked = true }
                waitUntil("Trashed row visible") { exists(scenario, "capture_record_${record.id}") }
                click(scenario, "capture_record_${record.id}")
                waitUntil("Restore action ready") { exists(scenario, "capture_restore") }
                click(scenario, "capture_restore")
                waitUntil("Restore persisted") { app.captureRepository.get(record.id) != null && app.captureRepository.get(record.id, true) == null }
                assertEquals(3L, app.captureRepository.count(record.id))
                assertEquals(RAW, app.captureRepository.observations(record.id).first().rawHex)
            }
        } finally { remove(record.id) }
    }
    @Test fun replayIntentOpensDryRunWithoutStartingAndSurvivesRotation() {
        ready(); val record = fixture(2)
        val profileId = "qa-capture-replay-${UUID.randomUUID()}"
        val profile = CaptureReplay.staticProfile(requireNotNull(app.captureRepository.get(record.id)), app.captureRepository.observations(record.id).first(), profileId, "QA replay handoff")
        app.repository.saveProfile(profile)
        val before = app.repository.runs().map { it.id }.toSet()
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).putExtra("captureReplayProfileId", profileId)).use { scenario ->
                waitUntil("Replay profile selected in dry-run") {
                    var selected = false
                    scenario.onActivity { activity ->
                        val root = activity.findViewById<ViewGroup>(android.R.id.content)
                        selected = root.findViewWithTag<RadioButton>("mode_dry")?.isChecked == true &&
                            app.getSharedPreferences("ui", 0).getString("profile", null) == profileId &&
                            root.findViewWithTag<Button>("start_run")?.isEnabled == true
                    }; selected
                }
                scenario.recreate()
                waitUntil("Rotated handoff remains dry-run") {
                    var dry = false
                    scenario.onActivity { activity -> dry = activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<RadioButton>("mode_dry")?.isChecked == true }
                    dry
                }
                scenario.onActivity { activity -> assertNotNull(activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<Button>("open_capture")) }
                assertEquals(before, app.repository.runs().map { it.id }.toSet())
            }
        } finally { remove(record.id) }
    }
    private fun ready() = waitUntil("Application archive initialization") { app.initializationComplete }
    private fun fixture(count: Int): CaptureRecord {
        val start = 1_700_000_000_000L
        val record = CaptureRecord("qa-capture-${UUID.randomUUID()}", "QA synthetische opname", "01:02:03:04:05:06", start, 1_000_000_000L, tagVariant = "android")
        app.captureRepository.create(record)
        repeat(count) { i -> app.captureRepository.appendObservation(record.id, CaptureObservation(i + 1L, start + i * 100, 1_000_000_000L + i * 100_000_000L,
            record.address, if (i == 3) 127 else -65, RAW, deviceName = "QA cache")) }
        app.captureRepository.appendEvent(record.id, CaptureEvent(1, start, "capture_started", "Synthetische instrumentatietest"))
        app.captureRepository.finish(record.id, RunStatus.STOPPED, start + count * 100)
        return requireNotNull(app.captureRepository.get(record.id))
    }
    private fun remove(id: String) {
        if (app.captureRepository.get(id) != null) app.captureRepository.trash(id)
        if (app.captureRepository.get(id, true) != null) app.captureRepository.purge(id)
    }
    private fun exists(scenario: ActivityScenario<CaptureActivity>, tag: String): Boolean {
        var found = false
        scenario.onActivity { found = it.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<android.view.View>(tag) != null }
        return found
    }
    private fun click(scenario: ActivityScenario<CaptureActivity>, tag: String) = scenario.onActivity {
        assertTrue(requireNotNull(it.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<Button>(tag)).performClick())
    }
    private fun selectedSample(scenario: ActivityScenario<CaptureActivity>, sequence: Long): Boolean {
        var result = false
        scenario.onActivity {
            val text = it.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<TextView>("capture_original_bytes")?.text?.toString().orEmpty()
            result = text.startsWith(it.getString(R.string.capture_original_record_heading, sequence) + "\n")
        }
        return result
    }
    private fun waitUntil(label: String, timeout: Long = 25_000, test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) { if (test()) return; SystemClock.sleep(40) }
        fail("Timed out: $label")
    }
    companion object { private const val RAW = "02010609ff65fc003412010000" }
}

