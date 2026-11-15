package org.anthrobios.vespasim.core

import org.anthrobios.vespasim.core.capture.CaptureReplay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

class ProfileValidationException(val errors: List<String>) : IllegalArgumentException(errors.joinToString("\n")) {
    constructor(message: String) : this(listOf(message))
}

/** A bounded, strict and versioned interchange format. Import never executes profile text. */
object ProfileCodec {
    const val MAX_BYTES = 262_144
    const val MAX_FRAMES = 30_000
    const val MAX_DURATION_MS = 7_200_000L
    private val safeId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

    internal fun checkStrictJson(text: String) { StrictJson(text).check() }

    fun decode(text: String): SimulationProfile {
        if (text.length > MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            throw ProfileValidationException("Profiel is groter dan $MAX_BYTES UTF-8 bytes.")
        }
        try {
            StrictJson(text).check()
            val tree = json.parseToJsonElement(text)
            checkPrimitiveTypes(tree)
            return json.decodeFromJsonElement<SimulationProfile>(tree).also { profile ->
                val errors = validate(profile)
                if (errors.isNotEmpty()) throw ProfileValidationException(errors)
            }
        } catch (failure: ProfileValidationException) {
            throw failure
        } catch (failure: SerializationException) {
            throw ProfileValidationException("Ongeldig profiel-JSON: ${failure.message?.take(500)}")
        } catch (failure: IllegalArgumentException) {
            throw ProfileValidationException("Ongeldig profiel: ${failure.message?.take(500)}")
        }
    }

    /** Stable field order, all defaults explicit, lower-case hex; no locale-dependent formatting. */
    fun encode(profile: SimulationProfile): String {
        val errors = validate(profile)
        if (errors.isNotEmpty()) throw ProfileValidationException(errors)
        val normalized = profile.copy(tracks = profile.tracks.map {
            it.copy(transmitterHex = it.transmitterHex.lowercase(), rawFrames = it.rawFrames.map(String::lowercase))
        })
        return json.encodeToString(normalized).also {
            if (it.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw ProfileValidationException("Het geëxporteerde profiel overschrijdt $MAX_BYTES UTF-8 bytes.")
        }
    }

    fun validate(profile: SimulationProfile): List<String> = buildList {
        fun check(condition: Boolean, message: String) { if (!condition) add(message) }
        check(profile.schemaVersion in 1..2, "schemaVersion moet 1 of 2 zijn; onbekende versies worden niet geconverteerd.")
        if (profile.schemaVersion == 2) {
            check(profile.tracks.size == 1 && profile.tracks.all { it.kind == PayloadKind.CAPTURED }, "Schema 2 vereist precies één CAPTURED-track.")
            check(profile.radio.mode == RadioMode.LEGACY_1M, "Opnamereplay ondersteunt uitsluitend legacy 1M.")
        }
        check(safeId.matches(profile.id), "id: gebruik 1–64 ASCII letters/cijfers, punt, streepje of underscore; begin met een letter/cijfer.")
        check(profile.name.isNotBlank() && profile.name.length <= 120 && validText(profile.name, false), "name: vereist, maximaal 120 tekens, zonder besturingstekens.")
        check(profile.description.length <= 4096 && validText(profile.description, true), "description: maximaal 4096 tekens en geen ongeldige Unicode/besturingstekens.")
        check(profile.durationMs in 250..MAX_DURATION_MS, "durationMs moet 250–7200000 zijn (maximaal twee uur).")
        check(profile.slotMs in 250..60_000, "slotMs moet 250–60000 zijn.")
        check(profile.companyId in 0..65535, "companyId moet 0–65535 zijn.")
        check(profile.radio.intervalMs in 100..10_240, "radio.intervalMs moet 100–10240 zijn.")
        check(profile.radio.txPowerDbm in -127..1, "radio.txPowerDbm moet -127–1 zijn voor ondersteuning vanaf Android 8; hardware kan dit verder beperken.")
        val localName = profile.radio.localName
        check(localName == null || (localName.isNotBlank() && validText(localName, false) && localName.toByteArray(Charsets.UTF_8).size <= 248), "radio.localName: null of een niet-lege naam van maximaal 248 UTF-8 bytes zonder besturingstekens.")
        check(profile.lossPercent in 0..100, "lossPercent moet 0–100 zijn; dit zijn geplande omissies, geen gemeten RF-verliezen.")
        check(profile.tracks.size in 1..32, "tracks moet 1–32 virtuele tags bevatten.")
        check(profile.tracks.map { it.id }.distinct().size == profile.tracks.size, "Elke track.id moet uniek zijn; shortId mag bewust gelijk zijn.")
        check(profile.pauses.size <= 512, "Maximaal 512 pauze-intervallen toegestaan.")
        val validSlot = profile.slotMs in 250..60_000
        if (validSlot && profile.durationMs > 0) {
            check((profile.durationMs - 1) / profile.slotMs < MAX_FRAMES, "Scenario bevat meer dan $MAX_FRAMES slots.")
        }
        val maximumPayload = if (profile.radio.mode == RadioMode.LEGACY_1M) 24 else 240
        for ((index, track) in profile.tracks.take(33).withIndex()) {
            val path = "tracks[$index]"
            check(safeId.matches(track.id), "$path.id: ongeldig of langer dan 64 tekens.")
            check(track.subtype in 0..3, "$path.subtype moet 0–3 zijn.")
            check(track.shortId in 0..65535, "$path.shortId moet 0–65535 zijn.")
            check(track.transmitterHex.length == 12 && track.transmitterHex.all(::isHex), "$path.transmitterHex vereist precies 12 hextekens (zes ruwe bytes).")
            val maxCounter = if (track.counterMode == CounterMode.RAW24) 16_777_215L else 65_535L
            check(track.counterStart in 0..maxCounter, "$path.counterStart past niet in de gekozen tellerbreedte.")
            check(track.counterStep in -16_777_215L..16_777_215L, "$path.counterStep moet -16777215–16777215 zijn.")
            check(track.counterResetEvery in 0..MAX_FRAMES, "$path.counterResetEvery moet 0–$MAX_FRAMES zijn.")
            check(track.temperatureRaw in 0..255, "$path.temperatureRaw moet 0–255 zijn; geen bevestigde temperatuureenheid.")
            check(track.rssi.min in -128..127 && track.rssi.max in -128..127 && track.rssi.min <= track.rssi.max, "$path.rssi: gebruik een oplopend signed-bytebereik -128–127.")
            check(track.rssi.periodSlots in 2..MAX_FRAMES, "$path.rssi.periodSlots moet 2–$MAX_FRAMES zijn.")
            check(track.rssi.unavailableEvery in 0..MAX_FRAMES, "$path.rssi.unavailableEvery moet 0–$MAX_FRAMES zijn.")
            check(track.rawFrames.size <= 256, "$path.rawFrames mag maximaal 256 frames bevatten.")
            if (track.kind == PayloadKind.RAW) check(track.rawFrames.isNotEmpty(), "$path: RAW vereist minstens één rawFrames-element; gebruik een lege string voor nul bytes.")
            else check(track.rawFrames.isEmpty(), "$path: rawFrames is alleen toegestaan bij kind RAW.")
            if (track.kind == PayloadKind.NAME_ONLY) check(localName != null, "$path: NAME_ONLY vereist radio.localName; er wordt geen manufacturer-data uitgezonden.")
            if (track.kind == PayloadKind.CAPTURED) {
                check(profile.schemaVersion == 2, "$path: CAPTURED vereist expliciet schemaVersion 2.")
                check(track.capturedFrames.size in 1..CaptureReplay.MAX_SAMPLES, "$path.capturedFrames vereist 1–${CaptureReplay.MAX_SAMPLES} bronnen.")
                for ((sampleIndex, source) in track.capturedFrames.take(CaptureReplay.MAX_SAMPLES + 1).withIndex()) {
                    try {
                        CaptureReplay.validateAdvertisement(source)
                        check(source.data.localName == localName, "$path.capturedFrames[$sampleIndex]: Local Name verschilt van radio.localName.")
                    } catch (failure: IllegalArgumentException) {
                        add("$path.capturedFrames[$sampleIndex]: ${failure.message}")
                    }
                }
                val sources = track.capturedFrames.take(CaptureReplay.MAX_SAMPLES + 1)
                check(sources.map { it.captureId }.distinct().size <= 1 && sources.map { it.observation.address.lowercase() }.distinct().size <= 1 && sources.map { it.importedSource }.distinct().size <= 1, "$path: alle bronwaarnemingen moeten uit dezelfde opname en van hetzelfde radioadres komen.")
                check(sources.zipWithNext().all { (a, b) -> a.observation.sequence < b.observation.sequence }, "$path: bronwaarnemingen moeten hun oorspronkelijke strikt oplopende volgorde behouden.")
            } else check(track.capturedFrames.isEmpty(), "$path: capturedFrames is alleen toegestaan bij CAPTURED.")
            val payloadSizes = when (track.kind) {
                PayloadKind.DIRECT -> listOf(6)
                PayloadKind.REPEATER -> listOf(11)
                PayloadKind.RAW -> track.rawFrames.map { it.length / 2 }
                PayloadKind.NAME_ONLY -> listOf(0)
                PayloadKind.CAPTURED -> emptyList()
            }
            check(payloadSizes.all { AdvertisingLayout.byteSize(it, track.kind != PayloadKind.NAME_ONLY, localName) <= AdvertisingLayout.maximumBytes(profile.radio.mode) }, "$path: naam en manufacturer-data samen overschrijden het advertentiebudget (${AdvertisingLayout.maximumBytes(profile.radio.mode)} bytes, inclusief gereserveerde Flags).")
            for ((frameIndex, raw) in track.rawFrames.take(257).withIndex()) {
                check(raw.length <= maximumPayload * 2 && raw.length % 2 == 0 && raw.all(::isHex), "$path.rawFrames[$frameIndex]: even aantal hextekens, maximaal $maximumPayload bytes voor deze radio-modus.")
            }
            check(track.activeFromMs >= 0 && track.activeFromMs < profile.durationMs, "$path.activeFromMs moet binnen de scenarioduur vallen.")
            val until = track.activeUntilMs ?: profile.durationMs
            check(until > track.activeFromMs && until <= profile.durationMs, "$path.activeUntilMs moet na activeFromMs liggen en niet na de scenarioduur.")
            if (validSlot) {
                check(track.activeFromMs % profile.slotMs == 0L, "$path.activeFromMs moet op een slotgrens liggen.")
                check(until == profile.durationMs || until % profile.slotMs == 0L, "$path.activeUntilMs moet op een slotgrens of op het scenario-einde liggen.")
            }
        }
        var previousEnd = -1L
        for ((index, pause) in profile.pauses.take(513).withIndex()) {
            check(pause.fromMs >= 0 && pause.untilMs > pause.fromMs && pause.untilMs <= profile.durationMs, "pauses[$index]: vereist 0 ≤ fromMs < untilMs ≤ durationMs.")
            check(pause.fromMs >= previousEnd, "pauses[$index]: pauzes moeten oplopend en niet-overlappend zijn.")
            if (validSlot) {
                check(pause.fromMs % profile.slotMs == 0L, "pauses[$index].fromMs moet op een slotgrens liggen.")
                check(pause.untilMs == profile.durationMs || pause.untilMs % profile.slotMs == 0L, "pauses[$index].untilMs moet op een slotgrens of scenario-einde liggen.")
            }
            previousEnd = pause.untilMs
        }
    }

    // Serialization libraries can accept quoted numbers; interchange profiles deliberately do not.
    private val integerFields = setOf("schemaVersion", "seed", "durationMs", "slotMs", "companyId", "lossPercent", "intervalMs", "txPowerDbm", "subtype", "shortId", "counterStart", "counterStep", "temperatureRaw", "counterResetEvery", "activeFromMs", "activeUntilMs", "min", "max", "periodSlots", "unavailableEvery", "fromMs", "untilMs", "sequence", "receivedAtMs", "timestampNanos", "rssiRaw", "primaryPhy", "secondaryPhy", "advertisingSid", "txPowerRaw", "dataStatus", "periodicInterval", "callbackType")
    private val stringFields = setOf("id", "name", "description", "mode", "kind", "transmitterHex", "counterMode", "captureId", "rawSha256", "address")
    private val integerLiteral = Regex("-?(0|[1-9][0-9]*)")
    private fun checkPrimitiveTypes(element: JsonElement) {
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                if (key in integerFields && !(key == "activeUntilMs" && value == JsonNull)) {
                    if (value !is JsonPrimitive || value.isString || !integerLiteral.matches(value.content)) throw ProfileValidationException("$key vereist een geheel JSON-getal, geen string/fractie/exponent.")
                }
                if (key in stringFields && (value !is JsonPrimitive || !value.isString)) throw ProfileValidationException("$key vereist een JSON-string.")
                if (key in setOf("legacy", "connectable", "importedSource") && (value !is JsonPrimitive || value.isString || value.content !in setOf("true", "false"))) throw ProfileValidationException("$key vereist een JSON-boolean.")
                if (key in setOf("localName", "deviceName", "rawHex") && value != JsonNull && (value !is JsonPrimitive || !value.isString)) throw ProfileValidationException("localName vereist een JSON-string of null.")
                if (key == "rawFrames" && value is JsonArray && value.any { it !is JsonPrimitive || !it.isString }) throw ProfileValidationException("rawFrames vereist uitsluitend hexstrings.")
                checkPrimitiveTypes(value)
            }
            is JsonArray -> element.forEach(::checkPrimitiveTypes)
            else -> Unit
        }
    }

    private fun isHex(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

    private fun validText(text: String, multiline: Boolean): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (char.code < 32 && !(multiline && char in "\n\r\t") || char.code == 127) return false
            if (char.isHighSurrogate()) {
                if (index >= text.length || !text[index++].isLowSurrogate()) return false
            } else if (char.isLowSurrogate()) return false
        }
        return true
    }

    /** Reject duplicate keys/deep structures before the serialization parser allocates a model. */
    private class StrictJson(private val source: String) {
        private var index = 0
        private var tokens = 0
        fun check() {
            whitespace()
            if (source.getOrNull(index) != '{') bad("Profiel moet een JSON-object zijn")
            value(0)
            whitespace()
            if (index != source.length) bad("Extra tekst na JSON-object")
        }
        private fun bad(message: String): Nothing = throw ProfileValidationException("$message op positie $index.")
        private fun whitespace() { while (source.getOrNull(index) in listOf(' ', '\t', '\n', '\r')) index++ }
        private fun take(expected: Char) {
            whitespace()
            if (source.getOrNull(index) != expected) bad("Verwacht '$expected'")
            index++
        }
        private fun value(depth: Int) {
            if (depth > 24) bad("JSON is te diep genest")
            if (++tokens > 50_000) bad("JSON bevat te veel waarden")
            whitespace()
            when (source.getOrNull(index)) {
                '{' -> {
                    index++
                    val keys = HashSet<String>()
                    whitespace()
                    if (source.getOrNull(index) == '}') { index++; return }
                    while (true) {
                        whitespace()
                        val key = string()
                        if (key.length > 128) bad("JSON-sleutel is te lang")
                        if (!keys.add(key)) bad("Dubbele JSON-sleutel '$key'")
                        take(':'); value(depth + 1); whitespace()
                        if (source.getOrNull(index) == '}') { index++; break }
                        take(',')
                    }
                }
                '[' -> {
                    index++; whitespace()
                    if (source.getOrNull(index) == ']') { index++; return }
                    while (true) {
                        value(depth + 1); whitespace()
                        if (source.getOrNull(index) == ']') { index++; break }
                        take(',')
                    }
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> bad("Ongeldige JSON-waarde")
            }
        }
        private fun literal(expected: String) {
            if (!source.startsWith(expected, index)) bad("Ongeldige JSON-waarde")
            index += expected.length
        }
        private fun string(): String {
            if (source.getOrNull(index) != '"') bad("Verwacht een JSON-string")
            index++
            val result = StringBuilder()
            while (index < source.length) {
                val char = source[index++]
                if (char == '"') {
                    if (!validUnicode(result)) bad("Ongeldige Unicode in JSON-string")
                    return result.toString()
                }
                if (char.code < 32) bad("Besturingsteken in JSON-string")
                if (char == '\\') {
                    val escaped = source.getOrNull(index++) ?: bad("Afgebroken escape")
                    result.append(when (escaped) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> {
                            if (index + 4 > source.length) bad("Afgebroken Unicode-escape")
                            val hex = source.substring(index, index + 4)
                            if (hex.any { !isHex(it) }) bad("Ongeldige Unicode-escape")
                            index += 4
                            hex.toInt(16).toChar()
                        }
                        else -> bad("Ongeldige JSON-escape")
                    })
                } else result.append(char)
                if (result.length > 16_384) bad("JSON-string is te lang")
            }
            bad("Niet-afgesloten JSON-string")
        }
        private fun validUnicode(value: CharSequence): Boolean {
            var i = 0
            while (i < value.length) {
                val c = value[i++]
                if (c.isHighSurrogate()) {
                    if (i >= value.length || !value[i++].isLowSurrogate()) return false
                } else if (c.isLowSurrogate()) return false
            }
            return true
        }
        private fun number() {
            if (source.getOrNull(index) == '-') index++
            when (source.getOrNull(index)) {
                '0' -> index++
                in '1'..'9' -> while (source.getOrNull(index) in '0'..'9') index++
                else -> bad("Ongeldig JSON-getal")
            }
            if (source.getOrNull(index) == '.') {
                index++
                if (source.getOrNull(index) !in '0'..'9') bad("Ongeldige fractie")
                while (source.getOrNull(index) in '0'..'9') index++
            }
            if (source.getOrNull(index) in listOf('e', 'E')) {
                index++
                if (source.getOrNull(index) in listOf('+', '-')) index++
                if (source.getOrNull(index) !in '0'..'9') bad("Ongeldige exponent")
                while (source.getOrNull(index) in '0'..'9') index++
            }
        }
    }
}
