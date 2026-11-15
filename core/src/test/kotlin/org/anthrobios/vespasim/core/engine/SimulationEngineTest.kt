package org.anthrobios.vespasim.core.engine

import org.anthrobios.vespasim.core.*
import org.anthrobios.vespasim.core.capture.*
import org.junit.Assert.*
import org.junit.Test

class SimulationEngineTest {
    private class Clock : EngineScheduler {
        data class Job(val at: Long, val id: Long, val work: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        var serial = 0L
        val jobs = mutableListOf<Job>()
        override fun nowMs() = now
        override fun schedule(delayMs: Long, task: () -> Unit): Cancellation {
            val job = Job(now + delayMs, ++serial, task)
            jobs += job
            return Cancellation { job.cancelled = true }
        }
        fun advance(ms: Long) {
            val end = now + ms
            var count = 0
            while (true) {
                val job = jobs.filter { !it.cancelled && it.at <= end }.minWithOrNull(compareBy<Job> { it.at }.thenBy { it.id }) ?: break
                jobs.remove(job)
                now = maxOf(now, job.at)
                job.work()
                check(++count < 100_000) { "Scheduler busy loop" }
            }
            now = end
        }
        fun stall(ms: Long) { now += ms; advance(0) }
    }

    private class FakeRadio : RadioDriver {
        val applied = mutableListOf<PlannedFrame>()
        val applyCallbacks = mutableListOf<(RadioResult) -> Unit>()
        val silenceCallbacks = mutableListOf<(RadioResult) -> Unit>()
        val closeCallbacks = mutableListOf<(RadioResult) -> Unit>()
        var autoApply = true
        var autoSilence = true
        var autoClose = true
        var closeResult = RadioResult(true)
        var throwsApply = false
        var silenceCalls = 0
        var closeCalls = 0
        override fun apply(frame: PlannedFrame, settings: RadioSettings, complete: (RadioResult) -> Unit) {
            if (throwsApply) error("Fake permission revoked")
            applied += frame
            applyCallbacks += complete
            if (autoApply) complete(RadioResult(true, actualTxPowerDbm = -9))
        }
        override fun silence(complete: (RadioResult) -> Unit) {
            silenceCalls++
            silenceCallbacks += complete
            if (autoSilence) complete(RadioResult(true))
        }
        override fun close(complete: (RadioResult) -> Unit) {
            closeCalls++
            closeCallbacks += complete
            if (autoClose) complete(closeResult)
        }
    }

    private class Lab(
        val mode: RunMode = RunMode.BLE,
        val speed: Int = 1,
        profile: SimulationProfile = profile(),
        val failingKind: String? = null,
    ) {
        val clock = Clock()
        val radio = FakeRadio()
        val events = mutableListOf<RunEvent>()
        val states = mutableListOf<EngineState>()
        var terminal: RunStatus? = null
        var terminalCalls = 0
        var wall = 1_700_000_000_000L
        val engine = SimulationEngine(
            RunRecord("test-run", profile, mode, wall, speed = speed), Planner.plan(profile), radio,
            clock, { wall + clock.now },
            { event -> if (event.kind == failingKind) error("disk full") else events += event },
            { states += it }, { status, _ -> terminal = status; terminalCalls++ },
        )
        fun count(kind: String) = events.count { it.kind == kind }
    }

    companion object {
        private fun profile(duration: Long = 2_500, pauses: List<TimeRange> = emptyList()) = SimulationProfile(
            id = "test-profile", name = "Engine test", durationMs = duration, slotMs = 500,
            tracks = listOf(TagTrack("tag-one")), pauses = pauses,
        )
    }

    @Test fun capturedRadioRequestCarriesSourceEvidenceAndNeverClaimsManufacturerOnlyOrMeasuredRf() {
        val record = CaptureRecord("capture-one", "Test capture", "AA:BB:CC:DD:EE:FF", 100, 100, 200, RunStatus.STOPPED)
        val sample = CaptureObservation(1, 150, 150, record.address, -80, "04ff65fcab")
        val profile = CaptureReplay.staticProfile(record, sample, "replay-one", "Captured", durationMs = 1000)
        val lab = Lab(profile = profile)
        lab.engine.start(); lab.clock.advance(1000)
        assertEquals(2, lab.radio.applied.size)
        assertTrue(lab.radio.applied.all { it.capturedAdvertisement?.observation == sample && it.reportedRssi == null })
        val requests = lab.events.filter { it.kind == "radio_request" }
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.message.contains("semantisch") && it.message.contains("geen identieke RF-replay") })
        assertTrue(requests.all { it.details["originalScanHex"] == sample.rawHex && it.details["originalScanSha256"] == CaptureReplay.sha256Hex(sample.rawHex!!) && it.details["captureId"] == record.id && it.details["captureObservationSequence"] == "1" && it.details["importedSource"] == "false" })
        assertTrue(requests.all { "companyId" !in it.details })
        assertEquals(RunStatus.COMPLETED, lab.terminal)
    }

    @Test fun capturedDryReplayHasNoRadioSideEffectsButPreservesItsSourceReferences() {
        val record = CaptureRecord("capture-one", "Test capture", "AA:BB:CC:DD:EE:FF", 100, 100, 200, RunStatus.STOPPED)
        val sample = CaptureObservation(1, 150, 150, record.address, 127, "020961")
        val profile = CaptureReplay.staticProfile(record, sample, "replay-one", "Captured", durationMs = 1000)
        val lab = Lab(mode = RunMode.DRY_RUN, profile = profile)
        lab.engine.start(); lab.clock.advance(1000)
        assertEquals(2, lab.count("simulated_frame"))
        assertTrue(lab.radio.applied.isEmpty())
        assertEquals(0, lab.radio.closeCalls)
        assertTrue(lab.events.filter { it.kind == "simulated_frame" }.all { it.details["captureId"] == record.id })
    }

    @Test fun dryRunNeverTouchesBluetoothAndCompletesAllFrames() {
        val lab = Lab(mode = RunMode.DRY_RUN)
        lab.engine.start(); lab.clock.advance(2_500)
        assertEquals(5, lab.count("simulated_frame"))
        assertEquals(0, lab.count("controller_accepted"))
        assertTrue(lab.radio.applied.isEmpty())
        assertEquals(0, lab.radio.closeCalls)
        assertEquals(RunStatus.COMPLETED, lab.terminal)
    }

    @Test fun nameOnlyAdvertisingReachesRadioAndLogsAbsenceOfManufacturerData() {
        val profile = Presets.all().single { it.id == "nano-name-recognition" }.copy(durationMs = 1000)
        val lab = Lab(profile = profile)
        lab.engine.start(); lab.clock.advance(1000)
        assertEquals(2, lab.radio.applied.size)
        assertTrue(lab.radio.applied.none { it.includeManufacturerData })
        val events = lab.events.filter { it.kind == "radio_request" || it.kind == "controller_accepted" }
        assertEquals(4, events.size)
        assertTrue(events.all { it.details["includeManufacturerData"] == "false" && it.details["localName"] == "VESPA-NANO-SIM" && "companyId" !in it.details })
        assertEquals(0, lab.count("simulated_silence"))
        assertEquals(RunStatus.COMPLETED, lab.terminal)
    }

    @Test fun nameOnlyDryRunRequiresNoRadioOrSystemName() {
        val profile = Presets.all().single { it.id == "nano-name-recognition" }.copy(durationMs = 1000)
        val lab = Lab(mode = RunMode.DRY_RUN, profile = profile)
        lab.engine.start(); lab.clock.advance(1000)
        assertEquals(2, lab.count("simulated_frame"))
        assertTrue(lab.radio.applied.isEmpty())
        assertEquals(0, lab.count("controller_accepted"))
    }

    @Test fun controllerAcknowledgementIsNotCalledTransmission() {
        val lab = Lab(); lab.engine.start(); lab.clock.advance(2_500)
        assertEquals(5, lab.count("controller_accepted"))
        assertEquals(1, lab.radio.closeCalls)
        assertFalse(lab.events.any { "transmitted" in it.kind || "received" in it.kind })
        assertEquals("-9", lab.events.first { it.kind == "controller_accepted" }.details["actualTxPowerDbm"])
    }

    @Test fun lateCallbackSkipsExpiredSlotsInsteadOfBursting() {
        val lab = Lab(profile = profile(5_000)); lab.radio.autoApply = false
        lab.engine.start(); lab.clock.advance(1_500)
        lab.radio.applyCallbacks.first()(RadioResult(true)); lab.clock.advance(0)
        assertEquals(listOf(1L, 4L), lab.radio.applied.map { it.sequence })
        assertEquals(2, lab.count("frame_skipped_late"))
    }

    @Test fun operationTimeoutFailsAndStopsRadio() {
        val lab = Lab(profile = profile(10_000)); lab.radio.autoApply = false
        lab.engine.start(); lab.clock.advance(5_000)
        assertEquals(RunStatus.FAILED, lab.terminal)
        assertEquals(1, lab.radio.closeCalls)
        assertEquals(1, lab.count("error"))
    }

    @Test fun duplicateCallbacksAreIgnored() {
        val lab = Lab(); lab.engine.start()
        lab.radio.applyCallbacks.first()(RadioResult(true))
        assertEquals(1, lab.count("controller_accepted"))
        assertEquals(1, lab.radio.applied.size)
    }

    @Test fun stoppedPendingStartIgnoresLateSuccess() {
        val lab = Lab(); lab.radio.autoApply = false
        lab.engine.start(); lab.engine.stop()
        lab.radio.applyCallbacks.first()(RadioResult(true)); lab.clock.advance(10_000)
        assertEquals(RunStatus.STOPPED, lab.terminal)
        assertEquals(0, lab.count("controller_accepted"))
        assertEquals(1, lab.terminalCalls)
    }

    @Test fun terminalStatusWaitsForStopAcknowledgement() {
        val lab = Lab(); lab.radio.autoClose = false
        lab.engine.start(); lab.engine.stop()
        assertNull(lab.terminal)
        lab.radio.closeCallbacks.single()(RadioResult(true))
        assertEquals(RunStatus.STOPPED, lab.terminal)
    }

    @Test fun cleanupFailureOverridesSuccessfulScenario() {
        val lab = Lab(); lab.radio.closeResult = RadioResult(false, "disconnect failed")
        lab.engine.start(); lab.clock.advance(2_500)
        assertEquals(RunStatus.FAILED, lab.terminal)
        assertEquals(1, lab.count("cleanup_error"))
    }

    @Test fun cleanupTimeoutFailsOnceAndIgnoresLateStop() {
        val lab = Lab(); lab.radio.autoClose = false
        lab.engine.start(); lab.engine.stop(); lab.clock.advance(5_000)
        assertEquals(RunStatus.FAILED, lab.terminal)
        lab.radio.closeCallbacks.single()(RadioResult(true))
        assertEquals(1, lab.terminalCalls)
    }

    @Test fun loggingFailureStopsRealRadioAndCannotBeReportedSuccessful() {
        val lab = Lab(failingKind = "controller_accepted")
        lab.engine.start()
        assertEquals(RunStatus.FAILED, lab.terminal)
        assertEquals(1, lab.radio.closeCalls)
        assertEquals(0, lab.count("controller_accepted"))
    }

    @Test fun initialLogFailureDoesNotStartRadio() {
        val lab = Lab(failingKind = "run_started")
        lab.engine.start()
        assertTrue(lab.radio.applied.isEmpty())
        assertEquals(RunStatus.FAILED, lab.terminal)
    }

    @Test fun pauseDisablesRadioAndFreezesScenarioClock() {
        val lab = Lab(); lab.engine.start(); lab.clock.advance(200)
        lab.engine.setPaused(true); lab.clock.advance(5_000)
        assertEquals(1, lab.radio.silenceCalls)
        assertEquals(1, lab.radio.applied.size)
        assertEquals(200L, lab.states.last().scenarioElapsedMs)
        lab.engine.setPaused(false)
        assertEquals(listOf(1L, 1L), lab.radio.applied.map { it.sequence })
        lab.clock.advance(300)
        assertEquals(2L, lab.radio.applied.last().sequence)
    }

    @Test fun resumeWhileDisablePendingContinuesAfterItsCallback() {
        val lab = Lab(); lab.radio.autoSilence = false
        lab.engine.start(); lab.clock.advance(100); lab.engine.setPaused(true)
        lab.clock.advance(100); lab.engine.setPaused(false)
        assertEquals(1, lab.radio.applied.size)
        lab.radio.silenceCallbacks.single()(RadioResult(true))
        assertEquals(listOf(1L, 1L), lab.radio.applied.map { it.sequence })
        lab.clock.advance(2_500)
        assertEquals(RunStatus.COMPLETED, lab.terminal)
    }

    @Test fun pauseDuringPendingApplyDisablesAfterAcceptance() {
        val lab = Lab(); lab.radio.autoApply = false
        lab.engine.start(); lab.clock.advance(100); lab.engine.setPaused(true)
        assertEquals(0, lab.radio.silenceCalls)
        lab.radio.applyCallbacks.single()(RadioResult(true))
        assertEquals(1, lab.radio.silenceCalls)
        assertTrue(lab.states.last().paused)
    }

    @Test fun plannedPauseDisablesAndNextPayloadReenablesThroughDriver() {
        val lab = Lab(profile = profile(pauses = listOf(TimeRange(500, 1_500))))
        lab.engine.start(); lab.clock.advance(2_500)
        assertEquals(listOf(1L, 4L, 5L), lab.radio.applied.map { it.sequence })
        assertEquals(1, lab.radio.silenceCalls)
        assertEquals(1, lab.count("controller_silent"))
    }

    @Test fun allSilentScenarioDoesNotStartAdvertiser() {
        val lab = Lab(profile = profile(pauses = listOf(TimeRange(0, 2_500))))
        lab.engine.start(); lab.clock.advance(2_500)
        assertTrue(lab.radio.applied.isEmpty())
        assertEquals(0, lab.radio.silenceCalls)
        assertEquals(RunStatus.COMPLETED, lab.terminal)
    }

    @Test fun acceleratedDryRunUsesTheSamePlan() {
        val lab = Lab(mode = RunMode.DRY_RUN, speed = 10)
        lab.engine.start(); lab.clock.advance(250)
        assertEquals(5, lab.count("simulated_frame"))
        assertEquals(RunStatus.COMPLETED, lab.terminal)
        assertEquals(250L, lab.events.last().elapsedMs)
        assertEquals("2500", lab.events.last().details["scenarioElapsedMs"])
    }

    @Test fun hardwareSpeedCannotBeAccelerated() {
        assertThrows(IllegalArgumentException::class.java) { Lab(mode = RunMode.BLE, speed = 2) }
    }

    @Test fun radioExceptionStillRunsCleanup() {
        val lab = Lab(); lab.radio.throwsApply = true
        lab.engine.start()
        assertEquals(RunStatus.FAILED, lab.terminal)
        assertEquals(1, lab.radio.closeCalls)
    }

    @Test fun monotonicTimelineSurvivesWallClockRollback() {
        val lab = Lab(mode = RunMode.DRY_RUN)
        lab.engine.start(); lab.clock.advance(500); lab.wall -= 100_000; lab.clock.advance(2_000)
        assertEquals(RunStatus.COMPLETED, lab.terminal)
        assertTrue(lab.events.zipWithNext().all { (a, b) -> a.elapsedMs <= b.elapsedMs })
        assertTrue(lab.events.zipWithNext().any { (a, b) -> a.atEpochMs > b.atEpochMs })
    }

    @Test fun secondStartIsRejectedAndStopIsIdempotent() {
        val lab = Lab(); lab.engine.start()
        assertThrows(IllegalStateException::class.java) { lab.engine.start() }
        lab.engine.stop(); lab.engine.stop()
        assertEquals(1, lab.terminalCalls)
    }

    @Test fun schedulerStallSkipsWithoutReplayingOldPayloads() {
        val lab = Lab(profile = profile(10_000)); lab.engine.start(); lab.clock.stall(7_200)
        assertEquals(listOf(1L, 15L), lab.radio.applied.map { it.sequence })
        assertEquals(13, lab.count("frame_skipped_late"))
    }

    @Test fun allEventsHaveContiguousSequenceAndNondecreasingMonotonicTime() {
        val lab = Lab(); lab.engine.start(); lab.clock.advance(200); lab.engine.setPaused(true)
        lab.clock.advance(100); lab.engine.setPaused(false); lab.clock.advance(2_500)
        assertEquals((1L..lab.events.size.toLong()).toList(), lab.events.map { it.sequence })
        assertTrue(lab.events.zipWithNext().all { (a, b) -> a.elapsedMs <= b.elapsedMs })
    }
    @Test fun independentEndDeadlineCancelsAnUnacknowledgedStart() {
        val lab = Lab(profile = profile(500)); lab.radio.autoApply = false
        lab.engine.start(); lab.clock.advance(500)
        assertEquals(RunStatus.FAILED, lab.terminal)
        assertEquals(1, lab.radio.closeCalls)
        lab.radio.applyCallbacks.single()(RadioResult(true))
        assertEquals(0, lab.count("controller_accepted"))
        assertEquals(1, lab.terminalCalls)
    }

    @Test fun pauseReschedulesIndependentEndDeadline() {
        val lab = Lab(profile = profile(1_000)); lab.engine.start(); lab.clock.advance(200)
        lab.engine.setPaused(true); lab.clock.advance(2_000)
        assertNull(lab.terminal)
        lab.engine.setPaused(false); lab.clock.advance(799)
        assertNull(lab.terminal)
        lab.clock.advance(1)
        assertEquals(RunStatus.COMPLETED, lab.terminal)
    }

}
