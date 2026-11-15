package org.anthrobios.vespasim.core.engine

import kotlinx.serialization.Serializable
import java.util.concurrent.CancellationException

/** Persisted before requesting an asynchronous platform name change. */
@Serializable
data class TemporaryNameJournal(
    val originalName: String,
    val temporaryName: String,
    val ownerRunId: String,
    val phase: TemporaryNamePhase = TemporaryNamePhase.APPLYING,
    val version: Int = 1,
)

@Serializable
enum class TemporaryNamePhase { APPLYING, ACTIVE, RESTORING, REJECTED }

interface TemporaryNameAdapter {
    /** Must throw when Bluetooth/name permission is unavailable, not substitute an empty name. */
    fun currentName(): String
    /** True means only that a request was accepted, never that the name already changed. */
    fun requestName(name: String): Boolean
}

interface TemporaryNameStore {
    /** Corrupt or unreadable data must throw. It must not be interpreted as no pending change. */
    fun read(): TemporaryNameJournal?
    fun write(journal: TemporaryNameJournal)
    fun clear()
}

/**
 * Serial-scheduler coordinator. It never starts a radio. Preparation succeeds only after a
 * read-back and durable ACTIVE journal; restoration runs after the caller has stopped its radio.
 * Observer failures cannot interrupt safety cleanup. The durable journal, not events, owns recovery.
 * A timed-out APPLYING request is deliberately retained: Android may still apply it later.
 */
class TemporaryNameSession(
    private val adapter: TemporaryNameAdapter,
    private val store: TemporaryNameStore,
    private val scheduler: EngineScheduler,
    private val onEvent: (kind: String, message: String) -> Unit = { _, _ -> },
    private val timeoutMs: Long = 5_000,
    private val pollMs: Long = 100,
) {
    private enum class Operation { PREPARE, RESTORE }
    private var operation: Operation? = null
    private var generation = 0L
    private var wake: Cancellation? = null
    private var completion: ((Result<Unit>) -> Unit)? = null
    val isBusy: Boolean get() = operation != null

    init { require(timeoutMs > 0 && pollMs > 0 && pollMs <= timeoutMs) }

    fun pending(): TemporaryNameJournal? = store.read()?.also {
        require(it.version == 1 && it.ownerRunId.isNotBlank()) { "Ongeldig Bluetoothnaam-herstelrecord" }
        require(it.originalName != it.temporaryName && it.originalName.toByteArray(Charsets.UTF_8).size <= 248 && it.temporaryName.isNotBlank() &&
            it.temporaryName.toByteArray(Charsets.UTF_8).size <= 248) { "Ongeldige namen in Bluetoothnaam-herstelrecord" }
    }

    fun prepare(expected: String?, ownerRunId: String, complete: (Result<Unit>) -> Unit) {
        if (isBusy) { complete(Result.failure(IllegalStateException("Bluetoothnaam wordt nog voorbereid of hersteld"))); return }
        // Dry/no-name callers need no adapter access and must never erase another pending journal.
        if (expected == null) { complete(Result.success(Unit)); return }
        val token = begin(Operation.PREPARE, complete)
        try {
            require(expected.isNotBlank() && expected.toByteArray(Charsets.UTF_8).size <= 248) { "Ongeldige tijdelijke Bluetoothnaam" }
            require(ownerRunId.isNotBlank()) { "Proefidentiteit ontbreekt" }
            check(pending() == null) { "Er staat nog Bluetoothnaamherstel open; herstel dit eerst via Telefoon" }
            val original = adapter.currentName()
            require(original.toByteArray(Charsets.UTF_8).size <= 248) { "Oorspronkelijke Bluetoothnaam is ongeldig" }
            if (original == expected) {
                event("name_no_change", "Bluetoothnaam komt al overeen; deze proef neemt geen naamherstel over")
                finish(token, Result.success(Unit))
                return
            }
            val journal = TemporaryNameJournal(original, expected, ownerRunId)
            store.write(journal)
            event("name_prepare_requested", "Oorspronkelijke Bluetoothnaam bewaard; tijdelijke tagnaam wordt aangevraagd")
            if (!adapter.requestName(expected)) {
                // Explicit rejection means no accepted asynchronous request. A failed clear remains
                // retryable without confusing this known rejection with an unconfirmed request.
                store.write(journal.copy(phase = TemporaryNamePhase.REJECTED))
                store.clear()
                finish(token, Result.failure(IllegalStateException("Android weigerde de tijdelijke Bluetoothnaam")))
                return
            }
            pollPreparation(journal, scheduler.nowMs() + timeoutMs, token)
        } catch (error: Exception) { failPending(token, error) }
    }

    /** Cancels any preparation, then attempts restoration; it never restarts an interrupted radio. */
    fun restore(complete: (Result<Unit>) -> Unit) {
        if (operation == Operation.RESTORE) {
            complete(Result.failure(IllegalStateException("Bluetoothnaamherstel loopt al")))
            return
        }
        val cancelled = if (operation == Operation.PREPARE) completion else null
        val token = begin(Operation.RESTORE, complete)
        // Mark RESTORE busy first so callback reentrancy cannot start another rename/radio.
        try { cancelled?.invoke(Result.failure(CancellationException("Bluetoothnaamvoorbereiding gestopt"))) }
        catch (_: Exception) { /* A caller failing to process cancellation must not prevent restoration. */ }
        if (!matches(token, Operation.RESTORE)) return
        try {
            val journal = pending()
            if (journal == null) { finish(token, Result.success(Unit)); return }
            if (journal.phase == TemporaryNamePhase.REJECTED) {
                store.clear()
                event("name_not_applied", "Geweigerd naamverzoek opgeruimd; geen Bluetoothnaam gewijzigd")
                finish(token, Result.success(Unit))
                return
            }
            pollRestoration(journal, scheduler.nowMs() + timeoutMs, token, requested = false)
        } catch (error: Exception) { failPending(token, error) }
    }

    private fun pollPreparation(journal: TemporaryNameJournal, deadline: Long, token: Long) {
        if (!matches(token, Operation.PREPARE)) return
        try {
            val current = adapter.currentName()
            if (current == journal.temporaryName) {
                store.write(journal.copy(phase = TemporaryNamePhase.ACTIVE))
                event("name_prepare_confirmed", "Tijdelijke Bluetoothnaam teruggelezen; radio mag starten")
                finish(token, Result.success(Unit))
            } else if (current != journal.originalName) {
                failPending(token, IllegalStateException("Bluetoothnaam is elders gewijzigd; uitzending niet gestart en herstelrecord behouden"))
            } else if (scheduler.nowMs() >= deadline) {
                failPending(token, IllegalStateException("Tijdelijke Bluetoothnaam niet bevestigd; herstelrecord blijft bewaard voor een mogelijk vertraagde wijziging"))
            } else later(deadline, token) { pollPreparation(journal, deadline, token) }
        } catch (error: Exception) { failPending(token, error) }
    }

    private fun pollRestoration(journal: TemporaryNameJournal, deadline: Long, token: Long, requested: Boolean) {
        if (!matches(token, Operation.RESTORE)) return
        try {
            val current = adapter.currentName()
            when {
                current == journal.originalName && journal.phase != TemporaryNamePhase.APPLYING -> {
                    store.clear()
                    event("name_restored", "Oorspronkelijke Bluetoothnaam teruggelezen; herstel afgerond")
                    finish(token, Result.success(Unit))
                }
                current != journal.originalName && current != journal.temporaryName -> {
                    if (journal.phase == TemporaryNamePhase.ACTIVE) {
                        // The accepted temporary name was confirmed before. A later external change
                        // relinquishes ownership: do not replace the user's current name.
                        store.clear()
                        event("name_external_preserved", "Bluetoothnaam is elders gewijzigd; huidige naam blijft behouden")
                        finish(token, Result.success(Unit))
                    } else failPending(token, IllegalStateException("Bluetoothnaam is elders gewijzigd terwijl een naamverzoek onbevestigd is; huidige naam niet overschreven, herstelrecord behouden"))
                }
                current == journal.temporaryName && !requested -> {
                    val restoring = journal.copy(phase = TemporaryNamePhase.RESTORING)
                    store.write(restoring)
                    event("name_restore_requested", "Oorspronkelijke Bluetoothnaam wordt hersteld")
                    if (!adapter.requestName(journal.originalName)) {
                        failPending(token, IllegalStateException("Android weigerde Bluetoothnaamherstel; herstelrecord blijft bewaard"))
                    } else pollRestoration(restoring, scheduler.nowMs() + timeoutMs, token, requested = true)
                }
                scheduler.nowMs() >= deadline -> failPending(token,
                    IllegalStateException("Bluetoothnaamherstel nog niet bevestigd; probeer herstel opnieuw via Telefoon"))
                else -> later(deadline, token) { pollRestoration(journal, deadline, token, requested) }
            }
        } catch (error: Exception) { failPending(token, error) }
    }

    private fun begin(next: Operation, complete: (Result<Unit>) -> Unit): Long {
        wake?.cancel(); wake = null
        generation++
        operation = next
        completion = complete
        return generation
    }

    private fun later(deadline: Long, token: Long, task: () -> Unit) {
        wake?.cancel()
        wake = scheduler.schedule(minOf(pollMs, (deadline - scheduler.nowMs()).coerceAtLeast(1))) {
            if (generation == token && operation != null) task()
        }
    }

    private fun matches(token: Long, expected: Operation) = generation == token && operation == expected

    private fun failPending(token: Long, error: Exception) {
        if (token != generation || operation == null) return
        event("name_recovery_pending", error.message ?: error.javaClass.simpleName)
        finish(token, Result.failure(error))
    }

    private fun finish(token: Long, result: Result<Unit>) {
        if (token != generation || operation == null) return
        wake?.cancel(); wake = null
        operation = null
        val callback = completion
        completion = null
        callback?.invoke(result)
    }

    private fun event(kind: String, message: String) {
        try { onEvent(kind, message) } catch (_: Exception) { /* Safety cleanup must survive logging/UI failures. */ }
    }
}
