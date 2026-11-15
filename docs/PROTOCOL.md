# BLE formats and evidence boundaries

OpenVespaTrack BLE Lab provides a deliberately synthetic test beacon, experimental manufacturer-data profiles, name-recognition profiles and controlled replay of supported Android scan content. These are separate formats. A matching decoder result establishes software consistency, not compatibility with an unmeasured device or firmware.

No mode pairs with a tag, writes settings, installs firmware or implements a tag's GATT server.

## OpenVespaTrack test beacon

The continuous test beacon is the recommended starting point for a known search point.

| Field | Value |
| --- | --- |
| Service-data UUID | `6f8a5b00-7d3e-4c6a-9b21-0a5c2d7e9101` |
| Service data | One version byte `01`, followed by eight synthetic identity bytes |
| Displayed identity | `OVT-` followed by the same eight bytes as 16 hexadecimal characters |
| Radio request | Legacy LE 1M, nonconnectable, scannable |
| Requested interval / power | 200 ms / −7 dBm |
| Active scan response | 128-bit service UUID list with the same UUID. No connection is offered. |
| Other content | No device name, manufacturer data or GATT service |

The random identity is stored locally for this installation. It is public in the advertisement, not a secret, authentication key or the phone's Bluetooth address. Clearing app data creates a new identity. This mode does not change the phone's Bluetooth name.

Version 0.4.0-rc.2 transmits the same identity in its primary advertisement but has no scan response. The receiver in newer BLE Lab builds recognises both versions under **Record & replay → OpenVespaTrack test beacons**. In another BLE scanner, start with all advertisements and inspect **Service Data**. A service UUID-list filter differs from a Service Data filter, and active scanners only see the new UUID list if they receive the scan response. Name, Nano and FC65 filters can hide this beacon. The app's controller status does not demonstrate reception. There is no scenario-duration cutoff, but Android or hardware interruptions can stop a session.

## Experimental manufacturer data

The default `companyId` is `64613` (`0xFC65`). Android supplies that ID separately from the payload: do not prepend `65 FC` to an ordinary payload.

| Track kind | Payload, excluding manufacturer ID |
| --- | --- |
| `DIRECT` | `[subtype << 2, shortId low, shortId high, field0, field1, field2]` |
| `REPEATER` | `[(subtype << 2) | 1, six transmitter bytes, signed RSSI byte, field0, field1, field2]` |
| `RAW` | Exactly one supplied hex frame, including deliberately empty or malformed data |
| `NAME_ONLY` | No manufacturer data; an explicitly configured Local Name |
| `CAPTURED` | Schema-2 source content reconstructed into supported AD fields |

Subtype is 0–3. Six-byte transmitter values retain their supplied order. Their relationship to a hardware MAC address and the correct display direction remain unconfirmed. Logical track ID, 16-bit short ID, transmitter bytes and the phone's physical Bluetooth address are distinct identifiers. Short IDs can collide.

Examples: DIRECT short ID 4660 and counter 1 gives `003412010000`. REPEATER transmitter `010203040506`, payload RSSI −56 and counter 1 gives `01010203040506c8010000`.

### Counter and uncertain third byte

`RAW24` stores three little-endian bytes modulo 2²⁴. `COUNTER16_TEMPERATURE` stores two counter bytes modulo 2¹⁶ and an explicit raw byte. That byte has **no confirmed physical unit**. Positive/negative steps wrap modulo the selected width; an explicit reset restarts the pattern.

The experimental decoder treats a third byte greater than 5 as a temperature candidate and consequently uses a 16-bit counter candidate. A RAW24 counter crossing 5→6 can therefore change interpretation. A raw temperature byte ≤5 can instead appear as part of a 24-bit counter. Test fixtures expose this uncertainty rather than adjusting bytes to conceal it.

### Received RSSI versus payload RSSI

Local RSSI is measured by the receiving device. Requested transmitter power is a controller setting, not receiver RSSI. The `REPEATER` RSSI byte is synthetic payload content describing a hypothetical upstream link.

Payload patterns are `CONSTANT`, `RAMP` and `TRIANGLE`; `unavailableEvery` inserts sentinel 127 at a chosen interval. Full signed-byte values are accepted for negative tests. The companion experimental decoder regards 127, −128 and values above 20 as unavailable/invalid; these must not appear as strong reception peaks.

## Name recognition

The compatibility prefixes `VESPA-NANO`, `VESPA-NANO-`, `VESPA-RANGE`, `vespa-` and `vn` are retained as technical detection data. Matching a prefix is a candidate classification, not verified model, ownership or firmware identity.

The direct `vn` format is `vnPPIDENTITY`: a two-character hexadecimal PP byte followed by eight characters in the alphabet `ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/`. Eight six-bit symbols encode a logical 48-bit identity. The meaning of PP is unconfirmed. For example, `vn0CqrvM3e7/` encodes synthetic identity `AA:BB:CC:DD:EE:FF` and PP `0C`.

This name does not set the phone's hardware address. One profile has one fixed Local Name, so several NAME_ONLY tracks do not create several independent name identities.

Android's public advertising API can include the system Bluetooth name, but cannot assign an arbitrary name independently per advertising set. Advanced name profiles therefore save the original system name, request the temporary name and wait for confirmation before advertising. Cleanup stops the radio before restoring the name. An unresolved restoration remains visible; an externally changed name is preserved. Dry-runs and the continuous test beacon make no name requests.

## Android scan observations

An observation stores the original Android `ScanRecord` bytes plus metadata. Android can combine advertising and scan-response content: these bytes are not a raw RF packet, HCI capture or proof of the original packet boundary.

`timestampNanos` is Android's monotonic observation time; `receivedAtMs` is UTC when the callback is received. They have different clock domains. Local `rssiRaw` values −127…126 are usable; invalid originals remain stored and create graph gaps. `txPowerRaw = 127` means unavailable. Cached `deviceName` is not necessarily an advertised Local Name.

The parser preserves original bytes, ordered AD fields, unknown fields, incomplete TLVs and decoding warnings. Its interpretation never rewrites source observations. Legacy/extended status and incomplete data remain available even when replay is blocked.

## Controlled replay

Replay accepts only complete legacy-1M observations containing supported, unambiguous name, manufacturer-data, service-UUID and service-data fields within the legacy budget. It reconstructs their semantic content through Android APIs.

Shortened names, invalid UTF-8, duplicate identities/fields, incomplete data, Tx Power AD, Appearance and unsupported AD types block replay with a reason. Source data remains available for review/export. Flags, field order and UUID-list representation may differ when Android rebuilds an advertisement.

Replay does not reproduce the original radio MAC, RF timing, received RSSI, chosen transmitter power, GATT server or scan-response boundary. A sequence uses explicit fixed slots in observation order, not reconstructed original transmission intervals. An embedded source hash checks consistency with supplied bytes; it does not authenticate a tag.

## Radio budget and physical identity

One advertising set is one physical transmitter. Logical tracks are multiplexed in time, not separate radio addresses. Silence explicitly disables advertising; an empty RAW manufacturer payload is still an advertisement.

Legacy profiles allow 24 manufacturer payload bytes and 31 total bytes. Extended profiles allow 240 payload bytes and a conservative 254 total bytes, additionally limited by the controller. The budget reserves three Flags bytes, four bytes for manufacturer AD/ID when present, and two bytes plus UTF-8 name length when present. Nothing is silently truncated.

Profile power requests are limited to −127…+1 dBm for the supported Android API range; hardware can restrict or substitute a value. Extended 1M and Coded PHY require actual controller support. An Android request or successful callback is not independent over-air evidence.

See [profiles](PROFILES.md), [hardware validation](HARDWARE.md) and the [test matrix](TEST_MATRIX.md).

## Primary API references

- [Android ScanResult](https://developer.android.com/reference/android/bluetooth/le/ScanResult)
- [AdvertiseData.Builder](https://developer.android.com/reference/android/bluetooth/le/AdvertiseData.Builder)
- [AdvertisingSetParameters.Builder](https://developer.android.com/reference/android/bluetooth/le/AdvertisingSetParameters.Builder)
- [Android Bluetooth advertising](https://source.android.com/docs/core/connect/bluetooth/ble_advertising)
