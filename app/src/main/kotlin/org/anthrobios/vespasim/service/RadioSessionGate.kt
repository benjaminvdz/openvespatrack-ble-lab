package org.anthrobios.vespasim.service

/** Process-local exclusion: scanning, advertising and temporary adapter renaming cannot overlap. */
object RadioSessionGate {
    private var heldBy: String? = null
    @Synchronized fun tryAcquire(owner: String): Boolean {
        require(owner.isNotBlank())
        if (heldBy != null && heldBy != owner) return false
        heldBy = owner
        return true
    }
    @Synchronized fun release(owner: String) { if (heldBy == owner) heldBy = null }
    @Synchronized fun owner(): String? = heldBy
    @Synchronized fun isBusyFor(owner: String): Boolean = heldBy != null && heldBy != owner
}
