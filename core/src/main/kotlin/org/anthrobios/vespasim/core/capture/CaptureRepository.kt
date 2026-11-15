package org.anthrobios.vespasim.core.capture

import org.anthrobios.vespasim.core.RunStatus
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Durable, selected-device evidence. All methods perform disk I/O: use a worker thread. */
class CaptureRepository(directory: File) {
    internal val root: File
    private val records: File
    private val trashDirectory: File
    private val mutex: ReentrantLock
    private val indexes = mutableMapOf<String, LogIndex>()

    init {
        require(!Files.isSymbolicLink(directory.toPath())) { "Opnamemap mag geen symlink zijn" }
        directory.mkdirs()
        root = directory.canonicalFile
        require(root.isDirectory) { "Opnamemap ontbreekt" }
        mutex = locks.computeIfAbsent(root.path) { ReentrantLock() }
        records = child(root, "records").also { it.mkdirs() }
        trashDirectory = child(root, "trash").also { it.mkdirs() }
        child(root, ".imports").mkdirs()
        syncDirectory(root)
    }

    fun create(record: CaptureRecord) = locked {
        CaptureCodec.validateRecord(record)
        require(record.status == RunStatus.RUNNING && record.importedFromId == null) { "Nieuwe opname moet lokaal en actief zijn" }
        require(!recordDirectory(record.id).exists() && !recordDirectory(record.id, true).exists()) { "Opname bestaat al" }
        require(sessionCount() < MAX_SESSIONS) { "Maximum aantal opnamen bereikt" }
        publish(record) { staging ->
            writeDurable(child(staging, OBSERVATIONS), byteArrayOf())
            writeDurable(child(staging, EVENTS), byteArrayOf())
            preserveOrigin(staging, CaptureCodec.encodeRecord(record).toByteArray(Charsets.UTF_8))
        }
    }

    fun appendObservation(id: String, observation: CaptureObservation) = locked {
        val record = requiredRecord(id)
        require(record.status == RunStatus.RUNNING) { "Opname is afgesloten" }
        require(observation.address.equals(record.address, true)) { "Waarneming hoort niet bij geselecteerd toestel" }
        val line = CaptureCodec.encodeObservation(observation)
        append(recordDirectory(id), OBSERVATIONS, line, observation.sequence, record.address)
    }

    fun appendEvent(id: String, event: CaptureEvent) = locked {
        require(requiredRecord(id).status == RunStatus.RUNNING) { "Opname is afgesloten" }
        append(recordDirectory(id), EVENTS, CaptureCodec.encodeEvent(event), event.sequence, null)
    }

    /** Each append already fsyncs; this method is a worker-queue barrier, not a buffer flush. */
    fun flush(id: String) = locked { requiredRecord(id); Unit }

    fun finish(id: String, status: RunStatus, endedAtMs: Long) = locked {
        require(status != RunStatus.RUNNING) { "Eindstatus vereist" }
        val record = requiredRecord(id)
        if (record.status != RunStatus.RUNNING) {
            require(record.status == status && record.endedAtMs == endedAtMs) { "Afgesloten opname is onveranderlijk" }
        } else {
            checkedIndex(recordDirectory(id), OBSERVATIONS, record.address)
            checkedIndex(recordDirectory(id), EVENTS, null)
            updateRecord(record.copy(status = status, endedAtMs = endedAtMs))
        }
    }

    fun get(id: String, trashed: Boolean = false): CaptureRecord? = locked { readRecord(recordDirectory(id, trashed)) }
    fun page(limit: Int = 50, offset: Int = 0, trashed: Boolean = false): CapturePage = locked {
        require(limit in 1..200 && offset >= 0) { "Ongeldige paginering" }
        val entries = mutableListOf<Pair<String, Long>>()
        val warnings = mutableListOf<String>()
        val parent = if (trashed) trashDirectory else records
        Files.newDirectoryStream(parent.toPath()).use { stream ->
            for (path in stream) {
                if (path.fileName.toString().startsWith(".")) continue
                require(entries.size + warnings.size < MAX_SESSIONS) { "Te veel opnamen" }
                try {
                    val record = readRecord(child(parent, path.fileName.toString())) ?: continue
                    entries += record.id to record.startedAtMs
                } catch (e: Exception) { warnings += "${path.fileName}: ${e.message}" }
            }
        }
        entries.sortWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
        CapturePage(entries.drop(offset).take(limit).map { requiredRecord(it.first, trashed) }, entries.size, warnings)
    }
    fun count(id: String): Long = summary(id).observationCount
    fun summary(id: String): CaptureSummary = locked {
        val record = requiredRecord(id)
        val directory = recordDirectory(id)
        val observations = checkedIndex(directory, OBSERVATIONS, record.address)
        val events = checkedIndex(directory, EVENTS, null)
        CaptureSummary(record, observations.count, events.count, observations.count, events.count)
    }
    fun observations(id: String, afterSequence: Long = 0, limit: Int = 200): List<CaptureObservation> = locked {
        val record = requiredRecord(id)
        readPage(recordDirectory(id), OBSERVATIONS, record.address, afterSequence, limit, CaptureCodec::decodeObservation)
    }
    fun events(id: String, afterSequence: Long = 0, limit: Int = 200): List<CaptureEvent> = locked {
        requiredRecord(id)
        readPage(recordDirectory(id), EVENTS, null, afterSequence, limit, CaptureCodec::decodeEvent)
    }
    fun rename(id: String, name: String) = locked { updateRecord(requiredRecord(id).copy(name = name)) }
    fun updateNotes(id: String, notes: String, tagVariant: String? = null) = locked {
        val record = requiredRecord(id)
        updateRecord(record.copy(notes = notes, tagVariant = tagVariant ?: record.tagVariant))
    }
    fun trash(id: String) = locked {
        require(requiredRecord(id).status != RunStatus.RUNNING) { "Stop de opname voor verwijderen" }
        moveNew(recordDirectory(id), recordDirectory(id, true))
        invalidate(id)
    }
    fun restore(id: String) = locked {
        requiredRecord(id, true)
        moveNew(recordDirectory(id, true), recordDirectory(id))
        invalidate(id)
    }
    /** UI must ask for confirmation. Only already trashed, terminal evidence is eligible. */
    fun purge(id: String) = locked {
        require(requiredRecord(id, true).status != RunStatus.RUNNING) { "Actieve opname kan niet verdwijnen" }
        deleteOwnedTree(recordDirectory(id, true))
        invalidate(id)
    }

    fun recoverInterruptedCaptures(): Int = recoverInterrupted()
    fun recoverInterrupted(): Int = locked {
        var recovered = 0
        val failures = mutableListOf<String>()
        try { CaptureArchives.cleanOrphanPreviews(this) }
        catch (e: Exception) { failures += "importstaging: ${e.message}" }
        Files.newDirectoryStream(records.toPath()).use { stream ->
            for (path in stream) {
                if (path.fileName.toString().startsWith(".")) continue
                try {
                    val directory = child(records, path.fileName.toString())
                    val record = readRecord(directory) ?: continue
                    if (record.status != RunStatus.RUNNING) continue
                    for (name in listOf(OBSERVATIONS, EVENTS)) {
                        val file = child(directory, name)
                        val index = buildIndex(file, if (name == OBSERVATIONS) record.address else null)
                        if (index.incompleteTail) {
                            val recovery = child(directory, "recovery").also { it.mkdirs() }
                            val kind = if (name == OBSERVATIONS) "observations" else "events"
                            val tail = child(recovery, "partial-tail-$kind-${UUID.randomUUID()}.bin")
                            RandomAccessFile(file, "r").use { input ->
                                input.seek(index.bytes)
                                val remaining = ByteArray((input.length() - index.bytes).toInt())
                                input.readFully(remaining)
                                writeDurable(tail, remaining)
                            }
                            RandomAccessFile(file, "rw").use { it.setLength(index.bytes); it.fd.sync() }
                            indexes.remove(file.path)
                        }
                    }
                    val eventIndex = checkedIndex(directory, EVENTS, null)
                    if (eventIndex.count < MAX_EVENTS && eventIndex.bytes + 1024 < MAX_EVENT_LOG_BYTES) {
                        val event = CaptureEvent(eventIndex.count + 1, System.currentTimeMillis(), "capture_interrupted", "Opname onderbroken door processtop; geen automatische radioherstart")
                        append(directory, EVENTS, CaptureCodec.encodeEvent(event), event.sequence, null)
                    }
                    updateRecord(record.copy(status = RunStatus.INTERRUPTED, endedAtMs = maxOf(record.startedAtMs, System.currentTimeMillis())))
                    recovered++
                } catch (e: Exception) { failures += "${path.fileName}: ${e.message}" }
            }
        }
        if (failures.isNotEmpty()) throw IOException("$recovered opnamen hersteld; beschadigde gegevens behouden: ${failures.joinToString("; ")}")
        recovered
    }

    fun exportZip(id: String, output: OutputStream, language: String = "en") = CaptureArchives.exportZip(this, id, output, language)
    fun exportZip(id: String, output: File, language: String = "en") {
        var ancestor = output.toPath().toAbsolutePath().normalize()
        while (true) {
            require(!Files.isSymbolicLink(ancestor)) { "Exportpad mag geen symlink bevatten" }
            ancestor = ancestor.parent ?: break
        }
        // CREATE_NEW is atomic: an existing file, including a symlink, is never opened.
        val channel = FileChannel.open(output.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        try { channel.use { exportZip(id, Channels.newOutputStream(it), language); it.force(true) } }
        catch (e: Exception) { output.delete(); throw e }
    }
    fun exportCsv(id: String, output: OutputStream) = CaptureArchives.exportCsv(this, id, output)
    fun reportHtml(id: String, language: String = "en"): String = CaptureArchives.reportHtml(this, id, language)
    fun previewImport(input: InputStream): CaptureImportPreview = CaptureArchives.preview(this, input)
    fun commitImport(preview: CaptureImportPreview): CaptureRecord = CaptureArchives.commit(this, preview)

    internal fun snapshot(id: String): CaptureSnapshot = locked {
        val record = requiredRecord(id)
        val directory = recordDirectory(id)
        val observationIndex = checkedIndex(directory, OBSERVATIONS, record.address)
        val eventIndex = checkedIndex(directory, EVENTS, null)
        val handles = mutableMapOf<String, SnapshotFile>()
        try {
            fun add(name: String, file: File, size: Long = file.length()) {
                require(handles.size < 66) { "Te veel bewijsbijlagen voor een opnamearchief" }
                handles[name] = SnapshotFile(RandomAccessFile(file, "r"), size)
            }
            add(OBSERVATIONS, child(directory, OBSERVATIONS), observationIndex.bytes)
            add(EVENTS, child(directory, EVENTS), eventIndex.bytes)
            for (folder in listOf("origins", "recovery")) {
                val parent = child(directory, folder)
                if (parent.exists()) Files.newDirectoryStream(parent.toPath()).use { paths ->
                    for (path in paths) {
                        val name = "$folder/${path.fileName}"
                        CaptureArchives.memberLimit(name) // exact allowlist
                        add(name, child(parent, path.fileName.toString()))
                    }
                }
            }
            CaptureSnapshot(record, observationIndex.count, eventIndex.count, handles)
        } catch (e: Exception) { handles.values.forEach { it.close() }; throw e }
    }

    internal fun importRecord(record: CaptureRecord, staging: File, names: Set<String>): CaptureRecord = locked {
        require(sessionCount() < MAX_SESSIONS) { "Maximum aantal opnamen bereikt" }
        require(!recordDirectory(record.id).exists() && !recordDirectory(record.id, true).exists()) { "Import-ID bestaat al" }
        publish(record) { target ->
            for (name in names.filter { it == OBSERVATIONS || it == EVENTS || it.startsWith("origins/") || it.startsWith("recovery/") }) {
                val source = safeRelative(staging, name)
                val destination = safeRelative(target, name)
                destination.parentFile.mkdirs()
                FileInputStream(source).use { input -> FileOutputStream(destination).use { output -> input.copyTo(output); output.fd.sync() } }
            }
            preserveOrigin(target, child(staging, "capture.json").readBytes())
        }
        record
    }
    private fun publish(record: CaptureRecord, populate: (File) -> Unit) {
        val staging = child(records, ".create-${UUID.randomUUID()}")
        check(staging.mkdir()) { "Kan opnamemap niet maken" }
        try {
            writeDurable(child(staging, "capture.json"), CaptureCodec.encodeRecord(record).toByteArray(Charsets.UTF_8))
            populate(staging)
            moveNew(staging, recordDirectory(record.id))
        } finally { if (staging.exists()) deleteOwnedTree(staging) }
    }
    private fun preserveOrigin(directory: File, bytes: ByteArray) {
        val parent = child(directory, "origins").also { it.mkdirs() }
        val file = child(parent, "${CaptureArchives.sha256(bytes)}.json")
        if (!file.exists()) writeDurable(file, bytes)
    }
    private fun updateRecord(record: CaptureRecord) {
        val bytes = CaptureCodec.encodeRecord(record).toByteArray(Charsets.UTF_8)
        val directory = recordDirectory(record.id)
        val temp = child(directory, ".metadata-${UUID.randomUUID()}")
        try {
            writeDurable(temp, bytes)
            Files.move(temp.toPath(), child(directory, "capture.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(directory)
        } finally { temp.delete() }
    }
    private fun readRecord(directory: File): CaptureRecord? {
        if (!directory.exists()) return null
        val file = child(directory, "capture.json")
        require(file.isFile && file.length() <= CaptureCodec.MAX_RECORD_BYTES) { "Opnamemetadata ontbreekt of is te groot" }
        val record = CaptureCodec.decodeRecord(strictUtf8(file.readBytes()))
        require(record.id == directory.name) { "Opname-ID en map komen niet overeen" }
        return record
    }
    private fun requiredRecord(id: String, trashed: Boolean = false) = readRecord(recordDirectory(id, trashed)) ?: throw IllegalArgumentException("Opname niet gevonden")
    private fun recordDirectory(id: String, trashed: Boolean = false): File {
        require(ID.matches(id)) { "Ongeldige opname-ID" }
        return child(if (trashed) trashDirectory else records, id)
    }
    private fun sessionCount(): Int {
        var count = 0
        for (directory in listOf(records, trashDirectory)) Files.newDirectoryStream(directory.toPath()).use { paths ->
            for (path in paths) if (!path.fileName.toString().startsWith(".")) { count++; if (count >= MAX_SESSIONS) return count }
        }
        return count
    }
    private fun append(directory: File, name: String, json: String, sequence: Long, address: String?) {
        val index = checkedIndex(directory, name, address)
        require(sequence == index.count + 1) { "Volgnummer moet exact aansluiten" }
        require(index.count < recordLimit(name)) { "Maximum aantal records bereikt" }
        val bytes = (json + "\n").toByteArray(Charsets.UTF_8)
        require(index.bytes + bytes.size <= byteLimit(name)) { "Opnamelog heeft maximale grootte bereikt" }
        val file = child(directory, name)
        try {
            FileOutputStream(file, true).use { it.write(bytes); it.flush(); it.fd.sync() }
            if (index.count % CHECKPOINT == 0L) index.checkpoints += index.bytes
            index.count++
            index.bytes += bytes.size
            index.length = file.length()
            index.modified = file.lastModified()
        } catch (e: Exception) { indexes.remove(file.path); throw e }
    }
    private fun checkedIndex(directory: File, name: String, address: String?): LogIndex {
        val file = child(directory, name)
        val index = indexes[file.path]?.takeIf { it.length == file.length() && it.modified == file.lastModified() }
            ?: buildIndex(file, address).also { indexes[file.path] = it }
        require(!index.incompleteTail) { "Onvolledige laatste logregel; eerst opnameherstel uitvoeren" }
        return index
    }
    private fun <T> readPage(directory: File, name: String, address: String?, after: Long, limit: Int, decode: (String) -> T): List<T> {
        require(after >= 0 && limit in 1..2000) { "Ongeldige paginering" }
        val index = checkedIndex(directory, name, address)
        if (after >= index.count) return emptyList()
        val block = (after / CHECKPOINT).toInt()
        val rows = mutableListOf<T>()
        RandomAccessFile(child(directory, name), "r").use { file ->
            file.seek(index.checkpoints[block])
            var sequence = block * CHECKPOINT
            while (file.filePointer < index.bytes && rows.size < limit) {
                val line = readLine(file, lineLimit(name))!!
                sequence++
                if (sequence > after) rows += decode(strictUtf8(line.bytes))
            }
        }
        return rows
    }
    private fun invalidate(id: String) { indexes.keys.removeAll { it.contains("/$id/") } }
    internal fun <T> locked(block: () -> T): T = mutex.withLock {
        val file = child(root, ".capture.lock")
        RandomAccessFile(file, "rw").use { raf -> raf.channel.lock().use { block() } }
    }

    companion object {
        internal const val OBSERVATIONS = "observations.jsonl"
        internal const val EVENTS = "events.jsonl"
        const val MAX_OBSERVATIONS = 200_000L
        const val MAX_EVENTS = 50_000L
        const val MAX_OBSERVATION_LOG_BYTES = 128L * 1024 * 1024
        const val MAX_EVENT_LOG_BYTES = 16L * 1024 * 1024
        private const val MAX_SESSIONS = 10_000
        private const val CHECKPOINT = 256L
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
        internal fun byteLimit(name: String) = if (name == OBSERVATIONS) MAX_OBSERVATION_LOG_BYTES else MAX_EVENT_LOG_BYTES
        internal fun lineLimit(name: String) = if (name == OBSERVATIONS) CaptureCodec.MAX_OBSERVATION_BYTES else CaptureCodec.MAX_EVENT_BYTES
        internal fun recordLimit(name: String) = if (name == OBSERVATIONS) MAX_OBSERVATIONS else MAX_EVENTS
        internal fun buildIndex(file: File, address: String?): LogIndex {
            require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "Logbestand ontbreekt of is een symlink" }
            val name = file.name
            require(file.length() <= byteLimit(name)) { "Logbestand te groot" }
            val index = LogIndex(length = file.length(), modified = file.lastModified())
            RandomAccessFile(file, "r").use { input ->
                while (input.filePointer < input.length()) {
                    val start = input.filePointer
                    val line = readLine(input, lineLimit(name))!!
                    if (!line.complete) { index.incompleteTail = true; break }
                    require(index.count < recordLimit(name)) { "Te veel logrecords" }
                    val text = strictUtf8(line.bytes)
                    val sequence = if (name == OBSERVATIONS) {
                        CaptureCodec.decodeObservation(text).also { require(address != null && it.address.equals(address, true)) { "Waarneming bevat een ander radioadres" } }.sequence
                    } else CaptureCodec.decodeEvent(text).sequence
                    require(sequence == index.count + 1) { "Ononderbroken volgnummers vereist" }
                    if (index.count % CHECKPOINT == 0L) index.checkpoints += start
                    index.count++
                    index.bytes = input.filePointer
                }
            }
            return index
        }
        internal fun child(parent: File, name: String): File {
            require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name) { "Ongeldig pad" }
            require(!Files.isSymbolicLink(parent.toPath())) { "Symlink niet toegestaan" }
            val file = File(parent, name)
            require(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == parent.canonicalFile) { "Pad ontsnapt uit opslag" }
            return file
        }
        internal fun safeRelative(parent: File, name: String): File = name.split('/').fold(parent) { current, segment -> child(current, segment) }
        internal fun writeDurable(file: File, bytes: ByteArray) {
            require(file.createNewFile()) { "Bestand bestaat al" }
            FileOutputStream(file).use { it.write(bytes); it.flush(); it.fd.sync() }
            syncDirectory(file.parentFile)
        }
        /** Some Android filesystems reject opening directories; file fsync and atomic moves remain mandatory. */
        private fun syncDirectory(directory: File) {
            try { FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } }
            catch (_: IOException) { /* Best effort only; not a guarantee against device/power failure. */ }
            catch (_: UnsupportedOperationException) { /* Filesystem does not support directory sync. */ }
        }
        internal fun moveNew(source: File, target: File) {
            require(!target.exists() && !Files.isSymbolicLink(target.toPath())) { "Doel bestaat al" }
            syncDirectory(source)
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(target.parentFile)
            if (source.parentFile != target.parentFile) syncDirectory(source.parentFile)
        }
        internal fun deleteOwnedTree(file: File) {
            require(!Files.isSymbolicLink(file.toPath())) { "Symlink niet toegestaan" }
            if (file.isDirectory) Files.newDirectoryStream(file.toPath()).use { paths -> for (path in paths) deleteOwnedTree(child(file, path.fileName.toString())) }
            if (file.exists()) check(file.delete()) { "Kan bestand niet verwijderen" }
        }
        internal fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        internal fun readLine(input: RandomAccessFile, maximum: Int): LogLine? {
            if (input.filePointer >= input.length()) return null
            val buffer = ByteArrayOutputStream()
            val block = ByteArray(minOf(4096, maximum + 1))
            while (input.filePointer < input.length()) {
                val position = input.filePointer
                val read = input.read(block, 0, minOf(block.size, maximum + 1 - buffer.size()))
                if (read < 0) break
                val newline = (0 until read).firstOrNull { block[it] == 10.toByte() }
                if (newline != null) {
                    buffer.write(block, 0, newline)
                    input.seek(position + newline + 1)
                    return LogLine(buffer.toByteArray(), true)
                }
                buffer.write(block, 0, read)
                require(buffer.size() <= maximum) { "Logregel te groot" }
            }
            return LogLine(buffer.toByteArray(), false)
        }
    }
}

data class CapturePage(val records: List<CaptureRecord>, val total: Int, val warnings: List<String> = emptyList())
data class CaptureSummary(val record: CaptureRecord, val observationCount: Long, val eventCount: Long, val lastObservationSequence: Long, val lastEventSequence: Long)
internal data class LogLine(val bytes: ByteArray, val complete: Boolean)
internal data class LogIndex(var count: Long = 0, var bytes: Long = 0, var incompleteTail: Boolean = false, var length: Long = 0, var modified: Long = 0, val checkpoints: MutableList<Long> = mutableListOf())
internal class SnapshotFile(val file: RandomAccessFile, val bytes: Long) : Closeable {
    fun input(): InputStream {
        file.seek(0)
        var remaining = bytes
        return object : InputStream() {
            override fun read(): Int { if (remaining == 0L) return -1; val value = file.read(); if (value < 0) throw EOFException(); remaining--; return value }
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (len == 0) return 0; if (remaining == 0L) return -1; val read = file.read(b, off, minOf(len.toLong(), remaining).toInt()); if (read < 0) throw EOFException(); remaining -= read; return read }
        }
    }
    override fun close() = file.close()
}
internal class CaptureSnapshot(val record: CaptureRecord, val observationCount: Long, val eventCount: Long, val files: Map<String, SnapshotFile>) : Closeable {
    override fun close() { files.values.forEach { it.close() } }
}

