package org.anthrobios.vespasim.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.Random

class NanoTagNameTest {
    @Test fun independentFixedVectorsPreserveLeadingZerosHighBitsAndAlphabet() {
        val vectors = mapOf(
            "00:00:00:00:00:00" to "vn0CAAAAAAAA",
            "00:00:00:00:00:01" to "vn0CAAAAAAAB",
            "01:02:03:04:05:06" to "vn0CAQIDBAUG",
            "AA:BB:CC:DD:EE:FF" to "vn0CqrvM3e7/",
            "FF:FF:FF:FF:FF:FF" to "vn0C////////",
        )
        for ((address, name) in vectors) {
            assertEquals(name, NanoTagName.encode(address, 0x0c))
            assertEquals(NanoTagName.Identity(address, 0x0c), NanoTagName.decode(name))
        }
    }

    @Test fun allParameterBytesRoundTripWithoutInventingTheirMeaning() {
        for (parameter in 0..255) {
            val name = NanoTagName.encode("aa:bb:cc:dd:ee:ff", parameter)
            assertEquals(12, name.length)
            assertEquals(parameter, NanoTagName.decode(name).parameter)
            assertEquals("AA:BB:CC:DD:EE:FF", NanoTagName.decode(name).logicalAddress)
        }
    }

    @Test fun boundedNonCanonicalIntegerNamesDecodeAndCanonicalize() {
        for (name in listOf("vn0cA", "vn0CAA", "vn0CAAAAAAAA")) {
            val identity = NanoTagName.decode(name)
            assertEquals("00:00:00:00:00:00", identity.logicalAddress)
            assertEquals("vn0CAAAAAAAA", NanoTagName.encode(identity.logicalAddress, identity.parameter))
        }
        assertEquals("00:00:00:00:00:3F", NanoTagName.decode("vnFF/").logicalAddress)
    }

    @Test fun malformedPrefixParameterAlphabetAndOversizedIdentityAreRejected() {
        for (name in listOf("", "vn", "vn0", "vn0C", "VN0CA", "vN0CA", "vnGGA", "vn0-A", "vn０CA", "vn0CA=", "vn0CA ", "vn0CA_", "vn0CA-", "vn0Cé", "vn0CAAAAAAAAA", "vn0C/////////", "vn0CA\n")) {
            assertThrows(name, IllegalArgumentException::class.java) { NanoTagName.decode(name) }
        }
    }

    @Test fun encoderRequiresSixCompleteOctetsAndAByteParameter() {
        for (address in listOf("AABBCCDDEEFF", "AA:BB:CC:DD:EE", "AA:BB:CC:DD:EE:FF:00", "AA:BB:CC:DD:EE:GG", "A:BB:CC:DD:EE:FF", " AA:BB:CC:DD:EE:FF", "ＡＡ:BB:CC:DD:EE:FF")) {
            assertThrows(address, IllegalArgumentException::class.java) { NanoTagName.encode(address, 12) }
        }
        for (parameter in listOf(-1, 256, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { NanoTagName.encode("AA:BB:CC:DD:EE:FF", parameter) }
        }
    }

    @Test fun generatedFortyEightBitValuesAgreeWithIndependentSixByteBase64() {
        val random = Random(20261026)
        repeat(10_000) {
            val bytes = ByteArray(6).also(random::nextBytes)
            val address = bytes.joinToString(":") { "%02X".format(it.toInt() and 255) }
            val parameter = random.nextInt(256)
            val actual = NanoTagName.encode(address, parameter)
            assertEquals(Base64.getEncoder().withoutPadding().encodeToString(bytes), actual.substring(4))
            assertEquals(NanoTagName.Identity(address, parameter), NanoTagName.decode(actual))
        }
    }

    @Test fun nanoPresetUsesOnlyTheReconstructedNameAndFitsLegacyBudget() {
        val preset = Presets.all().single { it.id == "nano-direct-vn" }
        assertEquals("vn0CqrvM3e7/", preset.radio.localName)
        assertEquals(emptyList<String>(), ProfileCodec.validate(preset))
        assertTrue(Planner.plan(preset).all { !it.includeManufacturerData && it.payloadHex == "" && it.localName == "vn0CqrvM3e7/" })
        assertEquals(17, AdvertisingLayout.byteSize(0, false, preset.radio.localName))
    }
}
