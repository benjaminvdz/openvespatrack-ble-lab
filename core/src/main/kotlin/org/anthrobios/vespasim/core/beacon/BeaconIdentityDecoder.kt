package org.anthrobios.vespasim.core.beacon

import org.anthrobios.vespasim.core.Protocol
import org.anthrobios.vespasim.core.capture.CaptureObservation
import org.anthrobios.vespasim.core.capture.CaptureParser

/**
 * Read-only recognition of the versioned OpenVespaTrack service-data identity.
 * A label is derived from received bytes, never a cached name, radio address or replay decision.
 * This is a public synthetic identifier, not authentication of the sending device.
 */
object BeaconIdentityDecoder {
    private val uuidBytes = CaptureParser.bytes(BeaconIdentity.SERVICE_UUID.replace("-", "")).reversedArray()

    fun serviceData(uuid: String, data: ByteArray): BeaconIdentity? {
        if (!uuid.equals(BeaconIdentity.SERVICE_UUID, ignoreCase = true) || data.size != 9 || data[0] != 1.toByte()) return null
        return BeaconIdentity(Protocol.hex(data.copyOfRange(1, data.size)))
    }

    /** Reject incomplete/ambiguous observations; unrelated well-formed AD fields are allowed. */
    fun inspect(observation: CaptureObservation): BeaconIdentity? {
        if (observation.dataStatus != 0) return null
        val raw = observation.rawHex ?: return null
        val bytes = try { CaptureParser.bytes(raw) } catch (_: IllegalArgumentException) { return null }
        var identity: BeaconIdentity? = null
        var seen = false
        var offset = 0
        while (offset < bytes.size) {
            val length = bytes[offset].toInt() and 255
            if (length == 0) return if (bytes.drop(offset + 1).all { it == 0.toByte() }) identity else null
            if (offset + length + 1 > bytes.size) return null
            val type = bytes[offset + 1].toInt() and 255
            if (type == 0x21 && length < 17) return null
            if (type == 0x21 && length >= 17 &&
                bytes.copyOfRange(offset + 2, offset + 18).contentEquals(uuidBytes)) {
                if (seen) return null
                seen = true
                identity = serviceData(BeaconIdentity.SERVICE_UUID, bytes.copyOfRange(offset + 18, offset + length + 1))
                    ?: return null
            }
            offset += length + 1
        }
        return identity
    }
}
