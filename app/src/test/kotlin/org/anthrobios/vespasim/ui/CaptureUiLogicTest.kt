package org.anthrobios.vespasim.ui

import org.anthrobios.vespasim.core.capture.CaptureObservation
import org.junit.Assert.*
import org.junit.Test

class CaptureUiLogicTest {
    private val nano = CaptureUiLogic.Device("01:02:03:04:05:06", "vn0CqrvM3e7/", "oude naam", "AA:BB:CC:DD:EE:FF", true, true, false, 1000)
    private val other = nano.copy(address = "11:22:33:44:55:66", advertisedName = null, cachedName = "VESPA-NANO-CACHE", logicalAddress = null, nano = false, vespa = false, fc65 = true)
    @Test fun nanoDefaultUsesAdvertisementCandidateNotCachedName() {
        assertTrue(CaptureUiLogic.visible(nano, CaptureUiLogic.Filter.NANO, ""))
        assertFalse(CaptureUiLogic.visible(other, CaptureUiLogic.Filter.NANO, ""))
        assertTrue(CaptureUiLogic.visible(other, CaptureUiLogic.Filter.FC65, ""))
        assertTrue(CaptureUiLogic.visible(other, CaptureUiLogic.Filter.ALL, ""))
        assertFalse(CaptureUiLogic.visible(other, CaptureUiLogic.Filter.VESPA, ""))
    }
    @Test fun textSearchIsAnAndFilterAndAcceptsFormattedLogicalIdentity() {
        assertTrue(CaptureUiLogic.visible(nano, CaptureUiLogic.Filter.NANO, " aabbccddeeff "))
        assertTrue(CaptureUiLogic.visible(nano, CaptureUiLogic.Filter.NANO, "aa:bb"))
        assertTrue(CaptureUiLogic.visible(nano, CaptureUiLogic.Filter.NANO, "OUDE"))
        assertFalse(CaptureUiLogic.visible(nano, CaptureUiLogic.Filter.NANO, "onbekend"))
        assertFalse(CaptureUiLogic.visible(other, CaptureUiLogic.Filter.NANO, "VESPA"))
    }
    @Test fun selectionExpiresAtFreshnessBoundaryAndCannotReappearFromFutureClock() {
        assertEquals(nano.address, CaptureUiLogic.selection(nano.address, listOf(nano), CaptureUiLogic.Filter.NANO, "", 11_000))
        assertNull(CaptureUiLogic.selection(nano.address, listOf(nano), CaptureUiLogic.Filter.NANO, "", 11_001))
        assertNull(CaptureUiLogic.selection(nano.address, listOf(nano), CaptureUiLogic.Filter.NANO, "", 999))
    }
    @Test fun hiddenRemovedAndClearedSelectionsStayCleared() {
        assertNull(CaptureUiLogic.selection(nano.address, listOf(nano), CaptureUiLogic.Filter.FC65, "", 1001))
        assertNull(CaptureUiLogic.selection(nano.address, listOf(nano), CaptureUiLogic.Filter.NANO, "does-not-match", 1001))
        assertNull(CaptureUiLogic.selection(nano.address, emptyList(), CaptureUiLogic.Filter.NANO, "", 1001))
        assertNull(CaptureUiLogic.selection(null, listOf(nano), CaptureUiLogic.Filter.NANO, "", 1001))
    }
    private fun observation(sequence: Long, ms: Long, rssi: Int = -65) = CaptureObservation(sequence, 1_700_000_000_000 + ms,
        ms * 1_000_000, "01:02:03:04:05:06", rssi, "020106")
    @Test fun measuredRssiUnavailableAndOutOfRangeBreakLines() {
        val rows = listOf(observation(1, 0), observation(2, 1, 127), observation(3, 2), observation(4, 3), observation(5, 4, -128), observation(6, 5))
        assertEquals(listOf(true, true, true, false, true, true), CaptureUiLogic.timeline(rows).map { it.startsSegment })
        assertNull(rows[1].measuredRssi); assertNull(rows[4].measuredRssi)
    }
    @Test fun longGapRegressedTimestampDuplicateTimestampAndAddressChangeBreakLines() {
        val rows = listOf(observation(1, 50), observation(2, 10_051), observation(3, 40), observation(4, 40),
            observation(5, 41).copy(address = "AA:BB:CC:DD:EE:FF"))
        val points = CaptureUiLogic.timeline(rows)
        assertTrue(points.all { it.startsSegment })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), points.map { it.observation.sequence })
        assertEquals(listOf(10L, 10011L, 0L, 0L, 1L), points.map { it.offsetMs })
    }
    @Test fun cursorUsesOriginalTimelineAndLatestSequenceForEqualTimestamp() {
        val points = CaptureUiLogic.timeline(listOf(observation(1, 100), observation(2, 200), observation(3, 150), observation(4, 150, 127)))
        assertNull(CaptureUiLogic.atCursor(points, -1))
        assertEquals(1L, CaptureUiLogic.atCursor(points, 49)?.sequence)
        assertEquals(4L, CaptureUiLogic.atCursor(points, 50)?.sequence)
        assertNull(CaptureUiLogic.atCursor(points, 50)?.measuredRssi)
        assertEquals(2L, CaptureUiLogic.atCursor(points, 100)?.sequence)
    }
    @Test fun emptyAndSingleSampleTimelineIsDefined() {
        assertTrue(CaptureUiLogic.timeline(emptyList()).isEmpty())
        assertNull(CaptureUiLogic.atCursor(emptyList(), 0))
        assertEquals(0L, CaptureUiLogic.timeline(listOf(observation(1, 3_000_000))).single().offsetMs)
        assertEquals(100, CaptureUiLogic.PAGE_SIZE)
    }
}
