package org.anthrobios.vespasim.core.storage

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.anthrobios.vespasim.core.*
import org.anthrobios.vespasim.core.capture.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LabRepositoryTest {
    @Test fun reportLanguageChangesOnlyPresentationAndEscapesOriginalEvidence() {
        val store = repository()
        val source = record(profile = profile().copy(name = "Exact <name>", description = "Original <script>notes</script>"))
        store.createRun(source)
        val diagnostic = "Gereed <img src=x onerror=bad()>"
        store.appendEvent(source.id, event(1, message = diagnostic))
        val runBefore = File(temporary.root, "runs/${source.id}/run.json").readBytes()
        val logBefore = log().readBytes()
        for ((language, heading) in listOf("en" to "Test and provenance", "nl" to "Proef en herkomst", "fr-FR" to "Test et provenance")) {
            val report = store.reportHtml(source.id, language)
            assertTrue(report.contains("<html lang=\"${language.substringBefore('-')}\">"))
            assertTrue(report.contains(heading))
            assertTrue(report.contains("Exact &lt;name&gt;"))
            assertTrue(report.contains("Original &lt;script&gt;notes&lt;/script&gt;"))
            assertTrue(report.contains("Gereed &lt;img src=x onerror=bad()&gt;"))
            assertFalse(report.contains("<script>"))
            assertFalse(report.contains("<img"))
            assertArrayEquals(runBefore, File(temporary.root, "runs/${source.id}/run.json").readBytes())
            assertArrayEquals(logBefore, log().readBytes())
        }
        assertTrue(store.reportHtml(source.id).contains("<html lang=\"en\">"))
        assertEquals(store.reportHtml(source.id), store.reportHtml(source.id, "unsupported\"><script>"))
    }

    @Test fun portableBundleDefaultsToEnglishAndCanCarryALocalizedReportWithoutChangingRawMembers() {
        val store = repository(); store.createRun(record()); store.appendEvent("run-a", event(1, message = "Oorspronkelijk bewijs"))
        fun exported(language: String?): Map<String, ByteArray> {
            val output = ByteArrayOutputStream()
            if (language == null) store.exportBundle("run-a", output) else store.exportBundle("run-a", output, language)
            return ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                buildMap { while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()) } }
            }
        }
        val english = exported(null)
        val french = exported("fr")
        assertTrue(english.getValue("report.html").toString(Charsets.UTF_8).contains("<html lang=\"en\">"))
        assertTrue(french.getValue("report.html").toString(Charsets.UTF_8).contains("<html lang=\"fr\">"))
        for (member in listOf("run.json", "profile.json", "plan.jsonl", "events.jsonl", "events.csv"))
            assertArrayEquals("Recorded member $member", english.getValue(member), french.getValue(member))
    }

    @Test fun reportCatalogPreservesEveryFormatArgumentAcrossLanguages() {
        val pattern = Regex("%[0-9]+\\\$[-+.0-9]*[a-zA-Z]")
        ReportLabel.entries.forEach { label ->
            val expected = pattern.findAll(label.en).map { it.value }.toList().sorted()
            for (text in listOf(label.nl, label.fr)) {
                assertTrue(text.isNotBlank())
                assertEquals(label.name, expected, pattern.findAll(text).map { it.value }.toList().sorted())
            }
        }
        CaptureReplay.differences.forEach { original ->
            assertNotEquals("Every replay caveat is translated", original, ReportText("en").replayDifference(original))
            assertNotEquals("Every replay caveat is translated", original, ReportText("fr").replayDifference(original))
        }
    }

    @Test fun directNanoReportDistinguishesDecodedLogicalAddressFromPhysicalRadio() {
        val store = repository()
        store.createRun(record(profile = Presets.all().single { it.id == "nano-direct-vn" }))
        val report = store.reportHtml("run-a", "nl")
        assertTrue(report.contains("Logisch tagadres uit naam"))
        assertTrue(report.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(report.contains("geen claim over het fysieke telefoonadres"))
        assertTrue(report.contains("Tagparameter PP"))
        assertTrue(report.contains("0C (hardwarebetekenis onbekend)"))
    }
    @Test fun originalVersionOneRunLoadsAndExportsWithoutRewritingStoredEvidence() {
        val fixture = listOf(File("examples/reports/android-dry-run.zip"), File("../examples/reports/android-dry-run.zip")).firstOrNull(File::isFile)
            ?: error("Oorspronkelijke versie-0.1-exportfixture ontbreekt")
        val original = mutableMapOf<String, ByteArray>()
        ZipInputStream(fixture.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                original[entry.name] = zip.readBytes()
            }
        }
        val runBytes = original.getValue("run.json")
        assertFalse(runBytes.toString(Charsets.UTF_8).contains("localName"))
        val oldRun = LabRepository.json.decodeFromString<RunRecord>(runBytes.toString(Charsets.UTF_8))
        val oldPlan = original.getValue("plan.jsonl").toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).map { LabRepository.json.decodeFromString<PlannedFrame>(it) }.toList()
        assertNull(oldRun.profile.radio.localName)
        assertTrue(oldPlan.all { it.includeManufacturerData && it.localName == null })
        val store = repository()
        val runDirectory = File(temporary.root, "runs/${oldRun.id}").apply { mkdir() }
        val metadata = File(runDirectory, "run.json").apply { writeBytes(runBytes) }
        val events = File(runDirectory, "events.jsonl").apply { writeBytes(original.getValue("events.jsonl")) }
        assertEquals(oldRun, store.run(oldRun.id))
        assertEquals(0, store.recoverInterruptedRuns())
        val exported = bundle(store, oldRun.id)
        assertArrayEquals(runBytes, metadata.readBytes())
        assertArrayEquals(original.getValue("events.jsonl"), events.readBytes())
        assertArrayEquals(original.getValue("events.jsonl"), exported.getValue("events.jsonl"))
        assertEquals(181, store.summary(oldRun.id).eventCount)
    }

    @Test fun nameOnlyBundleAndReportDoNotClaimManufacturerData() {
        val store = repository()
        val named = Presets.all().single { it.id == "nano-name-recognition" }.copy(durationMs = 1000)
        store.createRun(record(profile = named))
        val entries = bundle(store)
        val plan = entries.getValue("plan.jsonl").toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).map { LabRepository.json.decodeFromString<PlannedFrame>(it) }.toList()
        assertTrue(plan.all { !it.includeManufacturerData && it.localName == "VESPA-NANO-SIM" && it.payloadHex == "" })
        val report = entries.getValue("report.html").toString(Charsets.UTF_8)
        assertTrue(report.contains("Niet uitgezonden (alleen Local Name)"))
        assertTrue(report.contains("VESPA-NANO-SIM"))
    }
    @Test fun capturedReplayBundleContainsImmutableSourceAndTruthfulProvenanceReport() {
        val capture = CaptureRecord("capture-original", "Original", "AA:BB:CC:DD:EE:FF", 100, 100, 200, RunStatus.STOPPED,
            importedFromId = "external", importedArchiveSha256 = "ab".repeat(32))
        val sample = CaptureObservation(3, 150, 150, capture.address, -80, "04FF3412AB")
        val profile = CaptureReplay.staticProfile(capture, sample, "replay-one", "Captured", durationMs = 1000)
        val store = repository(); store.createRun(record(profile = profile))
        val members = bundle(store)
        val decoded = ProfileCodec.decode(members.getValue("profile.json").toString(Charsets.UTF_8))
        assertEquals(sample, decoded.tracks.single().capturedFrames.single().observation)
        val plan = members.getValue("plan.jsonl").toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).map { LabRepository.json.decodeFromString<PlannedFrame>(it) }.toList()
        assertTrue(plan.all { it.capturedAdvertisement?.observation == sample && it.reportedRssi == null })
        val html = members.getValue("report.html").toString(Charsets.UTF_8)
        assertTrue(html.contains("Semantische replay"))
        assertTrue(html.contains("Geïmporteerd opnamebewijs"))
        assertTrue(html.contains(CaptureReplay.sha256Hex(sample.rawHex!!)))
        assertTrue(html.contains("0x1234"))
        assertFalse(html.contains("0xFC65 (alleen tracks"))
        assertTrue(html.contains("geen ontvangstgrafiek"))
        assertTrue(html.contains("oorspronkelijke radio-intervallen"))
    }

    @get:Rule val temporary = TemporaryFolder()
    private fun profile(id: String = "test-profile") = SimulationProfile(
        id = id, name = "Synthetische test", durationMs = 2000, slotMs = 500,
        tracks = listOf(TagTrack(id = "tag-a")),
    )
    private fun record(id: String = "run-a", profile: SimulationProfile = profile(), mode: RunMode = RunMode.DRY_RUN) =
        RunRecord(id = id, profile = profile, mode = mode, startedAtMs = 1000)
    private fun event(sequence: Long, kind: String = "simulated_frame", message: String = "") =
        RunEvent(sequence, 1000 + sequence, sequence * 10, kind, frameSequence = sequence,
            trackId = "tag-a", payloadHex = "003412010000", message = message)
    private fun repository() = LabRepository(temporary.root)
    private fun log(id: String = "run-a") = File(temporary.root, "runs/$id/events.jsonl")
    private fun bundle(repository: LabRepository, run: String = "run-a"): Map<String, ByteArray> {
        val output = ByteArrayOutputStream()
        repository.exportBundle(run, output, "nl")
        val members = linkedMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertFalse("Duplicate member", members.containsKey(entry.name))
                members[entry.name] = zip.readBytes()
            }
        }
        return members
    }
    private fun failure(block: () -> Unit): Exception {
        try { block() } catch (e: Exception) { return e }
        throw AssertionError("Expected explicit failure")
    }

    @Test fun profilesRoundTripAndExplicitSaveReplacesOnlyThatProfile() {
        val store = repository()
        store.saveProfile(profile())
        store.saveProfile(profile("second"))
        store.saveProfile(profile().copy(name = "Gewijzigd"))
        assertEquals("Gewijzigd", store.profile("test-profile")!!.name)
        assertEquals(2, LabRepository(temporary.root).profiles().size)
        assertNull(store.profile("missing"))
    }

    @Test fun invalidProfileDoesNotReplacePreviousBytes() {
        val store = repository(); store.saveProfile(profile())
        val path = File(temporary.root, "profiles/test-profile.json")
        val before = path.readBytes()
        failure { store.saveProfile(profile().copy(slotMs = 1)) }
        assertArrayEquals(before, path.readBytes())
    }

    @Test fun identifiersCannotEscapeArchive() {
        val store = repository()
        for (id in listOf("../escape", "..", ".", "a/b", "a\\b", "", "/tmp/escape")) {
            failure { store.profile(id) }
            failure { store.run(id) }
            failure { store.createRun(record(id)) }
        }
        assertTrue(store.runs().isEmpty())
    }

    @Test fun symlinkProfileIsRejectedWithoutTouchingTarget() {
        val store = repository()
        val outside = temporary.newFile("outside.json").apply { writeText("unchanged") }
        Files.createSymbolicLink(File(temporary.root, "profiles/test-profile.json").toPath(), outside.toPath())
        failure { store.saveProfile(profile()) }
        assertEquals("unchanged", outside.readText())
    }

    @Test fun runCreationCannotOverwriteEvidence() {
        val store = repository(); store.createRun(record()); store.appendEvent("run-a", event(1))
        val before = log().readBytes()
        failure { store.createRun(record()) }
        assertArrayEquals(before, log().readBytes())
        assertEquals(1, store.summary("run-a").eventCount)
    }

    @Test fun creationIsVisibleOnlyWithMetadataAndLog() {
        val store = repository(); store.createRun(record())
        val directory = File(temporary.root, "runs/run-a")
        assertEquals(setOf("run.json", "events.jsonl"), directory.list()!!.toSet())
        assertEquals(record(), LabRepository(temporary.root).run("run-a"))
    }

    @Test fun invalidRunMetadataRejectedBeforeCreation() {
        val store = repository()
        listOf(
            record().copy(status = RunStatus.COMPLETED, endedAtMs = 3000),
            record().copy(endedAtMs = 3000),
            record(mode = RunMode.BLE).copy(speed = 2),
            record().copy(speed = 0),
            record().copy(startedAtMs = -1),
            record().copy(deviceInfo = "x".repeat(8193)),
        ).forEach { run -> failure { store.createRun(run) } }
        assertTrue(store.runs().isEmpty())
    }

    @Test fun profileSnapshotIsIndependentOfLaterProfileEdits() {
        val store = repository(); store.saveProfile(profile()); store.createRun(record())
        store.saveProfile(profile().copy(name = "New profile"))
        assertEquals("Synthetische test", store.run("run-a")!!.profile.name)
        val snapshot = ProfileCodec.decode(bundle(store)["profile.json"]!!.toString(Charsets.UTF_8))
        assertEquals(profile(), snapshot)
    }

    @Test fun eventBytesAndDetailsSurviveReopen() {
        val store = repository(); store.createRun(record())
        val original = event(1, message = "Eenheid onbevestigd — café").copy(details = mapOf("requested" to "-7", "actual" to "-8"))
        store.appendEvent("run-a", original)
        assertEquals(listOf(original), LabRepository(temporary.root).events("run-a"))
    }

    @Test fun malformedUnicodeIsRejectedRatherThanReplacedOnUtf8Write() {
        val store = repository(); store.createRun(record())
        failure { store.appendEvent("run-a", event(1, message = "broken\uD800")) }
        failure { store.appendEvent("run-a", event(1).copy(details = mapOf("x" to "\uDC00"))) }
        val valid = event(1, message = "Bij \uD83D\uDC1D")
        store.appendEvent("run-a", valid)
        assertEquals(valid.message, store.events("run-a").single().message)
    }

    @Test fun emptyAndMalformedPayloadsAreRepresentableWithoutAcceptingInvalidHex() {
        val store = repository(); store.createRun(record())
        store.appendEvent("run-a", event(1).copy(payloadHex = ""))
        store.appendEvent("run-a", event(2).copy(payloadHex = "00"))
        failure { store.appendEvent("run-a", event(3).copy(payloadHex = "xyz")) }
        failure { store.appendEvent("run-a", event(3).copy(payloadHex = "0")) }
        assertEquals(2, store.events("run-a").size)
    }

    @Test fun duplicateReorderedAndSkippedSequencesAreRejected() {
        val store = repository(); store.createRun(record())
        failure { store.appendEvent("run-a", event(2)) }
        store.appendEvent("run-a", event(1))
        failure { store.appendEvent("run-a", event(1)) }
        failure { store.appendEvent("run-a", event(3)) }
        store.appendEvent("run-a", event(2))
        assertEquals(listOf(1L, 2L), store.events("run-a").map { it.sequence })
    }

    @Test fun monotoneElapsedCannotRunBackwardsButWallClockCan() {
        val store = repository(); store.createRun(record()); store.appendEvent("run-a", event(1))
        failure { store.appendEvent("run-a", event(2).copy(elapsedMs = 9)) }
        store.appendEvent("run-a", event(2).copy(atEpochMs = 2))
        assertEquals(2, store.summary("run-a").eventCount)
    }

    @Test fun invalidAndOversizedLogFieldsFailBeforeMutation() {
        val store = repository(); store.createRun(record())
        val candidates = listOf(
            event(1).copy(sequence = 0), event(1).copy(elapsedMs = -1), event(1).copy(atEpochMs = -1),
            event(1).copy(kind = "<script>"), event(1).copy(message = "x".repeat(8193)),
            event(1).copy(payloadHex = "00".repeat(241)),
            event(1).copy(details = (1..65).associate { it.toString() to "x" }),
            event(1).copy(details = mapOf("x" to "a".repeat(4097))),
            event(1).copy(details = (1..32).associate { it.toString() to "a".repeat(4096) }),
        )
        candidates.forEach { e -> failure { store.appendEvent("run-a", e) } }
        assertEquals(0L, log().length())
    }

    @Test fun terminalRunsRejectNewEventsAndDifferentEndState() {
        val store = repository(); store.createRun(record()); store.appendEvent("run-a", event(1))
        store.finishRun("run-a", RunStatus.STOPPED, 5000)
        store.finishRun("run-a", RunStatus.STOPPED, 5000)
        failure { store.appendEvent("run-a", event(2)) }
        failure { store.finishRun("run-a", RunStatus.COMPLETED, 5000) }
        failure { store.finishRun("run-a", RunStatus.STOPPED, 5001) }
        assertEquals(RunStatus.STOPPED, store.run("run-a")!!.status)
    }

    @Test fun cannotFinishRunningOrMissingRun() {
        val store = repository(); store.createRun(record())
        failure { store.finishRun("run-a", RunStatus.RUNNING, 5000) }
        failure { store.finishRun("missing", RunStatus.COMPLETED, 5000) }
        failure { store.appendEvent("missing", event(1)) }
        failure { store.events("missing") }
    }

    @Test fun keysetPagingHasNoDuplicatesOrOmissions() {
        val store = repository(); store.createRun(record())
        (1L..21).forEach { store.appendEvent("run-a", event(it)) }
        val collected = mutableListOf<Long>()
        var cursor = 0L
        while (true) {
            val page = store.events("run-a", cursor, 4)
            if (page.isEmpty()) break
            collected += page.map { it.sequence }; cursor = page.last().sequence
        }
        assertEquals((1L..21).toList(), collected)
        assertTrue(store.events("run-a", Long.MAX_VALUE, 10).isEmpty())
        failure { store.events("run-a", -1, 5) }
        failure { store.events("run-a", 0, 0) }
        failure { store.events("run-a", 0, 2001) }
    }

    @Test fun summariesCountEvidenceKindsWithoutPretendingToCountRfPackets() {
        val store = repository(); store.createRun(record())
        val kinds = listOf("run_started", "frame_planned", "radio_request", "controller_accepted", "simulated_frame", "frame_skipped_late", "error", "radio_failed", "permission_error")
        kinds.forEachIndexed { index, kind -> store.appendEvent("run-a", event(index + 1L, kind)) }
        val summary = store.summary("run-a")
        assertEquals(9, summary.eventCount); assertEquals(1, summary.controllerAccepted)
        assertEquals(1, summary.simulatedFrames); assertEquals(1, summary.skippedLate); assertEquals(3, summary.errorCount)
    }

    @Test fun multipleRepositoryConnectionsInvalidateCachedIndex() {
        val first = repository(); first.createRun(record()); first.appendEvent("run-a", event(1))
        val second = LabRepository(temporary.root)
        second.appendEvent("run-a", event(2))
        assertEquals(2, first.summary("run-a").eventCount)
        first.appendEvent("run-a", event(3))
        assertEquals(3, second.summary("run-a").eventCount)
    }

    @Test fun concurrentSameSequenceHasExactlyOneWinner() {
        val store = repository(); store.createRun(record())
        val gate = CountDownLatch(1); val successes = AtomicInteger(); val failures = AtomicInteger()
        val workers = Executors.newFixedThreadPool(8)
        val tasks = (1..8).map {
            workers.submit {
                gate.await()
                try { LabRepository(temporary.root).appendEvent("run-a", event(1)); successes.incrementAndGet() }
                catch (_: IllegalArgumentException) { failures.incrementAndGet() }
            }
        }
        gate.countDown(); tasks.forEach { it.get(10, TimeUnit.SECONDS) }; workers.shutdown()
        assertEquals(1, successes.get()); assertEquals(7, failures.get())
        assertEquals(1, store.summary("run-a").eventCount)
    }

    @Test fun openingRepositoryDoesNotMarkAnActiveRunInterrupted() {
        val first = repository(); first.createRun(record())
        val second = LabRepository(temporary.root)
        assertEquals(RunStatus.RUNNING, second.run("run-a")!!.status)
        first.appendEvent("run-a", event(1))
    }

    @Test fun recoveryMarksOnlyOpenRunsAndIsIdempotent() {
        val store = repository(); store.createRun(record()); store.appendEvent("run-a", event(1))
        store.createRun(record("finished")); store.finishRun("finished", RunStatus.COMPLETED, 3000)
        assertEquals(1, store.recoverInterruptedRuns()); assertEquals(0, store.recoverInterruptedRuns())
        assertEquals(RunStatus.INTERRUPTED, store.run("run-a")!!.status)
        assertEquals("run_interrupted", store.events("run-a").last().kind)
        assertEquals(RunStatus.COMPLETED, store.run("finished")!!.status)
    }

    @Test fun partialTailIsNeverSilentlyIgnoredAndRecoveryPreservesItsBytes() {
        val store = repository(); store.createRun(record()); store.appendEvent("run-a", event(1))
        val tail = "{\"sequence\":2,\"broken\":".toByteArray()
        log().appendBytes(tail)
        failure { store.events("run-a") }; failure { store.summary("run-a") }
        failure { store.exportBundle("run-a", ByteArrayOutputStream()) }
        failure { store.finishRun("run-a", RunStatus.COMPLETED, 4000) }
        assertEquals(1, store.recoverInterruptedRuns())
        val records = store.events("run-a")
        assertEquals(listOf(1L, 2L), records.map { it.sequence }); assertEquals("error", records.last().kind)
        val members = bundle(store)
        val recovery = members.entries.single { it.key.startsWith("recovery/") }
        assertArrayEquals(tail, recovery.value)
        assertTrue(members["report.html"]!!.toString(Charsets.UTF_8).contains("herstelbijlagen"))
    }

    @Test fun completeJsonWithoutNewlineIsStillAnUncommittedTail() {
        val store = repository(); store.createRun(record()); store.appendEvent("run-a", event(1))
        val tail = LabRepository.json.encodeToString(event(2)).toByteArray()
        log().appendBytes(tail); store.recoverInterruptedRuns()
        assertEquals("error", store.events("run-a").last().kind)
        assertArrayEquals(tail, bundle(store).entries.single { it.key.startsWith("recovery/") }.value)
    }

    @Test fun corruptedMiddleLineIsNotDiscardedByRecovery() {
        val store = repository(); store.createRun(record()); store.appendEvent("run-a", event(1))
        log().appendText("not-json\n")
        val before = log().readBytes()
        failure { store.recoverInterruptedRuns() }
        assertArrayEquals(before, log().readBytes())
        assertEquals(RunStatus.RUNNING, store.run("run-a")!!.status)
    }

    @Test fun damagedEarlyMetadataDoesNotPreventLaterHealthyRecovery() {
        val store = repository()
        store.createRun(record("a-damaged")); store.createRun(record("z-healthy"))
        store.appendEvent("z-healthy", event(1))
        val metadata = File(temporary.root, "runs/a-damaged/run.json")
        metadata.writeText("{broken metadata")
        val before = metadata.readBytes()
        val error = failure { store.recoverInterruptedRuns() }
        assertTrue(error.message!!.contains("1 proef/proeven hersteld"))
        assertTrue(error.message!!.contains("a-damaged"))
        assertArrayEquals(before, metadata.readBytes())
        assertEquals(RunStatus.INTERRUPTED, store.run("z-healthy")!!.status)
        assertEquals("run_interrupted", store.events("z-healthy").last().kind)
        failure { store.recoverInterruptedRuns() }
        assertEquals(2, store.events("z-healthy").size)
    }

    @Test fun corruptedCompleteLogAndInvalidDirectoryDoNotBlockHealthyRun() {
        val store = repository()
        store.createRun(record("a-damaged")); store.createRun(record("z-healthy"))
        log("a-damaged").writeText("invalid complete record\n")
        val before = log("a-damaged").readBytes()
        File(temporary.root, "runs/b-not-a-directory").writeText("unrelated evidence")
        val error = failure { store.recoverInterruptedRuns() }
        assertTrue(error.message!!.contains("2 beschadigd"))
        assertArrayEquals(before, log("a-damaged").readBytes())
        assertEquals("unrelated evidence", File(temporary.root, "runs/b-not-a-directory").readText())
        assertEquals(RunStatus.RUNNING, store.run("a-damaged")!!.status)
        assertEquals(RunStatus.INTERRUPTED, store.run("z-healthy")!!.status)
    }

    @Test fun recoveryClampsEndTimeAfterClockRollbackAndPreservesObservedTime() {
        val store = repository()
        val futureStart = System.currentTimeMillis() + 86_400_000L
        store.createRun(record().copy(startedAtMs = futureStart))
        assertEquals(1, store.recoverInterruptedRuns())
        assertEquals(futureStart, store.run("run-a")!!.endedAtMs)
        val recovered = store.events("run-a").single()
        assertTrue(recovered.atEpochMs < futureStart)
        assertEquals("true", recovered.details["wallClockAdjusted"])
        assertEquals(recovered.atEpochMs.toString(), recovered.details["observedWallTimeMs"])
        assertEquals(futureStart.toString(), recovered.details["endedAtMs"])
    }

    @Test fun oversizedAndInvalidUtf8LogLinesFailBoundedly() {
        val store = repository(); store.createRun(record())
        log().writeBytes(byteArrayOf(0xc3.toByte(), 0x28, 10))
        failure { store.events("run-a") }
        log().writeText("x".repeat(LabRepository.MAX_EVENT_BYTES + 1))
        failure { store.recoverInterruptedRuns() }
    }

    @Test fun logSequenceCorruptionIsDetectedOnReopen() {
        val store = repository(); store.createRun(record()); store.appendEvent("run-a", event(1))
        log().appendText(LabRepository.json.encodeToString(event(1)) + "\n")
        failure { LabRepository(temporary.root).summary("run-a") }
    }

    @Test fun terminalRunCorruptionCannotBeSilentlyRepaired() {
        val store = repository(); store.createRun(record()); store.finishRun("run-a", RunStatus.COMPLETED, 2000)
        log().appendText("partial")
        assertEquals(0, store.recoverInterruptedRuns())
        failure { store.exportBundle("run-a", ByteArrayOutputStream()) }
    }

    @Test fun allBundleHashesAndLengthsMatchActualMembers() {
        val store = repository(); store.createRun(record()); store.appendEvent("run-a", event(1)); store.finishRun("run-a", RunStatus.COMPLETED, 5000)
        val entries = bundle(store)
        assertEquals(setOf("run.json", "profile.json", "plan.jsonl", "events.jsonl", "events.csv", "report.html", "manifest.json"), entries.keys)
        val manifest = Json.parseToJsonElement(entries["manifest.json"]!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals(1, manifest["eventCount"]!!.jsonPrimitive.long)
        assertFalse(manifest["actualRfPacketsVerified"]!!.jsonPrimitive.boolean)
        for (member in manifest["members"]!!.jsonArray) {
            val m = member.jsonObject; val bytes = entries[m["name"]!!.jsonPrimitive.content]!!
            assertEquals(bytes.size.toLong(), m["bytes"]!!.jsonPrimitive.long)
            assertEquals(sha256(bytes), m["sha256"]!!.jsonPrimitive.content)
        }
        assertEquals(sha256(entries["profile.json"]!!), manifest["profileSha256"]!!.jsonPrimitive.content)
        assertArrayEquals(log().readBytes(), entries["events.jsonl"])
    }

    @Test fun bundleContainsWholePlanEvenWhenStoppedEarly() {
        val store = repository(); store.createRun(record()); store.appendEvent("run-a", event(1)); store.finishRun("run-a", RunStatus.STOPPED, 2000)
        val entries = bundle(store)
        assertEquals(4, entries["plan.jsonl"]!!.toString(Charsets.UTF_8).trim().lines().size)
        assertEquals(1, entries["events.jsonl"]!!.toString(Charsets.UTF_8).trim().lines().size)
        assertTrue(entries["report.html"]!!.toString(Charsets.UTF_8).contains("eerder is gestopt"))
    }

    @Test fun exportingEmptyActiveRunIsExplicitSnapshot() {
        val store = repository(); store.createRun(record())
        val entries = bundle(store)
        val manifest = Json.parseToJsonElement(entries["manifest.json"]!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals("RUNNING", manifest["runStatusAtExport"]!!.jsonPrimitive.content)
        assertEquals(0, entries["events.jsonl"]!!.size)
        assertTrue(entries["report.html"]!!.toString(Charsets.UTF_8).contains("Lopend"))
    }

    @Test fun exportDoesNotCloseCallerOwnedStreamOrMutateEvidence() {
        val store = repository(); store.createRun(record()); store.appendEvent("run-a", event(1))
        val before = log().readBytes()
        val stream = object : ByteArrayOutputStream() { var closed = false; override fun close() { closed = true; super.close() } }
        store.exportBundle("run-a", stream); assertFalse(stream.closed)
        store.exportCsv("run-a", stream); assertFalse(stream.closed)
        assertArrayEquals(before, log().readBytes())
        assertEquals(RunStatus.RUNNING, store.run("run-a")!!.status)
    }

    @Test fun failedExportKeepsSourceHistoryUntouched() {
        val store = repository(); store.createRun(record()); store.appendEvent("run-a", event(1))
        val before = log().readBytes()
        val broken = object : OutputStream() { override fun write(value: Int) { throw IOException("disk full") } }
        failure { store.exportBundle("run-a", broken) }
        assertArrayEquals(before, log().readBytes())
        assertEquals(1, store.summary("run-a").eventCount)
    }

    @Test fun appendDuringExportDoesNotCrossCapturedWatermarkOrBlockWriter() {
        val store = repository(); store.createRun(record()); store.appendEvent("run-a", event(1))
        val other = LabRepository(temporary.root)
        val output = ByteArrayOutputStream()
        var appended = false
        val intercept = object : OutputStream() {
            override fun write(value: Int) {
                if (!appended) { appended = true; other.appendEvent("run-a", event(2)) }
                output.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (!appended) { appended = true; other.appendEvent("run-a", event(2)) }
                output.write(bytes, offset, length)
            }
        }
        store.exportBundle("run-a", intercept)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
        }
        assertEquals(1, entries["events.jsonl"]!!.toString(Charsets.UTF_8).trim().lines().size)
        val manifest = Json.parseToJsonElement(entries["manifest.json"]!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals(1, manifest["eventCount"]!!.jsonPrimitive.long)
        assertEquals(2, store.summary("run-a").eventCount)
    }

    @Test fun csvProtectsFormulasEvenAfterWhitespaceControlsAndBom() {
        for (value in listOf("=1+1", "+CMD", "-2+3", "@SUM(A1)", " \t=HYPERLINK()", "\uFEFF=1", "\r\n@x")) {
            assertTrue(value, EvidenceExport.csvCell(value).startsWith("\"'"))
        }
        assertEquals("\"abc\"", EvidenceExport.csvCell("abc"))
        assertEquals("\"a\"\"b\nc\"", EvidenceExport.csvCell("a\"b\nc"))
    }

    @Test fun csvAndJsonPreserveDifferentIntendedRepresentations() {
        val store = repository(); store.createRun(record()); store.appendEvent("run-a", event(1, message = "=1+1"))
        val entries = bundle(store)
        assertTrue(entries["events.csv"]!!.toString(Charsets.UTF_8).contains("\"'=1+1\""))
        val original = LabRepository.json.decodeFromString<RunEvent>(entries["events.jsonl"]!!.toString(Charsets.UTF_8).trim())
        assertEquals("=1+1", original.message)
    }

    @Test fun reportEscapesUntrustedTextAndHasNoExecutableResources() {
        val store = repository()
        val p = profile().copy(name = "<script>alert('x')</script>", description = "<img src=x onerror=alert(1)>")
        store.createRun(record(profile = p).copy(deviceInfo = "<iframe src='https://example.com'>"))
        store.appendEvent("run-a", event(1, message = "<svg onload=alert(2)>").copy(details = mapOf("x" to "</td><script>")))
        val report = store.reportHtml("run-a", "nl")
        assertFalse(report.contains("<script>")); assertFalse(report.contains("<iframe")); assertFalse(report.contains("<img"))
        assertTrue(report.contains("&lt;script&gt;")); assertTrue(report.contains("default-src 'none'"))
        assertTrue(report.contains("geen lokale ontvangstmeting") || report.contains("geen ontvangstgrafiek"))
    }

    @Test fun reportShowsOnlyLastHundredEventsWhileBundleKeepsAll() {
        val store = repository(); store.createRun(record())
        (1L..105).forEach { store.appendEvent("run-a", event(it, message = "unique-event-$it-end")) }
        val report = store.reportHtml("run-a", "nl")
        assertFalse(report.contains("unique-event-1-end")); assertTrue(report.contains("unique-event-105-end"))
        assertTrue(report.contains("Laatste 100 van 105"))
        assertEquals(105, bundle(store)["events.jsonl"]!!.toString(Charsets.UTF_8).trim().lines().size)
    }

    @Test fun reportChartContainsOnlyValidReportedRssiCandidates() {
        val store = repository()
        val p = profile().copy(tracks = listOf(TagTrack(id = "repeater", kind = PayloadKind.REPEATER)))
        store.createRun(record(profile = p))
        val html = store.reportHtml("run-a", "nl")
        assertTrue(html.contains("<svg")); assertTrue(html.contains("Geplande gerapporteerde RSSI"))
        assertTrue(html.contains("geen lokale ontvangstmetingen"))
    }

    @Test fun malformedProfileOnDiskIsReportedRatherThanSilentlyHidden() {
        val store = repository(); store.saveProfile(profile())
        File(temporary.root, "profiles/test-profile.json").writeText("{}")
        failure { store.profiles() }
    }

    @Test fun runMetadataFilenameMismatchAndInconsistentStatusAreRejected() {
        val store = repository(); store.createRun(record())
        val meta = File(temporary.root, "runs/run-a/run.json")
        meta.writeText(LabRepository.json.encodeToString(record("wrong-id")))
        failure { store.run("run-a") }
        meta.writeText(LabRepository.json.encodeToString(record().copy(status = RunStatus.COMPLETED)))
        failure { store.run("run-a") }
    }

    @Test fun listingIgnoresUnpublishedAtomicStagingDirectories() {
        val store = repository()
        File(temporary.root, "runs/.create-abandoned").mkdir()
        File(temporary.root, "profiles/.write-abandoned.tmp").writeText("partial")
        assertTrue(store.runs().isEmpty()); assertTrue(store.profiles().isEmpty())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

