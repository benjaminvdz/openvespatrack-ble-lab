# Architecture

The pure Kotlin/JVM `core` module contains strict profiles, deterministic planning, protocol encoding, the continuous beacon state machine, capture parsing/replay validation and local storage/export. Android `app` contains screens, settings, file pickers, permissions, foreground services and radio adapters.

## Three independent execution paths

Timed scenarios validate a profile, save an immutable snapshot and run a bounded slot plan. Their advertising engine multiplexes logical tracks through one physical advertising set. Silence disables advertising; zero-length manufacturer data is a different test.

The continuous test beacon uses a separate engine and persistent synthetic identity. It has explicit Start/Stop states and no scenario-duration timer. It does not implement an indefinitely large timed profile. Its requested service data is fixed for the installation.

Capture discovery and recording use the Android scanner. `RadioSessionGate` grants a single owner to capture, scenario advertising or beacon advertising. Unconfirmed cleanup retains ownership until release is confirmed; a new session cannot silently overwrite an unresolved radio action.

## Evidence and time

A plan, API request, controller callback and independent received observation are different records. A controller-accepted callback proves an API outcome, not packet delivery. A scan observation counts an Android callback observation, not every RF packet. Payload counters and repeater RSSI remain synthetic content.

Monotonic clocks control execution; UTC timestamps provide external context without automatically synchronizing devices. Late slots are logged and skipped rather than emitted as a catch-up burst. Timed user pauses freeze scenario time; actual elapsed time continues.

## Services and presentation

Radio sessions run in foreground services with visible status and Stop actions. Activities observe the session; rotating or leaving an Activity does not intentionally create another transmitter. Android may still interrupt services. There is no restart after process death or reboot.

Dry-runs do not request Bluetooth. They use a foreground-visible Activity lifecycle and stop after the last Activity leaves, with a brief recreation allowance. They cannot silently turn into real advertising.

Language and light/dark appearance are stored preferences. Android resources supply English, Dutch and French presentation. Stored profile fields, protocol identifiers and event keys remain stable across languages. Theme colours and bundled IBM Plex fonts follow the AnthroBios Nexus design system.

## Name-profile preparation

Only advanced profiles with a Local Name request a temporary system Bluetooth name. The coordinator durably journals original/requested values before asking Android, waits for exact confirmation and starts scenario time afterward. Radio cleanup precedes restoration. Unresolved preparation/restoration stays visible and blocks conflicting actions. An externally changed name is not overwritten.

Recovery can be explicitly retried after reopening without starting radio activity. The continuous beacon and nameless/dry-run profiles do not change the name.

## Capture and immutable sources

`AndroidCaptureScanner` copies source bytes and metadata. A recording filters one selected observed radio address. Generation checks separate late callbacks from subsequent sessions; MATCH_LOST is a status event, not a new RSSI sample. A bounded worker inbox keeps file I/O off scanner callbacks and the UI thread. Overload or storage failure ends the session visibly and preserves already durable evidence.

`CaptureCleanupRecovery` keeps a failed scanner-stop callback and radio ownership at process scope so an explicit retry can finish the same cleanup. Cleanup itself never starts a scanner or transmitter.

`CaptureRepository` stores append-only source observations separately from editable names/notes and diagnostics. Startup recovery marks abandoned active sessions interrupted and preserves incomplete tail bytes. Opening an ordinary repository does not repeatedly run startup recovery against a live session.

`CaptureParser` derives ordered AD fields, recognition candidates and replay eligibility without rewriting bytes. Cached device names do not establish a candidate identity. Capture charts use measured local RSSI, leave invalid data as gaps and avoid invented continuity across clock jumps or long gaps.

`CaptureReplay` embeds selected source observations and hashes into schema-2 profiles. These profiles reuse the timed planner, radio driver, event log and name coordinator. They reconstruct supported AD semantics, not the source MAC, RF timing, RSSI, GATT server or scan-response boundary.

## Storage boundaries

Profiles, run logs, recordings and trash are app-private. Android's Storage Access Framework grants access to explicitly chosen import/export files. There is no Internet permission or broad storage permission.

Metadata replacement is atomic and logs contain complete newline-terminated records. Source records and completed runs are preserved. Export takes a consistent prefix when a session is still active and labels it as a snapshot. Import is bounded, strictly validated, previewed and saved as a new recording only after an explicit action.

See [exports](EXPORTS.md), [hardware](HARDWARE.md), [testing](TESTING.md) and [public-source policy](PUBLICATION.md).
