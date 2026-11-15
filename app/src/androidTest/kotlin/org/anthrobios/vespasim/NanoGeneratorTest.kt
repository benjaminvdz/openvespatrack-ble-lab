package org.anthrobios.vespasim

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.view.inspector.WindowInspectorCompat
import org.anthrobios.vespasim.core.NanoTagName
import org.anthrobios.vespasim.core.PayloadKind
import org.anthrobios.vespasim.core.Planner
import org.anthrobios.vespasim.core.Protocol
import org.anthrobios.vespasim.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real native generator dialog; no accessibility bridge, Bluetooth permissions or hardware calls. */
@RunWith(AndroidJUnit4::class)
class NanoGeneratorTest {
    @Test fun userCanCreateADirectNanoProfileFromLogicalAddressAndParameter() {
        val application = ApplicationProvider.getApplicationContext<VespaApplication>()
        waitUntil("Archive initialization") { application.initializationComplete }
        val repository = application.repository
        val existing = repository.profiles().map { it.id }.toSet()
        val preferences = application.getSharedPreferences("ui", Context.MODE_PRIVATE)
        val previous = preferences.getString("profile", null)
        val runs = repository.runs().map { it.id }.toSet()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<Button>("tab_1").performClick())
                }
                waitUntil("Loaded profile editor") {
                    var ready = false
                    scenario.onActivity { activity ->
                        // The create button exists before the asynchronous profile load; the
                        // editor requires the selected profile and its encoded snapshot.
                        ready = activity.findViewById<ViewGroup>(android.R.id.content)
                            .findViewWithTag<EditText>("profile_json") != null
                    }
                    ready
                }
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<Button>("create_nano_profile").performClick())
                }
                waitUntil("Native Nano generator dialog", { describeWindows(scenario) }) {
                    var ready = false
                    scenario.onActivity { ready = nanoDialogRoot() != null }
                    ready
                }
                scenario.onActivity {
                    val root = requireNotNull(nanoDialogRoot()) { "Visible native Nano dialog disappeared" }
                    val address = requireNotNull(root.findViewWithTag<EditText>("nano_address"))
                    val parameter = requireNotNull(root.findViewWithTag<EditText>("nano_parameter"))
                    assertEquals(address.context.getString(R.string.main_logical_address_a11y), address.contentDescription.toString())
                    assertEquals(parameter.context.getString(R.string.main_parameter_a11y), parameter.contentDescription.toString())
                    address.setText("01:02:03:04:05:06")
                    parameter.setText("AB")
                    assertEquals("01:02:03:04:05:06", address.text.toString())
                    assertEquals("AB", parameter.text.toString())
                    // The actual dialog button executes its installed app listener. The platform
                    // button ID is stable even when the theme visually transforms text to uppercase.
                    val make = requireNotNull(root.findViewById<Button>(android.R.id.button1))
                    assertTrue(make.isShown && make.isEnabled)
                    assertTrue(make.text.toString().equals(make.context.getString(R.string.main_create_profile), ignoreCase = true))
                    assertTrue(make.performClick())
                }
                waitUntil("Generated profile saved") { repository.profiles().any { it.id !in existing && it.radio.localName == "vnABAQIDBAUG" } }
                val created = repository.profiles().filter { it.id !in existing }
                assertEquals("One dialog submission creates exactly one profile", 1, created.size)
                val profile = created.single()
                assertEquals(PayloadKind.NAME_ONLY, profile.tracks.single().kind)
                assertEquals("vnABAQIDBAUG", profile.radio.localName)
                assertEquals("766e41424151494442415547", Protocol.hex(requireNotNull(profile.radio.localName).toByteArray(Charsets.UTF_8)))
                assertEquals(NanoTagName.Identity("01:02:03:04:05:06", 0xAB), NanoTagName.decode(requireNotNull(profile.radio.localName)))
                assertTrue(Planner.plan(profile).all { it.payloadHex == "" && !it.includeManufacturerData && it.localName == "vnABAQIDBAUG" })
                waitUntil("Name-copy control shown") {
                    var visible = false
                    scenario.onActivity { activity -> visible = activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<Button>("copy_advertised_name") != null }
                    visible
                }
                scenario.onActivity { assertNull("Saving closes the native generator dialog", nanoDialogRoot()) }
                assertEquals(runs, repository.runs().map { it.id }.toSet())
            }
        } finally {
            assertTrue(preferences.edit().putString("profile", previous).commit())
        }
    }

    // The pinned AndroidX Test monitor (1.7.2, already supplied by runner 1.6.2) provides this
    // test-only bridge: public WindowInspector on API 29+, its own compatibility path on API 26.
    // It observes this process's real View roots and needs no production hook or accessibility
    // service connection. RestrictTo is suppressed only here; keep this helper inside androidTest.
    @SuppressLint("RestrictedApi")
    private fun visibleWindows(): List<View> = WindowInspectorCompat.getGlobalWindowViews()
        .filter { it.isAttachedToWindow && it.isShown && it.visibility == View.VISIBLE }

    /** Called only inside ActivityScenario.onActivity, as WindowInspector requires the main thread. */
    private fun nanoDialogRoot(): View? = visibleWindows().singleOrNull {
        it.findViewWithTag<EditText>("nano_address") != null &&
            it.findViewWithTag<EditText>("nano_parameter") != null
    }

    private fun describeWindows(scenario: ActivityScenario<MainActivity>): String {
        var description = ""
        scenario.onActivity {
            val rows = mutableListOf<String>()
            fun visit(view: View, depth: Int) {
                if (rows.size >= 80 || depth > 15) return
                rows += "${view.javaClass.simpleName}: tag=${view.tag} shown=${view.isShown} text=${(view as? TextView)?.text}"
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1)
            }
            visibleWindows().forEach { root -> visit(root, 0) }
            description = rows.joinToString("\n").ifBlank { "No attached visible native application window" }
        }
        return description
    }

    private fun waitUntil(message: String, diagnostics: () -> String = { "" }, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < until) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timeout: $message\n${diagnostics()}")
    }
}

