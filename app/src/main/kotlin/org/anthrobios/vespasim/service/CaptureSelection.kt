package org.anthrobios.vespasim.service

/** A displayed callback can only authorize capture while the original elapsed timestamp is fresh. */
internal object CaptureSelection {
    const val FRESH_MS = 10_000L
    fun isFresh(seenElapsedMs: Long, nowElapsedMs: Long): Boolean =
        seenElapsedMs >= 0 && nowElapsedMs >= seenElapsedMs && nowElapsedMs - seenElapsedMs <= FRESH_MS
}
