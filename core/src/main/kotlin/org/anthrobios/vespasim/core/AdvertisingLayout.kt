package org.anthrobios.vespasim.core

import org.anthrobios.vespasim.core.capture.AdvertisingPayload

/** Bounds for the AD elements used by our public Android advertiser, not a claim about RF delivery. */
object AdvertisingLayout {
    // Reserve Flags even for non-connectable sets, so the same profile is safe across controllers.
    fun byteSize(payloadBytes: Int, includeManufacturerData: Boolean, localName: String?): Int =
        3 + (if (includeManufacturerData) payloadBytes + 4 else 0) +
            (localName?.let { 2 + it.toByteArray(Charsets.UTF_8).size } ?: 0)

    /** Android groups service UUIDs by their shortest representable Bluetooth UUID width. */
    fun byteSize(payload: AdvertisingPayload): Int {
        val groups = payload.serviceUuids.groupingBy(::uuidBytes).eachCount()
        return 3 + (payload.localName?.toByteArray(Charsets.UTF_8)?.size?.plus(2) ?: 0) +
            payload.manufacturerData.sumOf { 4 + it.dataHex.length / 2 } +
            groups.entries.sumOf { (width, count) -> 2 + width * count } +
            payload.serviceData.sumOf { 2 + uuidBytes(it.uuid) + it.dataHex.length / 2 }
    }

    fun uuidBytes(uuid: String): Int {
        val normalized = java.util.UUID.fromString(uuid).toString()
        if (!normalized.endsWith("-0000-1000-8000-00805f9b34fb")) return 16
        return if (normalized.startsWith("0000")) 2 else 4
    }

    fun maximumBytes(mode: RadioMode): Int = if (mode == RadioMode.LEGACY_1M) 31 else 254

    fun requireMatchingName(expected: String?, actual: String?) {
        require(expected == null || actual == expected) {
            "Dit profiel vereist Bluetoothnaam '$expected'. De huidige naam is '${actual ?: "onbekend"}'. " +
                "De automatische naamvoorbereiding is nog niet bevestigd. Start opnieuw via de uitzendworkflow; de app bewaart de oorspronkelijke naam voor herstel."
        }
    }
}
