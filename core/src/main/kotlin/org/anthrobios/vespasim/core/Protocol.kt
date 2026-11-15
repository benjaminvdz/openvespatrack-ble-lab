package org.anthrobios.vespasim.core

/** Independent FC65 protocol hypothesis. No temperature unit or radio identity is inferred. */
object Protocol {
    const val COMPANY_ID: Int = 0xFC65
    const val VERSION: String = "fc65-hypothesis-1"
    private val digits = "0123456789abcdef".toCharArray()

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 15])
        }
    }

    /** Spaces, prefixes and separators are deliberately rejected: profile bytes are unambiguous. */
    fun unhex(value: String): ByteArray {
        require(value.length <= 480) { "Hexdata is langer dan 240 bytes." }
        require(value.length % 2 == 0) { "Hexdata moet een even aantal tekens bevatten." }
        return ByteArray(value.length / 2) { index ->
            val high = hexDigit(value[index * 2])
            val low = hexDigit(value[index * 2 + 1])
            require(high != null && low != null) { "Hexdata bevat een ongeldig teken op byte $index." }
            ((high shl 4) or low).toByte()
        }
    }

    private fun hexDigit(char: Char): Int? = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> null
    }

    /** frameIndex is the zero-based attempt number for this track, including planned omissions. */
    fun payload(track: TagTrack, frameIndex: Long): ByteArray {
        require(frameIndex >= 0) { "Frame-index mag niet negatief zijn." }
        require(track.subtype in 0..3) { "Subtype moet 0–3 zijn." }
        require(track.shortId in 0..65535) { "Kort tag-ID moet 0–65535 zijn." }
        require(track.counterResetEvery >= 0) { "Resetperiode mag niet negatief zijn." }
        if (track.kind == PayloadKind.NAME_ONLY || track.kind == PayloadKind.CAPTURED) return byteArrayOf()
        if (track.kind == PayloadKind.RAW) {
            require(track.rawFrames.isNotEmpty()) { "RAW vereist minstens één payload." }
            return unhex(track.rawFrames[(frameIndex % track.rawFrames.size).toInt()])
        }
        val modulus = if (track.counterMode == CounterMode.RAW24) 16_777_216L else 65_536L
        require(track.counterStart in 0 until modulus) { "Startteller past niet in de gekozen tellerbreedte." }
        require(track.counterStep in -16_777_215L..16_777_215L) { "Tellerstap buiten toegelaten bereik." }
        require(track.temperatureRaw in 0..255) { "Ruwe temperatuurbyte moet 0–255 zijn." }
        val index = if (track.counterResetEvery == 0) frameIndex else frameIndex % track.counterResetEvery
        // Reduce before multiplying: even Long.MAX_VALUE cannot overflow the arithmetic.
        val count = Math.floorMod(track.counterStart + (index % modulus) * (track.counterStep % modulus), modulus)
        val field = byteArrayOf(
            (count and 0xff).toByte(),
            ((count ushr 8) and 0xff).toByte(),
            if (track.counterMode == CounterMode.RAW24) ((count ushr 16) and 0xff).toByte()
            else track.temperatureRaw.toByte(),
        )
        val header = (track.subtype shl 2).toByte()
        return when (track.kind) {
            PayloadKind.DIRECT -> byteArrayOf(header, track.shortId.toByte(), (track.shortId ushr 8).toByte()) + field
            PayloadKind.REPEATER -> {
                val transmitter = unhex(track.transmitterHex)
                require(transmitter.size == 6) { "Transmitter-ID vereist precies zes ruwe bytes." }
                byteArrayOf((header.toInt() or 1).toByte()) + transmitter + byteArrayOf(reportedRssi(track.rssi, frameIndex).toByte()) + field
            }
            PayloadKind.RAW -> error("RAW werd hierboven verwerkt.")
            PayloadKind.NAME_ONLY -> error("NAME_ONLY werd hierboven verwerkt.")
            PayloadKind.CAPTURED -> error("CAPTURED werd hierboven verwerkt.")
        }
    }

    /** Candidate repeater payload field, not the locally received RSSI of the phone. */
    fun reportedRssi(pattern: RssiPattern, frameIndex: Long): Int {
        require(frameIndex >= 0) { "Frame-index mag niet negatief zijn." }
        require(pattern.min in -128..127 && pattern.max in -128..127 && pattern.min <= pattern.max) { "RSSI-bytebereik is ongeldig." }
        require(pattern.periodSlots in 2..30_000 && pattern.unavailableEvery in 0..30_000) { "RSSI-periode is ongeldig." }
        if (pattern.unavailableEvery > 0 && frameIndex % pattern.unavailableEvery == pattern.unavailableEvery - 1L) return 127
        val phase = (frameIndex % pattern.periodSlots).toInt()
        val span = pattern.max - pattern.min
        return when (pattern.mode) {
            RssiMode.CONSTANT -> pattern.min
            RssiMode.RAMP -> pattern.min + span * phase / (pattern.periodSlots - 1)
            RssiMode.TRIANGLE -> {
                val peak = pattern.periodSlots / 2
                if (phase <= peak) pattern.min + span * phase / peak
                else pattern.min + span * (pattern.periodSlots - phase) / (pattern.periodSlots - peak)
            }
        }
    }
}
