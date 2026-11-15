package org.anthrobios.vespasim.core.capture

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.anthrobios.vespasim.core.RunStatus
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.*

class CaptureRepositoryTest {
    private fun record(id: String = UUID.randomUUID().toString()) = CaptureRecord(id, "Nano test", "AA:BB:CC:DD:EE:FF", 1000, 100)
    private fun observation(sequence: Long, rssi: Int = -65) = CaptureObservation(sequence, 1000 + sequence, 100 + sequence, "AA:BB:CC:DD:EE:FF", rssi, "020106", "Nano")
    private fun repository(block: (File, CaptureRepository) -> Unit) {
        val root = Files.createTempDirectory("capture-test-").toFile()
        return try { block(root, CaptureRepository(root)) } finally { root.deleteRecursively() }
    }
    private fun finish(repo: CaptureRepository, id: String) = repo.finish(id, RunStatus.COMPLETED, 2000)
    private fun zip(repo: CaptureRepository, id: String): ByteArray = ByteArrayOutputStream().also { repo.exportZip(id, it) }.toByteArray()
    private fun unzip(bytes: ByteArray): MutableMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) { val next = zip.nextEntry ?: break; entries[next.name] = zip.readBytes(); zip.closeEntry() } }
        return entries
    }
    private fun rezip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()

    @Test fun appendsAreDurableSelectedAndStrictlySequenced() = repository { root, repo ->
        val record = record(); repo.create(record)
        repo.appendObservation(record.id, observation(1, 127))
        repo.appendObservation(record.id, observation(2).copy(timestampNanos = 0))
        repo.appendEvent(record.id, CaptureEvent(1, 1001, "started"))
        assertEquals(2L, CaptureRepository(root).count(record.id))
        assertEquals(127, repo.observations(record.id).first().rssiRaw)
        assertEquals(0L, repo.observations(record.id).last().timestampNanos)
        assertFails { repo.appendObservation(record.id, observation(2)) }
        assertFails { repo.appendObservation(record.id, observation(4)) }
        assertFails { repo.appendObservation(record.id, observation(3).copy(address = "00:00:00:00:00:00")) }
        assertFails { repo.appendEvent(record.id, CaptureEvent(3, 1002, "gap")) }
        assertEquals(2L, repo.summary(record.id).lastObservationSequence)
        finish(repo, record.id); finish(repo, record.id)
        assertFails { repo.appendObservation(record.id, observation(3)) }
        assertFails { repo.finish(record.id, RunStatus.INTERRUPTED, 2000) }
    }
    @Test fun paginationCrossesSparseIndexBoundaries() = repository { _, repo ->
        val record = record(); repo.create(record)
        for (n in 1L..600) repo.appendObservation(record.id, observation(n))
        assertEquals((251L..270).toList(), repo.observations(record.id, 250, 20).map { it.sequence })
        assertEquals(listOf(600L), repo.observations(record.id, 599, 10).map { it.sequence })
        assertTrue(repo.observations(record.id, Long.MAX_VALUE).isEmpty())
        assertFails { repo.observations(record.id, -1) }; assertFails { repo.observations(record.id, 0, 2001) }
        assertEquals(600L, repo.count(record.id))
    }
    @Test fun metadataEditsPreserveEvidenceAndCrudUsesTrash() = repository { root, repo ->
        val record = record(); repo.create(record); repo.appendObservation(record.id, observation(1))
        val evidence = File(root, "records/${record.id}/observations.jsonl").readBytes()
        assertFails { repo.trash(record.id) }; assertFails { repo.purge(record.id) }
        repo.rename(record.id, "Nieuwe naam"); repo.updateNotes(record.id, "Notities", "iphone")
        assertEquals("iphone", repo.get(record.id)!!.tagVariant)
        finish(repo, record.id)
        repo.trash(record.id)
        assertNull(repo.get(record.id)); assertEquals(1, repo.page(trashed = true).total)
        assertEquals("Nieuwe naam", repo.get(record.id, true)!!.name)
        repo.restore(record.id)
        assertContentEquals(evidence, File(root, "records/${record.id}/observations.jsonl").readBytes())
        assertEquals(1, File(root, "records/${record.id}/origins").list()!!.size)
        repo.trash(record.id); repo.purge(record.id)
        assertEquals(0, repo.page(trashed = true).total)
    }
    @Test fun sessionPagesAreStableAndCorruptMetadataDoesNotHideHealthyRecords() = repository { root, repo ->
        for (n in 1..6) repo.create(record("capture-$n").copy(startedAtMs = n.toLong()))
        assertEquals(listOf("capture-4", "capture-3"), repo.page(2, 2).records.map { it.id })
        File(root, "records/capture-6/capture.json").writeText("bad")
        val page = repo.page(); assertEquals(5, page.total); assertEquals(1, page.warnings.size)
    }
    @Test fun truncatedLastLineIsPreservedThenRecoveredWithoutAutoResume() = repository { root, repo ->
        val record = record(); repo.create(record); repo.appendObservation(record.id, observation(1))
        val file = File(root, "records/${record.id}/observations.jsonl")
        val tail = "{\"sequence\":2,\"rawHex\":\"".toByteArray()
        file.appendBytes(tail)
        assertFails { repo.count(record.id) }
        val restarted = CaptureRepository(root)
        assertEquals(1, restarted.recoverInterruptedCaptures())
        assertEquals(RunStatus.INTERRUPTED, restarted.get(record.id)!!.status)
        assertEquals(1L, restarted.count(record.id))
        assertContentEquals(tail, File(root, "records/${record.id}/recovery").listFiles()!!.single().readBytes())
        assertEquals("capture_interrupted", restarted.events(record.id).single().kind)
        assertEquals(0, restarted.recoverInterruptedCaptures())
        assertFails { restarted.appendObservation(record.id, observation(2)) }
    }
    @Test fun malformedCompleteLineIsNotDiscardedAndIndependentCaptureRecovers() = repository { root, repo ->
        val broken = record("broken"); val healthy = record("healthy")
        repo.create(broken); repo.create(healthy)
        val file = File(root, "records/broken/observations.jsonl").also { it.writeText("{bad}\n") }
        assertFailsWith<IOException> { repo.recoverInterrupted() }
        assertEquals("{bad}\n", file.readText())
        assertEquals(RunStatus.RUNNING, repo.get(broken.id)!!.status)
        assertEquals(RunStatus.INTERRUPTED, repo.get(healthy.id)!!.status)
    }
    @Test fun pathTraversalAndSymlinksAndClobberAreRejected() = repository { root, repo ->
        assertFails { repo.create(record("../oops")) }; assertFails { repo.get("../oops") }
        val record = record(); repo.create(record); finish(repo, record.id)
        assertFails { repo.create(record) }
        val export = File(root, "existing.zip").also { it.writeText("keep") }
        assertFails { repo.exportZip(record.id, export) }; assertEquals("keep", export.readText())
        val outside = File(root, "outside").also { it.mkdir() }
        val link = File(root, "linked")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertFails { CaptureRepository(link) }
        val observations = File(root, "records/${record.id}/observations.jsonl")
        observations.delete(); Files.createSymbolicLink(observations.toPath(), export.toPath())
        assertFails { repo.observations(record.id) }
    }
    @Test fun multipleRepositoryInstancesSerializeDuplicateSequenceAndCreation() = repository { root, repo ->
        val record = record(); repo.create(record)
        val other = CaptureRepository(root)
        val ready = CountDownLatch(2); val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(repo, other).map { current -> executor.submit<Boolean> { ready.countDown(); go.await(); runCatching { current.appendObservation(record.id, observation(1)) }.isSuccess } }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown()
            assertEquals(1, results.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1L, repo.count(record.id))
        } finally { executor.shutdownNow() }
    }
    @Test fun exportIncludesOriginalEvidenceHashesEscapedHtmlAndSafeCsv() = repository { root, repo ->
        val record = record().copy(name = "<script>alert(1)</script>", notes = "<img src=x>")
        repo.create(record); repo.appendObservation(record.id, observation(1).copy(deviceName = "=HYPERLINK(\"evil\")")); finish(repo, record.id)
        val bytes = zip(repo, record.id); val entries = unzip(bytes)
        assertContentEquals(File(root, "records/${record.id}/observations.jsonl").readBytes(), entries["observations.jsonl"])
        assertTrue(entries.keys.containsAll(listOf("capture.json", "events.jsonl", "observations.csv", "report.html", "manifest.json")))
        val html = entries.getValue("report.html").toString(Charsets.UTF_8)
        assertFalse(html.contains("<script>")); assertTrue(html.contains("&lt;img src=x&gt;"))
        assertTrue(entries.getValue("observations.csv").toString(Charsets.UTF_8).contains("'=HYPERLINK"))
        repo.previewImport(ByteArrayInputStream(bytes)).use { preview -> assertEquals(1L, preview.observationCount) }
    }
    @Test fun exportSnapshotRemainsConsistentDuringAppendAndPurge() = repository { _, repo ->
        val record = record(); repo.create(record); repo.appendObservation(record.id, observation(1))
        var changed = false
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (!changed) { changed = true; repo.appendObservation(record.id, observation(2)); finish(repo, record.id); repo.trash(record.id); repo.purge(record.id) }
                super.write(bytes, offset, length)
            }
        }
        repo.exportZip(record.id, output)
        repo.previewImport(ByteArrayInputStream(output.toByteArray())).use { preview ->
            assertEquals(1L, preview.observationCount)
            assertEquals(RunStatus.RUNNING, preview.record.status)
            val imported = repo.commitImport(preview)
            assertEquals(RunStatus.INTERRUPTED, imported.status)
        }
    }
    @Test fun localizedReportsPreserveOriginalArchiveEvidenceAndImportIntegrity() = repository { root, repo ->
        val record = record().copy(notes = "Oorspronkelijke notitie <img src=x>", deviceInfo = "Original device")
        repo.create(record)
        repo.appendObservation(record.id, observation(1, 127))
        repo.appendObservation(record.id, observation(2, -65))
        repo.appendEvent(record.id, CaptureEvent(1, 1001, "capture_started", "Oorspronkelijk bericht"))
        finish(repo, record.id)
        val baseline = unzip(zip(repo, record.id))
        val headings = mapOf("en" to "Received signal", "nl" to "Ontvangen signaal", "fr" to "Signal reçu")
        headings.forEach { (language, heading) ->
            val output = ByteArrayOutputStream()
            repo.exportZip(record.id, output, language)
            val entries = unzip(output.toByteArray())
            val html = entries.getValue("report.html").toString(Charsets.UTF_8)
            assertTrue(html.contains("<html lang=\"$language\">"))
            assertTrue(html.contains(heading))
            assertTrue(html.contains("Oorspronkelijke notitie &lt;img src=x&gt;"))
            assertFalse(html.contains("<img src=x>"))
            assertEquals(repo.reportHtml(record.id, language), html)
            for (name in listOf("capture.json", "observations.jsonl", "events.jsonl", "observations.csv")) {
                assertContentEquals(baseline.getValue(name), entries.getValue(name), "$language changed $name")
            }
            repo.previewImport(ByteArrayInputStream(output.toByteArray())).use { preview ->
                assertEquals(2L, preview.observationCount)
                assertEquals(1L, preview.eventCount)
                assertEquals(record.notes, preview.record.notes)
            }
        }
        assertContentEquals(baseline.getValue("observations.jsonl"), File(root, "records/${record.id}/observations.jsonl").readBytes())
    }
    @Test fun reportLanguageDefaultsToEnglishAndNormalizesRegionalVariants() = repository { _, repo ->
        val record = record(); repo.create(record); finish(repo, record.id)
        val english = repo.reportHtml(record.id)
        assertTrue(english.contains("<html lang=\"en\">"))
        assertEquals(english, repo.reportHtml(record.id, "unsupported"))
        assertEquals(repo.reportHtml(record.id, "fr"), repo.reportHtml(record.id, "FR_be"))
        assertEquals(repo.reportHtml(record.id, "nl"), repo.reportHtml(record.id, "nl-BE"))
    }
    @Test fun previewDoesNotSaveAndCommitCreatesNewIdWithSourceEvidence() = repository { root, repo ->
        val record = record(); repo.create(record); repo.appendObservation(record.id, observation(1)); finish(repo, record.id)
        val bytes = zip(repo, record.id)
        val preview = repo.previewImport(ByteArrayInputStream(bytes))
        assertEquals(1, repo.page().total)
        val imported = repo.commitImport(preview)
        assertNotEquals(record.id, imported.id); assertEquals(record.id, imported.importedFromId)
        assertEquals(CaptureArchives.sha256(bytes), imported.importedArchiveSha256)
        assertEquals(2, repo.page().total)
        assertContentEquals(File(root, "records/${record.id}/observations.jsonl").readBytes(), File(root, "records/${imported.id}/observations.jsonl").readBytes())
        assertFails { repo.commitImport(preview) }
        assertFalse(preview.directory.exists())
        repo.previewImport(ByteArrayInputStream(zip(repo, imported.id))).use { assertEquals(imported.id, it.record.id) }
    }
    @Test fun cancelledAndOrphanPreviewsAreCleanedButLiveReviewSurvivesRecovery() = repository { root, repo ->
        val record = record(); repo.create(record); finish(repo, record.id)
        val preview = repo.previewImport(ByteArrayInputStream(zip(repo, record.id)))
        val orphan = File(root, ".imports/${UUID.randomUUID()}").also { it.mkdir(); File(it, "partial.zip").writeText("partial") }
        val unrelated = File(root, ".imports/unknown").also { it.mkdir() }
        repo.recoverInterrupted()
        assertFalse(orphan.exists()); assertTrue(preview.directory.exists()); assertTrue(unrelated.exists())
        preview.close(); assertFalse(preview.directory.exists())
    }
    @Test fun corruptedHashesAndTruncatedZipAndUnsupportedPathsAreRejected() = repository { _, repo ->
        val record = record(); repo.create(record); repo.appendObservation(record.id, observation(1)); finish(repo, record.id)
        val original = zip(repo, record.id)
        val changed = unzip(original); changed["observations.jsonl"] = changed.getValue("observations.jsonl").toString(Charsets.UTF_8).replace("-65", "-66").toByteArray()
        assertFails { repo.previewImport(ByteArrayInputStream(rezip(changed))) }
        assertFails { repo.previewImport(ByteArrayInputStream(original.copyOf(original.size - 30))) }
        val traversal = unzip(original).also { it["../escape"] = byteArrayOf(1) }
        assertFails { repo.previewImport(ByteArrayInputStream(rezip(traversal))) }
        val unknown = unzip(original).also { it["surprise.txt"] = byteArrayOf(1) }
        assertFails { repo.previewImport(ByteArrayInputStream(rezip(unknown))) }
    }
    @Test fun duplicateManifestFieldsAndChangedStagedFilesAreRejected() = repository { _, repo ->
        val record = record(); repo.create(record); finish(repo, record.id)
        val original = zip(repo, record.id)
        val changed = unzip(original)
        changed["manifest.json"] = changed.getValue("manifest.json").toString(Charsets.UTF_8).replaceFirst("{", "{\"schemaVersion\":1,").toByteArray()
        assertFails { repo.previewImport(ByteArrayInputStream(rezip(changed))) }
        repo.previewImport(ByteArrayInputStream(original)).use { preview ->
            File(preview.directory, "files/events.jsonl").appendText("bad")
            assertFails { repo.commitImport(preview) }
            assertEquals(1, repo.page().total)
        }
    }
    @Test fun importRejectsOversizedCompressedMemberBeforeUnboundedAllocation() = repository { _, repo ->
        val bomb = ByteArrayOutputStream()
        ZipOutputStream(bomb).use { zip -> zip.putNextEntry(ZipEntry("capture.json")); repeat(70) { zip.write(ByteArray(1024)) }; zip.closeEntry() }
        assertFails { repo.previewImport(ByteArrayInputStream(bomb.toByteArray())) }
    }
    private fun repairManifest(entries: MutableMap<String, ByteArray>) {
        val manifest = Json.decodeFromString<CaptureManifest>(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        val repaired = manifest.copy(members = manifest.members.map { member ->
            val bytes = entries.getValue(member.name)
            CaptureMember(member.name, bytes.size.toLong(), CaptureArchives.sha256(bytes))
        })
        entries["manifest.json"] = Json { encodeDefaults = true }.encodeToString(repaired).toByteArray()
    }
    @Test fun rehashedArchivesStillRequireValidSelectedEvidenceAndSchema() = repository { _, repo ->
        val record = record(); repo.create(record); repo.appendObservation(record.id, observation(1)); finish(repo, record.id)
        val original = unzip(zip(repo, record.id))
        val intact = original.toMutableMap(); repairManifest(intact)
        repo.previewImport(ByteArrayInputStream(rezip(intact))).use { assertEquals(1L, it.observationCount) }
        val observation = original.getValue("observations.jsonl").toString(Charsets.UTF_8)
        val badLines = listOf(
            observation.replaceFirst("\"sequence\":1", "\"sequence\":2"),
            observation.replace("AA:BB:CC:DD:EE:FF", "00:00:00:00:00:00"),
            observation.trimEnd(),
            observation.replaceFirst("{", "{\"sequence\":1,"),
            observation.replaceFirst("\"sequence\":1", "\"sequence\":\"1\""),
            " ".repeat(CaptureCodec.MAX_OBSERVATION_BYTES + 1) + "\n",
        )
        for (bad in badLines) {
            val entries = original.toMutableMap(); entries["observations.jsonl"] = bad.toByteArray()
            repairManifest(entries)
            assertFails { repo.previewImport(ByteArrayInputStream(rezip(entries))) }
        }
        val invalidUnicode = original.toMutableMap(); invalidUnicode["observations.jsonl"] = byteArrayOf(0xff.toByte(), 10)
        repairManifest(invalidUnicode)
        assertFails { repo.previewImport(ByteArrayInputStream(rezip(invalidUnicode))) }
        val unknownSchema = original.toMutableMap()
        unknownSchema["capture.json"] = unknownSchema.getValue("capture.json").toString(Charsets.UTF_8).replace("\"schemaVersion\":1", "\"schemaVersion\":2").toByteArray()
        repairManifest(unknownSchema)
        assertFails { repo.previewImport(ByteArrayInputStream(rezip(unknownSchema))) }
        assertEquals(1, repo.page().total)
    }
    @Test fun quotedManifestCountersAndDuplicateZipMembersAreRejected() = repository { _, repo ->
        val record = record(); repo.create(record); finish(repo, record.id)
        val original = unzip(zip(repo, record.id))
        val quoted = original.toMutableMap()
        quoted["manifest.json"] = quoted.getValue("manifest.json").toString(Charsets.UTF_8).replace("\"observationCount\":0", "\"observationCount\":\"0\"").toByteArray()
        assertFails { repo.previewImport(ByteArrayInputStream(rezip(quoted))) }
        val entries = original.toMutableMap(); entries["capturx.json"] = entries.getValue("capture.json")
        val bytes = rezip(entries)
        val from = "capturx.json".toByteArray(); val to = "capture.json".toByteArray()
        for (index in 0..bytes.size - from.size) if (from.indices.all { bytes[index + it] == from[it] }) to.copyInto(bytes, index)
        assertFails { repo.previewImport(ByteArrayInputStream(bytes)) }
    }
    @Test fun boundedLineReaderHandlesExactLimitAndUnterminatedTail() {
        val root = Files.createTempDirectory("capture-lines-").toFile()
        try {
            val file = File(root, "line")
            file.writeBytes("12345678\nnext".toByteArray())
            RandomAccessFile(file, "r").use { input ->
                val first = CaptureRepository.readLine(input, 8)!!
                assertEquals("12345678", first.bytes.toString(Charsets.UTF_8)); assertTrue(first.complete)
                val last = CaptureRepository.readLine(input, 8)!!
                assertEquals("next", last.bytes.toString(Charsets.UTF_8)); assertFalse(last.complete)
            }
            file.writeText("123456789\n")
            RandomAccessFile(file, "r").use { input -> assertFails { CaptureRepository.readLine(input, 8) } }
        } finally { root.deleteRecursively() }
    }

    @Test fun oldRunAndProfileFoldersAreUntouched() = repository { root, repo ->
        val old = File(root, "old-runs").also { it.mkdir() }; val evidence = File(old, "legacy.json").also { it.writeText("old immutable evidence") }
        val record = record(); repo.create(record); repo.recoverInterrupted()
        assertEquals("old immutable evidence", evidence.readText())
    }
}

