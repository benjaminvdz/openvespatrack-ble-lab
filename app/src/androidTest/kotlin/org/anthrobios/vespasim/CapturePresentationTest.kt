package org.anthrobios.vespasim

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.anthrobios.vespasim.core.RunStatus
import org.anthrobios.vespasim.core.capture.CaptureObservation
import org.anthrobios.vespasim.core.capture.CaptureParser
import org.anthrobios.vespasim.core.capture.CaptureRecord
import org.anthrobios.vespasim.core.capture.CaptureReplay
import org.anthrobios.vespasim.ui.CapturePresentation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Display language must never alter durable raw bytes or legacy diagnostic evidence. */
@RunWith(AndroidJUnit4::class)
class CapturePresentationTest {
    @Test fun legacyScannerErrorsAndNestedServiceFailuresUseTheDisplayLanguage() {
        val expected = mapOf(
            "en" to "Recording start failed: Enable Bluetooth",
            "nl" to "Opname starten mislukt: Schakel Bluetooth in",
            "fr" to "Échec du démarrage de l’enregistrement : Activez Bluetooth",
        )
        val raw = "Opname starten mislukt: Schakel Bluetooth in"
        expected.forEach { (language, message) ->
            assertEquals(message, CapturePresentation.describe(context(language), raw))
        }
        assertEquals("Opname starten mislukt: Schakel Bluetooth in", raw)
    }

    @Test fun unsupportedAdTypeRetainsOriginalBytesAndDiagnosticsAcrossTranslations() {
        val observation = CaptureObservation(1, 1_700_000_000_000L, 1_000_000_000L,
            "01:02:03:04:05:06", -65, "02fe01")
        val original = CaptureParser.inspect(observation)
        val raw = requireNotNull(original.replayReason)
        assertEquals("AD-type 0xfe wordt niet stil weggelaten: heruitzenden niet ondersteund", raw)
        for (language in listOf("en", "nl", "fr")) {
            val translated = CapturePresentation.describe(context(language), raw)
            assertTrue(translated.contains("0xfe"))
            if (language != "nl") assertFalse(translated.contains("wordt niet"))
            assertEquals(original, CaptureParser.inspect(observation))
            assertEquals("02fe01", observation.rawHex)
        }
    }

    @Test fun unknownPlatformDiagnosticRemainsAvailableVerbatim() {
        val diagnostic = "PlatformDiagnostic(code=451, address=01:02:03:04:05:06)"
        for (language in listOf("en", "nl", "fr")) {
            assertEquals(diagnostic, CapturePresentation.describe(context(language), diagnostic))
        }
    }

    @Test fun generatedReplayDescriptionTranslatesWithoutRewritingTheProfile() {
        val record = CaptureRecord("ui-capture", "Example", "01:02:03:04:05:06", 1000, 1000,
            endedAtMs = 2000, status = RunStatus.STOPPED)
        val observation = CaptureObservation(1, 1000, 1000, record.address, -65, "02010609ff65fc003412010000")
        val profile = CaptureReplay.staticProfile(record, observation, "ui-replay", "Example replay")
        val originalDescription = profile.description
        val english = CapturePresentation.describe(context("en"), originalDescription)
        val french = CapturePresentation.describe(context("fr"), originalDescription)
        assertTrue(english.startsWith("One selected observation is repeated"))
        assertTrue(english.contains("Source recording: ui-capture"))
        assertTrue(french.startsWith("Une observation sélectionnée est répétée"))
        assertTrue(french.contains("Enregistrement source : ui-capture"))
        assertEquals(originalDescription, profile.description)
        val userNotes = "My own description\nBronopname: a note, not a generated replay\nKeep this wording"
        assertEquals(userNotes, CapturePresentation.describe(context("fr"), userNotes))
    }

    @Test fun originalRecordDetailFormatsAllEvidenceInEveryLanguage() {
        for (language in listOf("en", "nl", "fr")) {
            val context = context(language)
            val heading = context.getString(R.string.capture_original_record_heading, 101L)
            val formatted = context.getString(R.string.capture_scan_detail,
                heading, "2024-01-02", 1_000_000_000L, "01:02:03:04:05:06", "-65", -65,
                "test", "cache", true, false, 0, 1, 0, 255, 127, 1, "02fe01", "reason")
            assertTrue(formatted.startsWith(heading + "\n"))
            assertTrue(formatted.contains("01:02:03:04:05:06"))
            assertTrue(formatted.contains("02fe01"))
        }
    }

    private fun context(language: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag(language)))
        return base.createConfigurationContext(configuration)
    }
}
