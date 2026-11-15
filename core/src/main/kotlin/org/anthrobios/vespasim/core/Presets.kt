package org.anthrobios.vespasim.core

/** Small deterministic, editable starting points. No preset claims to reproduce a real transmitter. */
object Presets {
    fun all(): List<SimulationProfile> = listOf(
        SimulationProfile(
            id = "direct-baseline", name = "01 · Direct tag — baseline",
            description = "One synthetic FC65 tag with short ID 4660 and an incrementing three-byte field. Local RSSI is measured by the receiving device.",
            tracks = listOf(TagTrack("tag-a")),
        ),
        SimulationProfile(
            id = "repeater-triangle", name = "02 · Relay — triangular reported RSSI",
            description = "A six-byte transmitter identity with reported RSSI from -95 to -40. This value is payload content; it does not set the signal measured from this phone.",
            tracks = listOf(TagTrack("repeater-a", kind = PayloadKind.REPEATER, rssi = RssiPattern(RssiMode.TRIANGLE, -95, -40, 20))),
        ),
        SimulationProfile(
            id = "planned-dropouts", name = "03 · Pauses and omissions",
            description = "Two explicit radio pauses and 15% deterministic omissions. This percentage is a scenario setting, not measured packet loss.",
            seed = 76_543, tracks = listOf(TagTrack("tag-a")),
            pauses = listOf(TimeRange(10_000, 15_000), TimeRange(30_000, 40_000)), lossPercent = 15,
        ),
        SimulationProfile(
            id = "counter-wrap-reset", name = "04 · Counter wrap and reset",
            description = "Two alternating tracks: a 24-bit wrap and a 16-bit wrap with reset after 20 attempts. A counter gap alone does not establish radio loss.",
            durationMs = 30_000,
            tracks = listOf(
                TagTrack("wrap24", shortId = 4660, counterStart = 16_777_212),
                TagTrack("wrap16-reset", shortId = 4661, counterStart = 65_532, counterMode = CounterMode.COUNTER16_TEMPERATURE, counterResetEvery = 20),
            ),
        ),
        SimulationProfile(
            id = "boundary-five-six", name = "05 · Field boundary: byte 5 to 6",
            description = "A 24-bit field crosses from 0x05FFFF to 0x060000. The experimental decoder changes its candidate interpretation here; temperature remains unconfirmed.",
            durationMs = 10_000, slotMs = 1000,
            tracks = listOf(TagTrack("boundary-tag", counterStart = 393_214)),
        ),
        SimulationProfile(
            id = "short-id-collision", name = "06 · Two tracks sharing a short ID",
            description = "Both virtual tracks use ID 4660 with different counter sequences. They share one phone radio and are not independent physical Bluetooth transmitters.",
            tracks = listOf(TagTrack("logical-a", counterStart = 1), TagTrack("logical-b", counterStart = 50_001)),
        ),
        SimulationProfile(
            id = "malformed-and-unknown", name = "07 · Invalid and unknown payloads",
            description = "Cycles through empty, truncated, status and unknown FC65 payloads. BLE carries intentionally incomplete or unknown application data.",
            durationMs = 20_000, slotMs = 1000,
            tracks = listOf(TagTrack("raw-cases", kind = PayloadKind.RAW, rawFrames = listOf("", "00", "0001", "003412", "02", "03", "f03412010000", "013412"))),
        ),
        SimulationProfile(
            id = "rssi-unavailable", name = "08 · Relay — unavailable reported RSSI",
            description = "An increasing reported RSSI with sentinel 127 every fifth attempt. The receiving chart must distinguish unavailable values from strong reception.",
            tracks = listOf(TagTrack("repeater-unavailable", kind = PayloadKind.REPEATER, rssi = RssiPattern(RssiMode.RAMP, -90, -40, 15, 5))),
        ),
        SimulationProfile(
            id = "combined-tracking", name = "09 · Combined tracking scenario",
            description = "Direct and relayed records, arrival/departure, pauses, omissions and a sentinel. Tracks share one radio; the seed and profile reproduce the planned bytes.",
            seed = 202_610_26, durationMs = 60_000, lossPercent = 20,
            tracks = listOf(
                TagTrack("direct-a", activeUntilMs = 45_000),
                TagTrack("repeater", kind = PayloadKind.REPEATER, transmitterHex = "020304050607", rssi = RssiPattern(RssiMode.TRIANGLE, -100, -45, 24, 11)),
                TagTrack("direct-b", shortId = 4661, counterStart = 250, activeFromMs = 15_000),
            ),
            pauses = listOf(TimeRange(20_000, 25_000)),
        ),
        SimulationProfile(
            id = "nano-name-recognition", name = "10 · Name prefix — recognition",
            description = "Advertises only the synthetic name VESPA-NANO-SIM, without manufacturer data, service UUIDs or telemetry. The guarded name workflow temporarily changes the phone Bluetooth name and restores it after stopping. Hardware compatibility is unverified.",
            radio = RadioSettings(localName = "VESPA-NANO-SIM"),
            tracks = listOf(TagTrack("nano-name-probe", kind = PayloadKind.NAME_ONLY)),
        ),
        SimulationProfile(
            id = "nano-direct-vn", name = "11 · Name-encoded tag — identity",
            description = "Synthetic name vn0CqrvM3e7/ encodes logical identity AA:BB:CC:DD:EE:FF and parameter 0C, whose hardware meaning is unknown. Local Name only, without manufacturer data or GATT. The receiver measures RSSI. The phone name is temporarily changed and restored; its radio address is not selected.",
            radio = RadioSettings(localName = NanoTagName.encode("AA:BB:CC:DD:EE:FF", 0x0c)),
            tracks = listOf(TagTrack("nano-direct", kind = PayloadKind.NAME_ONLY)),
        ),
    )
}

