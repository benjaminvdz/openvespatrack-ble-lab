package org.anthrobios.vespasim

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.anthrobios.vespasim.ble.buildAdvertisingData
import org.anthrobios.vespasim.core.PlannedFrame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Checks real Android AdvertiseData objects; these tests never start a radio. */
@RunWith(AndroidJUnit4::class)
class AdvertisingDataTest {
    @Test fun nameOnlyBuildsLocalNameWithoutManufacturerServiceOrTxPowerElements() {
        val data = buildAdvertisingData(PlannedFrame(1, 0, 500, "name", 64613, "",
            includeManufacturerData = false, localName = "VESPA-NANO-SIM"))
        assertTrue(data.includeDeviceName)
        assertFalse(data.includeTxPowerLevel)
        assertEquals(0, data.manufacturerSpecificData.size())
        assertTrue(data.serviceData.isEmpty())
        assertTrue(data.serviceUuids.isEmpty())
    }

    @Test fun zeroLengthManufacturerDataRemainsAnExplicitManufacturerElement() {
        val data = buildAdvertisingData(PlannedFrame(1, 0, 500, "empty-raw", 64613, ""))
        assertFalse(data.includeDeviceName)
        assertFalse(data.includeTxPowerLevel)
        assertEquals(1, data.manufacturerSpecificData.size())
        assertArrayEquals(byteArrayOf(), data.manufacturerSpecificData.get(64613))
    }
}
