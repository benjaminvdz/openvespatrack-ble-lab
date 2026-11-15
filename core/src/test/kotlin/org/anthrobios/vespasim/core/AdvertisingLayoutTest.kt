package org.anthrobios.vespasim.core

import org.junit.Assert.*
import org.junit.Test

class AdvertisingLayoutTest {
    @Test fun layoutCountsAdHeadersManufacturerIdAndReservedFlags() {
        assertEquals(31, AdvertisingLayout.byteSize(24, true, null))
        assertEquals(7, AdvertisingLayout.byteSize(0, true, null))
        assertEquals(19, AdvertisingLayout.byteSize(0, false, "VESPA-NANO-SIM"))
        assertEquals(29, AdvertisingLayout.byteSize(6, true, "VESPA-NANO-SIM"))
    }

    @Test fun nameBudgetUsesUtf8BytesRatherThanCharacterCount() {
        assertEquals(13, AdvertisingLayout.byteSize(0, false, "🐝🐝"))
        assertEquals(31, AdvertisingLayout.byteSize(6, true, "é".repeat(8)))
    }

    @Test fun absentNameRequiresNoAccessToThePhoneName() {
        AdvertisingLayout.requireMatchingName(null, null)
        AdvertisingLayout.requireMatchingName(null, "A private phone name")
    }

    @Test fun expectedNameMustMatchExactlyAfterAutomaticPreparation() {
        AdvertisingLayout.requireMatchingName("VESPA-NANO-SIM", "VESPA-NANO-SIM")
        for (actual in listOf(null, "My phone", "vespa-nano-sim", "VESPA-NANO-SIM ")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                AdvertisingLayout.requireMatchingName("VESPA-NANO-SIM", actual)
            }
            assertTrue(error.message!!.contains("automatische naamvoorbereiding"))
        }
    }
}
