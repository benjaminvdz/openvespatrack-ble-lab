package org.anthrobios.vespasim.core.capture

import kotlinx.serialization.Serializable
import org.anthrobios.vespasim.core.RunStatus

/** A selected-address Android scan. Android combines advertising and scan-response data. */
@Serializable
data class CaptureRecord(
    val id: String,
    val name: String,
    val address: String,
    val startedAtMs: Long,
    val startedElapsedNanos: Long,
    val endedAtMs: Long? = null,
    val status: RunStatus = RunStatus.RUNNING,
    val tagVariant: String = "unknown",
    val notes: String = "",
    val appVersion: String = "0.3.0",
    val buildCommit: String = "unknown",
    val deviceInfo: String = "",
    val settings: Map<String, String> = emptyMap(),
    val schemaVersion: Int = 1,
    val importedFromId: String? = null,
    val importedArchiveSha256: String? = null,
)

/** Original callback values. deviceName is cached identity; it is never substituted for AD name. */
@Serializable
data class CaptureObservation(
    val sequence: Long,
    val receivedAtMs: Long,
    val timestampNanos: Long,
    val address: String,
    val rssiRaw: Int,
    val rawHex: String?,
    val deviceName: String? = null,
    val legacy: Boolean = true,
    val connectable: Boolean = false,
    val primaryPhy: Int = 1,
    val secondaryPhy: Int = 0,
    val advertisingSid: Int = 255,
    val txPowerRaw: Int = 127,
    val dataStatus: Int = 0,
    val periodicInterval: Int = 0,
    val callbackType: Int = 1,
) {
    val measuredRssi: Int? get() = rssiRaw.takeIf { it in -127..126 }
}

@Serializable
data class CaptureEvent(
    val sequence: Long,
    val atEpochMs: Long,
    val kind: String,
    val message: String = "",
    val details: Map<String, String> = emptyMap(),
)

@Serializable
data class ManufacturerElement(val companyId: Int, val dataHex: String)
@Serializable
data class ServiceDataElement(val uuid: String, val dataHex: String)

/** Semantic elements accepted by Android AdvertiseData.Builder, not original AD layout. */
data class AdvertisingPayload(
    val localName: String? = null,
    val manufacturerData: List<ManufacturerElement> = emptyList(),
    val serviceUuids: List<String> = emptyList(),
    val serviceData: List<ServiceDataElement> = emptyList(),
)

/** Immutable source snapshot embedded in a replay profile; a hash is not hardware attestation. */
@Serializable
data class CapturedAdvertisement(
    val captureId: String,
    val observation: CaptureObservation,
    val rawSha256: String,
    val importedSource: Boolean = false,
) {
    val data: AdvertisingPayload get() = CaptureParser.requireReplay(observation)
}

data class AdElement(val offset: Int, val type: Int, val dataHex: String)
data class CaptureInspection(
    val elements: List<AdElement>,
    val advertisedName: String?,
    val logicalNanoAddress: String?,
    val nanoCandidate: Boolean,
    val vespaCandidate: Boolean,
    val fc65: Boolean,
    val replayReason: String?,
    val payload: AdvertisingPayload?,
) {
    val replayable: Boolean get() = replayReason == null && payload != null
}
