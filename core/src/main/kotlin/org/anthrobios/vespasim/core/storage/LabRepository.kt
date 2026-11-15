package org.anthrobios.vespasim.core.storage

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.anthrobios.vespasim.core.*

/** Private app-local evidence store. A run's profile and complete log records are immutable. */
class LabRepository(root: File) {
    private val root = root.canonicalFile
    private val mutex = locks.computeIfAbsent(this.root.path) { ReentrantLock() }
    private val profileDir = File(this.root, "profiles")
    private val runDir = File(this.root, "runs")
    private val indexCache = mutableMapOf<String, CachedIndex>()

    init {
        mutex.withLock {
            ensureDirectory(this.root)
            ensureDirectory(profileDir)
            ensureDirectory(runDir)
        }
    }

    fun saveProfile(profile: SimulationProfile): String = locked {
        val encoded = ProfileCodec.encode(profile).toByteArray(Charsets.UTF_8)
        require(encoded.size <= ProfileCodec.MAX_BYTES) { "Profiel is te groot" }
        atomicWrite(safeChild(profileDir, profile.id, ".json"), encoded)
        profile.id
    }

    fun profiles(): List<SimulationProfile> = locked {
        listedFiles(profileDir, ".json").map { readProfile(it) }.sortedWith(compareBy({ it.name }, { it.id }))
    }

    fun profile(id: String): SimulationProfile? = locked {
        val file = safeChild(profileDir, id, ".json")
        if (file.exists()) readProfile(file) else null
    }

    fun createRun(run: RunRecord): Unit = locked {
        validateRun(run)
        require(run.status == RunStatus.RUNNING && run.endedAtMs == null) { "Nieuwe proef moet RUNNING zijn" }
        val target = safeChild(runDir, run.id)
        require(!target.exists()) { "Proef bestaat al: ${run.id}" }
        val staging = File(runDir, ".create-${UUID.randomUUID()}")
        require(staging.mkdir()) { "Kan tijdelijke proefmap niet maken" }
        try {
            writeSynced(File(staging, "run.json"), json.encodeToString(run).toByteArray(Charsets.UTF_8))
            writeSynced(File(staging, "events.jsonl"), byteArrayOf())
            // Metadata and log appear together, and an existing run is never replaced.
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun appendEvent(runId: String, event: RunEvent): Unit = locked {
        val run = requiredRun(runId)
        require(run.status == RunStatus.RUNNING) { "Proef is al afgesloten" }
        appendInternal(runId, event)
    }

    fun finishRun(runId: String, status: RunStatus, endedAtMs: Long): Unit = locked {
        require(status != RunStatus.RUNNING) { "Eindstatus mag niet RUNNING zijn" }
        require(endedAtMs >= 0) { "Ongeldige eindtijd" }
        val run = requiredRun(runId)
        if (run.status != RunStatus.RUNNING) {
            require(run.status == status && run.endedAtMs == endedAtMs) { "Afgesloten proef is onveranderlijk" }
        } else {
            // Refuse to declare a complete run while an uncommitted tail is still present.
            readIndex(runId)
            writeRun(run.copy(status = status, endedAtMs = endedAtMs))
        }
    }

    fun runs(): List<RunRecord> = locked {
        listedRunDirs().map { readRun(File(it, "run.json")) }
            .sortedWith(compareByDescending<RunRecord> { it.startedAtMs }.thenBy { it.id })
    }

    fun run(id: String): RunRecord? = locked {
        val dir = safeChild(runDir, id)
        if (dir.exists()) readRun(File(dir, "run.json")) else null
    }

    fun events(runId: String, afterSequence: Long = 0, limit: Int = 200): List<RunEvent> = locked {
        require(afterSequence >= 0) { "Cursor mag niet negatief zijn" }
        require(limit in 1..MAX_QUERY) { "Limiet moet 1..$MAX_QUERY zijn" }
        requiredRun(runId)
        val index = readIndex(runId)
        val result = ArrayList<RunEvent>(minOf(limit, 200))
        scanEvents(logFile(runId), index.byteLength) { event ->
            if (event.sequence > afterSequence && result.size < limit) result.add(event)
        }
        result
    }

    fun summary(runId: String): RunSummary = locked {
        val run = requiredRun(runId)
        readIndex(runId).summary(run)
    }

    /** Called once at process startup, never by merely opening another repository connection. */
    fun recoverInterruptedRuns(): Int = locked {
        var recovered = 0
        val failures = mutableListOf<String>()
        val directories = (runDir.listFiles() ?: throw IOException("Kan proefarchief niet lezen"))
            .filter { !it.name.startsWith(".") }.sortedBy { it.name }
        for (directory in directories) {
            try {
                require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "Ongeldige proefmap" }
                validateId(directory.name)
                if (recoverRun(directory)) recovered++
            } catch (failure: Exception) {
                // A damaged early run must not prevent independent healthy runs from recovery.
                failures += "${directory.name.take(96)}: ${failure.message.orEmpty().take(240)}"
            }
        }
        if (failures.isNotEmpty()) {
            throw IOException("$recovered proef/proeven hersteld; ${failures.size} beschadigd archief/deel niet hersteld. " +
                failures.take(10).joinToString("; ") + if (failures.size > 10) "; overige fouten: ${failures.size - 10}" else "")
        }
        recovered
    }

    private fun recoverRun(directory: File): Boolean {
        val run = readRun(File(directory, "run.json"))
        if (run.status != RunStatus.RUNNING) return false
        val log = logFile(run.id)
        val index = scanEvents(log, log.length(), allowIncompleteTail = true)
        if (index.incompleteTail.isNotEmpty()) {
            val recovery = File(directory, "recovery")
            ensureDirectory(recovery)
            val evidence = File(recovery, "partial-tail-${UUID.randomUUID()}.bin")
            writeSynced(evidence, index.incompleteTail)
            RandomAccessFile(log, "rw").use { file ->
                file.setLength(index.byteLength)
                file.fd.sync()
            }
            indexCache.remove(run.id)
        }
        val now = System.currentTimeMillis().coerceAtLeast(0)
        val endedAtMs = now.coerceAtLeast(run.startedAtMs)
        val tailSize = index.incompleteTail.size
        // Recovery preserves complete records and keeps incomplete bytes as a separate attachment.
        appendInternal(run.id, RunEvent(
            sequence = index.lastSequence + 1,
            atEpochMs = now,
            elapsedMs = index.lastElapsedMs,
            kind = if (tailSize > 0) "error" else "run_interrupted",
            message = if (tailSize > 0) "Onvolledige laatste logregel bewaard als herstelbijlage; proef onderbroken."
                      else "Proces eindigde zonder eindstatus; proef wordt niet automatisch hervat.",
            details = mapOf(
                "recoveredTailBytes" to tailSize.toString(), "recovery" to "process_start",
                "observedWallTimeMs" to now.toString(), "endedAtMs" to endedAtMs.toString(),
                "wallClockAdjusted" to (now < run.startedAtMs).toString(),
            ),
        ))
        writeRun(run.copy(status = RunStatus.INTERRUPTED, endedAtMs = endedAtMs))
        return true
    }

    fun exportBundle(runId: String, destination: OutputStream, language: String = "en") {
        val snapshot = snapshot(runId)
        EvidenceExport.bundle(snapshot, destination, language)
    }

    fun reportHtml(runId: String, language: String = "en"): String = EvidenceExport.report(snapshot(runId), language)

    fun exportCsv(runId: String, destination: OutputStream) {
        EvidenceExport.csv(snapshot(runId), destination)
    }

    private fun snapshot(runId: String): EvidenceSnapshot = locked {
        val run = requiredRun(runId)
        val index = readIndex(runId)
        val recoveryDir = File(safeChild(runDir, runId), "recovery")
        val attachments = if (recoveryDir.exists()) {
            ensureDirectory(recoveryDir)
            (recoveryDir.listFiles() ?: throw IOException("Kan herstelbijlagen niet lezen"))
                .sortedBy { it.name }.onEach {
                    require(it.isFile && !Files.isSymbolicLink(it.toPath())) { "Ongeldige herstelbijlage" }
                    require(it.name.matches(Regex("partial-tail-[a-fA-F0-9-]+\\.bin"))) { "Onbekende herstelbijlage" }
                    require(it.length() <= MAX_EVENT_BYTES) { "Herstelbijlage is te groot" }
                }
        } else emptyList()
        // Appends after this point cannot change the already committed prefix.
        EvidenceSnapshot(run, index.summary(run), logFile(runId), index.byteLength, attachments)
    }

    private fun appendInternal(runId: String, event: RunEvent) {
        validateEvent(event)
        val previous = readIndex(runId)
        require(previous.eventCount < MAX_EVENTS) { "Maximum aantal logregels bereikt" }
        require(event.sequence == previous.lastSequence + 1) { "Logvolgnummer moet ${previous.lastSequence + 1} zijn" }
        require(event.elapsedMs >= previous.lastElapsedMs) { "Monotone logtijd loopt terug" }
        val bytes = (json.encodeToString(event) + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_EVENT_BYTES) { "Logregel is te groot" }
        require(previous.byteLength + bytes.size <= MAX_LOG_BYTES) { "Logbestand is te groot" }
        val file = logFile(runId)
        FileOutputStream(file, true).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        val updated = previous.withEvent(event).copy(byteLength = previous.byteLength + bytes.size)
        indexCache[runId] = CachedIndex(file.length(), file.lastModified(), updated)
    }

    private fun readIndex(runId: String): LogIndex {
        val file = logFile(runId)
        val cached = indexCache[runId]
        if (cached != null && cached.size == file.length() && cached.modified == file.lastModified()) return cached.index
        val index = scanEvents(file, file.length())
        indexCache[runId] = CachedIndex(file.length(), file.lastModified(), index)
        return index
    }

    private fun requiredRun(id: String): RunRecord = runFile(id).let {
        require(it.exists()) { "Onbekende proef: $id" }
        readRun(it)
    }

    private fun readRun(file: File): RunRecord {
        val text = readBounded(file, MAX_RUN_BYTES).toString(Charsets.UTF_8)
        val run = try { json.decodeFromString<RunRecord>(text) } catch (e: Exception) {
            throw IOException("Ongeldige proefmetadata: ${file.name}", e)
        }
        validateRun(run)
        require(run.id == file.parentFile.name) { "Proef-ID wijkt af van bestandsmap" }
        require((run.status == RunStatus.RUNNING) == (run.endedAtMs == null)) { "Inconsistente eindstatus" }
        return run
    }

    private fun readProfile(file: File): SimulationProfile {
        val profile = ProfileCodec.decode(readBounded(file, ProfileCodec.MAX_BYTES).toString(Charsets.UTF_8))
        require(profile.id + ".json" == file.name) { "Profiel-ID wijkt af van bestandsnaam" }
        return profile
    }

    private fun writeRun(run: RunRecord) = atomicWrite(runFile(run.id), json.encodeToString(run).toByteArray(Charsets.UTF_8))
    private fun runFile(id: String): File = File(safeChild(runDir, id), "run.json").also { ensureSafeFile(it) }
    private fun logFile(id: String): File = File(safeChild(runDir, id), "events.jsonl").also {
        ensureSafeFile(it)
        require(it.isFile) { "Logbestand ontbreekt" }
    }

    private fun listedRunDirs(): List<File> = (runDir.listFiles() ?: throw IOException("Kan proefarchief niet lezen"))
        .filter { !it.name.startsWith(".") }.sortedBy { it.name }.onEach {
            require(it.isDirectory && !Files.isSymbolicLink(it.toPath())) { "Ongeldige proefmap" }
            validateId(it.name)
        }

    private fun listedFiles(directory: File, extension: String): List<File> =
        (directory.listFiles() ?: throw IOException("Kan profielarchief niet lezen"))
            .filter { !it.name.startsWith(".") && it.name.endsWith(extension) }.sortedBy { it.name }.onEach { ensureSafeFile(it) }

    private fun <T> locked(block: () -> T): T = mutex.withLock {
        val lockFile = File(root, ".repository.lock")
        ensureSafeFile(lockFile)
        RandomAccessFile(lockFile, "rw").channel.use { channel -> channel.lock().use { block() } }
    }

    private fun safeChild(parent: File, id: String, suffix: String = ""): File {
        validateId(id)
        require(!Files.isSymbolicLink(parent.toPath())) { "Symbolische archiefmap niet toegestaan" }
        require(parent.canonicalFile.toPath().startsWith(root.toPath())) { "Archiefmap ligt buiten privéarchief" }
        val file = File(parent, id + suffix)
        require(!Files.isSymbolicLink(file.toPath())) { "Symbolische links zijn niet toegestaan" }
        require(file.canonicalFile.parentFile == parent.canonicalFile) { "Pad buiten archief" }
        return file
    }

    private data class CachedIndex(val size: Long, val modified: Long, val index: LogIndex)

    companion object {
        const val MAX_QUERY = 2000
        const val MAX_EVENT_BYTES = 65_536
        const val MAX_EVENTS = 250_000L
        const val MAX_LOG_BYTES = 512L * 1024 * 1024
        private const val MAX_RUN_BYTES = 524_288
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
        internal val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; prettyPrint = false }

        private fun validateId(id: String) {
            require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) && id != "." && id != "..") { "Ongeldig archief-ID" }
        }

        private fun validateRun(run: RunRecord) {
            validateId(run.id)
            require(ProfileCodec.validate(run.profile).isEmpty()) { "Ongeldig proefprofiel" }
            require(run.startedAtMs >= 0 && (run.endedAtMs == null || run.endedAtMs >= 0)) { "Ongeldige proeftijd" }
            require(run.speed in 1..100 && (run.mode != RunMode.BLE || run.speed == 1)) { "Ongeldige proefsnelheid" }
            require(run.appVersion.length <= 128 && run.buildCommit.length <= 128 && run.deviceInfo.length <= 8192) { "Proefmetadata te groot" }
            require(listOf(run.appVersion, run.buildCommit, run.deviceInfo).all(::wellFormedUnicode)) { "Ongeldige Unicode in proefmetadata" }
        }

        private fun validateEvent(event: RunEvent) {
            require(event.sequence in 1..MAX_EVENTS) { "Ongeldig logvolgnummer" }
            require(event.atEpochMs >= 0 && event.elapsedMs >= 0) { "Ongeldige logtijd" }
            require(event.kind.matches(Regex("[a-z][a-z0-9_]{0,63}"))) { "Ongeldig gebeurtenistype" }
            require(event.frameSequence == null || event.frameSequence > 0) { "Ongeldig framevolgnummer" }
            require(event.trackId == null || event.trackId.length <= 96) { "Track-ID te lang" }
            require(event.payloadHex == null || (event.payloadHex.length <= 480 && event.payloadHex.length % 2 == 0 && event.payloadHex.all { it in "0123456789abcdefABCDEF" })) { "Ongeldige payloadhex" }
            require(event.message.length <= 8192) { "Logbericht te lang" }
            require(event.details.size <= 64 && event.details.all { it.key.length <= 128 && it.value.length <= 4096 }) { "Logcontext te groot" }
            require(wellFormedUnicode(event.message) && wellFormedUnicode(event.trackId.orEmpty()) && event.details.all { wellFormedUnicode(it.key) && wellFormedUnicode(it.value) }) { "Ongeldige Unicode in loggebeurtenis" }
        }

        private fun wellFormedUnicode(text: String): Boolean {
            var index = 0
            while (index < text.length) {
                val char = text[index++]
                if (char.isHighSurrogate()) {
                    if (index >= text.length || !text[index++].isLowSurrogate()) return false
                } else if (char.isLowSurrogate()) return false
            }
            return true
        }

        internal fun scanEvents(file: File, byteLimit: Long, allowIncompleteTail: Boolean = false, consume: (RunEvent) -> Unit = {}): LogIndex {
            require(byteLimit in 0..MAX_LOG_BYTES) { "Logbestand te groot" }
            var index = LogIndex()
            var remaining = byteLimit
            var offset = 0L
            val line = ByteArrayOutputStream()
            FileInputStream(file).buffered(16_384).use { input ->
                while (remaining > 0) {
                    val byte = input.read()
                    if (byte < 0) throw IOException("Logbestand verkort tijdens lezen")
                    remaining--; offset++
                    if (byte == 10) {
                        val event = try { json.decodeFromString<RunEvent>(strictUtf8(line.toByteArray())) } catch (e: Exception) {
                            throw IOException("Beschadigde logregel na volgnummer ${index.lastSequence}", e)
                        }
                        validateEvent(event)
                        require(event.sequence == index.lastSequence + 1) { "Beschadigde logvolgorde" }
                        require(event.elapsedMs >= index.lastElapsedMs) { "Beschadigde monotone logtijd" }
                        consume(event)
                        index = index.withEvent(event).copy(byteLength = offset)
                        line.reset()
                    } else {
                        if (line.size() >= MAX_EVENT_BYTES - 1) throw IOException("Logregel te groot")
                        line.write(byte)
                    }
                }
            }
            if (line.size() > 0) {
                if (!allowIncompleteTail) throw IOException("Onvolledige laatste logregel; herstel bij processtart is vereist")
                index = index.copy(incompleteTail = line.toByteArray())
            }
            return index
        }

        internal fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()

        private fun readBounded(file: File, maximum: Int): ByteArray {
            ensureSafeFile(file)
            require(file.length() <= maximum) { "Bestand te groot: ${file.name}" }
            FileInputStream(file).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maximum) { "Bestand te groot: ${file.name}" }
                    output.write(buffer, 0, count)
                }
                val result = output.toByteArray()
                strictUtf8(result)
                return result
            }
        }

        private fun ensureDirectory(file: File) {
            require(!Files.isSymbolicLink(file.toPath())) { "Symbolische archiefmap niet toegestaan" }
            if (!file.exists()) require(file.mkdirs()) { "Kan archiefmap niet maken" }
            require(file.isDirectory) { "Archiefpad is geen map" }
        }

        private fun ensureSafeFile(file: File) {
            require(!Files.isSymbolicLink(file.toPath())) { "Symbolisch archiefbestand niet toegestaan" }
            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) require(file.isFile) { "Archiefbestand is geen normaal bestand" }
        }

        private fun writeSynced(file: File, bytes: ByteArray) {
            ensureSafeFile(file)
            FileOutputStream(file).use { it.write(bytes); it.flush(); it.fd.sync() }
        }

        private fun atomicWrite(target: File, bytes: ByteArray) {
            ensureSafeFile(target)
            val temporary = File(target.parentFile, ".write-${UUID.randomUUID()}.tmp")
            try {
                writeSynced(temporary, bytes)
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
        }
    }
}

internal data class LogIndex(
    val eventCount: Long = 0,
    val lastSequence: Long = 0,
    val lastElapsedMs: Long = 0,
    val controllerAccepted: Long = 0,
    val simulatedFrames: Long = 0,
    val skippedLate: Long = 0,
    val errorCount: Long = 0,
    val byteLength: Long = 0,
    val incompleteTail: ByteArray = byteArrayOf(),
) {
    fun withEvent(event: RunEvent) = copy(
        eventCount = eventCount + 1,
        lastSequence = event.sequence,
        lastElapsedMs = event.elapsedMs,
        controllerAccepted = controllerAccepted + if (event.kind == "controller_accepted") 1 else 0,
        simulatedFrames = simulatedFrames + if (event.kind == "simulated_frame") 1 else 0,
        skippedLate = skippedLate + if (event.kind == "frame_skipped_late") 1 else 0,
        errorCount = errorCount + if (event.kind == "error" || event.kind.endsWith("_error") || event.kind.endsWith("_failed")) 1 else 0,
    )
    fun summary(run: RunRecord) = RunSummary(run, eventCount, controllerAccepted, simulatedFrames, skippedLate, errorCount)
}

internal data class EvidenceSnapshot(
    val run: RunRecord,
    val summary: RunSummary,
    val log: File,
    val logBytes: Long,
    val attachments: List<File>,
)

