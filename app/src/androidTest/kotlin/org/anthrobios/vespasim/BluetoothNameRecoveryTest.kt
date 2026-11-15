package org.anthrobios.vespasim

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.AtomicFile
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.anthrobios.vespasim.ble.AndroidTemporaryNameStore
import org.anthrobios.vespasim.core.engine.TemporaryNameJournal
import org.anthrobios.vespasim.core.engine.TemporaryNamePhase
import org.anthrobios.vespasim.service.SimulationService
import org.anthrobios.vespasim.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Android AtomicFile and recovery UI. No test requests Bluetooth permission or renaming. */
@RunWith(AndroidJUnit4::class)
class BluetoothNameRecoveryTest {
    private val application get() = ApplicationProvider.getApplicationContext<VespaApplication>()

    @Test fun recoveryJournalSurvivesReopeningAtEachPhaseAndClearsDurably() = inTemporaryDirectory { directory ->
        val original = TemporaryNameJournal("Telefoon — André 📱", "vn0CqrvM3e7/", "qa-journal")
        assertNull(AndroidTemporaryNameStore(directory).read())
        for (phase in TemporaryNamePhase.entries) {
            val journal = original.copy(phase = phase)
            AndroidTemporaryNameStore(directory).write(journal)
            assertEquals(journal, AndroidTemporaryNameStore(directory).read())
        }
        AndroidTemporaryNameStore(directory).clear()
        assertNull(AndroidTemporaryNameStore(directory).read())
    }

    @Test fun interruptedAtomicWriteKeepsTheLastCommittedRecoveryRecord() = inTemporaryDirectory { directory ->
        val journal = TemporaryNameJournal("Oorspronkelijke telefoon", "vn0CqrvM3e7/", "qa-interrupted")
        AndroidTemporaryNameStore(directory).write(journal)
        val atomic = AtomicFile(File(directory, "bluetooth-name-recovery.json"))
        // Simulate a process ending after startWrite, before finishWrite/failWrite.
        atomic.startWrite().use { output ->
            output.write("{\"originalName\":\"partial".toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        assertEquals(journal, AndroidTemporaryNameStore(directory).read())
        assertEquals(journal, AndroidTemporaryNameStore(directory).read())
    }

    @Test fun malformedOrOversizedRecoveryDataIsRejectedWithoutDeletingEvidence() = inTemporaryDirectory { directory ->
        val path = File(directory, "bluetooth-name-recovery.json")
        val malformedUtf8 = "{\"originalName\":\"".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) +
            "\",\"temporaryName\":\"vn0CqrvM3e7/\",\"ownerRunId\":\"qa-invalid\"}".toByteArray()
        val cases = listOf(
            "broken JSON" to "{broken".toByteArray(),
            "oversized data" to ByteArray(4097) { ' '.code.toByte() },
            "unknown version" to "{\"originalName\":\"Phone\",\"temporaryName\":\"vn0CqrvM3e7/\",\"ownerRunId\":\"qa-invalid\",\"version\":2}".toByteArray(),
            "malformed UTF-8" to malformedUtf8,
        )
        for ((label, bytes) in cases) {
            path.writeBytes(bytes)
            assertTrue("$label must not be interpreted as a valid recovery name", runCatching {
                AndroidTemporaryNameStore(directory).read()
            }.isFailure)
            assertArrayEquals("$label must remain available for diagnosis", bytes, path.readBytes())
        }
    }

    @Test fun pendingRecoveryIsShownAfterRecreationWithoutStartingOrRewritingARun() {
        waitUntil("Archive ready") { application.initializationComplete }
        stopServiceAndWait()
        val store = AndroidTemporaryNameStore(application.noBackupFilesDir)
        assertNull("Run instrumentation on an isolated device without a real pending name change", store.read())
        val journal = TemporaryNameJournal("QA original phone", "vn0CqrvM3e7/", "qa-pending-recovery")
        val runIds = application.repository.runs().map { it.id }.toSet()
        store.write(journal)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun assertRecoveryVisible() {
                    waitUntil("Recovery action visible on Telefoon") {
                        var visible = false
                        scenario.onActivity { activity ->
                            val root = activity.findViewById<ViewGroup>(android.R.id.content)
                            root.findViewWithTag<Button>("tab_3")?.performClick()
                            val message = root.findViewWithTag<TextView>("bluetooth_name_recovery")
                            val restore = root.findViewWithTag<Button>("restore_bluetooth_name")
                            visible = message?.text?.isNotBlank() == true && restore?.isEnabled == true
                        }
                        visible
                    }
                    assertEquals("Opening recovery must not rename, clear or rewrite the journal", journal, store.read())
                    assertEquals("Recovery display must never start a scenario", runIds, application.repository.runs().map { it.id }.toSet())
                }
                assertRecoveryVisible()
                scenario.recreate()
                assertRecoveryVisible()
            }
        } finally {
            stopServiceAndWait()
            store.clear()
        }
    }

    @Suppress("DEPRECATION")
    private fun stopServiceAndWait() {
        application.stopService(Intent(application, SimulationService::class.java))
        val manager = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        waitUntil("Previous test service stopped") {
            manager.getRunningServices(Int.MAX_VALUE).none { it.service.className == SimulationService::class.java.name }
        }
    }

    private fun inTemporaryDirectory(block: (File) -> Unit) {
        val directory = File(application.cacheDir, "qa-name-journal-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    private fun waitUntil(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timeout: $label")
    }
}
