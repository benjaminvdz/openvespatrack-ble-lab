package org.anthrobios.vespasim.core.beacon

import org.anthrobios.vespasim.core.Protocol
import org.anthrobios.vespasim.core.capture.CaptureObservation
import org.anthrobios.vespasim.core.capture.CaptureParser
import org.junit.Assert.*
import org.junit.Test

class BeaconIdentityDecoderTest {
    // Independent synthetic scan vector: flags + 0x21, little-endian UUID, v1 and eight ID bytes.
    private val serviceField = "1a2101917e2d5c0a219b6a4c3e7d005b8a6f010123456789abcdef"
    private val originalRc2 = "020106$serviceField"
    private fun observation(raw: String? = originalRc2) = CaptureObservation(
        1, 1_700_000_000_000, 1_000_000_000, "AA:BB:CC:DD:EE:FF", -63, raw,
    )

    @Test fun originalRc2ServiceDataIsRecognizedWithoutNameOrUuidList() {
        val input = observation()
        val identity = requireNotNull(BeaconIdentityDecoder.inspect(input))
        assertEquals("0123456789abcdef", identity.hex)
        assertEquals("OVT-0123456789ABCDEF", identity.label)
        assertEquals(-63, input.measuredRssi)
        assertNull(CaptureParser.inspect(input).advertisedName)
        assertFalse(CaptureParser.inspect(input).nanoCandidate)
        assertEquals(originalRc2, input.rawHex)
    }

    @Test fun combinedAdvertisementAndNewUuidScanResponseKeepTheSameIdentity() {
        val scanResponse = "110701917e2d5c0a219b6a4c3e7d005b8a6f"
        val input = observation(originalRc2 + scanResponse)
        assertEquals(BeaconIdentity("0123456789abcdef"), BeaconIdentityDecoder.inspect(input))
        // Recognition does not depend on whether combined content fits controlled legacy replay.
        assertFalse(CaptureParser.inspect(input).replayable)
        assertEquals(originalRc2 + scanResponse, input.rawHex)
    }

    @Test fun unrelatedCompleteFieldsDoNotHideAReceivedTestIdentity() {
        val withPower = observation(originalRc2 + "020af9")
        assertFalse(CaptureParser.inspect(withPower).replayable)
        assertEquals("OVT-0123456789ABCDEF", BeaconIdentityDecoder.inspect(withPower)?.label)
    }

    @Test fun uuidListOrCachedNameAloneNeverQualifiesAsATestBeacon() {
        val uuidOnly = observation("110701917e2d5c0a219b6a4c3e7d005b8a6f").copy(deviceName = "OVT-0123456789ABCDEF")
        assertNull(BeaconIdentityDecoder.inspect(uuidOnly))
        assertNull(BeaconIdentityDecoder.inspect(observation("020106").copy(deviceName = "OVT-0123456789ABCDEF")))
    }

    @Test fun anotherUuidOrWrongUuidByteOrderDoesNotMatch() {
        assertNull(BeaconIdentityDecoder.inspect(observation(originalRc2.replace("01917e2d", "02917e2d"))))
        assertNull(BeaconIdentityDecoder.inspect(observation("1a216f8a5b007d3e4c6a9b210a5c2d7e9101010123456789abcdef")))
        assertNull(BeaconIdentityDecoder.serviceData("00000000-0000-1000-8000-00805f9b34fb", BeaconIdentity("0123456789abcdef").serviceData()))
    }

    @Test fun onlyVersionOneWithExactlyEightIdentityBytesIsAccepted() {
        val valid = BeaconIdentity("0123456789abcdef").serviceData()
        assertEquals(BeaconIdentity("0123456789abcdef"), BeaconIdentityDecoder.serviceData(BeaconIdentity.SERVICE_UUID.uppercase(), valid))
        listOf(byteArrayOf(), byteArrayOf(1), valid.copyOf(8), valid + byteArrayOf(0), valid.copyOf().apply { this[0] = 2 }).forEach {
            assertNull(BeaconIdentityDecoder.serviceData(BeaconIdentity.SERVICE_UUID, it))
        }
        assertEquals("010123456789abcdef", Protocol.hex(valid))
    }

    @Test fun matchingUuidWithAnUnknownVersionOrInvalidLengthDoesNotBecomeACandidate() {
        assertNull(BeaconIdentityDecoder.inspect(observation(originalRc2.replace("8a6f01", "8a6f02"))))
        assertNull(BeaconIdentityDecoder.inspect(observation("19" + serviceField.drop(2).dropLast(2))))
        assertNull(BeaconIdentityDecoder.inspect(observation("1b" + serviceField.drop(2) + "00")))
    }

    @Test fun duplicateMatchingServiceDataIsAmbiguousEvenWhenIdentical() {
        assertNull(BeaconIdentityDecoder.inspect(observation(originalRc2 + serviceField)))
        assertNull(BeaconIdentityDecoder.inspect(observation(originalRc2 + serviceField.dropLast(2) + "ff")))
    }

    @Test fun incompleteMalformedOrTruncatedObservationsNeverQualify() {
        listOf(null, "", "0", "zz", originalRc2.dropLast(2), originalRc2 + "052101", originalRc2 + "0121").forEach {
            assertNull(BeaconIdentityDecoder.inspect(observation(it)))
        }
        assertNull(BeaconIdentityDecoder.inspect(observation().copy(dataStatus = 1)))
        assertNull(BeaconIdentityDecoder.inspect(observation().copy(dataStatus = 2)))
    }

    @Test fun zeroPaddingIsAcceptedButHiddenTrailingDataIsRejected() {
        assertEquals("OVT-0123456789ABCDEF", BeaconIdentityDecoder.inspect(observation(originalRc2 + "000000"))?.label)
        assertNull(BeaconIdentityDecoder.inspect(observation(originalRc2 + "000100")))
    }
}
