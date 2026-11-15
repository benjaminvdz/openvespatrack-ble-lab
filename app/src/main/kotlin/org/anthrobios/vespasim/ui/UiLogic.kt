package org.anthrobios.vespasim.ui

import org.anthrobios.vespasim.core.ProfileCodec
import org.anthrobios.vespasim.core.SimulationProfile
import org.anthrobios.vespasim.core.RunRecord
import org.anthrobios.vespasim.core.PlannedFrame
import org.anthrobios.vespasim.core.PayloadKind
import org.anthrobios.vespasim.core.NanoTagName
import org.anthrobios.vespasim.core.RadioSettings
import org.anthrobios.vespasim.core.TagTrack
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.Locale

typealias UiFormatter = (UiLogic.Label, List<Any?>) -> String

/** File and form rules kept independent of Android for deterministic tests. */
object UiLogic {
    const val MAX_PROFILE_BYTES = 262_144

    enum class Label(val english: String) {
        SILENCE("SILENCE: %1\$s"),
        PAUSE("planned pause"),
        INACTIVE("no active track"),
        OMISSION("deterministic omission"),
        CAPTURED("Captured advertising content · controlled reconstruction\n"),
        SOURCE("Source: %1\$s / observation %2\$s\n"),
        ADDRESS("Source address: %1\$s (not copied)\n"),
        HASH("Source bytes SHA-256: %1\$s\n"),
        LOCAL_NAME("Local Name: %1\$s"),
        NOT_RECORDED("not recorded"),
        EMPTY("(zero bytes)"),
        MANUFACTURER("Manufacturer 0x%1\$s: %2\$s\n"),
        SERVICE_UUID("Service UUID: %1\$s\n"),
        SERVICE_DATA("Service data %1\$s: %2\$s\n"),
        NOT_REPRODUCED("Original RSSI and radio timing are not reproduced."),
        NAME_ONLY("Local Name only: %1\$s\n%2\$sNo manufacturer data or GATT telemetry"),
        PAYLOAD("Manufacturer payload: %1\$s"),
        CAPTURED_FIELDS("Captured advertising fields; source bytes and SHA-256 are in the profile"),
        NO_MANUFACTURER("No manufacturer data (name only)"),
        MANUFACTURER_EXCEPT("Manufacturer 0x%1\$s (except NAME_ONLY)"),
        IDENTITY("Logical tag address: %1\$s\nPP: %2\$s (meaning unknown)\n"),
        PARAMETER_ERROR("PP requires exactly two hexadecimal characters (00–FF)."),
        INVALID_PROFILE("Invalid name-encoded tag profile."),
        PROVIDER_ERROR("The file provider is not supplying data."),
        SIZE_ERROR("The profile exceeds 256 KiB."),
        DURATION_ERROR("Duration must be an integer number of milliseconds."),
        SLOT_ERROR("Slot duration must be an integer number of milliseconds."),
        LOSS_ERROR("Omissions must be an integer percentage."),
        SEED_ERROR("Seed must be an integer."),
        EXTENSION_ERROR("Invalid file extension."),
        ADDRESS_ERROR("The logical tag address requires six hexadecimal octets, for example AA:BB:CC:DD:EE:FF."),
        TAG_NAME("Name-encoded tag · %1\$s"),
        TAG_DESCRIPTION("Synthetic name-encoded tag. Logical identity %1\$s, PP %2\$s (hardware meaning unknown), advertised name %3\$s. Local Name only; no manufacturer data or GATT. The receiver measures RSSI. Hardware compatibility is unverified.");
    }
    private fun english(label: Label, args: List<Any?>): String = String.format(Locale.ROOT, label.english, *args.toTypedArray())


    fun frameDescription(frame: PlannedFrame, format: UiFormatter = ::english): String {
        fun t(label: Label, vararg args: Any?) = format(label, args.toList())
        return when {
            frame.payloadHex == null -> t(Label.SILENCE, when (frame.silenceReason) {
                "planned_pause" -> t(Label.PAUSE)
                "no_active_track" -> t(Label.INACTIVE)
                "seeded_omission" -> t(Label.OMISSION)
                else -> frame.silenceReason.orEmpty()
            })
            frame.capturedAdvertisement != null -> requireNotNull(frame.capturedAdvertisement).let { source ->
                val data = source.data
                buildString {
                    append(t(Label.CAPTURED))
                    append(t(Label.SOURCE, source.captureId, source.observation.sequence))
                    append(t(Label.ADDRESS, source.observation.address))
                    append(t(Label.HASH, source.rawSha256))
                    append(t(Label.LOCAL_NAME, data.localName ?: t(Label.NOT_RECORDED))).append('\n')
                    data.manufacturerData.forEach { append(t(Label.MANUFACTURER, it.companyId.toString(16).padStart(4, '0'), it.dataHex.ifEmpty { t(Label.EMPTY) })) }
                    data.serviceUuids.forEach { append(t(Label.SERVICE_UUID, it)) }
                    data.serviceData.forEach { append(t(Label.SERVICE_DATA, it.uuid, it.dataHex.ifEmpty { t(Label.EMPTY) })) }
                    append(t(Label.NOT_REPRODUCED))
                }
            }
            !frame.includeManufacturerData -> t(Label.NAME_ONLY, frame.localName, nanoIdentityDescription(frame.localName, format))
            else -> t(Label.PAYLOAD, frame.payloadHex.orEmpty().ifEmpty { t(Label.EMPTY) }) + "\n" +
                t(Label.LOCAL_NAME, frame.localName ?: t(Label.NOT_RECORDED))
        }
    }

    fun manufacturerDescription(profile: SimulationProfile, format: UiFormatter = ::english): String = when {
        profile.tracks.any { it.kind == PayloadKind.CAPTURED } -> format(Label.CAPTURED_FIELDS, emptyList())
        profile.tracks.all { it.kind == PayloadKind.NAME_ONLY } -> format(Label.NO_MANUFACTURER, emptyList())
        else -> format(Label.MANUFACTURER_EXCEPT, listOf(profile.companyId.toString(16).uppercase(Locale.ROOT)))
    }

    fun nanoIdentityDescription(name: String?, format: UiFormatter = ::english): String = name?.let {
        runCatching { NanoTagName.decode(it) }.getOrNull()?.let { identity ->
            format(Label.IDENTITY, listOf(identity.logicalAddress, identity.parameter.toString(16).padStart(2, '0').uppercase(Locale.ROOT)))
        }
    }.orEmpty()

    fun nanoProfile(logicalAddress: String, parameterHex: String, id: String, format: UiFormatter = ::english): SimulationProfile {
        val parameter = parameterHex.trim()
        require(parameter.matches(Regex("[0-9A-Fa-f]{2}"))) { Label.PARAMETER_ERROR.english }
        val name = NanoTagName.encode(logicalAddress.trim(), parameter.toInt(16))
        val identity = NanoTagName.decode(name)
        return SimulationProfile(
            id = id,
            name = format(Label.TAG_NAME, listOf(identity.logicalAddress)),
            description = format(Label.TAG_DESCRIPTION, listOf(identity.logicalAddress, parameter.uppercase(Locale.ROOT), name)),
            radio = RadioSettings(localName = name),
            tracks = listOf(TagTrack("nano-direct", kind = PayloadKind.NAME_ONLY)),
        ).also { require(ProfileCodec.validate(it).isEmpty()) { Label.INVALID_PROFILE.english } }
    }

    data class HistoryPage(val items: List<RunRecord>, val index: Int, val pageCount: Int, val totalCount: Int, val selectedId: String?)

    /** Refresh follows the chosen run; explicit navigation follows the requested page. */
    fun historyPage(records: List<RunRecord>, currentPage: Int, selectedId: String?, requestedPage: Int? = null, pageSize: Int = 100): HistoryPage {
        require(pageSize in 1..200)
        if (records.isEmpty()) return HistoryPage(emptyList(), 0, 0, 0, null)
        val sorted = records.sortedWith(compareByDescending<RunRecord> { it.startedAtMs }.thenBy { it.id })
        val pageCount = (sorted.size - 1) / pageSize + 1
        val selectedIndex = sorted.indexOfFirst { it.id == selectedId }
        val page = (requestedPage ?: if (selectedIndex >= 0) selectedIndex / pageSize else currentPage).coerceIn(0, pageCount - 1)
        val from = page * pageSize
        val items = sorted.subList(from, minOf(from + pageSize, sorted.size)).toList()
        return HistoryPage(items, page, pageCount, sorted.size, items.firstOrNull { it.id == selectedId }?.id ?: items.first().id)
    }

    fun readProfileText(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var emptyReads = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                require(++emptyReads < 16) { Label.PROVIDER_ERROR.english }
                continue
            }
            emptyReads = 0
            require(bytes.size() + count <= MAX_PROFILE_BYTES) { Label.SIZE_ERROR.english }
            bytes.write(buffer, 0, count)
        }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }

    fun applyBasic(
        profile: SimulationProfile,
        name: String,
        duration: String,
        slot: String,
        loss: String,
        seed: String,
    ): SimulationProfile {
        val result = profile.copy(
            name = name.trim(),
            durationMs = duration.trim().toLongOrNull() ?: error(Label.DURATION_ERROR.english),
            slotMs = slot.trim().toIntOrNull() ?: error(Label.SLOT_ERROR.english),
            lossPercent = loss.trim().toIntOrNull() ?: error(Label.LOSS_ERROR.english),
            seed = seed.trim().toLongOrNull() ?: error(Label.SEED_ERROR.english),
        )
        val errors = ProfileCodec.validate(result)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        return result
    }

    fun uniqueImport(profile: SimulationProfile, existingIds: Set<String>): SimulationProfile {
        if (profile.id !in existingIds) return profile
        var newId: String
        do { newId = UUID.randomUUID().toString() } while (newId in existingIds)
        return profile.copy(id = newId)
    }

    /** A provider receives only a proposed basename, never an imported directory. */
    fun filename(value: String, extension: String): String {
        require(extension.matches(Regex("[a-z0-9]{1,10}"))) { Label.EXTENSION_ERROR.english }
        val clean = value.replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').take(70)
        return "${clean.ifEmpty { "vespa-simulatie" }}.$extension"
    }

    fun shortText(value: String, limit: Int = 500, suffix: String = "… [full text in export]"): String =
        if (value.length <= limit) value else value.take(limit) + suffix

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}

