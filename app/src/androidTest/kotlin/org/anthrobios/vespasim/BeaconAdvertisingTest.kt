package org.anthrobios.vespasim

import android.os.ParcelUuid
import android.bluetooth.BluetoothDevice
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.anthrobios.vespasim.ble.AndroidBeaconRadio
import org.anthrobios.vespasim.core.beacon.BeaconIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android builders; does not transmit, rename Bluetooth or request permissions. */
@RunWith(AndroidJUnit4::class)
class BeaconAdvertisingTest {
    @Test fun independentSyntheticIdentityUsesOnlyCustomServiceData() {
        val identity = BeaconIdentity("0123456789abcdef")
        val data = AndroidBeaconRadio.advertisement(identity)
        assertFalse(data.includeDeviceName)
        assertFalse(data.includeTxPowerLevel)
        assertEquals(0, data.manufacturerSpecificData.size())
        assertTrue(data.serviceUuids.isEmpty())
        assertEquals(1, data.serviceData.size)
        // Exact rc.2 primary-packet identity bytes must remain compatible with passive receivers.
        val expected = byteArrayOf(1, 1, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte())
        assertArrayEquals(expected, data.serviceData[ParcelUuid.fromString(BeaconIdentity.SERVICE_UUID)])
        assertEquals(30, 3 + 2 + 16 + expected.size) // conservative Flags + service-data AD budget
    }

    @Test fun activeScannerResponseOffersTheExactUuidWithoutChangingPrimaryServiceData() {
        val response = AndroidBeaconRadio.scanResponse()
        assertEquals(listOf(ParcelUuid.fromString("6f8a5b00-7d3e-4c6a-9b21-0a5c2d7e9101")), response.serviceUuids)
        assertFalse(response.includeDeviceName)
        assertFalse(response.includeTxPowerLevel)
        assertEquals(0, response.manufacturerSpecificData.size())
        assertTrue(response.serviceData.isEmpty())
        val responseBudget = 2 + 16 * response.serviceUuids.size
        assertEquals(18, responseBudget)
        assertTrue(responseBudget <= 31)
        assertTrue(AndroidBeaconRadio.advertisement(BeaconIdentity("0123456789abcdef")).serviceUuids.isEmpty())
    }

    @Test fun advertisingIsLegacyNonConnectableAndScannable() {
        val parameters = AndroidBeaconRadio.parameters()
        assertTrue(parameters.isLegacy)
        assertFalse(parameters.isConnectable)
        assertTrue(parameters.isScannable)
        assertEquals(BluetoothDevice.PHY_LE_1M, parameters.primaryPhy)
        assertEquals(BluetoothDevice.PHY_LE_1M, parameters.secondaryPhy)
        assertEquals(320, parameters.interval)
        assertEquals(-7, parameters.txPowerLevel)
    }
}
