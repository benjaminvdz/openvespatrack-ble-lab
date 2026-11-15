package org.anthrobios.vespasim.core.storage

import java.io.FileInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.anthrobios.vespasim.core.*
import org.anthrobios.vespasim.core.capture.CaptureReplay
import org.anthrobios.vespasim.core.storage.ReportLabel as L

internal object EvidenceExport {
    @Serializable
    private data class Member(val name: String, val bytes: Long, val sha256: String)
    @Serializable
    private data class Manifest(
        val schemaVersion: Int = 1,
        val exportedAtMs: Long,
        val runId: String,
        val runStatusAtExport: RunStatus,
        val throughEventSequence: Long,
        val eventCount: Long,
        val profileSha256: String,
        val appVersion: String,
        val buildCommit: String,
        val syntheticScenario: Boolean = true,
        val actualRfPacketsVerified: Boolean = false,
        val members: List<Member>,
        val notes: List<String>,
    )

    fun bundle(snapshot: EvidenceSnapshot, destination: OutputStream, language: String = "en") {
        val json = LabRepository.json
        val members = mutableListOf<Member>()
        val profile = ProfileCodec.encode(snapshot.run.profile).toByteArray(Charsets.UTF_8)
        ZipOutputStream(NonClosingOutputStream(destination)).use { zip ->
            fun member(name: String, write: (OutputStream) -> Unit) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                val hashing = object : OutputStream() {
                    override fun write(value: Int) { zip.write(value); digest.update(value.toByte()); bytes++ }
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        zip.write(buffer, offset, length)
                        digest.update(buffer, offset, length)
                        bytes += length
                    }
                    override fun close() { /* The ZIP owns the member stream. */ }
                }
                write(hashing)
                zip.closeEntry()
                members += Member(name, bytes, hex(digest.digest()))
            }
            member("run.json") { it.write(json.encodeToString(snapshot.run).toByteArray(Charsets.UTF_8)) }
            member("profile.json") { it.write(profile) }
            member("plan.jsonl") { output ->
                for (frame in Planner.plan(snapshot.run.profile)) {
                    output.write((json.encodeToString(frame) + "\n").toByteArray(Charsets.UTF_8))
                }
            }
            member("events.jsonl") { copyPrefix(snapshot.log, snapshot.logBytes, it) }
            member("events.csv") { csv(snapshot, it) }
            member("report.html") { it.write(report(snapshot, language).toByteArray(Charsets.UTF_8)) }
            for (attachment in snapshot.attachments) {
                member("recovery/${attachment.name}") { copyPrefix(attachment, attachment.length(), it) }
            }
            val manifest = Manifest(
                exportedAtMs = System.currentTimeMillis(),
                runId = snapshot.run.id,
                runStatusAtExport = snapshot.run.status,
                throughEventSequence = snapshot.summary.eventCount,
                eventCount = snapshot.summary.eventCount,
                profileSha256 = hex(MessageDigest.getInstance("SHA-256").digest(profile)),
                appVersion = snapshot.run.appVersion,
                buildCommit = snapshot.run.buildCommit,
                members = members.toList(),
                notes = listOf(
                    "All members use the same recorded run metadata and log boundary; later appends are outside this export.",
                    "plan.jsonl describes the complete planned scenario, including for stopped, running or failed tests.",
                    "Controller acceptance confirms an API operation, not every transmitted or received RF packet.",
                    "All virtual tags share one phone radio; a logical ID is not an independent Bluetooth address.",
                    "Reported relay RSSI is test data, not a local reception measurement; protocol meaning is hypothetical.",
                    "manifest.json is not included in member checksums; no digital signature is claimed.",
                    "CAPTURED profiles preserve original scan bytes, source observations and SHA-256 in profile/plan; new timing and advertising form a synthetic scenario, not an identical RF replay.",
                ),
            )
            zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0L })
            zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.finish()
        }
    }

    fun csv(snapshot: EvidenceSnapshot, destination: OutputStream) {
        val writer = OutputStreamWriter(NonClosingOutputStream(destination), Charsets.UTF_8)
        writer.write("sequence,utc_ms,elapsed_ms,kind,frame_sequence,track_id,payload_hex,message,details_json\r\n")
        LabRepository.scanEvents(snapshot.log, snapshot.logBytes) { event ->
            val fields = listOf(
                event.sequence.toString(), event.atEpochMs.toString(), event.elapsedMs.toString(),
                event.kind, event.frameSequence?.toString().orEmpty(), event.trackId.orEmpty(),
                event.payloadHex.orEmpty(), event.message, LabRepository.json.encodeToString(event.details),
            )
            writer.write(fields.joinToString(",") { csvCell(it) })
            writer.write("\r\n")
        }
        writer.flush()
    }

    fun report(snapshot: EvidenceSnapshot, language: String = "en"): String {
        val t = ReportText(language)
        val run = snapshot.run
        val summary = snapshot.summary
        val profile = run.profile
        val captured = profile.tracks.flatMap { it.capturedFrames }
        val plan = Planner.plan(profile)
        val recent = ArrayDeque<RunEvent>()
        LabRepository.scanEvents(snapshot.log, snapshot.logBytes) {
            recent.addLast(it)
            if (recent.size > REPORT_EVENTS) recent.removeFirst()
        }
        val grouped = plan.filter { it.payloadHex != null }.groupingBy { it.trackId.orEmpty() }.eachCount()
        val quiet = plan.count { it.payloadHex == null }
        val profileHash = hex(MessageDigest.getInstance("SHA-256").digest(ProfileCodec.encode(profile).toByteArray(Charsets.UTF_8)))
        return buildString {
            append("<!doctype html><html lang=\"${t.language}\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; img-src 'none'\">")
            append("<title>${html(t[L.TITLE])}</title><style>")
            append("body{font:15px/1.5 sans-serif;color:#262621;background:#fffef9;margin:28px auto;max-width:1050px;padding:0 18px}h1,h2{color:#795811}h1{margin-bottom:0}.sub{color:#57574e}.notice{border-left:4px solid #795811;background:#f1e5c6;padding:12px}table{border-collapse:collapse;width:100%;margin:14px 0}th,td{text-align:left;vertical-align:top;border-bottom:1px solid #c7c5b9;padding:7px;overflow-wrap:anywhere}th{background:#e9e7dd}code{overflow-wrap:anywhere;white-space:pre-wrap;font:12px monospace}.metric{display:inline-block;padding:12px 18px;background:#e9e7dd;margin:4px 6px 4px 0}svg{width:100%;height:auto}small{font-size:12px}thead{display:table-header-group}@media print{body{font-size:10pt;margin:0;padding:0}h2{break-after:avoid}tr,.notice,svg{break-inside:avoid}.metric{padding:6px}a{color:inherit}}")
            append("</style></head><body><h1>OpenVespaTrack BLE Lab</h1><p class=\"sub\">${html(t[L.REPORT])} · ${html(profile.name)}</p>")
            append("<p class=\"notice\"><strong>${html(t[if (run.mode == RunMode.DRY_RUN) L.DRY else L.BLE])}</strong> — ${html(t[if (captured.isEmpty()) L.SYNTHETIC else L.REPLAY])}</p>")
            for ((value, label) in listOf(summary.eventCount to L.EVENTS, summary.controllerAccepted to L.ACCEPTED, summary.simulatedFrames to L.SIMULATED, summary.skippedLate to L.LATE, summary.errorCount to L.ERRORS))
                append("<div class=\"metric\"><strong>$value</strong> ${html(t[label])}</div>")
            append("<h2>${html(t[L.PROVENANCE])}</h2><table><tbody>")
            row(t[L.RUN_ID], run.id)
            row(t[L.STATUS], statusName(run.status, t))
            row(t[L.START], utc(run.startedAtMs))
            row(t[L.END], run.endedAtMs?.let { utc(it) } ?: t[L.OPEN])
            row(t[L.SCENARIO], t[L.SCENARIO_VALUE, profile.durationMs, profile.slotMs, profile.seed, run.speed])
            row(t[L.RADIO], t[L.RADIO_VALUE, profile.radio.mode, profile.radio.intervalMs, profile.radio.txPowerDbm])
            row(t[L.LOSS], t[L.LOSS_VALUE, profile.lossPercent])
            row(t[L.MANUFACTURER_ID], if (captured.isNotEmpty()) captured.flatMap { it.data.manufacturerData }.map { "0x" + it.companyId.toString(16).padStart(4, '0') }.distinct().joinToString(", ").ifEmpty { t[L.NO_MANUFACTURER] } else if (profile.tracks.all { it.kind == PayloadKind.NAME_ONLY }) t[L.NAME_ONLY] else t[L.MANUFACTURER_VALUE, "0x${profile.companyId.toString(16).uppercase(Locale.ROOT).padStart(4, '0')}"])
            row(t[L.LOCAL_NAME], profile.radio.localName ?: t[L.NO_NAME])
            profile.radio.localName?.let { name -> runCatching { NanoTagName.decode(name) }.getOrNull() }?.let { identity ->
                row(t[L.LOGICAL_ADDRESS], t[L.LOGICAL_VALUE, identity.logicalAddress])
                row(t[L.PARAMETER], t[L.PARAMETER_VALUE, identity.parameter.toString(16).padStart(2, '0').uppercase(Locale.ROOT)])
            }
            row(t[L.BUILD], "${run.appVersion} / ${run.buildCommit}")
            row(t[L.DEVICE], run.deviceInfo)
            row(t[L.HASH], profileHash)
            append("</tbody></table>")
            if (profile.description.isNotEmpty()) append("<h2>${html(t[L.NOTES])}</h2><p>${html(profile.description)}</p>")
            append("<h2>${html(t[L.PLAN])}</h2><p>${html(t[L.PLAN_HELP, plan.size, quiet])}</p>")
            append("<table><thead><tr><th>${html(t[L.TRACK])}</th><th>${html(t[L.TYPE])}</th><th>${html(t[L.IDENTITY])}</th><th>${html(t[L.ACTIVE])}</th></tr></thead><tbody>")
            for (track in profile.tracks) {
                val identity = when (track.kind) {
                    PayloadKind.DIRECT -> t[L.DIRECT_ID, track.shortId]
                    PayloadKind.REPEATER -> t[L.RAW_ID, track.transmitterHex]
                    PayloadKind.RAW -> t[L.RAW_COUNT, track.rawFrames.size]
                    PayloadKind.NAME_ONLY -> t[L.NAME_ID, profile.radio.localName]
                    PayloadKind.CAPTURED -> t[L.CAPTURE_ID, track.capturedFrames.size, track.capturedFrames.firstOrNull()?.captureId]
                }
                append("<tr><td>${html(track.id)}</td><td>${html(track.kind.name)}</td><td>${html(identity)}</td><td>${grouped[track.id] ?: 0}</td></tr>")
            }
            append("</tbody></table>")
            if (captured.isNotEmpty()) {
                append("<h2>${html(t[L.CAPTURE])}</h2><table><thead><tr><th>${html(t[L.OBSERVATION])}</th><th>${html(t[L.ORIGIN])}</th><th>${html(t[L.SOURCE_HASH])}</th></tr></thead><tbody>")
                for (source in captured) append("<tr><td>${html(source.captureId)} / ${source.observation.sequence}</td><td>${html(t[if (source.importedSource) L.IMPORTED else L.LOCAL])}</td><td><code>${html(source.rawSha256)}</code></td></tr>")
                append("</tbody></table>")
                for (difference in CaptureReplay.differences) append("<p>${html(t.replayDifference(difference))}</p>")
            }
            append(rssiChart(plan, profile.durationMs, t))
            append("<h2>${html(t[L.RECENT])}</h2><p>${html(t[L.RECENT_HELP, recent.size, summary.eventCount, summary.eventCount])}</p>")
            append("<table><thead><tr><th>${html(t[L.NUMBER])}</th><th>${html(t[L.EVENT])}</th><th>${html(t[L.TRACK_PAYLOAD])}</th><th>${html(t[L.CONTEXT])}</th></tr></thead><tbody>")
            for (event in recent) {
                append("<tr><td>${event.sequence}<br>${event.elapsedMs}</td><td>${html(event.kind)}</td><td>${html(event.trackId.orEmpty())}<br><code>${html(event.payloadHex.orEmpty())}</code></td><td>${html(event.message)}")
                if (event.details.isNotEmpty()) append("<br><code>${html(LabRepository.json.encodeToString(event.details))}</code>")
                append("</td></tr>")
            }
            append("</tbody></table>")
            if (snapshot.attachments.isNotEmpty()) append("<p class=\"notice\">${html(t[L.RECOVERY, snapshot.attachments.size])}</p>")
            append("<h2>${html(t[L.INTERPRET])}</h2><p>${html(t[L.INTERPRET_HELP])}</p><p>${html(t[L.COMPARE])}</p>")
            append("<p class=\"sub\"><small>${html(t[L.FOOTER])}</small></p></body></html>")
        }
    }


    internal fun csvCell(value: String): String {
        val significant = value.dropWhile { it.isWhitespace() || it.isISOControl() || it == '\uFEFF' }
        val safe = if (significant.firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    internal fun html(value: String): String = buildString(value.length) {
        value.forEach { c -> append(when (c) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&#39;"; else -> c.toString() }) }
    }

    private fun StringBuilder.row(label: String, value: String) {
        append("<tr><th>${html(label)}</th><td>${html(value)}</td></tr>")
    }

    private fun rssiChart(plan: List<PlannedFrame>, durationMs: Long, t: ReportText): String {
        val values = plan.filter { it.reportedRssi != null && it.reportedRssi in -127..20 }
        if (values.isEmpty()) return "<p>${html(t[L.NO_RSSI])}</p>"
        val stride = ((values.size + 299) / 300).coerceAtLeast(1)
        val tracks = values.mapNotNull { it.trackId }.distinct()
        val colors = listOf("#795811", "#315e36", "#754010", "#873d30", "#7153a5", "#3866a0")
        return buildString {
            append("<h2>${html(t[L.RSSI])}</h2><p>${html(t[L.RSSI_HELP])}</p><svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 900 260\" role=\"img\" aria-label=\"${html(t[L.RSSI_A11Y])}\"><rect width=\"900\" height=\"260\" fill=\"#faf9f3\"/>")
            for (level in listOf(-120, -80, -40, 0)) {
                val y = 220.0 - (level + 127) * 190.0 / 147
                append("<line x1=\"58\" y1=\"$y\" x2=\"875\" y2=\"$y\" stroke=\"#ccdada\"/><text x=\"8\" y=\"${y + 4}\" font-size=\"12\">$level</text>")
            }
            values.filterIndexed { index, _ -> index % stride == 0 }.forEach { frame ->
                val x = 58.0 + frame.atMs.toDouble() / durationMs.coerceAtLeast(1) * 817
                val y = 220.0 - (requireNotNull(frame.reportedRssi) + 127) * 190.0 / 147
                val color = colors[tracks.indexOf(frame.trackId).coerceAtLeast(0) % colors.size]
                append("<circle cx=\"$x\" cy=\"$y\" r=\"3\" fill=\"$color\"><title>${html(frame.trackId.orEmpty())}: ${frame.reportedRssi} dBm @ ${frame.atMs} ms</title></circle>")
            }
            append("<text x=\"58\" y=\"246\" font-size=\"12\">0 s</text><text x=\"875\" y=\"246\" text-anchor=\"end\" font-size=\"12\">${durationMs / 1000.0} s</text></svg><p>")
            tracks.forEachIndexed { i, track -> append("<span style=\"color:${colors[i % colors.size]}\">${html(track)}</span> &nbsp; ") }
            append("</p>")
        }
    }

    private fun utc(milliseconds: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(milliseconds))

    private fun statusName(status: RunStatus, t: ReportText): String = when (status) {
        RunStatus.RUNNING -> t[L.RUNNING]
        RunStatus.COMPLETED -> t[L.COMPLETED]
        RunStatus.STOPPED -> t[L.STOPPED]
        RunStatus.FAILED -> t[L.FAILED]
        RunStatus.INTERRUPTED -> t[L.INTERRUPTED]
    }

    private fun copyPrefix(file: java.io.File, length: Long, output: OutputStream) {
        FileInputStream(file).buffered(16_384).use { input ->
            val buffer = ByteArray(16_384)
            var remaining = length
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("Bewijsbestand verkort tijdens export")
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
        override fun close() = flush()
    }
    private const val REPORT_EVENTS = 100
}

