package org.anthrobios.vespasim

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.anthrobios.vespasim.service.BeaconSessionStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises Android AtomicFile persistence; never starts a Bluetooth operation. */
@RunWith(AndroidJUnit4::class)
class BeaconSessionStoreTest {
    @Test fun markerSurvivesReopeningAndOnlyExplicitCleanupClearsIt() = withDirectory { directory ->
        val path = File(directory, "session")
        val first = BeaconSessionStore(path)
        assertFalse(first.wasRunning())
        first.markRunning()
        val reopened = BeaconSessionStore(path)
        assertTrue(reopened.wasRunning())
        assertTrue("Reading an interruption must not silently clear it", BeaconSessionStore(path).wasRunning())
        reopened.clearAfterConfirmedStop()
        assertFalse(BeaconSessionStore(path).wasRunning())
    }

    @Test fun malformedAndOversizedStateIsRejectedInsteadOfTreatedAsAnIdleSession() = withDirectory { directory ->
        val path = File(directory, "session")
        listOf("unknown-format", "x".repeat(33)).forEach { content ->
            path.writeText(content)
            try { BeaconSessionStore(path).wasRunning(); fail("Malformed session marker was accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun unwritableMarkerCannotReportSuccessfulPreparation() = withDirectory { directory ->
        val blocker = File(directory, "parent").apply { writeText("not a directory") }
        try { BeaconSessionStore(File(blocker, "session")).markRunning(); fail("Preparation unexpectedly succeeded") }
        catch (_: java.io.IOException) { }
    }

    private fun withDirectory(test: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "beacon-store-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try { test(directory) } finally { directory.deleteRecursively() }
    }
}
