package org.anthrobios.vespasim.core.capture

import org.anthrobios.vespasim.core.AdvertisingLayout
import org.anthrobios.vespasim.core.NanoTagName
import org.anthrobios.vespasim.core.Protocol
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.UUID

/** Parses original Android scan bytes, preserving their TLV ordering and unresolved fields. */
object CaptureParser {
    private val rangeService = "1640e2e3-5513-4323-bd54-39f4ff3fc100"

    fun inspect(observation: CaptureObservation): CaptureInspection {
        val elements = mutableListOf<AdElement>()
        var reason: String? = null
        fun reject(message: String) { if (reason == null) reason = message }
        var name: String? = null
        var nameSeen = false
        var flagsSeen = false
        val manufacturer = mutableListOf<ManufacturerElement>()
        val services = mutableListOf<String>()
        val serviceData = mutableListOf<ServiceDataElement>()
        val raw = observation.rawHex
        val bytes = if (raw == null) { reject("Android leverde geen oorspronkelijke scanbytes"); byteArrayOf() }
        else try { bytes(raw) } catch (error: IllegalArgumentException) { reject(error.message ?: "Ongeldige scanbytes"); byteArrayOf() }
        var offset = 0
        while (offset < bytes.size) {
            val length = bytes[offset].toInt() and 255
            if (length == 0) {
                if (bytes.drop(offset + 1).any { it.toInt() != 0 }) reject("Niet-nulbytes na AD-eindmarkering; scheiding is niet ondubbelzinnig")
                break
            }
            if (offset + 1 + length > bytes.size) { reject("Afgebroken AD-veld op byte $offset"); break }
            val type = bytes[offset + 1].toInt() and 255
            val data = bytes.copyOfRange(offset + 2, offset + 1 + length)
            elements += AdElement(offset, type, Protocol.hex(data))
            try {
                when (type) {
                    0x01 -> {
                        require(!flagsSeen && data.size == 1) { "Dubbele of ongeldige Flags" }
                        flagsSeen = true
                    }
                    0x08, 0x09 -> {
                        require(!nameSeen) { "Dubbele of tegenstrijdige Local Name-velden" }
                        nameSeen = true
                        name = utf8(data)
                        require(!name.isBlank() && CaptureCodec.text(name, 248, false)) { "Ongeldige Local Name" }
                        require(type == 0x09) { "Verkorte Local Name kan niet exact als volledige naam worden gereconstrueerd" }
                    }
                    0xff -> {
                        require(data.size >= 2) { "Manufacturer-data mist het company-ID" }
                        val id = (data[0].toInt() and 255) or ((data[1].toInt() and 255) shl 8)
                        require(manufacturer.none { it.companyId == id }) { "Herhaald manufacturer-ID: Android zou velden samenvoegen" }
                        manufacturer += ManufacturerElement(id, Protocol.hex(data.copyOfRange(2, data.size)))
                    }
                    0x02, 0x03, 0x04, 0x05, 0x06, 0x07 -> {
                        val width = when (type) { 0x02, 0x03 -> 2; 0x04, 0x05 -> 4; else -> 16 }
                        require(data.isNotEmpty() && data.size % width == 0) { "Ongeldige service-UUID-lijst" }
                        for (i in data.indices step width) {
                            val uuid = uuid(data.copyOfRange(i, i + width))
                            require(uuid !in services) { "Herhaalde service-UUID" }
                            services += uuid
                        }
                    }
                    0x16, 0x20, 0x21 -> {
                        val width = when (type) { 0x16 -> 2; 0x20 -> 4; else -> 16 }
                        require(data.size >= width) { "Servicedata mist een volledige UUID" }
                        val uuid = uuid(data.copyOfRange(0, width))
                        require(serviceData.none { it.uuid == uuid }) { "Herhaalde servicedata-UUID: Android zou een veld vervangen" }
                        serviceData += ServiceDataElement(uuid, Protocol.hex(data.copyOfRange(width, data.size)))
                    }
                    0x0a -> reject("Opgenomen Tx Power-AD kan Android niet met de oorspronkelijke waarde heruitzenden")
                    0x19 -> reject("Appearance-AD heeft geen passende openbare Android-uitzendfunctie")
                    else -> reject("AD-type 0x${type.toString(16).padStart(2, '0')} wordt niet stil weggelaten: heruitzenden niet ondersteund")
                }
            } catch (error: IllegalArgumentException) { reject(error.message ?: "Ongeldig AD-veld") }
            offset += length + 1
        }
        val payload = AdvertisingPayload(name, manufacturer.toList(), services.toList(), serviceData.toList())
        if (!observation.legacy) reject("Extended advertising blijft bewaard; deze replay ondersteunt alleen legacy 1M")
        if (observation.dataStatus != 0) reject("Android meldt onvolledige of afgebroken advertisingdata")
        if (observation.primaryPhy != 1) reject("De oorspronkelijke primary PHY is geen legacy 1M")
        if (name == null && manufacturer.isEmpty() && services.isEmpty() && serviceData.isEmpty()) reject("Geen ondersteunde advertentie-inhoud gevonden")
        if (AdvertisingLayout.byteSize(payload) > 31) reject("Samengevoegde advertentie-inhoud past niet in 31 bytes inclusief gereserveerde Flags")
        val nano = name?.let { it.startsWith("vn") || it.startsWith("VESPA-NANO") } == true
        val fc65 = manufacturer.any { it.companyId == 0xfc65 }
        val vespa = nano || fc65 || name?.let { it.startsWith("VESPA-RANGE") || it.startsWith("vespa-") } == true || rangeService in services
        val logical = name?.let { runCatching { NanoTagName.decode(it).logicalAddress }.getOrNull() }
        return CaptureInspection(elements.toList(), name, logical, nano, vespa, fc65, reason, if (reason == null) payload else null)
    }

    fun requireReplay(observation: CaptureObservation): AdvertisingPayload {
        CaptureCodec.validateObservation(observation)
        val inspection = inspect(observation)
        require(inspection.replayable) { inspection.replayReason ?: "Geen ondersteunde advertentie" }
        return requireNotNull(inspection.payload)
    }

    fun bytes(rawHex: String): ByteArray {
        require(rawHex.length <= CaptureCodec.MAX_RAW_BYTES * 2 && rawHex.length % 2 == 0 && rawHex.all(CaptureCodec::hex)) { "Ongeldige of te grote scanhex" }
        return ByteArray(rawHex.length / 2) { rawHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Bluetooth AD UUID bytes are little-endian, including the full 128-bit integer. */
    private fun uuid(bytes: ByteArray): String = when (bytes.size) {
        2, 4 -> {
            var value = 0L
            for (i in bytes.indices) value = value or ((bytes[i].toLong() and 255) shl (i * 8))
            "${value.toString(16).padStart(8, '0')}-0000-1000-8000-00805f9b34fb"
        }
        16 -> {
            val hex = Protocol.hex(bytes.reversedArray())
            UUID.fromString("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}").toString().lowercase(Locale.ROOT)
        }
        else -> error("UUID vereist 2, 4 of 16 bytes")
    }
    private fun utf8(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("Local Name bevat ongeldige UTF-8") }
    }
}
