# Android radio and physical validation

Android 8.0/API 26 or later is required. The phone must support BLE advertising to act as a test beacon. A generic Bluetooth version claim does not establish advertising, extended advertising or Coded PHY support.

The app controls one physical transmitter and one explicit radio activity at a time. It does not choose arbitrary hardware addresses, pair, offer a GATT server, write tag settings or bypass Android battery policy.

## Continuous test beacon

**Test beacon → Start beacon** starts a foreground session containing the custom service-data identity documented in [protocol](PROTOCOL.md). There is no automatic duration cutoff. Stop is available in the screen and notification. Allow notifications to keep the notification action visible; denying them is not the same as denying Bluetooth.

Screen rotation, leaving the Activity or switching the screen off does not intentionally end the beacon. Android, battery restrictions, Bluetooth changes, permission revocation, force-stop and reboot can interrupt it. A subsequent start must be explicit. Do not claim uninterrupted operation on a device until tested with an independent receiver.

Use all-BLE/service-data reception. Existing FC65/name-only filters can hide this custom beacon. Match UUID and identity; the address may follow Android's privacy policy. A cached receiver row is not fresh reception: use timestamps to confirm Stop.

If cleanup cannot be confirmed, the app retains radio ownership and shows an uncertain-stop state. Retry Stop or turn Bluetooth off. A failed stop must not be presented as confirmed silence.

## Permissions and lifecycle

On Android 12+, advertising requires Nearby devices permissions for advertising/connect. Scanning additionally requires scan access. The recording flow requests precise location and an enabled location setting because BLE scanning can depend on them. The app does not request GPS positions, background location or Internet access.

The scanner does not assert `neverForLocation`, because Android can otherwise filter some beacon content. Discovery is a short visible activity; recordings use a native filter for the selected radio address. A rotating address is not automatically tracked as the same device.

Recording runs as a user-started foreground service. Validate Android 10/11 (API 29/30), recent Android versions and manufacturer battery restrictions separately with Home and screen-off. A foreground service and filter do not guarantee every packet or every device's background behaviour.

Dry-runs require no Bluetooth and stop shortly after the last Activity leaves, with a recreation allowance. They remain a software test.

## Timed scenarios and replay

Legacy scenarios are nonconnectable/nonscannable. They offer configured manufacturer data, Local Name or supported replay AD content. Local Name profiles temporarily change the general system Bluetooth name, confirm it before radio start and restore it after cleanup; unresolved recovery remains visible. Continuous beacon mode does not change it.

Extended 1M/Coded require both transmitter and receiver support. The requested interval, controller-selected power and scenario slot time are separate values. Radio delays can keep old data active longer than a planned slot. The engine logs overdue slots and avoids catch-up bursts. Planned silence/pause disables advertising rather than merely withholding data updates.

`controller_accepted` means API/controller acceptance. Independent reception must be measured separately. Local receiver RSSI and synthetic repeater payload RSSI have different meanings; neither proves a distance or direction.

## Physical acceptance record

For each test, record exact APK hash, app version/source commit, phone/Android version, receiver/controller/firmware, scan filter, battery/location settings and approximate duration. Export supporting logs when applicable.

| Test | Required independent observation |
| --- | --- |
| Start/Stop beacon | Matching service UUID/identity appears after start; fresh observations cease after Stop |
| Screen off / Home | Fresh observations continue for the documented duration and settings |
| Long beacon session | Observe beyond two hours to distinguish it from timed scenario/recording limits; report gaps and battery use |
| Restart | Force-stop/reboot and reopen; no new advertisements before explicit Start |
| Permission / Bluetooth loss | Visible interruption, no silent restart; explicit retry after repair |
| Rapid taps / rotation | One session; no duplicate transmitter; Stop remains reachable |
| Busy radio | Capture/scenario/beacon conflict refused; unconfirmed cleanup stays blocked |
| Notification stop | Same termination as in-app Stop, including while screen is locked |
| Manufacturer profile | Exact ID and received payload bytes compared with the synthetic plan |
| Name profile | Exact advertised name; original system name restored; externally changed name preserved |
| Replay | Supported AD content matches; address, field order, RF timing and scan-response differences recorded |
| Recording | Original scan bytes, valid local RSSI and both clocks retained; interrupted sessions remain exportable |
| Extended / Coded | Supported hardware at both ends; receiver evidence of the requested PHY |

A software/emulator result is not a physical-radio result. Mark tests **not run** when no suitable phone or receiver is available. See [testing](TESTING.md) for build validation and [test matrix](TEST_MATRIX.md) for failure cases.

## Primary Android references

- [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [BluetoothLeScanner](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner)
- [AdvertisingSet](https://developer.android.com/reference/android/bluetooth/le/AdvertisingSet)
- [Foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Foreground location access](https://developer.android.com/develop/sensors-and-location/location/permissions)
