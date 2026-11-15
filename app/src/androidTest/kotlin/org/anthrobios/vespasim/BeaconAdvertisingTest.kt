package org.anthrobios.vespasim

import android.os.ParcelUuid
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
        assertArrayEquals(identity.serviceData(), data.serviceData[ParcelUuid.fromString(BeaconIdentity.SERVICE_UUID)])
    }

    @Test fun advertisingIsLegacyNonConnectableAndNonScannable() {
        val parameters = AndroidBeaconRadio.parameters()
        assertTrue(parameters.isLegacy)
        assertFalse(parameters.isConnectable)
        assertFalse(parameters.isScannable)
        assertEquals(320, parameters.interval)
        assertEquals(-7, parameters.txPowerLevel)
    }
}
