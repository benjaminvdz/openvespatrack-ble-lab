package org.anthrobios.vespasim.core.capture

import org.anthrobios.vespasim.core.*
import java.security.MessageDigest

/** Explicit, bounded semantic replay preparation; this object never touches a Bluetooth adapter. */
object CaptureReplay {
    const val MAX_SAMPLES = 256
    val differences: List<String> = listOf(
        "De bron bevat Android ScanRecord-bytes: advertentie en scan response kunnen samengevoegd zijn; de oorspronkelijke plaatsing en AD-volgorde worden niet hersteld.",
        "Android bouwt nieuwe advertentievelden en kiest Flags, UUID-breedte/lijsttype en veldvolgorde. Exacte RF-pakketten worden niet gekopieerd.",
        "Het fysieke Bluetoothadres, ontvangen RSSI, oorspronkelijk zendvermogen, connecteerbaarheid en GATT-diensten van de tag worden niet nagebootst.",
        "Een reeks gebruikt de expliciet gekozen vaste slotduur, inclusief herhaalde ontvangen inhoud; de oorspronkelijke radio-intervallen en ontvangsttijden worden niet gereproduceerd.",
        "Een opgenomen Local Name wordt via de bestaande tijdelijke naamvoorbereiding ingesteld en nadien hersteld. Controlleracceptatie bewijst geen RF-ontvangst.",
    )

    fun staticProfile(record: CaptureRecord, observation: CaptureObservation, id: String, name: String, durationMs: Long = 60_000): SimulationProfile {
        val frames = sourceFrames(record, listOf(observation))
        return profile(frames, id, name, durationMs, 500, "Eén geselecteerde waarneming wordt herhaald; geen reconstructie van oorspronkelijke timing.")
    }

    fun sequenceProfile(record: CaptureRecord, observations: List<CaptureObservation>, id: String, name: String, slotMs: Int = 500): SimulationProfile {
        require(slotMs in 250..60_000) { "Kies een vaste slotduur van 250–60000 ms" }
        require(observations.size in 1..MAX_SAMPLES) { "Selecteer 1–$MAX_SAMPLES waarnemingen; er wordt niets stil afgekapt" }
        require(observations.zipWithNext().all { (first, second) -> first.sequence < second.sequence }) { "Kies waarnemingen in de oorspronkelijke, strikt oplopende ontvangstvolgorde" }
        val frames = sourceFrames(record, observations)
        return profile(frames, id, name, frames.size.toLong() * slotMs, slotMs, "Geselecteerde ontvangen inhoud in oorspronkelijke volgorde; iedere waarneming krijgt één vast tijdslot, zonder deduplicatie.")
    }

    /** Integrity detects accidental alteration; a hash does not authenticate a sender or imported archive. */
    fun sha256Hex(rawHex: String): String = Protocol.hex(MessageDigest.getInstance("SHA-256").digest(CaptureParser.bytes(rawHex)))

    fun validateAdvertisement(value: CapturedAdvertisement) {
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(value.captureId)) { "Ongeldige bronopname-ID" }
        require(Regex("[0-9a-f]{64}").matches(value.rawSha256)) { "Ongeldige SHA-256 van bronbytes" }
        CaptureCodec.validateObservation(value.observation)
        val raw = requireNotNull(value.observation.rawHex) { "Bronbytes ontbreken" }
        require(sha256Hex(raw) == value.rawSha256) { "Bronbytes komen niet overeen met de vastgelegde SHA-256" }
        CaptureParser.requireReplay(value.observation)
    }

    private fun sourceFrames(record: CaptureRecord, observations: List<CaptureObservation>): List<CapturedAdvertisement> {
        CaptureCodec.validateRecord(record)
        require(record.status != RunStatus.RUNNING) { "Stop de opname voordat een replayprofiel wordt gemaakt" }
        require(observations.size in 1..MAX_SAMPLES) { "Selecteer 1–$MAX_SAMPLES waarnemingen; er wordt niets stil afgekapt" }
        val frames = observations.map { source ->
            require(source.address.equals(record.address, ignoreCase = true)) { "Waarneming ${source.sequence} hoort bij een ander radioadres" }
            // A detached immutable value snapshot: future repository edits cannot mutate this profile.
            val observation = source.copy()
            CaptureParser.requireReplay(observation)
            CapturedAdvertisement(record.id, observation, sha256Hex(requireNotNull(observation.rawHex)), record.importedFromId != null)
        }
        require(frames.map { it.data.localName }.distinct().size == 1) { "Alle geselecteerde waarnemingen moeten exact dezelfde Local Name hebben (of allemaal geen naam)" }
        return frames.toList()
    }

    private fun profile(frames: List<CapturedAdvertisement>, id: String, name: String, durationMs: Long, slotMs: Int, explanation: String): SimulationProfile {
        val profile = SimulationProfile(
            schemaVersion = 2, id = id, name = name,
            description = "$explanation\nBronopname: ${frames.first().captureId}. Semantische replay van Android-scaninhoud; geen identieke radiozender.\n" + differences.joinToString("\n"),
            durationMs = durationMs, slotMs = slotMs,
            radio = RadioSettings(mode = RadioMode.LEGACY_1M, localName = frames.first().data.localName),
            tracks = listOf(TagTrack(id = "captured", kind = PayloadKind.CAPTURED, capturedFrames = frames)),
        )
        // Also check the serialized interchange limit, so saving cannot unexpectedly lose source frames.
        ProfileCodec.encode(profile)
        return profile
    }
}
