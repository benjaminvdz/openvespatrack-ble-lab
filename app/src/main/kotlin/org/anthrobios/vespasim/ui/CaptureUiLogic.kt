package org.anthrobios.vespasim.ui

import org.anthrobios.vespasim.core.capture.CaptureObservation
import org.anthrobios.vespasim.core.beacon.BeaconIdentity
import java.util.Locale

/** Pure display rules. Cached device names never make a device a Nano candidate. */
object CaptureUiLogic {
    // Append new values: existing saved UI-state ordinals retain their meaning.
    enum class Filter { NANO, VESPA, FC65, ALL, TEST_BEACON }
    data class Device(
        val address: String, val advertisedName: String?, val cachedName: String?,
        val logicalAddress: String?, val nano: Boolean, val vespa: Boolean,
        val fc65: Boolean, val lastSeenMs: Long,
        val testBeacon: BeaconIdentity? = null,
    )
    const val FRESH_MS = 10_000L
    const val PAGE_SIZE = 100
    fun visible(device: Device, filter: Filter, query: String): Boolean {
        val candidate = when (filter) {
            Filter.NANO -> device.nano
            Filter.VESPA -> device.vespa
            Filter.FC65 -> device.fc65
            Filter.ALL -> true
            Filter.TEST_BEACON -> device.testBeacon != null
        }
        val q = query.trim().lowercase(Locale.ROOT)
        return candidate && (q.isEmpty() || listOfNotNull(device.address, device.advertisedName,
            device.cachedName, device.logicalAddress, device.testBeacon?.label, device.testBeacon?.hex).any {
                it.lowercase(Locale.ROOT).contains(q) || it.replace(":", "").lowercase(Locale.ROOT).contains(q.replace(":", ""))
            })
    }
    fun fresh(device: Device, nowMs: Long): Boolean = nowMs >= device.lastSeenMs && nowMs - device.lastSeenMs <= FRESH_MS
    fun selection(address: String?, devices: List<Device>, filter: Filter, query: String, nowMs: Long): String? =
        devices.firstOrNull { it.address == address && visible(it, filter, query) && fresh(it, nowMs) }?.address

    data class Point(val observation: CaptureObservation, val offsetMs: Long, val startsSegment: Boolean)
    /** Input order is evidence. Do not sort away timestamp regressions or bridge missing RSSI. */
    fun timeline(observations: List<CaptureObservation>): List<Point> {
        val base = observations.minOfOrNull { it.timestampNanos } ?: return emptyList()
        var previous: CaptureObservation? = null
        return observations.map { item ->
            val before = previous
            val gap = before == null || before.measuredRssi == null || item.measuredRssi == null ||
                item.timestampNanos <= before.timestampNanos ||
                item.timestampNanos - before.timestampNanos > 10_000_000_000L || item.address != before.address
            previous = item
            Point(item, (item.timestampNanos - base) / 1_000_000, gap)
        }
    }
    fun atCursor(points: List<Point>, cursorMs: Long): CaptureObservation? =
        points.filter { it.offsetMs <= cursorMs }.maxWithOrNull(compareBy<Point> { it.offsetMs }.thenBy { it.observation.sequence })?.observation
}

