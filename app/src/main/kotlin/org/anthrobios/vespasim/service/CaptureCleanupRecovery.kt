package org.anthrobios.vespasim.service

/** Keep the original callback and gate owner recoverable even if Android destroys an idle Service.
 * Retrying is invoked only by a new explicit capture-screen action; it never starts a scanner. */
internal object CaptureCleanupRecovery {
    private data class Pending(val owner: String, val stop: () -> String?, val problem: String)
    private var pending: Pending? = null

    @Synchronized fun remember(owner: String, stop: () -> String?, problem: String) {
        check(pending == null || pending?.owner == owner) { "Er staat al een andere scannerstop open" }
        pending = Pending(owner, stop, problem)
    }

    @Synchronized fun problem(): String? = pending?.problem

    @Synchronized fun retry(): String? {
        val previous = pending ?: return null
        val failure = try { previous.stop() } catch (failure: Exception) { failure.message ?: failure.javaClass.simpleName }
        if (failure == null) {
            RadioSessionGate.release(previous.owner)
            pending = null
        } else pending = previous.copy(problem = failure)
        return failure
    }
}
