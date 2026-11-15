package org.anthrobios.vespasim.core.capture

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.anthrobios.vespasim.core.RunStatus
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.anthrobios.vespasim.core.capture.CaptureRepository.Companion.child
import org.anthrobios.vespasim.core.capture.CaptureRepository.Companion.safeRelative

/** A fully verified private staging copy. Closing cancels the preview and removes its files. */
class CaptureImportPreview internal constructor(
    val record: CaptureRecord,
    val observationCount: Long,
    val eventCount: Long,
    val archiveSha256: String,
    internal val repositoryRoot: File,
    internal val directory: File,
    internal val manifest: CaptureManifest,
) : Closeable {
    internal var closed = false
    @Synchronized override fun close() {
        if (!closed) {
            CaptureRepository.deleteOwnedTree(directory)
            CaptureArchives.releasePreview(directory)
            closed = true
        }
    }
}

@Serializable internal data class CaptureManifest(
    val format: String = "vespa-android-capture",
    val schemaVersion: Int = 1,
    val captureId: String,
    val observationCount: Long,
    val eventCount: Long,
    val members: List<CaptureMember>,
)
@Serializable internal data class CaptureMember(val name: String, val bytes: Long, val sha256: String)

internal object CaptureArchives {
    private const val MAX_ARCHIVE_BYTES = 160L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 384L * 1024 * 1024
    private const val MAX_MEMBERS = 70
    private val activePreviews = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    internal fun releasePreview(directory: File) { activePreviews.remove(directory.path) }
    /** Called under repository lock at startup. A live in-process review is never removed. */
    internal fun cleanOrphanPreviews(repository: CaptureRepository) {
        val parent = child(repository.root, ".imports")
        java.nio.file.Files.newDirectoryStream(parent.toPath()).use { paths ->
            for (path in paths) {
                val name = path.fileName.toString()
                if (!Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(name)) continue
                val directory = child(parent, name)
                if (directory.path !in activePreviews) CaptureRepository.deleteOwnedTree(directory)
            }
        }
    }
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val originPattern = Regex("origins/[0-9a-f]{64}\\.json")
    private val recoveryPattern = Regex("recovery/partial-tail-(observations|events)-[0-9a-fA-F-]{36}\\.bin")

    fun exportZip(repository: CaptureRepository, id: String, output: OutputStream, language: String = "en") {
        repository.snapshot(id).use { snapshot ->
            val members = mutableListOf<CaptureMember>()
            val zip = ZipOutputStream(NonClosingOutput(output))
            try {
                fun entry(name: String, producer: (OutputStream) -> Unit) {
                    zip.putNextEntry(ZipEntry(name).also { it.time = snapshot.record.startedAtMs })
                    val checked = DigestOutput(zip, memberLimit(name))
                    producer(checked)
                    checked.flush()
                    zip.closeEntry()
                    members += CaptureMember(name, checked.bytes, checked.hash())
                }
                entry("capture.json") { it.write(CaptureCodec.encodeRecord(snapshot.record).toByteArray(Charsets.UTF_8)) }
                for ((name, file) in snapshot.files.toSortedMap()) entry(name) { file.input().copyTo(it) }
                entry("observations.csv") { writeCsv(snapshot, it) }
                entry("report.html") { it.write(html(snapshot, language).toByteArray(Charsets.UTF_8)) }
                val manifest = CaptureManifest(captureId = snapshot.record.id, observationCount = snapshot.observationCount, eventCount = snapshot.eventCount, members = members)
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.finish()
                zip.flush()
            } finally { zip.close() }
        }
    }
    fun exportCsv(repository: CaptureRepository, id: String, output: OutputStream) = repository.snapshot(id).use { writeCsv(it, output) }
    fun reportHtml(repository: CaptureRepository, id: String, language: String = "en"): String = repository.snapshot(id).use { html(it, language) }

    fun preview(repository: CaptureRepository, input: InputStream): CaptureImportPreview {
        val directory = repository.locked {
            val imports = child(repository.root, ".imports")
            // A small bounded number of simultaneous reviews; canceled previews should be closed.
            val count = java.nio.file.Files.newDirectoryStream(imports.toPath()).use { it.count() }
            require(count < 8) { "Sluit open importvoorbeelden voor een nieuwe import" }
            child(imports, UUID.randomUUID().toString()).also { check(it.mkdir()); activePreviews.add(it.path) }
        }
        try {
            val archive = child(directory, "input.zip")
            val archiveHash: String
            FileOutputStream(archive).use { output ->
                val checked = DigestOutput(output, MAX_ARCHIVE_BYTES)
                input.copyTo(checked)
                checked.flush()
                output.fd.sync()
                archiveHash = checked.hash()
            }
            // Require a complete central directory too: ZipInputStream alone accepts truncated ZIPs.
            val centralNames = mutableSetOf<String>()
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    require(!entry.isDirectory && centralNames.size < MAX_MEMBERS && centralNames.add(entry.name)) { "Ongeldige ZIP-directory" }
                    require(entry.size in 0..memberLimit(entry.name)) { "ZIP-item te groot" }
                }
            }
            val extracted = child(directory, "files").also { check(it.mkdir()) }
            val actual = mutableMapOf<String, CaptureMember>()
            var expanded = 0L
            ZipInputStream(FileInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name !in actual && actual.size < MAX_MEMBERS) { "Dubbele, directory- of te veel ZIP-items" }
                    val maximum = memberLimit(entry.name)
                    require(entry.size < 0 || entry.size <= maximum) { "ZIP-item te groot" }
                    val target = safeRelative(extracted, entry.name)
                    target.parentFile.mkdirs()
                    require(target.createNewFile()) { "Dubbel ZIP-doel" }
                    val digest: DigestOutput
                    FileOutputStream(target).use { output ->
                        digest = DigestOutput(output, maximum)
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            expanded += read
                            require(expanded <= MAX_EXPANDED_BYTES) { "Uitgepakt archief te groot" }
                            digest.write(buffer, 0, read)
                        }
                        digest.flush()
                        output.fd.sync()
                    }
                    zip.closeEntry() // validates CRC
                    actual[entry.name] = CaptureMember(entry.name, digest.bytes, digest.hash())
                }
            }
            require(actual.keys == centralNames) { "Lokale ZIP-items en directory verschillen" }
            val manifestFile = child(extracted, "manifest.json")
            require(manifestFile.isFile) { "Archiefmanifest ontbreekt" }
            val manifestText = CaptureRepository.strictUtf8(manifestFile.readBytes())
            CaptureCodec.checkJson(manifestText)
            validateManifestTypes(manifestText)
            val manifest = json.decodeFromString<CaptureManifest>(manifestText)
            validateManifest(manifest, actual.filterKeys { it != "manifest.json" })
            val record = CaptureCodec.decodeRecord(CaptureRepository.strictUtf8(child(extracted, "capture.json").readBytes()))
            require(record.id == manifest.captureId) { "Manifest en opname-ID verschillen" }
            val observations = CaptureRepository.buildIndex(child(extracted, "observations.jsonl"), record.address)
            val events = CaptureRepository.buildIndex(child(extracted, "events.jsonl"), null)
            require(!observations.incompleteTail && !events.incompleteTail) { "Archief bevat een onvolledige logregel" }
            require(observations.count == manifest.observationCount && events.count == manifest.eventCount) { "Recordaantallen verschillen van manifest" }
            val originName = "origins/${actual.getValue("capture.json").sha256}.json"
            val evidenceAttachments = actual.keys.count { it.startsWith("origins/") || it.startsWith("recovery/") }
            require(evidenceAttachments + (if (originName in actual) 0 else 1) <= 64) { "Te veel bronbijlagen om nieuwe importherkomst te bewaren" }
            for (name in actual.keys.filter { it.startsWith("origins/") }) {
                val bytes = safeRelative(extracted, name).readBytes()
                require(name == "origins/${sha256(bytes)}.json") { "Bronmetadatahash verschilt" }
                CaptureCodec.decodeRecord(CaptureRepository.strictUtf8(bytes))
            }
            return CaptureImportPreview(record, observations.count, events.count, archiveHash, repository.root, directory, manifest)
        } catch (e: Exception) {
            CaptureRepository.deleteOwnedTree(directory)
            releasePreview(directory)
            throw e
        }
    }
    fun commit(repository: CaptureRepository, preview: CaptureImportPreview): CaptureRecord = synchronized(preview) {
        require(!preview.closed && preview.repositoryRoot == repository.root) { "Importvoorbeeld is gesloten of behoort bij andere opslag" }
        val extracted = child(preview.directory, "files")
        // The review's exact verified bytes must still be present before publishing anything.
        for (member in preview.manifest.members) {
            val file = safeRelative(extracted, member.name)
            require(file.isFile && file.length() == member.bytes && sha256(file) == member.sha256) { "Importvoorbeeld is gewijzigd" }
        }
        val source = preview.record
        val record = source.copy(
            id = UUID.randomUUID().toString(),
            status = if (source.status == RunStatus.RUNNING) RunStatus.INTERRUPTED else source.status,
            endedAtMs = source.endedAtMs ?: maxOf(source.startedAtMs, System.currentTimeMillis()),
            importedFromId = source.id,
            importedArchiveSha256 = preview.archiveSha256,
        )
        val imported = repository.importRecord(record, extracted, preview.manifest.members.map { it.name }.toSet())
        preview.close()
        imported
    }

    private fun validateManifestTypes(text: String) {
        val root = json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("Manifestobject vereist")
        fun integer(value: JsonElement?) = value is JsonPrimitive && !value.isString && Regex("0|[1-9][0-9]*").matches(value.content)
        require(listOf("schemaVersion", "observationCount", "eventCount").all { integer(root[it]) }) { "Manifesttellers vereisen gehele JSON-getallen" }
        require(listOf("format", "captureId").all { (root[it] as? JsonPrimitive)?.isString == true }) { "Manifestidentificatie vereist tekst" }
        val members = root["members"] as? JsonArray ?: throw IllegalArgumentException("Manifestitems vereisen array")
        require(members.size < MAX_MEMBERS) { "Te veel manifestitems" }
        for (item in members) {
            val member = item as? JsonObject ?: throw IllegalArgumentException("Manifestitem vereist object")
            require(integer(member["bytes"]) && listOf("name", "sha256").all { (member[it] as? JsonPrimitive)?.isString == true }) { "Ongeldige manifestitemtypes" }
        }
    }

    private fun validateManifest(manifest: CaptureManifest, actual: Map<String, CaptureMember>) {
        require(manifest.format == "vespa-android-capture" && manifest.schemaVersion == 1) { "Onbekend opnamearchief" }
        require(manifest.observationCount in 0..CaptureRepository.MAX_OBSERVATIONS && manifest.eventCount in 0..CaptureRepository.MAX_EVENTS) { "Ongeldige manifesttellers" }
        require(manifest.members.size in 5 until MAX_MEMBERS && manifest.members.map { it.name }.distinct().size == manifest.members.size) { "Dubbele manifestnamen" }
        require(manifest.members.map { it.name }.toSet() == actual.keys) { "Manifest en ZIP-items verschillen" }
        require(actual.keys.containsAll(setOf("capture.json", "observations.jsonl", "events.jsonl", "observations.csv", "report.html"))) { "Vereist opnamebestand ontbreekt" }
        for (member in manifest.members) {
            require(member.name != "manifest.json" && member.bytes in 0..memberLimit(member.name) && hashPattern.matches(member.sha256)) { "Ongeldig manifestitem" }
            require(actual[member.name] == member) { "Hash of grootte wijkt af: ${member.name}" }
        }
    }
    internal fun memberLimit(name: String): Long = when {
        name == "capture.json" || name == "manifest.json" || originPattern.matches(name) -> CaptureCodec.MAX_RECORD_BYTES.toLong()
        name == "observations.jsonl" -> CaptureRepository.MAX_OBSERVATION_LOG_BYTES
        name == "events.jsonl" -> CaptureRepository.MAX_EVENT_LOG_BYTES
        name == "observations.csv" -> 192L * 1024 * 1024
        name == "report.html" -> 4L * 1024 * 1024
        recoveryPattern.matches(name) -> CaptureCodec.MAX_EVENT_BYTES.toLong()
        else -> throw IllegalArgumentException("Niet-toegestaan archiefpad: $name")
    }
    private fun writeCsv(snapshot: CaptureSnapshot, output: OutputStream) {
        val writer = OutputStreamWriter(output, Charsets.UTF_8)
        writer.write("sequence,received_at_epoch_ms,scan_timestamp_nanos,address,rssi_raw_dbm,rssi_valid,raw_hex,cached_device_name,legacy,connectable,primary_phy,secondary_phy,advertising_sid,tx_power_raw,data_status,periodic_interval,callback_type\r\n")
        eachObservation(snapshot) { row ->
            writer.write(listOf(row.sequence, row.receivedAtMs, row.timestampNanos, csv(row.address), row.rssiRaw, row.measuredRssi != null, csv(row.rawHex ?: ""), csv(row.deviceName ?: ""), row.legacy, row.connectable, row.primaryPhy, row.secondaryPhy, row.advertisingSid, row.txPowerRaw, row.dataStatus, row.periodicInterval, row.callbackType).joinToString(","))
            writer.write("\r\n")
        }
        writer.flush()
    }
    private fun html(snapshot: CaptureSnapshot, language: String): String {
        val text = CaptureReportText(language)
        fun label(key: CaptureReportLabel, vararg values: Any?) = escape(text.get(key, *values))
        val record = snapshot.record
        var minimum: Int? = null
        var maximum: Int? = null
        var valid = 0L
        var invalid = 0L
        var sum = 0L
        val samples = mutableListOf<Pair<Long, Int>>()
        val stride = maxOf(1L, (snapshot.observationCount + 499) / 500)
        eachObservation(snapshot) { row ->
            val rssi = row.measuredRssi
            if (rssi == null) invalid++ else {
                valid++; sum += rssi
                minimum = minOf(minimum ?: rssi, rssi); maximum = maxOf(maximum ?: rssi, rssi)
                if ((row.sequence - 1) % stride == 0L) samples += row.sequence to rssi
            }
        }
        val points = samples.joinToString("") { (sequence, rssi) ->
            val x = 35 + ((sequence - 1) * 730.0 / maxOf(1L, snapshot.observationCount - 1)).toInt()
            val y = 20 + ((126 - rssi) * 220.0 / 253).toInt()
            "<circle cx=\"$x\" cy=\"$y\" r=\"2\" fill=\"#795811\"><title>#$sequence: $rssi dBm</title></circle>"
        }
        val status = CaptureReportLabel.valueOf(record.status.name)
        val average = if (valid > 0) String.format(text.locale, "%.2f", sum.toDouble() / valid) else "—"
        return """<!doctype html><html lang="${text.language}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'"><title>${escape(record.name)}</title><style>body{font:16px system-ui;max-width:950px;margin:2rem auto;padding:1rem;color:#262621;background:#faf9f3}h1,h2,th{font-family:ui-monospace,monospace}table{border-collapse:collapse;width:100%}td,th{padding:.5rem;border:1px solid #c7c5b9;text-align:left;overflow-wrap:anywhere}pre{white-space:pre-wrap;overflow-wrap:anywhere}svg{width:100%;border:1px solid #c7c5b9}footer{margin-top:2rem;border-top:1px solid #c7c5b9;color:#57574e}@media print{body{max-width:none;margin:0;background:white}}</style></head><body>
<h1>${escape(record.name)}</h1><p>${label(CaptureReportLabel.INTRO, text[status])}</p>
<table>
<tr><th>${label(CaptureReportLabel.RECORDING)}</th><td>${escape(record.id)}</td></tr>
<tr><th>${label(CaptureReportLabel.ADDRESS)}</th><td>${escape(record.address)}</td></tr>
<tr><th>${label(CaptureReportLabel.VARIANT)}</th><td>${escape(record.tagVariant)}</td></tr>
<tr><th>${label(CaptureReportLabel.TIME)}</th><td>${record.startedAtMs} / ${record.endedAtMs ?: label(CaptureReportLabel.ACTIVE)}</td></tr>
<tr><th>${label(CaptureReportLabel.BUILD)}</th><td>${escape(record.appVersion)} / ${escape(record.buildCommit)}</td></tr>
<tr><th>${label(CaptureReportLabel.COUNTS)}</th><td>${snapshot.observationCount} / ${snapshot.eventCount}</td></tr>
<tr><th>${label(CaptureReportLabel.SOURCE)}</th><td>${escape(record.importedFromId ?: text[CaptureReportLabel.LOCAL])}</td></tr>
<tr><th>${label(CaptureReportLabel.HASH)}</th><td>${escape(record.importedArchiveSha256 ?: text[CaptureReportLabel.NOT_APPLICABLE])}</td></tr>
</table>
<h2>${label(CaptureReportLabel.SIGNAL)}</h2><p>${label(CaptureReportLabel.STATISTICS, valid, invalid, minimum ?: "—", maximum ?: "—", average)}</p>
<svg viewBox="0 0 800 275" role="img" aria-label="${label(CaptureReportLabel.CHART)}"><text x="0" y="18">126</text><text x="0" y="250">−127</text><line x1="35" y1="240" x2="770" y2="240" stroke="#c7c5b9"/>$points<text x="35" y="268" font-size="12">${label(CaptureReportLabel.CHART_AXIS)}</text></svg>
<p>${label(CaptureReportLabel.INTERPRETATION)}</p>
<h2>${label(CaptureReportLabel.NOTES)}</h2><pre>${escape(record.notes)}</pre>
<h2>${label(CaptureReportLabel.DEVICE)}</h2><pre>${escape(record.deviceInfo)}</pre>
<h2>${label(CaptureReportLabel.SETTINGS)}</h2><pre>${escape(record.settings.entries.joinToString("\n") { "${it.key}: ${it.value}" })}</pre>
<p>${label(CaptureReportLabel.ARCHIVE)}</p><footer>${label(CaptureReportLabel.FOOTER)}</footer></body></html>"""
    }
    private fun eachObservation(snapshot: CaptureSnapshot, action: (CaptureObservation) -> Unit) {
        val source = snapshot.files.getValue("observations.jsonl")
        source.file.seek(0)
        while (source.file.filePointer < source.bytes) {
            val line = CaptureRepository.readLine(source.file, CaptureCodec.MAX_OBSERVATION_BYTES) ?: throw EOFException()
            require(line.complete && source.file.filePointer <= source.bytes) { "Ongeldige exportmomentopname" }
            action(CaptureCodec.decodeObservation(CaptureRepository.strictUtf8(line.bytes)))
        }
    }
    private fun csv(value: String): String {
        val safe = if (value.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r', '\n')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input -> val buffer = ByteArray(32 * 1024); while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private class NonClosingOutput(output: OutputStream) : FilterOutputStream(output) { override fun close() { flush() } }
    private class DigestOutput(private val output: OutputStream, private val maximum: Long) : OutputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L; private set
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            require(bytes + length <= maximum) { "Bestand overschrijdt import/exportlimiet" }
            output.write(buffer, offset, length)
            digest.update(buffer, offset, length)
            bytes += length
        }
        override fun flush() = output.flush()
        fun hash(): String = digest.digest().joinToString("") { "%02x".format(it) }
    }
}

