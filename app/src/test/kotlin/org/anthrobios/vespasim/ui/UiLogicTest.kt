package org.anthrobios.vespasim.ui

import org.anthrobios.vespasim.core.ProfileCodec
import org.anthrobios.vespasim.core.SimulationProfile
import org.anthrobios.vespasim.core.TagTrack
import org.anthrobios.vespasim.core.RunRecord
import org.anthrobios.vespasim.core.RunMode
import org.anthrobios.vespasim.core.PlannedFrame
import org.anthrobios.vespasim.core.Presets
import org.anthrobios.vespasim.core.Planner
import org.anthrobios.vespasim.core.RunStatus
import org.anthrobios.vespasim.core.capture.CaptureObservation
import org.anthrobios.vespasim.core.capture.CaptureRecord
import org.anthrobios.vespasim.core.capture.CaptureReplay
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.CharacterCodingException

class UiLogicTest {
    @Test fun capturedServicesAreShownAsSourceContentAndNeverAsNameOnly() {
        val address = "AA:BB:CC:DD:EE:FF"
        val record = CaptureRecord("capture-ui", "Meting", address, 100, 100, 200, RunStatus.STOPPED)
        val observation = CaptureObservation(1, 110, 110, address, -65, "03030f1804160f1801")
        val profile = CaptureReplay.staticProfile(record, observation, "replay-ui", "Replay")
        val frame = Planner.plan(profile).first()
        val label = UiLogic.frameDescription(frame)
        assertTrue(label.contains("Service UUID: 0000180f-0000-1000-8000-00805f9b34fb"))
        assertTrue(label.contains("Service data 0000180f-0000-1000-8000-00805f9b34fb: 01"))
        assertTrue(label.contains("capture-ui / observation 1"))
        assertTrue(label.contains(CaptureReplay.sha256Hex(observation.rawHex!!)))
        assertFalse(label.contains("Local Name only"))
        assertTrue(UiLogic.manufacturerDescription(profile).startsWith("Captured advertising fields"))
        assertTrue(UiLogic.frameDescription(frame.copy(payloadHex = null, silenceReason = "loss")).startsWith("SILENCE"))
    }
    @Test fun nanoGeneratorCreatesAValidatedStandaloneNameOnlyProfile() {
        val profile = UiLogic.nanoProfile(" aa:bb:cc:dd:ee:ff ", "0c", "nano-test")
        assertEquals("vn0CqrvM3e7/", profile.radio.localName)
        assertEquals("NAME_ONLY", profile.tracks.single().kind.name)
        assertEquals(profile, ProfileCodec.decode(ProfileCodec.encode(profile)))
        assertTrue(UiLogic.nanoIdentityDescription(profile.radio.localName).contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(UiLogic.nanoIdentityDescription(profile.radio.localName).contains("meaning unknown"))
        assertEquals("", UiLogic.nanoIdentityDescription("VESPA-NANO-SIM"))
        assertEquals("", UiLogic.nanoIdentityDescription("vn0Cbad="))
    }

    @Test fun nanoGeneratorRejectsAmbiguousParametersAndInvalidAddresses() {
        for (parameter in listOf("", "C", "0x0C", "256", "GG", "０C")) {
            assertThrows(IllegalArgumentException::class.java) { UiLogic.nanoProfile("AA:BB:CC:DD:EE:FF", parameter, "test") }
        }
        assertThrows(IllegalArgumentException::class.java) { UiLogic.nanoProfile("AA:BB", "0C", "test") }
    }

    @Test fun newProfileLanguageChangesNotesOnlyAndKeepsExactlyTheSameRadioIdentity() {
        val english = UiLogic.nanoProfile("AA:BB:CC:DD:EE:FF", "0C", "localized-test")
        val localized = UiLogic.nanoProfile("AA:BB:CC:DD:EE:FF", "0C", "localized-test") { label, args ->
            "translated ${label.name}: ${args.joinToString(" / ")}"
        }
        assertNotEquals(english.name, localized.name)
        assertNotEquals(english.description, localized.description)
        assertEquals(english, localized.copy(name = english.name, description = english.description))
        assertEquals("vn0CqrvM3e7/", localized.radio.localName)
    }
    @Test fun displayDistinguishesNameOnlyEmptyManufacturerAndSilence() {
        val raw = PlannedFrame(1, 0, 500, "probe", 64613, "")
        val named = raw.copy(includeManufacturerData = false, localName = "VESPA-NANO-SIM")
        assertTrue(UiLogic.frameDescription(raw).contains("zero bytes"))
        assertTrue(UiLogic.frameDescription(named).contains("No manufacturer data"))
        assertTrue(UiLogic.frameDescription(named.copy(payloadHex = null, silenceReason = "planned_pause")).startsWith("SILENCE"))
        assertTrue(UiLogic.manufacturerDescription(Presets.all().single { it.id == "nano-name-recognition" }).startsWith("No manufacturer"))
    }
    private fun profile() = SimulationProfile(id = "ui-test", name = "Proef", tracks = listOf(TagTrack(id = "tag")))

    @Test fun importedUnicodeIsPreservedExactly() {
        val source = ProfileCodec.encode(profile().copy(name = "Récepteur — bijen 🐝"))
        assertEquals(source, UiLogic.readProfileText(ByteArrayInputStream(source.toByteArray(Charsets.UTF_8))))
    }

    @Test fun invalidUtf8FailsInsteadOfChangingTheProfile() {
        assertThrows(CharacterCodingException::class.java) { UiLogic.readProfileText(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
    }

    @Test fun exactByteLimitAcceptedAndOversizedInputRejectedEarly() {
        val max = UiLogic.MAX_PROFILE_BYTES
        assertEquals(max, UiLogic.readProfileText(ByteArrayInputStream(ByteArray(max) { 32 })).length)
        var consumed = 0
        val source = object : InputStream() {
            override fun read(): Int { consumed++; return 32 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes.fill(32, offset, offset + length); consumed += length; return length
            }
        }
        assertThrows(IllegalArgumentException::class.java) { UiLogic.readProfileText(source) }
        assertTrue(consumed <= max + 8192)
    }

    @Test fun providerWithNoProgressCannotSpinForever() {
        val source = object : InputStream() { override fun read() = 0; override fun read(b: ByteArray, off: Int, len: Int) = 0 }
        assertThrows(IllegalArgumentException::class.java) { UiLogic.readProfileText(source) }
    }

    @Test fun collidingImportGetsNewIdWithoutChangingScenario() {
        val original = profile()
        val imported = UiLogic.uniqueImport(original, setOf(original.id))
        assertNotEquals(original.id, imported.id)
        assertEquals(original, imported.copy(id = original.id))
        assertEquals(original, UiLogic.uniqueImport(original, emptySet()))
    }

    @Test fun basicFormRetainsAdvancedFieldsAndUsesExactIntegers() {
        val original = profile()
        val value = UiLogic.applyBasic(original, "  Nieuwe proef  ", "10000", "1000", "35", "-33")
        assertEquals("Nieuwe proef", value.name)
        assertEquals(10_000L, value.durationMs)
        assertEquals(1000, value.slotMs)
        assertEquals(35, value.lossPercent)
        assertEquals(-33L, value.seed)
        assertEquals(original.tracks, value.tracks)
        assertEquals(original.radio, value.radio)
    }

    @Test fun overflowFractionsAndInvalidRangesDoNotSave() {
        for (duration in listOf("1.5", "9223372036854775808", "-1", "")) {
            assertThrows(Exception::class.java) { UiLogic.applyBasic(profile(), "Test", duration, "500", "0", "1") }
        }
        assertThrows(Exception::class.java) { UiLogic.applyBasic(profile(), "Test", "10000", "100", "0", "1") }
        assertThrows(Exception::class.java) { UiLogic.applyBasic(profile(), "Test", "10000", "500", "101", "1") }
        assertThrows(Exception::class.java) { UiLogic.applyBasic(profile(), " ", "10000", "500", "0", "1") }
    }

    @Test fun importedNamesCannotCreatePathsOrExcessiveBasenames() {
        assertEquals("private-name.json", UiLogic.filename("../../private/name", "json"))
        assertEquals("vespa-simulatie.zip", UiLogic.filename("../../../", "zip"))
        val result = UiLogic.filename("A".repeat(1000), "html")
        assertTrue(result.length <= 75)
        assertFalse(result.contains('/'))
        assertThrows(IllegalArgumentException::class.java) { UiLogic.filename("x", "../z") }
    }

    @Test fun longDisplayFieldsExplicitlyIdentifyTruncation() {
        assertEquals("abc", UiLogic.shortText("abc", 3))
        assertEquals("abc… [full text in export]", UiLogic.shortText("abcd", 3))
        assertEquals("abc… [texte complet dans l’export]", UiLogic.shortText("abcd", 3, "… [texte complet dans l’export]"))
    }

    @Test fun localizedPresentationPreservesFrameDataAndUsesTranslatedSilenceReasons() {
        val frame = PlannedFrame(1, 0, 500, "literal-track", 64613, "00FF", localName = "Gereed")
        val rendered = UiLogic.frameDescription(frame) { label, args -> "${label.name}[${args.joinToString("|")}]" }
        assertEquals("PAYLOAD[00FF]\nLOCAL_NAME[Gereed]", rendered)
        assertEquals("00FF", frame.payloadHex)
        assertEquals("Gereed", frame.localName)
        val silent = frame.copy(payloadHex = null, silenceReason = "planned_pause")
        assertEquals("SILENCE[PAUSE[]]", UiLogic.frameDescription(silent) { label, args -> "${label.name}[${args.joinToString("|")}]" })
        assertEquals("planned_pause", silent.silenceReason)
    }

    @Test fun profileSnapshotHashDetectsChangesAcrossPickerRecreation() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", UiLogic.sha256(""))
        val json = ProfileCodec.encode(profile())
        assertEquals(UiLogic.sha256(json), UiLogic.sha256(json))
        assertNotEquals(UiLogic.sha256(json), UiLogic.sha256(ProfileCodec.encode(profile().copy(seed = 2))))
    }

    private fun runs(count: Int) = (0 until count).map { RunRecord("run-$it", profile(), RunMode.DRY_RUN, it.toLong()) }

    @Test fun historyRefreshKeepsChosenRunWhenNewRecordsShiftItToAnotherPage() {
        val original = UiLogic.historyPage(runs(205), 0, "run-105")
        assertEquals(0, original.index)
        val refreshed = UiLogic.historyPage(runs(206), original.index, original.selectedId)
        assertEquals(1, refreshed.index)
        assertEquals("run-105", refreshed.selectedId)
        assertTrue(refreshed.items.any { it.id == "run-105" })
        assertEquals(100, refreshed.items.size)
    }

    @Test fun explicitHistoryNavigationReachesOldRunsAndClampsBoundaries() {
        val old = UiLogic.historyPage(runs(205), 0, "run-204", requestedPage = 2)
        assertEquals(2, old.index)
        assertEquals(3, old.pageCount)
        assertEquals(205, old.totalCount)
        assertEquals(listOf("run-4", "run-3", "run-2", "run-1", "run-0"), old.items.map { it.id })
        assertEquals("run-4", old.selectedId)
        assertEquals(2, UiLogic.historyPage(runs(205), 0, null, 999).index)
        assertEquals(0, UiLogic.historyPage(runs(205), 0, null, -1).index)
    }

    @Test fun missingSelectionAndEmptyHistoryResetSafely() {
        val page = UiLogic.historyPage(runs(3), 2, "removed-run")
        assertEquals(0, page.index)
        assertEquals("run-2", page.selectedId)
        val empty = UiLogic.historyPage(emptyList(), 12, "removed-run")
        assertEquals(0, empty.pageCount)
        assertEquals(0, empty.totalCount)
        assertNull(empty.selectedId)
        assertTrue(empty.items.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { UiLogic.historyPage(runs(1), 0, null, pageSize = 0) }
    }

    @Test fun tiedRunTimestampsHaveDeterministicPages() {
        val tied = listOf("b", "c", "a").map { RunRecord(it, profile(), RunMode.DRY_RUN, 100) }
        assertEquals(listOf("a", "b"), UiLogic.historyPage(tied, 0, null, pageSize = 2).items.map { it.id })
        assertEquals(listOf("c"), UiLogic.historyPage(tied.reversed(), 0, null, requestedPage = 1, pageSize = 2).items.map { it.id })
    }
}

