package org.anthrobios.vespasim.core

import org.junit.Assert.*
import org.junit.Test

class ProfileCodecTest {
    private val minimal = """{"id":"test","name":"Test","tracks":[{"id":"a"}]}"""
    private fun profile() = ProfileCodec.decode(minimal)
    private fun reject(text: String) = assertThrows(ProfileValidationException::class.java) { ProfileCodec.decode(text) }

    @Test fun minimalProfileGetsDocumentedDefaults() {
        val p = profile()
        assertEquals(1, p.schemaVersion)
        assertEquals(64613, p.companyId)
        assertEquals(60_000L, p.durationMs)
        assertEquals(RadioMode.LEGACY_1M, p.radio.mode)
        assertEquals(4660, p.tracks.single().shortId)
        assertNull(p.radio.localName)
    }

    @Test fun optionalLocalNameRoundTripsAndWrongTypesAreRejected() {
        val named = profile().copy(radio = RadioSettings(localName = "VESPA-NANO-SIM"))
        assertEquals(named, ProfileCodec.decode(ProfileCodec.encode(named)))
        assertNull(ProfileCodec.decode(minimal.dropLast(1) + ",\"radio\":{\"localName\":null}}").radio.localName)
        for (value in listOf("123", "false", "[]", "{}", "\"\"", "\" \"", "\"Bad\\nname\"")) {
            reject(minimal.dropLast(1) + ",\"radio\":{\"localName\":$value}}")
        }
    }

    @Test fun nameOnlyRequiresAnExplicitValidNameAndNoRawPayload() {
        val named = profile().copy(radio = RadioSettings(localName = "VESPA-NANO-SIM"), tracks = listOf(TagTrack("n", kind = PayloadKind.NAME_ONLY)))
        assertEquals(emptyList<String>(), ProfileCodec.validate(named))
        assertTrue(ProfileCodec.validate(named.copy(radio = RadioSettings())).isNotEmpty())
        assertTrue(ProfileCodec.validate(named.copy(tracks = listOf(TagTrack("n", kind = PayloadKind.NAME_ONLY, rawFrames = listOf("00"))))).isNotEmpty())
    }

    @Test fun combinedNameAndPayloadBudgetIsEnforcedForEveryFrameAndUnicode() {
        val p = profile()
        assertEquals(emptyList<String>(), ProfileCodec.validate(p.copy(radio = RadioSettings(localName = "é".repeat(8)))))
        assertTrue(ProfileCodec.validate(p.copy(radio = RadioSettings(localName = "é".repeat(9)))).isNotEmpty())
        assertTrue(ProfileCodec.validate(p.copy(radio = RadioSettings(localName = "VESPA-NANO-SIM"), tracks = listOf(TagTrack("r", kind = PayloadKind.REPEATER)))).isNotEmpty())
        assertTrue(ProfileCodec.validate(p.copy(radio = RadioSettings(localName = "x"), tracks = listOf(TagTrack("r", kind = PayloadKind.RAW, rawFrames = listOf("00", "00".repeat(24)))))).isNotEmpty())
        val extended = p.copy(radio = RadioSettings(RadioMode.EXTENDED_1M, localName = "é".repeat(124)), tracks = listOf(TagTrack("n", kind = PayloadKind.NAME_ONLY)))
        assertEquals(emptyList<String>(), ProfileCodec.validate(extended))
        assertTrue(ProfileCodec.validate(extended.copy(radio = extended.radio.copy(localName = "é".repeat(125)))).isNotEmpty())
    }

    @Test fun canonicalRoundTripPreservesContentAndNormalizesOnlyHexCase() {
        val p = profile().copy(name = "Tag bij boom 🌳", description = "Regel één\nRegel twee", tracks = listOf(TagTrack("raw", kind = PayloadKind.RAW, transmitterHex = "ABCDEF123456", rawFrames = listOf("00FF"))))
        val encoded = ProfileCodec.encode(p)
        val decoded = ProfileCodec.decode(encoded)
        assertEquals(p.copy(tracks = p.tracks.map { it.copy(transmitterHex = "abcdef123456", rawFrames = listOf("00ff")) }), decoded)
        assertEquals(encoded, ProfileCodec.encode(decoded))
        assertTrue(encoded.contains("\"schemaVersion\": 1"))
    }

    @Test fun unknownFieldsAtEveryRelevantLevelAreRejected() {
        for (text in listOf(
            minimal.dropLast(1) + ",\"oops\":1}",
            minimal.replace("\"id\":\"a\"", "\"id\":\"a\",\"oops\":1"),
            minimal.dropLast(1) + ",\"radio\":{\"oops\":1}}",
            minimal.replace("\"id\":\"a\"", "\"id\":\"a\",\"rssi\":{\"oops\":1}"),
        )) reject(text)
    }

    @Test fun duplicateKeysIncludingEscapedAliasesAreRejected() {
        reject(minimal.replace("\"id\":\"test\"", "\"id\":\"test\",\"id\":\"other\""))
        reject(minimal.replace("\"id\":\"a\"", "\"id\":\"a\",\"i\\u0064\":\"b\""))
        reject(minimal.dropLast(1) + ",\"radio\":{\"intervalMs\":200,\"intervalMs\":300}}")
    }

    @Test fun duplicateKeyNamesInDifferentObjectsAreAllowed() {
        assertEquals(2, ProfileCodec.decode(minimal.replace("[{\"id\":\"a\"}]", "[{\"id\":\"a\"},{\"id\":\"b\"}]")).tracks.size)
    }

    @Test fun malformedJsonAndTrailingDocumentsAreRejected() {
        for (text in listOf("", "[]", "null", minimal + minimal, minimal + " trailing", minimal.dropLast(1), minimal.dropLast(1) + ",}", "\uFEFF" + minimal)) reject(text)
    }

    @Test fun stringsCannotMasqueradeAsNumbersOrEnumsAsNumbers() {
        reject(minimal.dropLast(1) + ",\"slotMs\":\"500\"}")
        reject(minimal.dropLast(1) + ",\"durationMs\":6e4}")
        reject(minimal.dropLast(1) + ",\"slotMs\":500.0}")
        reject(minimal.replace("\"name\":\"Test\"", "\"name\":123"))
        reject(minimal.replace("\"id\":\"a\"", "\"id\":\"a\",\"kind\":0"))
        reject(minimal.replace("\"id\":\"a\"", "\"id\":\"a\",\"kind\":\"RAW\",\"rawFrames\":[123]"))
    }

    @Test fun unsupportedVersionsUnknownEnumsAndMissingFieldsFail() {
        reject(minimal.dropLast(1) + ",\"schemaVersion\":2}")
        reject(minimal.replace("\"id\":\"a\"", "\"id\":\"a\",\"kind\":\"TAG_FROM_FUTURE\""))
        reject("""{"id":"test","name":"Test"}""")
        reject(minimal.replace("\"id\":\"test\",", ""))
    }

    @Test fun nullIsOnlyAllowedForOptionalActiveEnd() {
        assertNull(ProfileCodec.decode(minimal.replace("\"id\":\"a\"", "\"id\":\"a\",\"activeUntilMs\":null")).tracks.single().activeUntilMs)
        reject(minimal.dropLast(1) + ",\"slotMs\":null}")
        reject(minimal.replace("\"name\":\"Test\"", "\"name\":null"))
    }

    @Test fun oversizedUtf8AndDeepOrHugeStringsFailBeforeModelDecode() {
        reject(" ".repeat(ProfileCodec.MAX_BYTES + 1))
        reject("é".repeat(ProfileCodec.MAX_BYTES / 2 + 1))
        reject("{\"x\":" + "[".repeat(30) + "0" + "]".repeat(30) + "}")
        reject(minimal.replace("Test", "x".repeat(16_385)))
    }

    @Test fun invalidUnicodeAndControlNamesAreRejectedButValidEscapesWork() {
        reject(minimal.replace("Test", "\\uD800"))
        reject(minimal.replace("Test", "\\uDC00"))
        reject(minimal.replace("Test", "\\uD800x"))
        reject(minimal.replace("Test", "\\u００４１"))
        reject(minimal.replace("Test", "Bad\\nname"))
        assertEquals("Tag 🌳", ProfileCodec.decode(minimal.replace("Test", "Tag \\uD83C\\uDF33")).name)
    }

    @Test fun invalidNumbersAndLeadingZerosAreRejected() {
        for (number in listOf("NaN", "Infinity", "-Infinity", "0500", "+500", "500.", "5e", "9223372036854775808")) reject(minimal.dropLast(1) + ",\"durationMs\":$number}")
    }

    @Test fun identityAndDisplayTextAreBounded() {
        val p = profile()
        for (invalid in listOf(p.copy(id = "../escape"), p.copy(id = "."), p.copy(id = "x".repeat(65)), p.copy(name = " "), p.copy(name = "x".repeat(121)), p.copy(description = "x".repeat(4097)))) {
            assertTrue(ProfileCodec.validate(invalid).isNotEmpty())
            assertThrows(ProfileValidationException::class.java) { ProfileCodec.encode(invalid) }
        }
    }

    @Test fun scenarioAndRadioBoundsRejectUnsafeOrNonsensicalValues() {
        val p = profile()
        for (invalid in listOf(p.copy(durationMs = 0), p.copy(durationMs = Long.MAX_VALUE), p.copy(slotMs = 249), p.copy(slotMs = 60001), p.copy(companyId = -1), p.copy(companyId = 65536), p.copy(lossPercent = 101), p.copy(lossPercent = -1), p.copy(radio = RadioSettings(intervalMs = 99)), p.copy(radio = RadioSettings(intervalMs = 10241)), p.copy(radio = RadioSettings(txPowerDbm = 2)), p.copy(radio = RadioSettings(txPowerDbm = 20)), p.copy(radio = RadioSettings(txPowerDbm = -128)))) {
            assertTrue(invalid.toString(), ProfileCodec.validate(invalid).isNotEmpty())
        }
    }

    @Test fun transmitPowerBoundsRemainCompatibleWithOlderAdvertisingSetBuilders() {
        for (power in listOf(-127, -7, 0, 1)) {
            assertEquals(emptyList<String>(), ProfileCodec.validate(profile().copy(radio = RadioSettings(txPowerDbm = power))))
        }
        for (power in listOf(-128, 2, 20)) {
            reject(minimal.dropLast(1) + ",\"radio\":{\"txPowerDbm\":$power}}")
        }
    }

    @Test fun trackCountAndIdsAreBoundedWhileShortIdCollisionsRemainLegal() {
        val p = profile()
        assertTrue(ProfileCodec.validate(p.copy(tracks = emptyList())).isNotEmpty())
        assertTrue(ProfileCodec.validate(p.copy(tracks = (1..33).map { TagTrack("tag$it") })).isNotEmpty())
        assertTrue(ProfileCodec.validate(p.copy(tracks = listOf(TagTrack("same"), TagTrack("same")))).isNotEmpty())
        assertEquals(emptyList<String>(), ProfileCodec.validate(p.copy(tracks = listOf(TagTrack("a"), TagTrack("b")))))
    }

    @Test fun trackNumericAndHexFieldsAreValidated() {
        val p = profile()
        for (track in listOf(TagTrack("a", subtype = 4), TagTrack("a", shortId = 65536), TagTrack("a", transmitterHex = "１２３４５６７８９０１２"), TagTrack("a", counterStart = 16_777_216), TagTrack("a", counterMode = CounterMode.COUNTER16_TEMPERATURE, counterStart = 65536), TagTrack("a", counterStep = Long.MIN_VALUE), TagTrack("a", counterResetEvery = -1), TagTrack("a", temperatureRaw = 256), TagTrack("a", rssi = RssiPattern(min = 0, max = -10)), TagTrack("a", rssi = RssiPattern(periodSlots = 1)), TagTrack("a", rssi = RssiPattern(unavailableEvery = -1)))) {
            assertTrue(track.toString(), ProfileCodec.validate(p.copy(tracks = listOf(track))).isNotEmpty())
        }
    }

    @Test fun legacyAndExtendedRawDataHaveDifferentConservativeLimits() {
        val p = profile()
        fun raw(bytes: Int) = p.copy(tracks = listOf(TagTrack("r", kind = PayloadKind.RAW, rawFrames = listOf("ab".repeat(bytes)))))
        assertEquals(emptyList<String>(), ProfileCodec.validate(raw(24)))
        assertTrue(ProfileCodec.validate(raw(25)).isNotEmpty())
        assertEquals(emptyList<String>(), ProfileCodec.validate(raw(240).copy(radio = RadioSettings(RadioMode.EXTENDED_1M))))
        assertTrue(ProfileCodec.validate(raw(241).copy(radio = RadioSettings(RadioMode.EXTENDED_CODED))).isNotEmpty())
    }

    @Test fun rawFramesRequireExplicitBytesAndRejectWhitespaceOrUnusedFields() {
        val p = profile()
        for (track in listOf(TagTrack("r", kind = PayloadKind.RAW), TagTrack("r", kind = PayloadKind.RAW, rawFrames = listOf("0")), TagTrack("r", kind = PayloadKind.RAW, rawFrames = listOf("aa bb")), TagTrack("r", rawFrames = listOf("00")), TagTrack("r", kind = PayloadKind.RAW, rawFrames = List(257) { "00" }))) {
            assertTrue(ProfileCodec.validate(p.copy(tracks = listOf(track))).isNotEmpty())
        }
        assertEquals(emptyList<String>(), ProfileCodec.validate(p.copy(tracks = listOf(TagTrack("r", kind = PayloadKind.RAW, rawFrames = listOf(""))))))
    }

    @Test fun pauseWindowsMustBeOrderedAlignedAndWithinDuration() {
        val p = profile()
        for (pauses in listOf(listOf(TimeRange(0, 0)), listOf(TimeRange(-500, 500)), listOf(TimeRange(100, 500)), listOf(TimeRange(500, 1001)), listOf(TimeRange(0, 70000)), listOf(TimeRange(1000, 2000), TimeRange(500, 1000)), listOf(TimeRange(0, 1500), TimeRange(1000, 2000)))) {
            assertTrue(pauses.toString(), ProfileCodec.validate(p.copy(pauses = pauses)).isNotEmpty())
        }
        assertEquals(emptyList<String>(), ProfileCodec.validate(p.copy(pauses = listOf(TimeRange(0, 500), TimeRange(500, 1000)))))
    }

    @Test fun trackWindowsRequireSlotBoundariesExceptFinalPartialSlot() {
        val p = profile().copy(durationMs = 1751)
        assertEquals(emptyList<String>(), ProfileCodec.validate(p.copy(tracks = listOf(TagTrack("a", activeUntilMs = 1751)))))
        for (track in listOf(TagTrack("a", activeFromMs = 1), TagTrack("a", activeUntilMs = 1100), TagTrack("a", activeFromMs = 1500, activeUntilMs = 1000), TagTrack("a", activeFromMs = 2000))) {
            assertTrue(ProfileCodec.validate(p.copy(tracks = listOf(track))).isNotEmpty())
        }
    }
}
