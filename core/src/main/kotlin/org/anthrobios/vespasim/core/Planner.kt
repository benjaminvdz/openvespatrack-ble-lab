package org.anthrobios.vespasim.core

/** Pure reproducible scenario planner. One virtual track occupies a physical-radio slot at a time. */
object Planner {
    fun plan(profile: SimulationProfile): List<PlannedFrame> {
        val errors = ProfileCodec.validate(profile)
        if (errors.isNotEmpty()) throw ProfileValidationException(errors)
        val count = ((profile.durationMs - 1) / profile.slotMs + 1).toInt()
        val frames = ArrayList<PlannedFrame>(count)
        val attempts = LongArray(profile.tracks.size)
        var cursor = 0
        var random = profile.seed
        var pauseIndex = 0
        for (slot in 0 until count) {
            val at = slot.toLong() * profile.slotMs
            val duration = minOf(profile.slotMs.toLong(), profile.durationMs - at)
            // Fixed 64-bit LCG; overflow is defined two's-complement on Kotlin/JVM.
            // One draw per global slot, including pauses/no active tracks, keeps the loss pattern stable.
            random = random * 6364136223846793005L + 1442695040888963407L
            val omitted = ((random ushr 32) % 100L).toInt() < profile.lossPercent
            while (pauseIndex < profile.pauses.size && profile.pauses[pauseIndex].untilMs <= at) pauseIndex++
            val paused = profile.pauses.getOrNull(pauseIndex)?.let { at >= it.fromMs && at < it.untilMs } == true
            var selected = -1
            for (offset in profile.tracks.indices) {
                val candidate = (cursor + offset) % profile.tracks.size
                val track = profile.tracks[candidate]
                if (at >= track.activeFromMs && at < (track.activeUntilMs ?: profile.durationMs)) {
                    selected = candidate
                    break
                }
            }
            val track = profile.tracks.getOrNull(selected)
            val attempt = if (selected >= 0) attempts[selected]++ else 0
            if (selected >= 0) cursor = (selected + 1) % profile.tracks.size
            val silence = when {
                paused -> "planned_pause"
                track == null -> "no_active_track"
                omitted -> "seeded_omission"
                else -> null
            }
            val payload = if (silence == null && track != null) Protocol.hex(Protocol.payload(track, attempt)) else null
            val captured = if (payload != null && track?.kind == PayloadKind.CAPTURED) {
                track.capturedFrames[(attempt % track.capturedFrames.size).toInt()]
            } else null
            frames += PlannedFrame(
                sequence = slot + 1L,
                atMs = at,
                durationMs = duration,
                trackId = track?.id,
                companyId = profile.companyId,
                payloadHex = payload,
                reportedRssi = if (payload != null && track?.kind == PayloadKind.REPEATER) Protocol.reportedRssi(track.rssi, attempt) else null,
                silenceReason = silence,
                includeManufacturerData = track?.kind != PayloadKind.NAME_ONLY && track?.kind != PayloadKind.CAPTURED,
                localName = if (silence == null) profile.radio.localName else null,
                capturedAdvertisement = captured,
            )
        }
        return frames
    }
}
