package org.anthrobios.vespasim

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.anthrobios.vespasim.ui.Appearance
import org.anthrobios.vespasim.ui.AppearanceActivity
import org.anthrobios.vespasim.ui.BeaconActivity
import org.anthrobios.vespasim.ui.CaptureActivity
import org.anthrobios.vespasim.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real screenshots for release documentation, with no scan or advertising start action.
 * Run on the documentation emulator (Pixel 2, 1080 × 1920). Pull the PNG files from
 * targetContext.getExternalFilesDir(null)/screenshots after this test completes.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotCaptureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun captureIdleReleaseScreens() {
        val preferences = context.getSharedPreferences(Appearance.PREFERENCES, Context.MODE_PRIVATE)
        val previousTheme = preferences.getString(Appearance.THEME_KEY, null)
        val previousLanguage = preferences.getString(Appearance.LANGUAGE_KEY, null)
        try {
            waitUntil("Application initialization") {
                (context.applicationContext as VespaApplication).initializationComplete
            }
            captureBeacon("dark", "en", "beacon-dark.png")
            captureBeacon("light", "nl", "beacon-light.png")

            Appearance.setPreferences(context, "light", "en")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitUntil("Loaded workspace profile") {
                    var ready = false
                    scenario.onActivity { activity ->
                        val root = activity.findViewById<ViewGroup>(android.R.id.content)
                        ready = root.findViewWithTag<Button>("start_run")?.isEnabled == true
                    }
                    ready
                }
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    assertFalse(root.findViewWithTag<Button>("stop_run").isEnabled)
                    assertTrue(root.findViewWithTag<Button>("open_beacon").isEnabled)
                }
                screenshot("workspace-light.png")
            }

            Appearance.setPreferences(context, "dark", "en")
            ActivityScenario.launch(CaptureActivity::class.java).use { scenario ->
                waitUntil("Idle capture picker") { hasView(scenario, "capture_start") }
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    assertFalse(root.findViewWithTag<Button>("capture_start").isEnabled)
                    assertEquals(activity.getString(R.string.capture_find_devices),
                        root.findViewWithTag<Button>("capture_scan").text.toString())
                }
                screenshot("capture-dark.png")
            }

            Appearance.setPreferences(context, "light", "fr")
            ActivityScenario.launch(AppearanceActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals("fr", activity.resources.configuration.locales[0].language)
                    val picker = activity.findViewById<ViewGroup>(android.R.id.content)
                        .findViewWithTag<Spinner>("appearance_language")
                    assertEquals(4, picker.count)
                    assertTrue(picker.performClick())
                }
                screenshot("settings-language.png")
            }
        } finally {
            val editor = preferences.edit()
            if (previousTheme == null) editor.remove(Appearance.THEME_KEY)
                else editor.putString(Appearance.THEME_KEY, previousTheme)
            if (previousLanguage == null) editor.remove(Appearance.LANGUAGE_KEY)
                else editor.putString(Appearance.LANGUAGE_KEY, previousLanguage)
            assertTrue("Restore appearance preferences", editor.commit())
        }
    }

    private fun captureBeacon(theme: String, language: String, filename: String) {
        Appearance.setPreferences(context, theme, language)
        ActivityScenario.launch(BeaconActivity::class.java).use { scenario ->
            waitUntil("Idle beacon identity") {
                var ready = false
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    ready = root.findViewWithTag<TextView>("beacon_identity")?.text
                        ?.matches(Regex("OVT-[0-9A-F]{16}")) == true &&
                        root.findViewWithTag<Button>("beacon_start")?.isEnabled == true
                }
                ready
            }
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                assertFalse(root.findViewWithTag<Button>("beacon_stop").isEnabled)
                assertEquals(activity.getString(R.string.beacon_ready),
                    root.findViewWithTag<TextView>("beacon_status").text.toString())
            }
            screenshot(filename)
        }
    }

    private fun screenshot(filename: String) {
        instrumentation.waitForIdleSync()
        // Wait for native popup/layout work to settle, without replacing or drawing over the UI.
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
        assertEquals("The app must be visible without a system dialog covering it",
            context.packageName, instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Android did not return a screenshot for $filename"
        }
        try {
            assertTrue("Screenshot has a rendered viewport", bitmap.width > 0 && bitmap.height > 0)
            val directory = File(requireNotNull(context.getExternalFilesDir(null)), "screenshots")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, filename).outputStream().use { output ->
                assertTrue("Write $filename", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun <A : Activity> hasView(scenario: ActivityScenario<A>, tag: String): Boolean {
        var found = false
        scenario.onActivity {
            found = it.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>(tag) != null
        }
        return found
    }

    private fun waitUntil(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(40)
        }
        fail("Timed out: $label")
    }
}
