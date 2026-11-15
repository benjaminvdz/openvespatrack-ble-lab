# Release screenshots

These PNGs are direct captures of the running native **0.4.0-rc.2** app on
2026-11-15, Android API 28, 1080 × 1920 pixels at 420 dpi. They were produced
by `ScreenshotCaptureTest` and reviewed visually. No generated artwork or
composited UI is used.

| Filename | Content |
| --- | --- |
| `beacon-dark.png` | Idle test beacon, dark, English |
| `beacon-light.png` | Idle test beacon, light, Dutch |
| `workspace-light.png` | Loaded simulation workspace, light, English; no run started |
| `capture-dark.png` | Idle recording workspace, dark, English |
| `settings-language.png` | Appearance settings with language menu open, light, French |

The synthetic beacon ID belongs only to the test installation. Start and Scan
were not pressed, and the displayed idle state is not a physical radio test.

The captures were reviewed during preparation of the original rc2 application
build. They are preserved unchanged in the clean public source snapshot.
`BUILD-INFO.txt` accompanying the
[rc2 release](https://github.com/benjaminvdz/openvespatrack-ble-lab/releases/tag/v0.4.0-rc.2)
identifies the original APK build commit; the public source import has a
separate commit. No screenshot is presented as a new physical-radio result
merely because the project was published.

Only the current release screenshots are included here. Historical development
captures remain outside the public source repository.
