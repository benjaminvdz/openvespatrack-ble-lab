# Stored evidence and export formats

All working data is app-private until the user explicitly exports it through Android's Storage Access Framework. Exports can contain observed radio addresses, device names, timestamps and free text. Review them before sharing. The app does not upload them automatically.

Completed runs, original received records and their source profiles are evidence. A later decoder or terminology change does not rewrite them.

## Timed run storage and ZIP

Profiles and runs live under `files/lab/profiles/` and `files/lab/runs/`. Each run stores an immutable profile snapshot, plan and append-only events. Metadata replacement is atomic; appends contain complete newline-terminated JSON records.

A run ZIP contains `run.json`, `profile.json`, `plan.jsonl`, `events.jsonl`, `events.csv`, `report.html`, `manifest.json` and any retained `recovery/partial-tail-….bin` files. The manifest records member byte counts and SHA-256, excluding itself. An active export fixes a consistent complete-record boundary and identifies a snapshot, not a completed measurement.

Event fields include sequence (starting at 1), UTC `atEpochMs`, monotonic actual `elapsedMs`, `kind`, `frameSequence`, `trackId`, `payloadHex`, `message` and `details`. Actual elapsed time includes user pauses; `details.scenarioElapsedMs` is the separate scenario clock.

- `controller_accepted`: controller/API outcome, not confirmed packet transmission or reception.
- `simulated_frame`: dry-run software event; no radio.
- `frame_skipped_late`: a software deadline was missed, not measured RF loss.
- Null payload: no active frame. Empty payload with manufacturer data: deliberate empty RAW payload. NAME_ONLY has no manufacturer data; these states must remain distinct.
- Name preparation/restoration events distinguish requested, confirmed, failed and externally changed names. Original system names may be private.

Startup recovery closes abandoned active sessions as interrupted and preserves incomplete tail bytes separately. Corrupt complete records produce a visible error rather than disappearing. Recovery never resumes the radio.

| Timed-run item | Limit |
| --- | --- |
| Profile JSON | 256 KiB |
| Event line, including newline | 64 KiB |
| Events / total log | 250000 / 512 MiB |
| Query page | 1–2000 events; default 200 |
| HTML event table | Last 100; complete data remains in the logs |

Limits are explicit failures. Old evidence is not silently rotated away.

## Recording storage

Each recording has a UUID under `files/captures/records/<id>/`; deleted recordings move to `files/captures/trash/<id>/`.

| File | Content |
| --- | --- |
| `capture.json` | Schema, name, chosen radio address, times/status, researcher labels/notes, build/device and scan settings |
| `observations.jsonl` | Complete original received observations, one JSON object and newline per record |
| `events.jsonl` | Separate capture, stop, error and recovery diagnostics |
| `origins/<sha256>.json` | Preserved source metadata; filename hashes exact bytes |
| `recovery/partial-tail-….bin` | Preserved incomplete append bytes |

Observation fields are `sequence`, `receivedAtMs`, `timestampNanos`, `address`, `rssiRaw`, `rawHex`, `deviceName`, `legacy`, `connectable`, `primaryPhy`, `secondaryPhy`, `advertisingSid`, `txPowerRaw`, `dataStatus`, `periodicInterval` and `callbackType`.

`rawHex: null` means Android supplied no scan record, distinct from an existing empty byte array. `deviceName` is cached Android metadata, not necessarily advertised Local Name. See [protocol](PROTOCOL.md) for clocks and RSSI validity.

Renaming, notes and researcher labels do not change received records. Only closed recordings can move to trash. Restore returns the same recording; permanent deletion is a separate confirmation. Exported files and existing replay profiles remain independent copies.

Every append is synchronized and consecutive sequence numbers plus the selected address are validated. Parent-directory synchronization is attempted where supported, but no universal power-loss guarantee is claimed. Startup recovery preserves incomplete tails, reports corrupt complete lines and still recovers healthy recordings. It does not automatically resume any session.

## Recording ZIP and import

The recording ZIP contains `capture.json`, `observations.jsonl`, `events.jsonl`, `observations.csv`, `report.html`, `manifest.json` and present origins/recovery files. Its stable compatibility identifier is `format: "vespa-android-capture"`, `schemaVersion: 1`. The manifest contains recording ID, observation/diagnostic counts and each other member's path, length and SHA-256.

Import accepts this format only; it is not a generic ZIP, Linux JSONL, HCI or pcap importer. Renaming another format does not convert it. Validation checks ZIP structure/CRC, unique allowed paths, exact member list, size/hash, strict schemas, counts, consecutive sequence and chosen address. Unknown fields/files are rejected. Imported HTML is not executed as app UI.

After validation, a preview appears. Saving publishes a copy under a new local UUID and never overwrites an existing recording. `importedFromId`, `importedArchiveSha256` and source metadata retain provenance. An active exported source imports as INTERRUPTED. Cancelled previews and abandoned temporary imports are cleaned up. A valid hash establishes consistency with supplied bytes, not authentic RF reception or tag identity.

CSV retains original observations with an added `rssi_valid` column. Formula-like text is protected as spreadsheet text. HTML escapes free text, has no scripts/external resources and shows context, provenance, settings, valid local RSSI statistics and at most 500 regularly selected sequence points. It does not invent a continuous signal. Full observations remain in JSONL/CSV.

SAF can leave a partial destination file after a provider write failure; the source remains and an error is shown. Verify important exports before uninstalling or deleting originals.

| Recording/import item | Limit |
| --- | --- |
| Original scan record | 1650 bytes |
| Live Android recording | 2 hours or 50000 accepted observations |
| Writer inbox | 512 observations; overflow ends visibly |
| Recording metadata | 64 KiB |
| Observation / diagnostic JSON line | 8 KiB / 16 KiB |
| Stored/imported observations | 200000 and 128 MiB JSONL |
| Diagnostic events | 50000 and 16 MiB JSONL |
| Local recordings, including trash | 10000 |
| Imported ZIP | 160 MiB compressed, 384 MiB expanded, 70 members |
| Origins/recovery attachments | 64 total, including newly added source metadata |
| Imported CSV / HTML | 192 MiB / 4 MiB |
| Metadata, manifest, each source-metadata member | 64 KiB each |

Recording limits do **not** impose a timer on the separate continuous test beacon. Replay profiles have their own 256 KiB and 256-source limits; a valid large recording need not fit into one replay profile.

## Synthetic regression fixtures

[android-dry-run.zip](../examples/reports/android-dry-run.zip) is a historical Android emulator SAF export of a synthetic dry-run. It is retained unchanged for regression tests, not evidence that the current build received RF packets.

[synthetic-nano-120.zip](../examples/capture-fixtures/synthetic-nano-120.zip) contains deliberately synthetic observations and fictitious identities. It is suitable for import/review tutorials without a real tag. Neither fixture demonstrates reception from physical hardware. The publication audit inspects their archive members; unrelated run exports and private captures do not belong in the public source tree.
