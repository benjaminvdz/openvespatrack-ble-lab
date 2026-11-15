package org.anthrobios.vespasim.service

/** Bounded callback ownership. Closing stops acceptance but never discards accepted samples. */
internal class CaptureInbox<T>(private val capacity: Int, private val maximumAccepted: Long = Long.MAX_VALUE) {
    enum class Offer { ACCEPTED, ACCEPTED_LIMIT, FULL, CLOSED }
    private val queue = ArrayDeque<T>()
    private var open = true
    private var accepted = 0L
    var overflowCount: Long = 0
        private set
    init { require(capacity > 0 && maximumAccepted > 0) }
    @Synchronized fun offer(value: T): Offer {
        if (!open) return Offer.CLOSED
        if (queue.size >= capacity) {
            overflowCount++
            open = false
            return Offer.FULL
        }
        queue.addLast(value)
        accepted++
        if (accepted == maximumAccepted) {
            open = false
            return Offer.ACCEPTED_LIMIT
        }
        return Offer.ACCEPTED
    }
    @Synchronized fun poll(): T? = if (queue.isEmpty()) null else queue.removeFirst()
    @Synchronized fun close() { open = false }
    @Synchronized fun size(): Int = queue.size
    @Synchronized fun isOpen(): Boolean = open
}
