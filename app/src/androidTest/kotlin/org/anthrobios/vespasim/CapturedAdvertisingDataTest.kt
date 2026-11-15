package org.anthrobios.vespasim

import android.os.ParcelUuid
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.anthrobios.vespasim.ble.buildAdvertisingData
import org.anthrobios.vespasim.core.PlannedFrame
import org.anthrobios.vespasim.core.capture.CaptureObservation
import org.anthrobios.vespasim.core.capture.CaptureReplay
import org.anthrobios.vespasim.core.capture.CapturedAdvertisement
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real platform builders only; no advertiser is started by these tests. */
@RunWith(AndroidJUnit4::class)
class CapturedAdvertisingDataTest {
    private fun frame(raw: String, name: String? = null): PlannedFrame {
        val sample = CaptureObservation(1, 100, 10_000_000, "C0:11:22:33:44:55", -65, raw)
        return PlannedFrame(1, 0, 500, "capture", 0, "", includeManufacturerData = false, localName = name,
            capturedAdvertisement = CapturedAdvertisement("test-capture", sample, CaptureReplay.sha256Hex(raw)))
    }

    @Test fun capturedManufacturerUuidAndServiceDataUseSeparateAndroidElements() {
        val data = buildAdvertisingData(frame("02010605ff65fc010203030d1804160d1801"))
        val service = ParcelUuid(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"))
        assertFalse(data.includeDeviceName)
        assertFalse(data.includeTxPowerLevel)
        assertEquals(1, data.manufacturerSpecificData.size())
        assertArrayEquals(byteArrayOf(1, 2), data.manufacturerSpecificData[0xfc65])
        assertEquals(listOf(service), data.serviceUuids)
        assertArrayEquals(byteArrayOf(1), data.serviceData[service])
        assertEquals(1, data.serviceData.size)
    }

    @Test fun differentManufacturersIncludingEmptyPayloadRemainIndependent() {
        val data = buildAdvertisingData(frame("03ff65fc05ff4c000102"))
        assertEquals(2, data.manufacturerSpecificData.size())
        assertArrayEquals(byteArrayOf(), data.manufacturerSpecificData[0xfc65])
        assertArrayEquals(byteArrayOf(1, 2), data.manufacturerSpecificData[0x004c])
    }

    @Test fun capturedCompleteNanoNameUsesAdapterNameWithoutAddingManufacturerData() {
        val data = buildAdvertisingData(frame("0d09766e30437172764d3365372f", "vn0CqrvM3e7/"))
        assertTrue(data.includeDeviceName)
        assertFalse(data.includeTxPowerLevel)
        assertEquals(0, data.manufacturerSpecificData.size())
        assertTrue(data.serviceData.isEmpty())
    }

    @Test fun genericUuid128IsNotTruncatedToManufacturerBytes() {
        val data = buildAdvertisingData(frame("110700c13ffff43954bd23431355e3e24016"))
        assertEquals(listOf(ParcelUuid(UUID.fromString("1640e2e3-5513-4323-bd54-39f4ff3fc100"))), data.serviceUuids)
        assertEquals(0, data.manufacturerSpecificData.size())
    }

    @Test fun recordedNameMustMatchFrameAndCannotBeTakenFromCachedIdentity() {
        assertThrows(IllegalArgumentException::class.java) { buildAdvertisingData(frame("03096162", "ac")) }
        val source = frame("03ff65fc")
        val advertisement = source.capturedAdvertisement!!
        val cached = advertisement.copy(observation = advertisement.observation.copy(deviceName = "vn-cached"))
        assertFalse(buildAdvertisingData(source.copy(capturedAdvertisement = cached)).includeDeviceName)
    }

    @Test fun appearanceAndTxPowerAreRejectedInsteadOfBeingOmitted() {
        for (unsupported in listOf("03190000", "020ac5")) {
            assertThrows(IllegalArgumentException::class.java) { buildAdvertisingData(frame("03ff65fc$unsupported")) }
        }
    }

    @Test fun duplicateManufacturerCannotBeCollapsedByAndroidBuilder() {
        assertThrows(IllegalArgumentException::class.java) { buildAdvertisingData(frame("04ff65fc0104ff65fc02")) }
    }

    @Test fun changedSourceHashTruncationAndSilenceNeverReachBuilder() {
        val source = frame("03ff65fc")
        val advertisement = source.capturedAdvertisement!!
        assertThrows(IllegalArgumentException::class.java) {
            buildAdvertisingData(source.copy(capturedAdvertisement = advertisement.copy(rawSha256 = "0".repeat(64))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildAdvertisingData(source.copy(capturedAdvertisement = advertisement.copy(observation = advertisement.observation.copy(dataStatus = 2))))
        }
        assertThrows(IllegalArgumentException::class.java) { buildAdvertisingData(source.copy(payloadHex = null)) }
    }
}
