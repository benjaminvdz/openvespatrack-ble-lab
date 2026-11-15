package org.anthrobios.vespasim.core.capture

import org.anthrobios.vespasim.core.RunStatus
import org.junit.Assert.*
import org.junit.Test

class CaptureCodecTest {
    private val record = CaptureRecord("capture-1", "Nano proef 🐝", "AA:BB:CC:DD:EE:FF", 1000, 10, deviceInfo = "Test radio", settings = mapOf("mode" to "selected-address"))
    private val sample = CaptureObservation(1, 1000, 100, "AA:BB:CC:DD:EE:FF", -67, "04FF65FC00", deviceName = "Cached\nname")
    private val event = CaptureEvent(1, 1000, "capture_started", "Opname begonnen", mapOf("sequence" to "ongewijzigde tekst"))
    private fun rejectRecord(text: String) { assertThrows(IllegalArgumentException::class.java) { CaptureCodec.decodeRecord(text) } }
    private fun rejectSample(text: String) { assertThrows(IllegalArgumentException::class.java) { CaptureCodec.decodeObservation(text) } }

    @Test fun captureRecordRoundTripPreservesMetadataAndImportedSource() {
        val imported = record.copy(importedFromId = "external-1", importedArchiveSha256 = "ab".repeat(32))
        assertEquals(record, CaptureCodec.decodeRecord(CaptureCodec.encodeRecord(record)))
        assertEquals(imported, CaptureCodec.decodeRecord(CaptureCodec.encodeRecord(imported)))
    }

    @Test fun observationRoundTripPreservesHexCaseRawIntegersAndDistinctClocks() {
        val original = sample.copy(receivedAtMs = 12, timestampNanos = 9900990099, rssiRaw = Int.MAX_VALUE, txPowerRaw = Int.MIN_VALUE, primaryPhy = 100)
        assertEquals(original, CaptureCodec.decodeObservation(CaptureCodec.encodeObservation(original)))
        assertTrue(CaptureCodec.encodeObservation(original).contains("04FF65FC00"))
    }

    @Test fun absentRawAndCachedNameRemainNull() {
        val original = sample.copy(rawHex = null, deviceName = null)
        val decoded = CaptureCodec.decodeObservation(CaptureCodec.encodeObservation(original))
        assertEquals(original, decoded)
        assertNull(decoded.rawHex)
        assertFalse(CaptureParser.inspect(decoded).replayable)
    }

    @Test fun maximumRawScanRecordIsAcceptedAndNextByteRejected() {
        val maximum = sample.copy(rawHex = "ab".repeat(CaptureCodec.MAX_RAW_BYTES))
        assertEquals(maximum, CaptureCodec.decodeObservation(CaptureCodec.encodeObservation(maximum)))
        assertThrows(IllegalArgumentException::class.java) { CaptureCodec.encodeObservation(maximum.copy(rawHex = maximum.rawHex + "00")) }
        for (hex in listOf("a", "aa bb", "ＦＦ", "xz")) assertThrows(IllegalArgumentException::class.java) { CaptureCodec.validateObservation(sample.copy(rawHex = hex)) }
    }

    @Test fun eventRoundTripDoesNotTreatDetailsKeysAsReservedFieldTypes() {
        assertEquals(event, CaptureCodec.decodeEvent(CaptureCodec.encodeEvent(event)))
        val value = record.copy(settings = mapOf("sequence" to "literal", "legacy" to "literal"))
        assertEquals(value, CaptureCodec.decodeRecord(CaptureCodec.encodeRecord(value)))
    }

    @Test fun malformedDuplicateEscapedDuplicateAndTrailingJsonAreRejected() {
        val json = CaptureCodec.encodeRecord(record)
        for (bad in listOf("", "[]", "null", json + json, json.dropLast(1), json.replace("\"id\":", "\"id\":\"other\",\"id\":"), json.replace("\"id\":", "\"i\\u0064\":\"other\",\"id\":"))) rejectRecord(bad)
    }

    @Test fun duplicateNestedSettingsAndUnknownFieldsAreRejected() {
        val json = CaptureCodec.encodeRecord(record)
        rejectRecord(json.replace("\"mode\":", "\"mode\":\"first\",\"mode\":"))
        rejectRecord(json.dropLast(1) + ",\"unknown\":\"value\"}")
        rejectSample(CaptureCodec.encodeObservation(sample).dropLast(1) + ",\"inventedRfAddress\":\"00\"}")
    }

    @Test fun numbersBooleansAndStringsMustHaveExactJsonTypes() {
        val json = CaptureCodec.encodeObservation(sample)
        for (value in listOf("\"1\"", "1.0", "1e0", "true", "null", "9223372036854775808")) rejectSample(json.replace("\"sequence\":1", "\"sequence\":$value"))
        for (value in listOf("\"true\"", "1", "null")) rejectSample(json.replace("\"legacy\":true", "\"legacy\":$value"))
        for (value in listOf("123", "true", "[]", "{}")) rejectSample(json.replace("\"rawHex\":\"04FF65FC00\"", "\"rawHex\":$value"))
    }

    @Test fun unicodeSurrogatesAreStrictlyValidatedButValidPairSurvives() {
        val json = CaptureCodec.encodeRecord(record)
        for (escape in listOf("\\uD800", "\\uDC00", "\\uD800x", "\\uD800\\u0041")) rejectRecord(json.replace("Nano proef 🐝", escape))
        assertEquals("🐝", CaptureCodec.decodeRecord(json.replace("Nano proef 🐝", "\\ud83d\\udc1d")).name)
        assertThrows(IllegalArgumentException::class.java) { CaptureCodec.validateObservation(sample.copy(deviceName = "\uD800")) }
    }

    @Test fun boundedSchemaIndependentJsonCheckRejectsDeepOversizedAndDuplicateInput() {
        CaptureCodec.checkJson("{\"a\":[1,true,{\"b\":null}],\"x\":\"🐝\"}")
        for (bad in listOf("{\"x\":" + "[".repeat(25) + "0" + "]".repeat(25) + "}", "{\"x\":1,\"x\":2}")) assertThrows(IllegalArgumentException::class.java) { CaptureCodec.checkJson(bad) }
        assertThrows(IllegalArgumentException::class.java) { CaptureCodec.checkJson("{\"x\":\"é\"}", 9) }
        assertThrows(IllegalArgumentException::class.java) { CaptureCodec.checkJson("{}", -1) }
    }

    @Test fun recordStateRequiresConsistentCompletionAndValidIdentity() {
        val completed = record.copy(status = RunStatus.STOPPED, endedAtMs = 900) // Wall clock can move backwards.
        CaptureCodec.validateRecord(completed)
        for (bad in listOf(record.copy(id = "../escape"), record.copy(address = "not-address"), record.copy(name = " "), record.copy(name = "bad\nname"), record.copy(status = RunStatus.COMPLETED), record.copy(endedAtMs = 2000), record.copy(tagVariant = "unconfirmed-device"), record.copy(schemaVersion = 2), record.copy(importedFromId = "other"), record.copy(importedArchiveSha256 = "ab".repeat(32)))) {
            assertThrows(bad.toString(), IllegalArgumentException::class.java) { CaptureCodec.validateRecord(bad) }
        }
    }

    @Test fun observationIdentityTimeAndSequenceAreBoundedWithoutPretendingRssiValidity() {
        for (bad in listOf(sample.copy(sequence = 0), sample.copy(receivedAtMs = -1), sample.copy(timestampNanos = -1), sample.copy(address = "../../bad"), sample.copy(deviceName = "x".repeat(1025)))) assertThrows(IllegalArgumentException::class.java) { CaptureCodec.validateObservation(bad) }
        CaptureCodec.validateObservation(sample.copy(rssiRaw = 127, dataStatus = 99))
    }

    @Test fun noteSettingsAndEventLimitsAreEnforced() {
        for (bad in listOf(record.copy(notes = "x".repeat(4097)), record.copy(settings = (1..65).associate { "$it" to "x" }), record.copy(settings = mapOf("k" to "x".repeat(513))))) assertThrows(IllegalArgumentException::class.java) { CaptureCodec.validateRecord(bad) }
        for (bad in listOf(event.copy(kind = " "), event.copy(message = "x".repeat(4097)), event.copy(details = mapOf("k" to "x".repeat(1025))), event.copy(sequence = 0))) assertThrows(IllegalArgumentException::class.java) { CaptureCodec.validateEvent(bad) }
        val json = CaptureCodec.encodeEvent(event)
        assertThrows(IllegalArgumentException::class.java) { CaptureCodec.decodeEvent(json.replace("\"ongewijzigde tekst\"", "1")) }
    }
}
