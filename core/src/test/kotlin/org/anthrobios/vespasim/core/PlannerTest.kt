package org.anthrobios.vespasim.core

import java.util.Random
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PlannerTest {
    @Test fun nameOnlyIsActiveAdvertisingAndDistinctFromZeroByteManufacturerData() {
        val preset = Presets.all().single { it.id == "nano-name-recognition" }
        val named = preset.copy(durationMs = 1500, pauses = listOf(TimeRange(500, 1000)))
        val plan = Planner.plan(named)
        assertEquals(listOf("", null, ""), plan.map { it.payloadHex })
        assertTrue(plan.none { it.includeManufacturerData })
        assertEquals(listOf("VESPA-NANO-SIM", null, "VESPA-NANO-SIM"), plan.map { it.localName })
        assertEquals("planned_pause", plan[1].silenceReason)
        val raw = Planner.plan(named.copy(pauses = emptyList(), tracks = listOf(TagTrack("raw", kind = PayloadKind.RAW, rawFrames = listOf(""))))).first()
        assertEquals("", raw.payloadHex)
        assertTrue(raw.includeManufacturerData)
    }
    private fun profile() = SimulationProfile(id = "test", name = "Test", durationMs = 3000, tracks = listOf(TagTrack("a")))

    @Test fun slotsAreContiguousAndEndExactlyAtRequestedDuration() {
        val frames = Planner.plan(profile().copy(durationMs = 1751))
        assertEquals(listOf(0L, 500L, 1000L, 1500L), frames.map { it.atMs })
        assertEquals(listOf(500L, 500L, 500L, 251L), frames.map { it.durationMs })
        assertEquals(listOf(1L, 2L, 3L, 4L), frames.map { it.sequence })
        assertEquals(1751L, frames.sumOf { it.durationMs })
    }

    @Test fun multiTrackRoundRobinUsesPerTrackCounters() {
        val frames = Planner.plan(profile().copy(tracks = listOf(TagTrack("a"), TagTrack("b", shortId = 12, counterStart = 100))))
        assertEquals(listOf("a", "b", "a", "b", "a", "b"), frames.map { it.trackId })
        assertEquals(listOf(0, 100, 1, 101, 2, 102), frames.map { Protocol.unhex(it.payloadHex!!)[3].toInt() and 255 })
    }

    @Test fun plannedPauseDisablesRadioAndAttemptCountersAdvanceAcrossSilence() {
        val frames = Planner.plan(profile().copy(pauses = listOf(TimeRange(500, 1500))))
        assertEquals(listOf(null, "planned_pause", "planned_pause", null, null, null), frames.map { it.silenceReason })
        assertNull(frames[1].payloadHex)
        assertNull(frames[2].payloadHex)
        assertEquals("003412030000", frames[3].payloadHex)
    }

    @Test fun completeLossHasNoPayloadButRetainsLogicalTrackAndSlot() {
        val frames = Planner.plan(profile().copy(lossPercent = 100))
        assertTrue(frames.all { it.payloadHex == null && it.silenceReason == "seeded_omission" && it.trackId == "a" })
        assertEquals(6, frames.size)
    }

    @Test fun noLossProducesAllPayloads() {
        assertTrue(Planner.plan(profile()).all { it.payloadHex != null && it.silenceReason == null })
    }

    @Test fun seedIsReproducibleAndChangingSeedChangesOmissionSchedule() {
        val p = profile().copy(durationMs = 60_000, lossPercent = 37, seed = Long.MIN_VALUE)
        assertEquals(Planner.plan(p), Planner.plan(p))
        assertNotEquals(Planner.plan(p).map { it.silenceReason }, Planner.plan(p.copy(seed = 99)).map { it.silenceReason })
    }

    @Test fun lossScheduleIsIndependentOfTracksAndExplicitPauses() {
        val p = profile().copy(durationMs = 20_000, lossPercent = 40)
        val reference = Planner.plan(p)
        val modified = Planner.plan(p.copy(tracks = listOf(TagTrack("b", activeFromMs = 5000)), pauses = listOf(TimeRange(6000, 8000))))
        for (i in modified.indices.filter { modified[it].atMs >= 8000 }) {
            assertEquals(reference[i].silenceReason, modified[i].silenceReason)
        }
    }

    @Test fun inactiveTracksDoNotBroadcastAndJoiningTracksBeginAtTheirOwnZeroAttempt() {
        val frames = Planner.plan(profile().copy(tracks = listOf(TagTrack("a", activeFromMs = 1000, activeUntilMs = 2000))))
        assertEquals(listOf("no_active_track", "no_active_track", null, null, "no_active_track", "no_active_track"), frames.map { it.silenceReason })
        assertNull(frames[0].trackId)
        assertEquals("003412000000", frames[2].payloadHex)
    }

    @Test fun roundRobinSkipsInactiveTracksWithoutCreatingPhantomIdentities() {
        val frames = Planner.plan(profile().copy(tracks = listOf(TagTrack("a", activeUntilMs = 1000), TagTrack("b", activeFromMs = 500), TagTrack("c", activeFromMs = 1500))))
        assertEquals(listOf("a", "b", "b", "c", "b", "c"), frames.map { it.trackId })
    }

    @Test fun emptyRawPayloadIsNotConvertedToSilence() {
        val frames = Planner.plan(profile().copy(tracks = listOf(TagTrack("raw", kind = PayloadKind.RAW, rawFrames = listOf("")))))
        assertTrue(frames.all { it.payloadHex == "" && it.silenceReason == null })
    }

    @Test fun onlyRepeaterFramesExposeReportedRssiAndMissingSentinelIsRetained() {
        val frames = Planner.plan(profile().copy(tracks = listOf(TagTrack("a"), TagTrack("r", kind = PayloadKind.REPEATER, rssi = RssiPattern(unavailableEvery = 1)))))
        assertNull(frames[0].reportedRssi)
        assertEquals(127, frames[1].reportedRssi)
        assertEquals(127, Protocol.unhex(frames[1].payloadHex!!)[7].toInt())
    }

    @Test fun allPresetsAreValidFiniteAndRoundTripCanonically() {
        val profiles = Presets.all()
        assertTrue(profiles.size >= 8)
        assertEquals(profiles.size, profiles.map { it.id }.distinct().size)
        for (preset in profiles) {
            assertEquals(preset.name, emptyList<String>(), ProfileCodec.validate(preset))
            val encoded = ProfileCodec.encode(preset)
            assertEquals(encoded, ProfileCodec.encode(ProfileCodec.decode(encoded)))
            val frames = Planner.plan(preset)
            assertTrue(frames.isNotEmpty())
            assertEquals(preset.durationMs, frames.last().atMs + frames.last().durationMs)
        }
    }

    @Test fun maximumPermittedPlanIsBounded() {
        val frames = Planner.plan(profile().copy(durationMs = 7_200_000, slotMs = 250))
        assertEquals(28_800, frames.size)
        assertEquals(7_200_000, frames.sumOf { it.durationMs }.toInt())
    }

    @Test fun importableExampleFilesMatchBuiltInProfilesExactly() {
        val directory = listOf(File("examples"), File("../examples")).firstOrNull(File::isDirectory)
            ?: error("Voorbeeldprofielen ontbreken; deze controle mag niet worden overgeslagen.")
        val profiles = Presets.all()
        assertEquals(profiles.size, directory.listFiles { file -> file.extension == "json" }!!.size)
        for (profile in profiles) {
            assertEquals(profile, ProfileCodec.decode(File(directory, "${profile.id}.json").readText()))
        }
    }

    @Test fun invalidProfileCannotReachPlanLoopOrOverflow() {
        for (p in listOf(profile().copy(slotMs = 0), profile().copy(durationMs = Long.MAX_VALUE), profile().copy(tracks = emptyList()))) {
            assertThrows(ProfileValidationException::class.java) { Planner.plan(p) }
        }
    }

    @Test fun generatedProfilesPreserveTimingPayloadLimitsAndDeterminism() {
        val random = Random(42)
        repeat(400) {
            val slot = (random.nextInt(8) + 1) * 250
            val duration = random.nextInt(30_000) + 250L
            val p = profile().copy(durationMs = duration, slotMs = slot, seed = random.nextLong(), lossPercent = random.nextInt(101))
            val frames = Planner.plan(p)
            assertEquals(duration, frames.sumOf { it.durationMs })
            assertEquals(frames, Planner.plan(p))
            assertTrue(frames.all { it.durationMs > 0 && it.durationMs <= slot && (it.payloadHex?.length ?: 0) <= 48 })
            for (index in 1 until frames.size) assertEquals(frames[index - 1].atMs + frames[index - 1].durationMs, frames[index].atMs)
        }
    }
}
