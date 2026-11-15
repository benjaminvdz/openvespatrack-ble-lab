# Installation, updates and backup

## Requirements

Android 8.0/API 26 or later is required. Live scanning and advertising need suitable Bluetooth hardware; the Android version alone does not establish advertising, extended advertising or Coded PHY support.

The app works locally without an account or network connection. Dry-runs and saved-recording review do not need a Bluetooth adapter.

## Install a release APK

1. Open [GitHub Releases](https://github.com/benjaminvdz/openvespatrack-ble-lab/releases) and select a release. Check its preview status.
2. Download the signed APK and checksum file. Source-code ZIPs and unsigned APKs are not installers.
3. Compare the APK's SHA-256 with the exact filename in the checksum file. A checksum detects changed bytes; it is not a publisher signature.
4. Open the APK. Android may ask to permit installation from the browser or file manager used for that action.
5. Launch **OpenVespaTrack BLE Lab**. In **Settings & about**, choose English, Dutch or French and light, dark or system appearance.

Keep Android's security protections enabled. A managed phone may require administrator approval for externally distributed previews.

## Update an existing installation

The app identity is `org.anthrobios.vespasim`. The visible name can change without changing this identity. Android also requires a matching signing certificate and a suitable version code for an update.

Development APKs from different build environments can have different certificates. If Android rejects an update, keep the old installation until important data has been exported and checked.

Before a deliberate reinstall, save these outside the app:

- Profiles as JSON.
- Important device recordings as recording ZIPs.
- Important timed test sessions as research ZIPs.

Uninstalling or clearing app data removes the private archive. Profiles and recording ZIPs can be imported again. A research ZIP preserves a timed run's evidence; it is not imported back as editable run history.

## Permissions

| Feature | Permission context |
| --- | --- |
| Test beacon and timed transmission | Nearby-device advertising; connection access where required to inspect adapter state |
| Recording and discovery | Bluetooth scan/connect and Android's required location permissions/settings for complete beacon scanning |
| Foreground Stop notification | Notification permission on Android 13 or later |
| Import/export | Access to the file or destination selected in Android's document picker |

Recording permissions do not mean that the app records GPS positions. There is no broad storage permission or automatic upload.

If notification permission is denied, Android can hide the foreground notification from the drawer. Use the in-app Stop control. Grant notification permission for convenient access to Stop.

## Troubleshooting

**Beacon does not start:** stop any recording or timed radio test, enable Bluetooth, grant the requested permissions and check advertising support.

**Another receiver cannot see it:** first check that the transmitting screen says **Active · controller accepted**. Keep both devices close and start a foreground scan in **Record & replay → OpenVespaTrack test beacons** in newer BLE Lab builds. In 0.4.0-rc.2 or another BLE scanner, use **all BLE devices** with an empty name/address filter and look under **Service Data** for UUID `6f8a5b00-7d3e-4c6a-9b21-0a5c2d7e9101`. The displayed `OVT-` identity is not a Bluetooth device name. A generic Bluetooth pairing list or a tag-specific filter will not reliably show it.

**Radio stopped after a system action:** Bluetooth-off, permission revocation, force-stop, reboot, process termination and manufacturer battery restrictions can interrupt it. Inspect the state and explicitly start a new test.

**A legacy profile left a temporary Bluetooth name:** enable Bluetooth and use the explicit name-recovery action. Preserve app data while its original-name recovery record is needed. A later user-set name is not overwritten.

[English](tutorials.md) · [Nederlands](tutorials-nl.md) · [Français](tutorials-fr.md)
