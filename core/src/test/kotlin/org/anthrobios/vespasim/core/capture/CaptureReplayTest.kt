package org.anthrobios.vespasim.core.capture

import org.anthrobios.vespasim.core.*
import org.junit.Assert.*
import org.junit.Test

class CaptureReplayTest {
    private val record = CaptureRecord("capture-1", "Testopname", "AA:BB:CC:DD:EE:FF", 1000, 100, 4000, RunStatus.STOPPED)
    private val sample = CaptureObservation(1, 1500, 111_000, record.address, -67, "04FF65FC00")
    private fun profile() = CaptureReplay.staticProfile(record, sample, "replay-1", "Statisch")
    private fun reject(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun staticProfileContainsExactSourceSnapshotAndNoFabricatedReceiverRssi() {
        val profile = profile()
        assertEquals(2, profile.schemaVersion)
        assertEquals(PayloadKind.CAPTURED, profile.tracks.single().kind)
        val source = profile.tracks.single().capturedFrames.single()
        assertEquals(record.id, source.captureId)
        assertEquals(sample, source.observation)
        assertEquals("04FF65FC00", source.observation.rawHex)
        assertEquals(CaptureReplay.sha256Hex(sample.rawHex!!), source.rawSha256)
        assertFalse(source.importedSource)
        val frames = Planner.plan(profile)
        assertEquals(120, frames.size)
        assertTrue(frames.all { it.payloadHex == "" && !it.includeManufacturerData && it.reportedRssi == null && it.capturedAdvertisement == source })
    }

    @Test fun sha256UsesRawBytesNotHexSpellingAndMatchesKnownVector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", CaptureReplay.sha256Hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", CaptureReplay.sha256Hex("616263"))
        assertEquals(CaptureReplay.sha256Hex("abFF"), CaptureReplay.sha256Hex("ABff"))
    }

    @Test fun selectedSequenceUsesFixedSlotsAndRetainsRepeatedContentInOrder() {
        val observations = listOf(sample.copy(sequence = 4, timestampNanos = 1000), sample.copy(sequence = 8, timestampNanos = 9000), sample.copy(sequence = 9, timestampNanos = 70000000, rawHex = "04ff65fc01"))
        val profile = CaptureReplay.sequenceProfile(record, observations, "replay", "Reeks", 750)
        assertEquals(2250L, profile.durationMs)
        val plan = Planner.plan(profile)
        assertEquals(listOf(0L, 750L, 1500L), plan.map { it.atMs })
        assertEquals(listOf(4L, 8L, 9L), plan.map { it.capturedAdvertisement!!.observation.sequence })
        assertEquals(listOf("04FF65FC00", "04FF65FC00", "04ff65fc01"), plan.map { it.capturedAdvertisement!!.observation.rawHex })
        assertEquals(plan, Planner.plan(ProfileCodec.decode(ProfileCodec.encode(profile))))
    }

    @Test fun malformedAnySelectedRecordRejectsWholeSequenceWithoutFiltering() {
        val bad = sample.copy(sequence = 2, rawHex = "020af3")
        reject { CaptureReplay.sequenceProfile(record, listOf(sample, bad), "r", "Replay", 500) }
    }

    @Test fun sequenceBoundsOrderingAndExplicitSlotBoundsAreValidated() {
        reject { CaptureReplay.sequenceProfile(record, emptyList(), "r", "Replay", 500) }
        reject { CaptureReplay.sequenceProfile(record, List(257) { sample.copy(sequence = it + 1L) }, "r", "Replay", 500) }
        reject { CaptureReplay.sequenceProfile(record, listOf(sample, sample), "r", "Replay", 500) }
        reject { CaptureReplay.sequenceProfile(record, listOf(sample.copy(sequence = 2), sample), "r", "Replay", 500) }
        for (slot in listOf(249, 60001, -1, Int.MAX_VALUE)) reject { CaptureReplay.sequenceProfile(record, listOf(sample), "r", "Replay", slot) }
        for (slot in listOf(250, 60000)) assertEquals(slot, CaptureReplay.sequenceProfile(record, listOf(sample), "r", "Replay", slot).slotMs)
    }

    @Test fun matchingLocalNameIsRequiredAndCachedNameDoesNotCount() {
        val one = sample.copy(rawHex = "020961")
        val two = one.copy(sequence = 2, rawHex = "020962")
        reject { CaptureReplay.sequenceProfile(record, listOf(one, two), "r", "Replay", 500) }
        reject { CaptureReplay.sequenceProfile(record, listOf(one, sample.copy(sequence = 2)), "r", "Replay", 500) }
        val p = CaptureReplay.sequenceProfile(record, listOf(one, one.copy(sequence = 2, deviceName = "Other cached name")), "r", "Replay", 500)
        assertEquals("a", p.radio.localName)
    }

    @Test fun runningOrCrossAddressEvidenceCannotBeReplayedAsSameCapture() {
        reject { CaptureReplay.staticProfile(record.copy(status = RunStatus.RUNNING, endedAtMs = null), sample, "r", "Replay") }
        reject { CaptureReplay.staticProfile(record, sample.copy(address = "00:00:00:00:00:00"), "r", "Replay") }
        assertEquals(record.id, CaptureReplay.staticProfile(record, sample.copy(address = record.address.lowercase()), "r", "Replay").tracks.single().capturedFrames.single().captureId)
    }

    @Test fun importedEvidenceRemainsClearlyMarkedInProfileAndRoundTrip() {
        val imported = record.copy(importedFromId = "foreign", importedArchiveSha256 = "ab".repeat(32))
        val profile = CaptureReplay.staticProfile(imported, sample, "r", "Replay")
        assertTrue(ProfileCodec.decode(ProfileCodec.encode(profile)).tracks.single().capturedFrames.single().importedSource)
    }

    @Test fun sourceTamperingFailsValidationEvenWhenNewAdBytesAreOtherwiseReplayable() {
        val profile = profile()
        val source = profile.tracks.single().capturedFrames.single()
        for (bad in listOf(source.copy(rawSha256 = "00".repeat(32)), source.copy(observation = sample.copy(rawHex = "04ff65fc01")), source.copy(captureId = "../escape"), source.copy(observation = sample.copy(rawHex = null)))) {
            reject { ProfileCodec.encode(profile.copy(tracks = listOf(profile.tracks.single().copy(capturedFrames = listOf(bad))))) }
        }
    }

    @Test fun capturedSchemaRequiresLegacyRadioOneTrackAndConsistentNames() {
        val p = profile()
        val namedSource = sample.copy(rawHex = "020961")
        val named = CaptureReplay.staticProfile(record, namedSource, "r", "Name")
        for (bad in listOf(p.copy(schemaVersion = 1), p.copy(radio = RadioSettings(mode = RadioMode.EXTENDED_1M)), p.copy(tracks = listOf(p.tracks.single(), TagTrack("synthetic"))), named.copy(radio = named.radio.copy(localName = "b")), p.copy(tracks = listOf(p.tracks.single().copy(capturedFrames = emptyList()))))) reject { ProfileCodec.encode(bad) }
    }

    @Test fun oldSchemaOneExportsDoNotAcquireCapturedFieldsAndStillPlanIdentically() {
        for (profile in Presets.all()) {
            val encoded = ProfileCodec.encode(profile)
            assertFalse(encoded.contains("capturedFrames"))
            assertEquals(1, ProfileCodec.decode(encoded).schemaVersion)
            assertEquals(Planner.plan(profile), Planner.plan(ProfileCodec.decode(encoded)))
        }
    }

    @Test fun strictNestedSourceTypesAndDuplicateKeysAreRejected() {
        val encoded = ProfileCodec.encode(profile())
        for (bad in listOf(encoded.replace("\"sequence\": 1", "\"sequence\": \"1\""), encoded.replace("\"legacy\": true", "\"legacy\": \"true\""), encoded.replace("\"importedSource\": false", "\"importedSource\": 0"), encoded.replace("\"rawHex\": \"04FF65FC00\"", "\"rawHex\": 4"), encoded.replace("\"rawSha256\":", "\"rawSha256\": \"wrong\", \"rawSha256\":"))) reject { ProfileCodec.decode(bad) }
    }

    @Test fun lossesAndPauseRemainSilenceAndNeverEmitCapturedPayloadAccidentally() {
        val profile = profile().copy(lossPercent = 100)
        assertTrue(Planner.plan(profile).all { it.payloadHex == null && it.capturedAdvertisement == null })
        val paused = profile().copy(pauses = listOf(TimeRange(0, 500)))
        assertNull(Planner.plan(paused).first().capturedAdvertisement)
        assertNotNull(Planner.plan(paused)[1].capturedAdvertisement)
    }

    @Test fun invalidProfileIdDurationAndOverlongSerializationAreExplicitErrors() {
        reject { CaptureReplay.staticProfile(record, sample, "../escape", "Replay") }
        reject { CaptureReplay.staticProfile(record, sample, "r", "Replay", 249) }
        reject { CaptureReplay.staticProfile(record, sample, "r", "Replay", Long.MAX_VALUE) }
        val big = List(256) { sample.copy(sequence = it + 1L, deviceName = "x".repeat(1024)) }
        reject { CaptureReplay.sequenceProfile(record, big, "r", "Replay", 500) }
    }
}
