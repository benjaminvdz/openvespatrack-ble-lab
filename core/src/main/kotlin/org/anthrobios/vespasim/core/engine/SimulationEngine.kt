package org.anthrobios.vespasim.core.engine

import org.anthrobios.vespasim.core.*

fun interface Cancellation { fun cancel() }

/** All engine entry points and radio completions must run on this serial scheduler. */
interface EngineScheduler {
    fun nowMs(): Long
    fun schedule(delayMs: Long, task: () -> Unit): Cancellation
}

data class RadioResult(val success: Boolean, val message: String = "", val actualTxPowerDbm: Int? = null)

/** Success describes a controller callback, never proof of an over-air packet. */
interface RadioDriver {
    fun apply(frame: PlannedFrame, settings: RadioSettings, complete: (RadioResult) -> Unit)
    fun silence(complete: (RadioResult) -> Unit)
    fun close(complete: (RadioResult) -> Unit)
}

data class EngineState(
    val running: Boolean,
    val paused: Boolean,
    val currentFrame: PlannedFrame?,
    val scenarioElapsedMs: Long,
    val message: String,
)

/**
 * Deterministic plan, actual monotonic execution. Slow callbacks never produce a catch-up burst:
 * already expired slots are logged as skipped. User pause freezes scenario time and disables BLE.
 */
class SimulationEngine(
    private val run: RunRecord,
    private val frames: List<PlannedFrame>,
    private val radio: RadioDriver,
    private val scheduler: EngineScheduler,
    private val wallClock: () -> Long,
    private val appendEvent: (RunEvent) -> Unit,
    private val onState: (EngineState) -> Unit,
    private val onFinished: (RunStatus, String) -> Unit,
    private val operationTimeoutMs: Long = 5_000,
    private val cleanupTimeoutMs: Long = 5_000,
) {
    private var started = false
    private var running = false
    private var closing = false
    private var pending = false
    private var paused = false
    private var pauseStarted = 0L
    private var pausedTotal = 0L
    private var startTime = 0L
    private var eventSequence = 0L
    private var nextIndex = 0
    private var currentIndex = -1
    private var current: PlannedFrame? = null
    private var resumeCurrent = false
    private var radioSilent = true
    private var message = ""
    private var operation = 0L
    private var wakeGeneration = 0L
    private var wake: Cancellation? = null
    private var timeout: Cancellation? = null
    private var deadline: Cancellation? = null
    private var persistenceFailed = false

    init {
        require(run.speed in 1..100)
        require(run.mode != RunMode.BLE || run.speed == 1)
        require(operationTimeoutMs > 0 && cleanupTimeoutMs > 0)
        require(frames.size <= 30_000)
        require(frames.withIndex().all { (i, f) ->
            f.sequence == i + 1L && f.durationMs > 0 && f.atMs >= 0 &&
                (i == 0 && f.atMs == 0L || i > 0 && frames[i - 1].atMs + frames[i - 1].durationMs == f.atMs)
        }) { "Plan is niet aaneengesloten en oplopend" }
        require(frames.isNotEmpty() && frames.last().atMs + frames.last().durationMs == run.profile.durationMs)
    }

    fun start() {
        check(!started) { "Een engine kan maar eenmaal starten" }
        started = true
        running = true
        startTime = scheduler.nowMs()
        if (!emit("run_started", text = if (run.mode == RunMode.DRY_RUN)
            "Hardwareloze simulatie; er wordt niets uitgezonden." else
            "BLE-scenario gestart; controlleracceptatie bewijst geen radio-ontvangst.")) return
        armDeadline()
        pump()
    }

    fun stop(reason: String = "Gestopt door gebruiker") {
        if (running && !closing) terminate(RunStatus.STOPPED, reason)
    }

    fun fail(reason: String) {
        if (running && !closing) {
            emit("error", text = reason)
            if (!closing) terminate(RunStatus.FAILED, reason)
        }
    }

    fun setPaused(value: Boolean) {
        if (!running || closing || paused == value) return
        val now = scheduler.nowMs()
        paused = value
        if (value) {
            pauseStarted = now
            resumeCurrent = false
        } else {
            pausedTotal += (now - pauseStarted).coerceAtLeast(0)
            resumeCurrent = current?.let { scenarioMs() < it.atMs + it.durationMs } == true
        }
        cancelWake()
        deadline?.cancel(); deadline = null
        if (!value) armDeadline()
        if (!emit(if (value) "run_paused" else "run_resumed",
                text = if (value) "Scenario gepauzeerd; uitschakelen radio aangevraagd." else "Scenario hervat.")) return
        update(if (value) "Pauzeren; wachten op uitschakelbevestiging" else "Hervatten")
        if (!pending) pump()
    }

    private fun scenarioMs(): Long {
        val now = scheduler.nowMs()
        val frozen = if (paused) (now - pauseStarted).coerceAtLeast(0) else 0L
        return ((now - startTime - pausedTotal - frozen).coerceAtLeast(0) * run.speed).coerceAtMost(run.profile.durationMs)
    }

    private fun pump() {
        if (!running || closing || pending) return
        if (paused) {
            if (run.mode == RunMode.BLE && !radioSilent) {
                requestSilence(null) {
                    if (paused) update("Gepauzeerd; controller bevestigde uitschakelen") else pump()
                }
            } else update("Gepauzeerd")
            return
        }
        val elapsed = scenarioMs()
        if (elapsed >= run.profile.durationMs) {
            terminate(RunStatus.COMPLETED, "Scenario voltooid")
            return
        }
        if (resumeCurrent) {
            resumeCurrent = false
            if (currentIndex >= 0) {
                val frame = frames[currentIndex]
                if (elapsed >= frame.atMs && elapsed < frame.atMs + frame.durationMs) {
                    applyFrame(frame, resuming = true)
                    return
                }
            }
        }
        // Yield after a small chunk so Stop remains responsive even after a long process stall.
        var skipped = 0
        while (nextIndex < frames.size && frames[nextIndex].atMs + frames[nextIndex].durationMs <= elapsed) {
            val frame = frames[nextIndex++]
            if (!emit("frame_skipped_late", frame, "Tijdslot verlopen voordat de controlleraanvraag kon beginnen.")) return
            if (++skipped == 16) { schedule(0) { pump() }; return }
        }
        if (nextIndex >= frames.size) {
            scheduleUntil(run.profile.durationMs)
            return
        }
        val frame = frames[nextIndex]
        if (frame.atMs > elapsed) {
            scheduleUntil(frame.atMs)
            return
        }
        currentIndex = nextIndex++
        applyFrame(frame, resuming = false)
    }

    private fun applyFrame(frame: PlannedFrame, resuming: Boolean) {
        current = frame
        if (!emit(if (resuming) "frame_resumed" else "frame_planned", frame,
                frame.silenceReason ?: if (frame.capturedAdvertisement != null) "Geplande semantische replay van vastgelegde scaninhoud" else if (frame.includeManufacturerData) "Gepland manufacturer-payloadtijdslot" else "Geplande naamadvertentie zonder manufacturer-data")) return
        update(if (frame.payloadHex == null) "Geplande stilte: ${frame.silenceReason}" else "Tijdslot ${frame.sequence} · ${frame.trackId}")
        if (run.mode == RunMode.DRY_RUN) {
            if (!emit(if (frame.payloadHex == null) "simulated_silence" else "simulated_frame", frame,
                    "Alleen simulatie; Bluetooth wordt niet aangeroepen.")) return
            scheduleUntil(frame.atMs + frame.durationMs)
        } else if (frame.payloadHex == null) {
            requestSilence(frame) { scheduleUntil(frame.atMs + frame.durationMs) }
        } else {
            if (!emit("radio_request", frame, if (frame.capturedAdvertisement != null) "Opgenomen AD-inhoud semantisch aan Android-controller aangeboden; geen identieke RF-replay." else if (frame.includeManufacturerData) "Manufacturer-payload aan Android-controller aangeboden." else "Alleen Local Name aan Android-controller aangeboden; geen manufacturer-data.")) return
            radioSilent = false
            operation("Payload instellen", { done -> radio.apply(frame, run.profile.radio, done) }) { result ->
                if (!emit("controller_accepted", frame,
                        "Android bevestigde de advertentie-instelling; geen bevestiging van RF-verzending of ontvangst.",
                        result.actualTxPowerDbm?.let { mapOf("actualTxPowerDbm" to it.toString()) } ?: emptyMap())) return@operation
                if (paused) pump() else scheduleUntil(frame.atMs + frame.durationMs)
            }
        }
    }

    private fun requestSilence(frame: PlannedFrame?, after: () -> Unit) {
        if (radioSilent) { after(); return }
        if (!emit("silence_requested", frame, "Advertenties uitschakelen aangevraagd.")) return
        operation("Radio uitschakelen", { done -> radio.silence(done) }) {
            radioSilent = true
            if (emit("controller_silent", frame, "Android bevestigde dat deze advertising-set is uitgeschakeld.")) after()
        }
    }

    private fun operation(label: String, request: ((RadioResult) -> Unit) -> Unit, success: (RadioResult) -> Unit) {
        if (!running || closing) return
        check(!pending)
        pending = true
        val token = ++operation
        timeout = scheduler.schedule(operationTimeoutMs) {
            if (running && !closing && pending && token == operation) {
                pending = false
                fail("Timeout: $label. Radio wordt opgeruimd; werkelijke uitzending onbekend.")
            }
        }
        try {
            request { result ->
                if (!running || closing || !pending || token != operation) return@request
                timeout?.cancel(); timeout = null
                pending = false
                if (!result.success) fail("$label mislukt: ${result.message}")
                else success(result)
            }
        } catch (e: Exception) {
            if (running && !closing && token == operation) {
                timeout?.cancel(); timeout = null
                pending = false
                fail("$label: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun terminate(status: RunStatus, reason: String) {
        if (closing || !running) return
        closing = true
        pending = false
        ++operation
        cancelWake()
        timeout?.cancel()
        deadline?.cancel(); deadline = null
        update("Afronden: $reason")
        if (run.mode == RunMode.DRY_RUN) { finish(status, reason); return }
        var completed = false
        timeout = scheduler.schedule(cleanupTimeoutMs) {
            if (!completed) {
                completed = true
                emit("cleanup_error", text = "Geen stopbevestiging ontvangen. Zet Bluetooth uit als uitzending blijft doorgaan.")
                finish(RunStatus.FAILED, "$reason; radio-stop niet bevestigd")
            }
        }
        try {
            radio.close { result ->
                if (completed) return@close
                completed = true
                timeout?.cancel(); timeout = null
                if (result.success) finish(status, reason)
                else {
                    emit("cleanup_error", text = result.message)
                    finish(RunStatus.FAILED, "$reason; opruimen mislukt: ${result.message}")
                }
            }
        } catch (e: Exception) {
            if (!completed) {
                completed = true
                timeout?.cancel(); timeout = null
                emit("cleanup_error", text = e.message ?: "Radio-stop mislukt")
                finish(RunStatus.FAILED, "$reason; opruimen mislukt")
            }
        }
    }

    private fun finish(status: RunStatus, reason: String) {
        if (!running) return
        val finalStatus = if (persistenceFailed) RunStatus.FAILED else status
        emit("run_finished", text = reason, extra = mapOf("status" to finalStatus.name))
        running = false
        update(reason)
        onFinished(if (persistenceFailed) RunStatus.FAILED else finalStatus, reason)
    }

    private fun armDeadline() {
        deadline?.cancel()
        val remaining = (run.profile.durationMs - scenarioMs()).coerceAtLeast(0)
        deadline = scheduler.schedule((remaining + run.speed - 1) / run.speed) {
            if (running && !closing && !paused) {
                val unfinished = pending && run.mode == RunMode.BLE
                if (!emit("scenario_deadline", text = "Geplande eindtijd bereikt; radio wordt gestopt.",
                        extra = mapOf("unrequestedSlots" to (frames.size - nextIndex).toString(), "controllerPending" to unfinished.toString()))) return@schedule
                if (unfinished) {
                    emit("error", text = "Eindtijd bereikt terwijl een controlleraanvraag nog onbevestigd was.")
                    if (!closing) terminate(RunStatus.FAILED, "Scenario-eindtijd bereikt met onbevestigde controlleraanvraag")
                } else terminate(RunStatus.COMPLETED, "Scenario voltooid")
            }
        }
    }

    private fun scheduleUntil(scenarioTime: Long) {
        if (!running || closing) return
        if (paused) { pump(); return }
        val remaining = (scenarioTime - scenarioMs()).coerceAtLeast(0)
        schedule((remaining + run.speed - 1) / run.speed) { pump() }
    }

    private fun schedule(delay: Long, task: () -> Unit) {
        cancelWake()
        val token = wakeGeneration
        wake = scheduler.schedule(delay.coerceAtLeast(0)) {
            if (running && !closing && token == wakeGeneration) task()
        }
    }

    private fun cancelWake() { ++wakeGeneration; wake?.cancel(); wake = null }

    private fun emit(kind: String, frame: PlannedFrame? = null, text: String = "", extra: Map<String, String> = emptyMap()): Boolean {
        if (persistenceFailed) return false
        return try {
            appendEvent(RunEvent(
                sequence = ++eventSequence,
                atEpochMs = wallClock().coerceAtLeast(0),
                elapsedMs = (scheduler.nowMs() - startTime).coerceAtLeast(0),
                kind = kind,
                frameSequence = frame?.sequence,
                trackId = frame?.trackId,
                payloadHex = frame?.payloadHex,
                message = text,
                details = extra + mapOf("scenarioElapsedMs" to scenarioMs().toString()) +
                    (frame?.takeIf { it.payloadHex != null }?.let {
                        mapOf("includeManufacturerData" to it.includeManufacturerData.toString(), "localName" to it.localName.orEmpty()) +
                            (if (it.includeManufacturerData) mapOf("companyId" to it.companyId.toString()) else emptyMap()) +
                            (it.capturedAdvertisement?.let { source -> mapOf(
                                "captureId" to source.captureId,
                                "captureObservationSequence" to source.observation.sequence.toString(),
                                "originalScanHex" to source.observation.rawHex.orEmpty(),
                                "originalScanSha256" to source.rawSha256,
                                "originalScanSource" to "Android combined ScanRecord; semantic replay",
                                "importedSource" to source.importedSource.toString(),
                            ) } ?: emptyMap())
                    } ?: emptyMap()),
            ))
            true
        } catch (e: Exception) {
            persistenceFailed = true
            if (!closing) terminate(RunStatus.FAILED, "Logopslag mislukt: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun update(value: String) {
        message = value
        onState(EngineState(running, paused, current, scenarioMs(), message))
    }
}
