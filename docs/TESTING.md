# Build and release validation

The public preview is **0.4.0-rc.2**. This document separates repeatable commands from observations. A build is not an install test; an emulator is not a BLE receiver.

## Required local checks

Use JDK 17 and Android SDK Platform 36, then run:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
python3 scripts/publication_audit.py
python3 -m unittest discover -s scripts -p 'test_publication_audit.py'
```

On an available Android emulator/device:

```sh
./gradlew :app:connectedDebugAndroidTest
```

The [Android workflow](../.github/workflows/android.yml) supplies CI checks. Record the exact tested commit, commands, results and artifact hashes for a release; previous-version results cannot be reused as current evidence.

## Release evidence to record

| Check | Required evidence |
| --- | --- |
| Unit tests / lint / assembly | Actual command outcome and matching source commit |
| Translation/appearance | English, Dutch, French; light and dark; main, beacon, capture and settings |
| Instrumentation | Android version/device, test count/result and any skipped cases |
| Installable artifact | Exact signed APK, SHA-256, certificate fingerprint, install/launch outcome |
| Protocol cross-check | Exact companion executable/version and vector result when encoder changes |
| Physical beacon | Receiver, UUID/identity, duration, screen-off and Stop results |
| Recording/replay | Phone/receiver models, supported content, lifecycle and limits observed |

Build/release automation may assemble an unsigned release variant when no private signing configuration is present. Such an APK is not a downloadable installer. A debug-signed preview must be labelled explicitly; changing signing certificates can require a data-destructive reinstall, so export data first.

## Beacon discovery candidate (0.4.0-rc.3)

The receiver recognises the original rc.2 payload. The new transmitter retains that payload and adds the UUID list in a scan response. Use the second-device procedure in [hardware validation](HARDWARE.md) to check actual reception, RSSI, screen-off operation and Stop. The results below belong to rc.2.

## Published rc.2 evidence

Local candidate checks on 2026-11-15 (JDK 17, SDK 36, Gradle 8.13):

- Core: 272 tests passed; app JVM: 52 tests passed.
- Android lint and debug/release assemblies passed. The release assembly is unsigned.
- All 857 Android string resources are present in EN/NL/FR with matching format arguments.
- Public-source audit and its 9 regression tests passed; clean-export manifest hashes verified.
- Android API 28: focused native appearance and screenshot checks passed, including
  rendered button colours after repeated refreshes and enable/disable transitions.
  Published screenshots were captured from native activities and visually reviewed.

The original APK build commit is recorded in `BUILD-INFO.txt`. The initial
public repository imports its clean application source with updated publication
documentation/workflow metadata; its import commit is distinct from that build.
The Android workflow runs API 26/35 instrumentation, and automated publication
requires the build and device-validation gates to succeed.

The source includes automated coverage for the continuous beacon, radio lifecycle, language formatting and theme contrast. Test counts and command outcomes must be tied to the matching build. Screenshots demonstrate the running interface, not transmission.

Physical reception, long sessions, manufacturer battery restrictions and API 29/30 background recording require suitable hardware and remain unverified until an explicit release record says otherwise. See the [acceptance matrix](TEST_MATRIX.md) and [hardware procedure](HARDWARE.md).

## Fixtures and historical records

The two public ZIP fixtures are intentionally synthetic and retained unchanged. [The run fixture](../examples/reports/android-dry-run.zip) exercises saved run/export compatibility; [the recording fixture](../examples/capture-fixtures/synthetic-nano-120.zip) exercises capture import/review. Neither proves present-build radio reception.

Historical validation reports and old screenshots stay in the private preparation archive and are excluded from the clean public export. They are not rewritten or presented as checks of the new version. See [publication preparation](PUBLICATION.md).
