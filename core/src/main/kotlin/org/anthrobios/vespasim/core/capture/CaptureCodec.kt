package org.anthrobios.vespasim.core.capture

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.anthrobios.vespasim.core.ProfileCodec
import org.anthrobios.vespasim.core.RunStatus

/** Strict bounded interchange for original scan evidence; no input executes radio actions. */
object CaptureCodec {
    const val MAX_RAW_BYTES = 1650
    const val MAX_RECORD_BYTES = 65_536
    const val MAX_OBSERVATION_BYTES = 8192
    const val MAX_EVENT_BYTES = 16_384
    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val addressPattern = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val integers = setOf("sequence", "receivedAtMs", "timestampNanos", "rssiRaw", "primaryPhy", "secondaryPhy", "advertisingSid", "txPowerRaw", "dataStatus", "periodicInterval", "callbackType", "startedAtMs", "startedElapsedNanos", "endedAtMs", "schemaVersion", "atEpochMs")
    private val booleans = setOf("legacy", "connectable")
    private val integerPattern = Regex("-?(0|[1-9][0-9]*)")

    fun encodeRecord(record: CaptureRecord): String { validateRecord(record); return bounded(json.encodeToString(record), MAX_RECORD_BYTES) }
    fun decodeRecord(text: String): CaptureRecord = json.decodeFromJsonElement<CaptureRecord>(tree(text, MAX_RECORD_BYTES)).also(::validateRecord)
    fun encodeObservation(observation: CaptureObservation): String { validateObservation(observation); return bounded(json.encodeToString(observation), MAX_OBSERVATION_BYTES) }
    fun decodeObservation(text: String): CaptureObservation = json.decodeFromJsonElement<CaptureObservation>(tree(text, MAX_OBSERVATION_BYTES)).also(::validateObservation)
    fun encodeEvent(event: CaptureEvent): String { validateEvent(event); return bounded(json.encodeToString(event), MAX_EVENT_BYTES) }
    fun decodeEvent(text: String): CaptureEvent = json.decodeFromJsonElement<CaptureEvent>(tree(text, MAX_EVENT_BYTES)).also(::validateEvent)

    /** Syntax, duplicate keys, bounded depth and valid Unicode, independent of any capture schema. */
    fun checkJson(text: String, maximumBytes: Int = MAX_RECORD_BYTES) {
        require(maximumBytes in 1..16_777_216) { "Ongeldige JSON-limiet" }
        bounded(text, maximumBytes)
        ProfileCodec.checkStrictJson(text)
    }

    fun validateRecord(value: CaptureRecord) {
        require(value.schemaVersion == 1) { "Onbekende opnameversie" }
        require(idPattern.matches(value.id)) { "Ongeldige opname-ID" }
        require(value.name.isNotBlank() && text(value.name, 120, false)) { "Opnamenaam vereist 1–120 geldige tekens" }
        require(addressPattern.matches(value.address)) { "Ongeldig geselecteerd radioadres" }
        require(value.startedAtMs >= 0 && value.startedElapsedNanos >= 0 && (value.endedAtMs == null || value.endedAtMs >= 0)) { "Ongeldig opnametijdstip" }
        require((value.status == RunStatus.RUNNING) == (value.endedAtMs == null)) { "Opnamestatus en eindtijd komen niet overeen" }
        require(value.tagVariant in setOf("android", "iphone", "range_pro", "unknown")) { "Onbekende tagvariant" }
        require(text(value.notes, 4096, true) && text(value.deviceInfo, 4096, true)) { "Te lange of ongeldige notities/toestelgegevens" }
        require(text(value.appVersion, 64, false) && text(value.buildCommit, 128, false)) { "Ongeldige buildgegevens" }
        require(value.settings.size <= 64 && value.settings.all { text(it.key, 128, false) && text(it.value, 512, true) }) { "Te grote of ongeldige opname-instellingen" }
        require(value.importedFromId == null || idPattern.matches(value.importedFromId)) { "Ongeldige importbron-ID" }
        require(value.importedArchiveSha256 == null || hashPattern.matches(value.importedArchiveSha256)) { "Ongeldige importbronhash" }
        require((value.importedFromId == null) == (value.importedArchiveSha256 == null)) { "Importbron en archiefhash moeten samen aanwezig zijn" }
    }

    fun validateObservation(value: CaptureObservation) {
        require(value.sequence > 0 && value.receivedAtMs >= 0 && value.timestampNanos >= 0) { "Ongeldig waarnemingsnummer of tijdstip" }
        require(addressPattern.matches(value.address)) { "Ongeldig waargenomen radioadres" }
        require(value.rawHex == null || (value.rawHex.length <= MAX_RAW_BYTES * 2 && value.rawHex.length % 2 == 0 && value.rawHex.all(::hex))) { "Ongeldige of te grote oorspronkelijke scanbytes" }
        require(value.deviceName == null || (value.deviceName.length <= 1024 && validUnicode(value.deviceName))) { "Ongeldige gecachte toestelnaam" }
        // RSSI/PHY/data status are unmodified platform integers; eligibility is a separate interpretation.
    }

    fun validateEvent(value: CaptureEvent) {
        require(value.sequence > 0 && value.atEpochMs >= 0) { "Ongeldig lognummer of tijdstip" }
        require(value.kind.isNotBlank() && text(value.kind, 80, false)) { "Ongeldig logtype" }
        require(text(value.message, 4096, true)) { "Ongeldig of te lang logbericht" }
        require(value.details.size <= 32 && value.details.all { text(it.key, 128, false) && text(it.value, 1024, true) }) { "Te grote of ongeldige logdetails" }
    }

    private fun bounded(text: String, limit: Int): String {
        require(text.length <= limit && text.toByteArray(Charsets.UTF_8).size <= limit) { "Opname-JSON overschrijdt $limit bytes" }
        return text
    }
    private fun tree(source: String, limit: Int): JsonElement {
        checkJson(source, limit)
        val root = json.parseToJsonElement(source)
        require(root is JsonObject) { "Opname-JSON vereist een object" }
        primitiveTypes(root)
        return root
    }
    private fun primitiveTypes(value: JsonElement, stringMap: Boolean = false) {
        when (value) {
            is JsonObject -> value.forEach { (key, child) ->
                if (stringMap) require(child is JsonPrimitive && child.isString) { "$key vereist een tekstwaarde" }
                else if (key in integers && child != JsonNull) require(child is JsonPrimitive && !child.isString && integerPattern.matches(child.content)) { "$key vereist een geheel JSON-getal" }
                else if (key in booleans) require(child is JsonPrimitive && !child.isString && child.content in setOf("true", "false")) { "$key vereist een JSON-boolean" }
                else if (child is JsonPrimitive && child != JsonNull) require(child.isString) { "$key vereist een JSON-string" }
                primitiveTypes(child, key in setOf("settings", "details"))
            }
            is JsonArray -> value.forEach { primitiveTypes(it, stringMap) }
            else -> Unit
        }
    }
    internal fun text(value: String, maximum: Int, multiline: Boolean): Boolean =
        value.length <= maximum && validUnicode(value) && value.all { it.code >= 32 && it.code != 127 || multiline && it in "\r\n\t" }
    internal fun validUnicode(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (c.isHighSurrogate()) { if (i >= value.length || !value[i++].isLowSurrogate()) return false }
            else if (c.isLowSurrogate()) return false
        }
        return true
    }
    internal fun hex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}
