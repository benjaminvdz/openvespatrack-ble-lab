# Profiles: import, edit and reproduce

Profiles are UTF-8 JSON. Schema 1 describes synthetic timed scenarios; schema 2 embeds observations for controlled replay. Import validates before use and never starts Bluetooth. Every saved run retains its own profile snapshot, so later edits do not change earlier evidence. The standalone continuous **Test beacon** has its own identity and engine; profile duration limits below do not apply to it.

The eleven [examples](../examples) match the built-in starter profiles. They include manufacturer-data boundary cases and two name-recognition cases. These are synthetic fixtures.

## Minimal example

```json
{
  "schemaVersion": 1,
  "id": "workbench-test",
  "name": "Synthetic workbench tag",
  "description": "Controlled manufacturer-data experiment",
  "durationMs": 30000,
  "slotMs": 500,
  "seed": 123,
  "companyId": 64613,
  "radio": { "mode": "LEGACY_1M", "intervalMs": 200, "txPowerDbm": -7 },
  "tracks": [{ "id": "tag-a", "kind": "DIRECT", "shortId": 4660, "counterStart": 1 }],
  "pauses": [{ "fromMs": 10000, "untilMs": 15000 }],
  "lossPercent": 0
}
```

Omitted optional fields take the defaults below. Export writes explicit defaults in stable field order and lowercase hex. Integers cannot be JSON strings, fractions or exponent notation.

## Scenario fields

| Field | Default | Constraint / meaning |
| --- | --- | --- |
| `schemaVersion` | Required | 1 for synthetic scenarios; 2 for captured replay |
| `id` | Required | 1–64 ASCII letters, digits, dot, underscore or hyphen; first character alphanumeric |
| `name` | Required | Nonempty; at most 120 characters |
| `description` | Empty | At most 4096 characters; newlines permitted |
| `seed` | 1 | Signed 64-bit seed for planned omissions |
| `durationMs` | 60000 | 250–7200000 ms |
| `slotMs` | 500 | 250–60000 ms; one logical track per slot |
| `companyId` | 64613 | 0–65535, separate from payload bytes |
| `radio.mode` | `LEGACY_1M` | Also `EXTENDED_1M`, `EXTENDED_CODED` with hardware support |
| `radio.intervalMs` | 200 | Requested advertisement interval, 100–10240 ms; distinct from slot duration |
| `radio.txPowerDbm` | −7 | Requested −127…+1 dBm; hardware may further restrict it |
| `radio.localName` | `null` | Optional exact name, 1–248 UTF-8 bytes and within total AD budget |
| `tracks` | Required | 1–32 tracks with unique IDs |
| `pauses` | Empty | At most 512 ordered, nonoverlapping intervals |
| `lossPercent` | 0 | 0–100% deterministic planned omissions; not measured packet loss |

Intervals use `[fromMs, untilMs)`: inclusive start, exclusive end. Boundaries align to slots; scenario end may shorten the final slot. The planner rejects more than 30000 slots (the current duration/slot limits allow at most 28800).

A non-null Local Name requires a temporary change to the phone's general Bluetooth name during real advertising, with confirmation and guarded restoration. Dry-run does not change it. See [protocol](PROTOCOL.md).

## Track fields

| Field | Default | Constraint / meaning |
| --- | --- | --- |
| `id` | Required | Unique logical track ID, not Bluetooth address |
| `kind` | `DIRECT` | Schema 1: DIRECT, REPEATER, RAW, NAME_ONLY; schema 2: CAPTURED |
| `subtype` | 0 | 0–3; further meaning unconfirmed |
| `shortId` | 4660 | 0–65535; deliberate collisions permitted |
| `transmitterHex` | `010203040506` | Exactly six bytes as 12 ASCII hex characters |
| `counterMode` | `RAW24` | Or `COUNTER16_TEMPERATURE` |
| `counterStart` | 0 | 0–16777215 for RAW24; 0–65535 for 16-bit |
| `counterStep` | 1 | −16777215…16777215, modulo selected width |
| `counterResetEvery` | 0 | 0 disables; otherwise 1–30000 track attempts |
| `temperatureRaw` | 22 | Raw byte 0–255, with no confirmed physical unit |
| `rssi.mode` | `CONSTANT` | Also RAMP and TRIANGLE; REPEATER payload only |
| `rssi.min` / `rssi.max` | −70 / −40 | Ordered signed-byte range −128…127 |
| `rssi.periodSlots` | 20 | 2–30000 track attempts per cycle |
| `rssi.unavailableEvery` | 0 | Every Nth attempt uses sentinel 127; 0 disables |
| `rawFrames` | Empty | RAW requires 1–256 hex strings; empty for other kinds |
| `capturedFrames` | Empty | CAPTURED only: 1–256 immutable source snapshots |
| `activeFromMs` | 0 | Slot-aligned activity start |
| `activeUntilMs` | `null` | Slot-aligned end; null uses scenario end |

Hex contains only ASCII `0–9a–fA–F`: no prefix, spaces or colons. An empty RAW hex string deliberately means zero manufacturer payload bytes, **not silence**. Limits are 24 payload bytes in legacy and 240 in extended mode, plus the combined AD budget.

NAME_ONLY requires `radio.localName` and no raw frames. It supplies no manufacturer data. The `nano-name-recognition.json` fixture uses `VESPA-NANO-SIM`; `nano-direct-vn.json` uses `vn0CqrvM3e7/`, synthetic logical identity `AA:BB:CC:DD:EE:FF` and PP `0C`. PP has no confirmed hardware meaning. These identifiers do not set the phone's physical address.

Combined AD budgets are 31 legacy / 254 extended bytes plus controller restrictions. Reserve three Flags bytes, four manufacturer AD/ID bytes if present, and two plus UTF-8 name bytes if present. A six-byte direct payload and `VESPA-NANO-SIM` use 29 bytes; an eleven-byte repeater payload with that name uses 34 and fails legacy validation.

## Schema-2 replay

Create a profile from a stopped recording. One observation defaults to a 60-second repeating profile. A sequence preserves every selected observation, including identical consecutive content, in increasing original sequence order. Each receives an explicit 250–60000 ms slot (default 500); duration equals count × slot duration. Selecting more than 256 is rejected, not truncated.

Schema 2 requires exactly one CAPTURED track, LEGACY_1M and empty rawFrames. Sources must belong to the same recording and observed radio address, have strictly increasing observation sequence numbers, and share an identical Local Name or all have no name. `radio.localName` must match. The 256 KiB profile limit still applies; 256 sources will not always fit.

Each captured frame contains:

| Field | Meaning |
| --- | --- |
| `captureId` | Source recording ID when the profile was created |
| `observation` | Full source scan bytes, address, both clocks, RSSI and Android metadata |
| `rawSha256` | Lowercase SHA-256 of raw scan bytes; consistency check, not authenticity |
| `importedSource` | Whether the recording originated from an imported bundle |

The source is embedded, not a mutable file reference. Renaming/deleting a recording cannot alter an existing replay profile or run. Validation accepts only supported complete legacy content; unsupported fields remain preserved in the recording and receive a replay refusal reason. See [protocol](PROTOCOL.md).

Schema-1 imports remain supported. Older applications that know only schema 1 cannot read schema 2. Existing saved evidence is not rewritten to match new schemas or labels.

## Deterministic planning

The planner rotates through active tracks. Each selected track's attempt counter advances even during a planned pause or omission, so payload counters may jump after silence. Inactive tracks do not accrue attempts. RAW frame choice and payload RSSI pattern use this same per-track attempt index.

Every global slot, including silence and no active track, consumes one value from the defined 64-bit two's-complement generator:

`state = state * 6364136223846793005 + 1442695040888963407`

Omit when `(state unsigned-shift-right 32) % 100 < lossPercent`; initial state is `seed`. This is a reproducible test schedule, not a security random generator. A profile pause takes precedence, then no active track, then planned omission. Silence carries a reason and null payload. Execution can separately skip expired slots; that does not change the saved plan.

## Import validation

Profiles are limited to 262144 UTF-8 bytes and JSON depth 24. Unknown fields, duplicate keys (including differently escaped spellings), invalid Unicode, unsupported schema/enums and wrong types are rejected. No unknown content is silently executed or discarded. Import, editing and offline inspection never start radio activity.

Keep profile, seed, app build and each run export together for repeatable experiments. Compare independent receiver bytes and timestamps separately from the plan.
