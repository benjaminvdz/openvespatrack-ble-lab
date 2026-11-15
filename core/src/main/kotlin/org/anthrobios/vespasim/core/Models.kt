package org.anthrobios.vespasim.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import org.anthrobios.vespasim.core.capture.CapturedAdvertisement

@Serializable
enum class RadioMode { LEGACY_1M, EXTENDED_1M, EXTENDED_CODED }

@Serializable
data class RadioSettings(
    val mode: RadioMode = RadioMode.LEGACY_1M,
    val intervalMs: Int = 200,
    val txPowerDbm: Int = -7,
    /** Exact Local Name; the explicit send workflow temporarily prepares and restores the adapter name. Null omits Local Name. */
    val localName: String? = null,
)

@Serializable
enum class PayloadKind { DIRECT, REPEATER, RAW, NAME_ONLY, CAPTURED }

@Serializable
enum class CounterMode { RAW24, COUNTER16_TEMPERATURE }

@Serializable
enum class RssiMode { CONSTANT, RAMP, TRIANGLE }

@Serializable
data class RssiPattern(
    val mode: RssiMode = RssiMode.CONSTANT,
    val min: Int = -70,
    val max: Int = -40,
    val periodSlots: Int = 20,
    val unavailableEvery: Int = 0,
)

@Serializable
data class TimeRange(val fromMs: Long, val untilMs: Long)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TagTrack(
    val id: String,
    val kind: PayloadKind = PayloadKind.DIRECT,
    val subtype: Int = 0,
    val shortId: Int = 4660,
    val transmitterHex: String = "010203040506",
    val counterStart: Long = 0,
    val counterStep: Long = 1,
    val counterMode: CounterMode = CounterMode.RAW24,
    val temperatureRaw: Int = 22,
    val counterResetEvery: Int = 0,
    val rssi: RssiPattern = RssiPattern(),
    val rawFrames: List<String> = emptyList(),
    val activeFromMs: Long = 0,
    val activeUntilMs: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val capturedFrames: List<CapturedAdvertisement> = emptyList(),
)

@Serializable
data class SimulationProfile(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val description: String = "",
    val seed: Long = 1,
    val durationMs: Long = 60_000,
    val slotMs: Int = 500,
    val companyId: Int = 64613,
    val radio: RadioSettings = RadioSettings(),
    val tracks: List<TagTrack>,
    val pauses: List<TimeRange> = emptyList(),
    val lossPercent: Int = 0,
)

/** Null payload means silence. Captured advertisements carry their own semantic AD data; empty hex otherwise represents name-only. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PlannedFrame(
    val sequence: Long,
    val atMs: Long,
    val durationMs: Long,
    val trackId: String?,
    val companyId: Int,
    val payloadHex: String?,
    val reportedRssi: Int? = null,
    val silenceReason: String? = null,
    val includeManufacturerData: Boolean = true,
    val localName: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val capturedAdvertisement: CapturedAdvertisement? = null,
)

@Serializable
enum class RunMode { DRY_RUN, BLE }

@Serializable
enum class RunStatus { RUNNING, COMPLETED, STOPPED, FAILED, INTERRUPTED }

@Serializable
data class RunRecord(
    val id: String,
    val profile: SimulationProfile,
    val mode: RunMode,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val status: RunStatus = RunStatus.RUNNING,
    val appVersion: String = "0.1.0",
    val buildCommit: String = "unknown",
    val deviceInfo: String = "",
    val speed: Int = 1,
)

/** Software events describe intent/API acknowledgements, never verified over-air transmissions. */
@Serializable
data class RunEvent(
    val sequence: Long,
    val atEpochMs: Long,
    val elapsedMs: Long,
    val kind: String,
    val frameSequence: Long? = null,
    val trackId: String? = null,
    val payloadHex: String? = null,
    val message: String = "",
    val details: Map<String, String> = emptyMap(),
)

@Serializable
data class RunSummary(
    val run: RunRecord,
    val eventCount: Long,
    val controllerAccepted: Long,
    val simulatedFrames: Long,
    val skippedLate: Long,
    val errorCount: Long,
)
