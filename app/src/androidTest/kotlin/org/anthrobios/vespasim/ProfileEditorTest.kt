package org.anthrobios.vespasim

import android.content.Context
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.anthrobios.vespasim.core.ProfileCodec
import org.anthrobios.vespasim.core.RunStatus
import org.anthrobios.vespasim.core.capture.CaptureObservation
import org.anthrobios.vespasim.core.capture.CaptureRecord
import org.anthrobios.vespasim.core.capture.CaptureReplay
import org.anthrobios.vespasim.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real editor regression: asynchronous serialization cannot overwrite more recent user input. */
@RunWith(AndroidJUnit4::class)
class ProfileEditorTest {
    @Test fun asyncBasicEditPreservesNewerInputAndCapturedProvenance() {
        val app = ApplicationProvider.getApplicationContext<VespaApplication>()
        waitUntil("initialization") { app.initializationComplete }
        val capture = CaptureRecord("editor-source", "Synthetische bron", "AA:BB:CC:DD:EE:FF", 1, 1, 2, RunStatus.STOPPED)
        val observation = CaptureObservation(1, 1, 1, capture.address, -80, "04ff65fc01")
        val profile = CaptureReplay.staticProfile(capture, observation, UUID.randomUUID().toString(), "Editorbron")
        app.repository.saveProfile(profile)
        val preferences = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
        val previous = preferences.getString("profile", null)
        check(preferences.edit().putString("profile", profile.id).commit())
        val previousRuns = app.repository.runs().map { it.id }.toSet()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<Button>("tab_1").performClick()
                }
                fun readEditor(): String? {
                    var json: String? = null
                    scenario.onActivity { activity -> json = activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<EditText>("profile_json")?.text?.toString() }
                    return json
                }
                waitUntil("captured profile editor") { readEditor()?.let { ProfileCodec.decode(it).id == profile.id } == true }
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    root.findViewWithTag<EditText>("profile_name").setText("Eerste invoer")
                    assertTrue(root.findViewWithTag<Button>("apply_basic").performClick())
                    // Same UI turn: a worker result cannot yet have been applied to this editor.
                    root.findViewWithTag<EditText>("profile_name").setText("Nieuwste invoer")
                }
                waitUntil("newer edit preserved") {
                    var preserved = false
                    scenario.onActivity { activity ->
                        val root = activity.findViewById<ViewGroup>(android.R.id.content)
                        preserved = root.findViewWithTag<TextView>("notice").text.toString() == activity.getString(R.string.main_edit_changed)
                        assertEquals("Nieuwste invoer", root.findViewWithTag<EditText>("profile_name").text.toString())
                    }
                    preserved
                }
                assertEquals(profile.name, ProfileCodec.decode(requireNotNull(readEditor())).name)
                scenario.onActivity { activity -> assertTrue(activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<Button>("apply_basic").performClick()) }
                waitUntil("latest basic fields applied") { readEditor()?.let { ProfileCodec.decode(it).name == "Nieuwste invoer" } == true }
                val edited = ProfileCodec.decode(requireNotNull(readEditor()))
                assertEquals(profile.tracks.single().capturedFrames, edited.tracks.single().capturedFrames)
                assertEquals(profile, app.repository.profile(profile.id)) // Applying fields is not Save.
                assertEquals(previousRuns, app.repository.runs().map { it.id }.toSet())
            }
        } finally {
            check(preferences.edit().putString("profile", previous).commit())
        }
    }

    private fun waitUntil(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timeout: $label")
    }
}

