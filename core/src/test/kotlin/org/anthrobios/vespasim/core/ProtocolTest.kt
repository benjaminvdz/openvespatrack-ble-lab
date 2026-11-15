package org.anthrobios.vespasim.core

import java.io.File
import java.util.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun directBytesAreLittleEndianWithoutCompanyPrefix() {
        assertEquals("003412010000", Protocol.hex(Protocol.payload(TagTrack("a", counterStart = 1), 0)))
        assertEquals("0ccdab030201", Protocol.hex(Protocol.payload(TagTrack("a", subtype = 3, shortId = 0xabcd, counterStart = 0x010203), 0)))
    }

    @Test fun repeaterKeepsRawTransmitterOrderAndSignedByte() {
        val track = TagTrack("a", kind = PayloadKind.REPEATER, transmitterHex = "ABCD01020304", counterStart = 1, rssi = RssiPattern(min = -56, max = -56))
        assertEquals("01abcd01020304c8010000", Protocol.hex(Protocol.payload(track, 0)))
    }

    @Test fun counterWidthsWrapAndNegativeStepsWrapWithoutOverflow() {
        assertEquals("003412000000", Protocol.hex(Protocol.payload(TagTrack("a", counterStart = 0xffffff), 1)))
        assertEquals("003412ffffff", Protocol.hex(Protocol.payload(TagTrack("a", counterStep = -1), 1)))
        assertEquals("003412000016", Protocol.hex(Protocol.payload(TagTrack("a", counterMode = CounterMode.COUNTER16_TEMPERATURE, counterStart = 65535), 1)))
    }

    @Test fun hugeOrdinalUsesModularArithmetic() {
        val track = TagTrack("a", counterStart = 127, counterStep = 16_777_215)
        assertArrayEquals(Protocol.payload(track, Long.MAX_VALUE % 16_777_216), Protocol.payload(track, Long.MAX_VALUE))
    }

    @Test fun periodicCounterResetRestartsAtConfiguredStart() {
        val track = TagTrack("a", counterStart = 55, counterStep = 2, counterResetEvery = 3)
        assertEquals(listOf(55, 57, 59, 55, 57), (0L..4L).map { Protocol.payload(track, it)[3].toInt() and 0xff })
    }

    @Test fun thirdByteBoundaryIsNotSilentlyReinterpretedByEncoder() {
        val track = TagTrack("a", counterStart = 0x05ffff)
        assertEquals("003412ffff05", Protocol.hex(Protocol.payload(track, 0)))
        assertEquals("003412000006", Protocol.hex(Protocol.payload(track, 1)))
    }

    @Test fun rawFramesCycleExactlyAndEmptyIsAllowed() {
        val track = TagTrack("a", kind = PayloadKind.RAW, rawFrames = listOf("", "03AA", "003412"))
        assertEquals(listOf("", "03aa", "003412", ""), (0L..3L).map { Protocol.hex(Protocol.payload(track, it)) })
    }

    @Test fun rssiRampIncludesBothEndpoints() {
        val rssi = RssiPattern(RssiMode.RAMP, -100, -40, 4)
        assertEquals(listOf(-100, -80, -60, -40, -100), (0L..4L).map { Protocol.reportedRssi(rssi, it) })
    }

    @Test fun triangleIncludesPeakAndDoesNotOvershootForOddOrEvenPeriod() {
        assertEquals(listOf(-100, -70, -40, -70), (0L..3L).map { Protocol.reportedRssi(RssiPattern(RssiMode.TRIANGLE, -100, -40, 4), it) })
        for (period in 2..25) {
            val pattern = RssiPattern(RssiMode.TRIANGLE, -111, 19, period)
            val values = (0L until period.toLong()).map { Protocol.reportedRssi(pattern, it) }
            assertEquals(-111, values.min())
            assertEquals(19, values.max())
            assertTrue(values.all { it in -111..19 })
        }
    }

    @Test fun unavailableSentinelOverridesPatternAtSpecifiedAttempts() {
        val pattern = RssiPattern(RssiMode.CONSTANT, -90, -90, unavailableEvery = 3)
        assertEquals(listOf(-90, -90, 127, -90, -90, 127), (0L..5L).map { Protocol.reportedRssi(pattern, it) })
    }

    @Test fun allSignedRssiByteValuesArePreservedIncludingInvalidTestValues() {
        for (rssi in -128..127) {
            val track = TagTrack("a", kind = PayloadKind.REPEATER, rssi = RssiPattern(min = rssi, max = rssi))
            assertEquals(rssi, Protocol.payload(track, 0)[7].toInt())
        }
    }

    @Test fun hexRejectsAmbiguousOrOversizedRepresentations() {
        for (input in listOf("0", "0x00", "00 11", "gg", "０１", "a".repeat(482))) {
            assertThrows(IllegalArgumentException::class.java) { Protocol.unhex(input) }
        }
        assertArrayEquals(byteArrayOf(), Protocol.unhex(""))
    }

    @Test fun hexRoundTripsAllBytesAndGeneratedArrays() {
        val random = Random(20261026)
        repeat(2_000) {
            val bytes = ByteArray(random.nextInt(241)).also(random::nextBytes)
            assertArrayEquals(bytes, Protocol.unhex(Protocol.hex(bytes)))
        }
    }

    @Test fun directRandomCasesPreserveHeaderIdAndCounter() {
        val random = Random(654321)
        repeat(10_000) {
            val id = random.nextInt(65536)
            val subtype = random.nextInt(4)
            val counter = random.nextInt(16_777_216)
            val payload = Protocol.payload(TagTrack("a", shortId = id, subtype = subtype, counterStart = counter.toLong()), 0)
            assertEquals(6, payload.size)
            assertEquals(subtype shl 2, payload[0].toInt())
            assertEquals(id, (payload[1].toInt() and 255) or ((payload[2].toInt() and 255) shl 8))
            assertEquals(counter, (payload[3].toInt() and 255) or ((payload[4].toInt() and 255) shl 8) or ((payload[5].toInt() and 255) shl 16))
        }
    }

    @Test fun invalidStandaloneProtocolArgumentsFailClearly() {
        for (track in listOf(TagTrack("a", subtype = 4), TagTrack("a", shortId = -1), TagTrack("a", counterStart = -1), TagTrack("a", counterStep = Long.MAX_VALUE), TagTrack("a", kind = PayloadKind.RAW), TagTrack("a", kind = PayloadKind.REPEATER, transmitterHex = "00"))) {
            assertThrows(IllegalArgumentException::class.java) { Protocol.payload(track, 0) }
        }
        assertThrows(IllegalArgumentException::class.java) { Protocol.payload(TagTrack("a"), -1) }
    }

    @Test fun independentlyAuthoredCrossLanguageFixturesMatchActualEncoder() {
        val fixture = listOf(File("fixtures/protocol-vectors.json"), File("../fixtures/protocol-vectors.json"))
            .firstOrNull(File::isFile) ?: error("Protocolfixture ontbreekt; deze test mag niet worden overgeslagen.")
        val vectors = Json.parseToJsonElement(fixture.readText()).jsonObject.getValue("vectors").jsonArray
        assertTrue(vectors.size >= 17)
        for (vector in vectors) {
            val value = vector.jsonObject
            val track = Json.decodeFromJsonElement<TagTrack>(value.getValue("buildTrack"))
            assertEquals(value.getValue("id").jsonPrimitive.content, value.getValue("payloadHex").jsonPrimitive.content,
                Protocol.hex(Protocol.payload(track, value.getValue("frameIndex").jsonPrimitive.long)))
        }
    }
}
