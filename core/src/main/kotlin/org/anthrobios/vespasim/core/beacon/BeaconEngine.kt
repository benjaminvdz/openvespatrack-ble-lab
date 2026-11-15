package org.anthrobios.vespasim.core.beacon

import org.anthrobios.vespasim.core.engine.Cancellation
import org.anthrobios.vespasim.core.engine.EngineScheduler
import java.util.Locale

/** An app-local synthetic identity, unrelated to the phone address or captured tags. */
data class BeaconIdentity(val hex: String) {
    init { require(hex.matches(Regex("[0-9a-f]{16}"))) }
    val label: String get() = "OVT-${hex.uppercase(Locale.ROOT)}"
    fun serviceData(): ByteArray = byteArrayOf(1) + hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        const val SERVICE_UUID = "6f8a5b00-7d3e-4c6a-9b21-0a5c2d7e9101"
        const val INTERVAL_MS = 200
        const val REQUESTED_TX_POWER_DBM = -7
        // Flags (3), AD header (2), UUID (16), version (1), synthetic ID (8).
        const val LEGACY_BUDGET_BYTES = 30
    }
}

enum class BeaconPhase { IDLE, STARTING, ACTIVE, STOPPING, STOPPED, FAILED, STOP_UNCONFIRMED }
enum class BeaconIssue { NONE, PERMISSION, BLUETOOTH_OFF, UNSUPPORTED, BUSY, CONTROLLER, START_TIMEOUT, STOP_TIMEOUT, STORAGE, STORAGE_CLEANUP, FOREGROUND, INTERRUPTED }
data class BeaconResult(val success: Boolean, val issue: BeaconIssue = BeaconIssue.NONE, val platformCode: Int? = null, val txPowerDbm: Int? = null)
data class BeaconState(
    val phase: BeaconPhase = BeaconPhase.IDLE,
    val issue: BeaconIssue = BeaconIssue.NONE,
    val acceptedAtMs: Long? = null,
    val actualTxPowerDbm: Int? = null,
    val platformCode: Int? = null,
) {
    val ownsRadio: Boolean get() = phase in setOf(BeaconPhase.STARTING, BeaconPhase.ACTIVE, BeaconPhase.STOPPING, BeaconPhase.STOP_UNCONFIRMED)
}

/** Callbacks and methods are serialized on the engine scheduler. Stop must handle a pending start. */
interface BeaconRadio {
    fun start(identity: BeaconIdentity, complete: (BeaconResult) -> Unit)
    fun stop(complete: (BeaconResult) -> Unit)
}

/**
 * One immutable advertising request with no session deadline and no repeated frame queue.
 * ACTIVE means controller acceptance, never independent RF reception. A missing stop callback
 * keeps ownership unresolved; a late confirmation can still complete cleanup.
 */
class BeaconEngine(
    private val identity: BeaconIdentity,
    private val radio: BeaconRadio,
    private val scheduler: EngineScheduler,
    private val onState: (BeaconState) -> Unit,
    private val onCleaned: () -> Unit,
    private val operationTimeoutMs: Long = 5_000,
) {
    var state = BeaconState(); private set
    private var timeout: Cancellation? = null
    private var failure = BeaconIssue.NONE
    private var cleaned = false
    private var stoppingGeneration = 0L

    init { require(operationTimeoutMs > 0) }

    fun start() {
        if (state.phase != BeaconPhase.IDLE) return
        publish(state.copy(phase = BeaconPhase.STARTING))
        timeout = scheduler.schedule(operationTimeoutMs) { fail(BeaconIssue.START_TIMEOUT) }
        try {
            radio.start(identity) startCompletion@{ result ->
                if (state.phase != BeaconPhase.STARTING) return@startCompletion
                timeout?.cancel(); timeout = null
                if (result.success) publish(BeaconState(BeaconPhase.ACTIVE,
                    acceptedAtMs = scheduler.nowMs(), actualTxPowerDbm = result.txPowerDbm))
                else fail(result.issue.takeUnless { it == BeaconIssue.NONE } ?: BeaconIssue.CONTROLLER, result.platformCode)
            }
        } catch (_: Exception) { fail(BeaconIssue.CONTROLLER) }
    }

    fun stop() {
        if (state.phase == BeaconPhase.IDLE) {
            publish(state.copy(phase = BeaconPhase.STOPPED))
            finishCleanup()
        } else if (state.phase in setOf(BeaconPhase.STARTING, BeaconPhase.ACTIVE, BeaconPhase.STOP_UNCONFIRMED)) beginStop()
    }

    fun fail(issue: BeaconIssue, platformCode: Int? = null) {
        if (state.phase !in setOf(BeaconPhase.STARTING, BeaconPhase.ACTIVE)) return
        failure = issue
        state = state.copy(platformCode = platformCode)
        beginStop()
    }

    private fun beginStop() {
        timeout?.cancel()
        val generation = ++stoppingGeneration
        publish(state.copy(phase = BeaconPhase.STOPPING, issue = failure))
        timeout = scheduler.schedule(operationTimeoutMs) {
            if (!cleaned && generation == stoppingGeneration) {
                publish(state.copy(phase = BeaconPhase.STOP_UNCONFIRMED, issue = BeaconIssue.STOP_TIMEOUT))
            }
        }
        try {
            radio.stop { result ->
                if (cleaned) return@stop
                // Any genuine stop confirmation resolves ownership, even after a retry or timeout.
                if (result.success) {
                    timeout?.cancel(); timeout = null
                    publish(state.copy(phase = if (failure == BeaconIssue.NONE) BeaconPhase.STOPPED else BeaconPhase.FAILED, issue = failure))
                    finishCleanup()
                } else if (generation == stoppingGeneration) {
                    timeout?.cancel(); timeout = null
                    publish(state.copy(phase = BeaconPhase.STOP_UNCONFIRMED,
                        issue = result.issue.takeUnless { it == BeaconIssue.NONE } ?: BeaconIssue.CONTROLLER,
                        platformCode = result.platformCode ?: state.platformCode))
                }
            }
        } catch (_: Exception) {
            timeout?.cancel(); timeout = null
            publish(state.copy(phase = BeaconPhase.STOP_UNCONFIRMED, issue = BeaconIssue.CONTROLLER))
        }
    }

    private fun finishCleanup() { if (!cleaned) { cleaned = true; onCleaned() } }
    private fun publish(value: BeaconState) { state = value; onState(value) }
}
