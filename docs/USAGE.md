# Operating guide

[English quickstart](tutorials.md) · [Nederlands](tutorials-nl.md) · [Français](tutorials-fr.md)

## Continuous test beacon

Open **Test beacon**, check the synthetic identity and choose **Start beacon**.
Grant advertising permissions and enable Bluetooth yourself if needed. The
beacon holds one fixed advertisement without a scenario deadline. Use another
receiver to verify that the chosen identity is received before leaving the
phone as a test point.

The identity starts with `OVT-` and contains 16 hexadecimal digits. It persists
between ordinary launches. It is a synthetic test identifier, not the phone's
Bluetooth address. The beacon uses service UUID
`6f8a5b00-7d3e-4c6a-9b21-0a5c2d7e9101`; receivers should show all BLE devices or
match that service-data field. Other tag-specific filters may hide it.

Use **Stop** on the screen or in the foreground notification. Leaving the
screen, rotating the phone or turning the screen off does not intentionally end
the test. Android can still interrupt it through force-stop, reboot, process
termination, permission loss, Bluetooth-off or battery restrictions. Reopening
the app never silently restarts radio activity.

The beacon does not rename the phone, create a GATT server or emulate another
tag's firmware. Controller acceptance is not independently observed reception.

## Record a device

1. Open **Record & replay** and choose the recording tab.
2. Enable Bluetooth and Android's required location settings. Grant scan/connect
   and location permissions when requested; this app does not request GPS fixes.
3. Choose the appropriate known-tag filter or show all BLE advertisements.
   Search and select the intended recently observed device.
4. Give the recording a useful name and notes. Variant labels are user context,
   not proof of firmware identity.
5. Start recording the selected device. Discovery observations before that
   action are not part of the persistent recording.
6. Stop from the app or notification. Measurements are saved automatically.

The capture uses the selected radio address. If a device rotates its address,
the app does not silently follow another identity. A recording is bounded to
two hours or 50,000 accepted observations. Storage or queue failures stop it
visibly while preserving already stored data. These capture limits do not set
the duration of the separate test beacon.

The app serializes capture, beacon and simulation radio ownership. Stop one
radio operation before starting another.

## Review and export

Saved recordings offer the original scan bytes, Android metadata, measured
local RSSI and diagnostic events. Offline playback and its time cursor do not
transmit, request advertising permission or change the Bluetooth name. Invalid
RSSI values and gaps must not become invented measurements.

Rename or edit notes without changing the received bytes. A completed recording
can move to the trash and be restored. Permanent removal is a separate action.
Export ZIP for a complete portable recording; CSV and HTML provide other views.
The file picker lets you choose where to save. Exports can contain identifying
addresses, names, timestamps and notes.

Recording-ZIP import validates and previews the archive before making a new
local copy. It never overwrites another recording or starts radio activity.
It accepts this application's recording format, not arbitrary HCI, pcap or
desktop ZIP formats. See [EXPORTS.md](EXPORTS.md).

## Reconstruct recorded advertisement content

Choose a suitable observation from a stopped recording. Prepare a repeated
advertisement or a series from the selected page, then review the source,
hashes, timing and limits. Save the profile and open it in dry-run mode first.
Actual BLE advertising requires a separate explicit start.

Replay reconstructs supported name, manufacturer, UUID and service-data fields.
It does not copy the original address, RF timing, received RSSI, transmit power,
scan-response boundary, connectability or GATT services. Unsupported or oversized
content is rejected without truncating the original recording. Timed replay
remains bounded; it is separate from the continuous test beacon.

## Timed synthetic scenarios

The **Test** tab runs a saved profile. Start with **Start dry-run**, inspect its
plan and reports, then explicitly choose **Start BLE advertising** for a radio
test. Dry-run speed changes affect only the hardwareless test.

Use **Pause**, **Resume** or **Stop**. Dry-runs stop when the application leaves
the foreground, allowing a short activity-recreation interval. Real radio
scenarios use a foreground service and stop at their configured end time.
The graph shows planned repeater-payload RSSI, not measured reception.
One advertising set multiplexes logical tracks; it is not multiple independent
physical transmitters.

## Name-encoded profiles and recovery

Some advanced profiles use `radio.localName`, including the `vn` name format.
Android exposes a general Bluetooth name rather than an arbitrary name per
advertising set. For those profiles, the app stores the original name, requests
the temporary name, verifies it and only then starts the scenario. Stop and
normal completion attempt to restore the original name.

A pending recovery record is retained after interruption. Reopen the app,
enable Bluetooth and use the explicit Bluetooth-name recovery action.
Recovery does not restart advertising. A different name subsequently chosen by
the user is preserved. Keep app data until outstanding recovery is resolved.

The custom **Test beacon** uses service data and does not use this name flow.

## Profiles, settings and reports

**Profiles** supports strict JSON import, validation, editing, copying and export.
Saving a profile changes neither completed run snapshots nor recorded source
bytes. Import is bounded and starts no radio. See [PROFILES.md](PROFILES.md).

**Logs & report** provides saved timed sessions, paginated logs, CSV, HTML,
research ZIP and Android printing/PDF. A planned frame, Android request,
controller acceptance and receiver observation remain different kinds of
evidence.

**Settings & about** controls interface language and appearance and links to
OpenVespaTrack and AnthroBios Nexus. Changing presentation does not translate
old user notes, profile names or evidence records.

[Installation and backup](INSTALLATION.md) · [Field tests](HARDWARE.md)
