# Acceptance matrix

This is a test plan, not a claim that every row ran on physical hardware. The [release validation record](TESTING.md) identifies actual checks and limitations.

## Meaning of evidence

Protocol vectors establish encoder/decoder consistency. Dry-runs exercise software without transmitting. Android callbacks report controller outcomes. A separate receiver is needed to establish RF reception. Received local RSSI, requested transmitter power and synthetic repeater RSSI remain separate quantities.

## Core and storage

| Area | Cases | Expected result |
| --- | --- | --- |
| Encoding | Direct/repeater boundaries, signed RSSI, malformed/empty RAW, counter wrap/reset, third-byte 5/6 | Exact bytes and explicit uncertainty; no invented temperature unit |
| Profiles | Round-trip, duplicate/unknown keys, Unicode, depth/type/size errors, PHY/power/budget boundaries | Deterministic valid profiles; bounded rejection before storage/radio |
| Planning | Same seed, active windows, colliding short IDs, pauses, 0/100% omissions, late slots | Stable plan; logical identity separate from physical address; no catch-up burst |
| Timed controller | Success/error/timeout, slow updates, pause/stop during pending operation, late/duplicate callbacks | At most one operation; no restart after stop; uncertain cleanup visible |
| Beacon controller | Identity encoding/persistence, unbounded elapsed time, start/stop/timeout, late callbacks | No duration cutoff; explicit start; Stop and radio ownership remain safe |
| Radio gate | Competing capture, scenario and beacon starts; failed cleanup | One owner; ownership released only after confirmed cleanup |
| Name coordinator | Confirm/refuse/timeout, stop during preparation, process loss, external name change | No radio before exact confirmation; durable recovery; external name preserved |
| Run storage | Immutable profile snapshot, concurrent append/export, corrupt/full/partial lines, disk failure | Complete evidence retained; prefix-consistent export; visible failure |
| Recording | Address/sequence checks, late callbacks, null records, MATCH_LOST, full inbox | Original bytes/metadata retained; no observation in the wrong session |
| Import | ZIP traversal/duplicate paths/bomb, CRC/hash/schema/count mismatch, cancellation | Bounded validation before preview; explicit new-copy save; no overwrite/radio |
| Export | Every member hash/size, formula-like CSV text, HTML text, failed SAF write | Safe report text; original data retained; no false export success |
| Replay | 1/256/257 sources, wrong identity/order/hash/name, unsupported AD, byte budget | Accept whole valid selection or refuse with reason; no silent truncation |
| Recovery | Process loss, interrupted append, one corrupt recording among healthy recordings | Preserve tails, report corruption, recover healthy evidence; no auto-resume |

## Android, languages and appearance

| Area | Cases | Expected result |
| --- | --- | --- |
| Initial launch | Permissions absent, Bluetooth unavailable | Read-only/dry-run paths remain usable; no automatic radio |
| Permissions | Denied/revoked Nearby devices, location, notification | Specific recovery message; no successful session fabricated |
| Lifecycle | Rotation, Home, screen off, process kill, reboot | One radio session; no restart after interruption; dry-run documented foreground rule |
| Languages | English, Dutch, French; switch and reopen each screen/dialog/notification | Translated presentation; technical format keys/UUIDs unchanged |
| Themes | Light/dark/system; restart; main/capture/beacon/settings | Consistent AnthroBios Nexus colours, legible text and visible state/action hierarchy |
| Accessibility | Large text, landscape, small screen, TalkBack focus | Controls remain reachable and status understandable |
| Reports | Import synthetic fixture, offline timeline, export HTML/CSV/ZIP and print | No unexpected radio; readable evidence and correct provenance |
| Upgrade | Same certificate vs changed certificate; export first | Preserve stored evidence with compatible upgrade; no unsupported update promise |

## Physical tests

Use the procedure in [hardware validation](HARDWARE.md). In particular, verify continuous beacon reception past two hours, screen off, notification Stop, radio conflicts and interruption recovery with a second receiver. API 29/30 recording behaviour and recent Android behaviour need separate device observations.

For experimental name/manufacturer profiles, compare exact received bytes and saved run evidence. A receiver restricted to those formats will not automatically recognize the standalone custom beacon.

## Companion protocol cross-check

The shared [protocol vectors](../fixtures/protocol-vectors.json) exercise fixed synthetic bytes in Kotlin. For an independently built companion executable:

```sh
python3 scripts/verify_protocol_vectors.py /absolute/path/to/ble-tagfinder
```

This script uses a temporary database and synthetic btsnoop input to check the companion's import, decoder, replay and export behaviour. Record its exact binary version/commit. Passing this check establishes implementation agreement, not physical-tag compatibility. Do not alter old fixtures solely to conceal a regression.
