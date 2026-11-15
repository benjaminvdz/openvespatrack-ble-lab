package org.anthrobios.vespasim.core

/** Experimental name-encoded tag format; hardware and firmware compatibility remain unverified. */
object NanoTagName {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val addressPattern = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")
    private val parameterPattern = Regex("[0-9A-Fa-f]{2}")

    data class Identity(val logicalAddress: String, val parameter: Int)

    /** Canonical eight-digit radix-64 integer representation of exactly 48 logical address bits. */
    fun encode(logicalAddress: String, parameter: Int): String {
        require(addressPattern.matches(logicalAddress)) { "Logisch tagadres vereist zes hexoctetten, bijvoorbeeld AA:BB:CC:DD:EE:FF." }
        require(parameter in 0..255) { "PP moet een byte zijn (00–FF); hardwarebetekenis onbekend." }
        var value = logicalAddress.replace(":", "").toLong(16)
        val encoded = CharArray(8)
        for (index in 7 downTo 0) {
            encoded[index] = ALPHABET[(value and 63).toInt()]
            value = value ushr 6
        }
        return "vn${parameter.toString(16).padStart(2, '0').uppercase(java.util.Locale.ROOT)}${String(encoded)}"
    }

    /** Strict bounded research parser. Unlike the original client, malformed PP is never guessed. */
    fun decode(name: String): Identity {
        require(name.startsWith("vn") && name.length in 5..12) { "Nano-naam vereist vn + twee PP-hextekens + 1–8 radix64-tekens." }
        val parameterText = name.substring(2, 4)
        require(parameterPattern.matches(parameterText)) { "PP vereist precies twee ASCII hextekens." }
        var value = 0L
        for (character in name.substring(4)) {
            val digit = ALPHABET.indexOf(character)
            require(digit >= 0) { "Nano-identiteit bevat een ongeldig radix64-teken; geen padding, spaties of URL-safe alfabet." }
            value = (value shl 6) or digit.toLong()
        }
        // At most eight digits ensures 0 <= value <= 2^48-1, without truncation or overflow.
        val hex = value.toString(16).padStart(12, '0').uppercase(java.util.Locale.ROOT)
        return Identity(hex.chunked(2).joinToString(":"), parameterText.toInt(16))
    }
}

