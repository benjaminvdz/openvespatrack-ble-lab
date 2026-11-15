package org.anthrobios.vespasim.core.capture

import org.anthrobios.vespasim.core.AdvertisingLayout
import org.anthrobios.vespasim.core.NanoTagName
import org.anthrobios.vespasim.core.Protocol
import org.junit.Assert.*
import org.junit.Test

class CaptureParserTest {
    private fun observation(hex: String?) = CaptureObservation(1, 1000, 100, "AA:BB:CC:DD:EE:FF", -60, hex)
    private fun inspect(hex: String?) = CaptureParser.inspect(observation(hex))
    private fun ad(type: Int, hex: String) = "%02x%02x".format(hex.length / 2 + 1, type) + hex
    private fun name(value: String) = ad(9, Protocol.hex(value.toByteArray(Charsets.UTF_8)))
    private fun rejected(hex: String?): CaptureInspection = inspect(hex).also { assertFalse(it.toString(), it.replayable); assertNotNull(it.replayReason); assertNull(it.payload) }

    @Test fun completeNanoNameUsesOnlyAdvertisedBytesAndDecodesLogicalIdentity() {
        val nano = NanoTagName.encode("AA:BB:CC:DD:EE:FF", 0x50)
        val sample = observation("020106" + name(nano)).copy(deviceName = "Cached phone name")
        val parsed = CaptureParser.inspect(sample)
        assertEquals(nano, parsed.advertisedName)
        assertEquals("AA:BB:CC:DD:EE:FF", parsed.logicalNanoAddress)
        assertTrue(parsed.nanoCandidate && parsed.vespaCandidate && parsed.replayable)
        assertEquals(listOf(1, 9), parsed.elements.map { it.type })
        assertEquals(listOf(0, 3), parsed.elements.map { it.offset })
    }

    @Test fun cachedBluetoothNameDoesNotBecomeAdvertisedIdentityOrNanoEvidence() {
        val parsed = CaptureParser.inspect(observation("04ff65fc00").copy(deviceName = "vn50qrvM3e7/"))
        assertNull(parsed.advertisedName)
        assertFalse(parsed.nanoCandidate)
        assertTrue(parsed.fc65 && parsed.vespaCandidate)
        assertNull(parsed.payload!!.localName)
    }

    @Test fun malformedVnRemainsCandidateWithoutInventedLogicalAddress() {
        val parsed = inspect(name("vnZZinvalid?"))
        assertTrue(parsed.nanoCandidate)
        assertNull(parsed.logicalNanoAddress)
        assertTrue(parsed.replayable) // Literal valid UTF-8 name can be sent, even if Nano hypothesis fails.
    }

    @Test fun manufacturerLittleEndianIdsAndEmptyPayloadsRemainDistinct() {
        val parsed = inspect("04ff65fcab03ff3412")
        assertTrue(parsed.replayable)
        assertEquals(listOf(ManufacturerElement(0xfc65, "ab"), ManufacturerElement(0x1234, "")), parsed.payload!!.manufacturerData)
        assertEquals(12, AdvertisingLayout.byteSize(parsed.payload!!))
    }

    @Test fun duplicateManufacturerIdsAndTruncatedIdsAreNotSilentlyMerged() {
        rejected("04ff65fc0104ff65fc02")
        rejected("02ff65")
        rejected("01ff")
    }

    @Test fun serviceUuid16And32AreLittleEndianAndKeepFullWidth() {
        val parsed = inspect("03030d18050578563412")
        assertTrue(parsed.replayable)
        assertEquals(listOf("0000180d-0000-1000-8000-00805f9b34fb", "12345678-0000-1000-8000-00805f9b34fb"), parsed.payload!!.serviceUuids)
        assertEquals(13, AdvertisingLayout.byteSize(parsed.payload!!))
    }

    @Test fun serviceUuid128ReversesAllSixteenBytes() {
        val parsed = inspect("110700c13ffff43954bd23431355e3e24016")
        assertTrue(parsed.replayable)
        assertEquals(listOf("1640e2e3-5513-4323-bd54-39f4ff3fc100"), parsed.payload!!.serviceUuids)
        assertTrue(parsed.vespaCandidate)
        assertEquals(21, AdvertisingLayout.byteSize(parsed.payload!!))
    }

    @Test fun incompleteUuidListsAreSemanticallyRepresentableAndDifferenceIsDisclosed() {
        assertTrue(inspect("03020d18").replayable)
        assertTrue(CaptureReplay.differences.any { it.contains("lijsttype") })
    }

    @Test fun duplicateUuidAcrossWidthsOrListFieldsIsRejected() {
        rejected("03030d1803030d18")
        rejected("03030d1805050d180000")
        rejected("03020d1803030d18")
    }

    @Test fun partialOrEmptyUuidListsAreRejected() {
        for (hex in listOf("0103", "02030d", "0405785634", "03070102")) rejected(hex)
    }

    @Test fun serviceData16And32DecodeIndependentlyFromServiceLists() {
        val parsed = inspect("04160d18ab062078563412cd")
        assertTrue(parsed.replayable)
        assertEquals(listOf(ServiceDataElement("0000180d-0000-1000-8000-00805f9b34fb", "ab"), ServiceDataElement("12345678-0000-1000-8000-00805f9b34fb", "cd")), parsed.payload!!.serviceData)
        assertEquals(15, AdvertisingLayout.byteSize(parsed.payload!!))
    }

    @Test fun serviceData128KeepsByteOrderAndOpaqueValue() {
        val parsed = inspect("132100c13ffff43954bd23431355e3e24016beef")
        assertTrue(parsed.replayable)
        assertEquals(ServiceDataElement("1640e2e3-5513-4323-bd54-39f4ff3fc100", "beef"), parsed.payload!!.serviceData.single())
    }

    @Test fun missingServiceDataUuidAndDuplicateCanonicalUuidAreRejected() {
        for (hex in listOf("02160d", "0420785634", "03210000", "04160d180104160d1802", "03160d1805200d180000")) rejected(hex)
    }

    @Test fun listingAndProvidingDataForSameServiceIsAllowed() {
        val parsed = inspect("03030d1804160d1801")
        assertTrue(parsed.replayable)
        assertEquals(1, parsed.payload!!.serviceUuids.size)
        assertEquals(1, parsed.payload!!.serviceData.size)
    }

    @Test fun flagsAreMetadataAndUnsupportedTxPowerRemainsInEvidence() {
        val parsed = rejected("020106020af304ff65fc01")
        assertEquals(listOf(1, 10, 255), parsed.elements.map { it.type })
        assertEquals("f3", parsed.elements[1].dataHex)
        assertTrue(parsed.fc65)
        assertTrue(parsed.replayReason!!.contains("Tx Power"))
    }

    @Test fun duplicateMalformedFlagsAndFlagsOnlyAreRejected() {
        for (hex in listOf("020106020106" + name("a"), "0101" + name("a"), "03010606" + name("a"), "020106")) rejected(hex)
    }

    @Test fun unknownAppearanceSolicitationAndServiceFlagsAreNeverDropped() {
        for (type in listOf(0x19, 0x14, 0x15, 0x1f, 0x3d, 0x00)) {
            val parsed = rejected(name("vn50a") + ad(type, "0102"))
            assertEquals(type, parsed.elements.last().type)
            assertTrue(parsed.nanoCandidate)
        }
    }

    @Test fun nullEmptyOversizeOddHexAndMalformedAdAreRejected() {
        for (hex in listOf(null, "", "0", "xz", "00".repeat(1651), "05ff65fc00", "05")) rejected(hex)
    }

    @Test fun paddingIsRetainedInOriginalEvidenceButNonZeroTrailingDataIsAmbiguous() {
        val base = name("vn50a")
        assertTrue(inspect(base + "000000").replayable)
        rejected(base + "000102")
        rejected("00" + base)
    }

    @Test fun shortenedConflictingAndInvalidUtf8NamesAreRejected() {
        rejected(ad(8, "766e353061"))
        rejected(name("one") + name("one"))
        rejected(ad(8, "61") + name("a"))
        for (bytes in listOf("c0af", "eda080", "f0808080", "f4908080", "80", "e282")) rejected(ad(9, bytes))
    }

    @Test fun utf8NameBudgetUsesBytesAndProhibitsControlCharacters() {
        assertTrue(inspect(name("é".repeat(13))).replayable)
        rejected(name("é".repeat(14)))
        for (value in listOf("", " ", "a\u0000b", "a\nb", "a\u007fb")) rejected(name(value))
        assertEquals("🐝", inspect(name("🐝")).advertisedName)
    }

    @Test fun controllerBudgetIncludesReservedFlagsEvenIfSourceHadNone() {
        val max = ad(255, "65fc" + "00".repeat(24))
        assertTrue(inspect(max).replayable)
        assertEquals(31, AdvertisingLayout.byteSize(inspect(max).payload!!))
        rejected(ad(255, "65fc" + "00".repeat(25)))
    }

    @Test fun combinedAdvAndResponseMustFitOneNewAdvertisement() {
        val parsed = rejected(name("vn50qrvM3e7/") + ad(255, "65fc" + "00".repeat(11)))
        assertTrue(parsed.replayReason!!.contains("31 bytes"))
        assertTrue(parsed.nanoCandidate && parsed.fc65)
    }

    @Test fun extendedIncompleteOrNon1mAreExplicitlyNonReplayableButKeepHints() {
        val base = observation(name("vn50a"))
        for (sample in listOf(base.copy(legacy = false), base.copy(dataStatus = 1), base.copy(dataStatus = 2), base.copy(dataStatus = 99), base.copy(primaryPhy = 3))) {
            val parsed = CaptureParser.inspect(sample)
            assertFalse(parsed.replayable)
            assertTrue(parsed.nanoCandidate)
            assertEquals("vn50a", parsed.advertisedName)
        }
    }

    @Test fun unavailableRssiAndScanResultTxPowerDoNotCorruptAdContent() {
        val base = observation(name("vn50a"))
        assertNull(base.copy(rssiRaw = 127).measuredRssi)
        assertNull(base.copy(rssiRaw = -128).measuredRssi)
        assertEquals(-127, base.copy(rssiRaw = -127).measuredRssi)
        assertEquals(126, base.copy(rssiRaw = 126).measuredRssi)
        assertTrue(CaptureParser.inspect(base.copy(rssiRaw = Int.MAX_VALUE, txPowerRaw = 127)).replayable)
        assertTrue(CaptureParser.inspect(base.copy(txPowerRaw = Int.MIN_VALUE)).replayable)
    }
}
