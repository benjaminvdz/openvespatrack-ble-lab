# OpenVespaTrack BLE Lab 0.4.0-rc.2

An Android component of
[OpenVespaTrack](https://www.anthrobiosnexus.com/projects/openvespatrack/)
by AnthroBios Nexus.

This preview introduces a continuous test beacon with a persistent synthetic
identity, explicit Start and Stop, and a foreground notification with Stop.
Timed simulations and recording/replay remain separate modes.

The interface includes AnthroBios Nexus light, dark and system appearances,
bundled IBM Plex fonts, and English, Dutch and French translations. Installation
guides, tutorials and actual app screenshots are included in the source. Button
state colours stay consistent when screens refresh, and navigation labels wrap
on narrow displays.

[Download the Android preview APK](https://github.com/benjaminvdz/openvespatrack-ble-lab/releases/download/v0.4.0-rc.2/openvespatrack-ble-lab-0.4.0-rc.2-preview.apk).
`BUILD-INFO.txt` records its original build commit, version and signing
certificate; verify the downloaded files with `SHA256SUMS.txt`.

The named `openvespatrack-ble-lab-0.4.0-rc.2-source.zip` attachment is an audited
source export with a per-file SHA-256 manifest. GitHub's automatic **Source code**
ZIP/tar downloads contain the clean public tag tree without that generated
manifest.

This initial public release preserves the validated rc2 APK unchanged. Its
original build commit remains in `BUILD-INFO.txt`; the public import has its
own commit and updates publication documentation/workflow metadata while
preserving application source.

The APK uses a development signing certificate. It is installable, but a
previous or later build may use a different certificate. Export important data
before a deliberate reinstall; no stable production update chain is promised.

The beacon has no scenario deadline. Android controller acceptance is not proof
of reception. Physical receiver checks, prolonged screen-off operation and OEM
battery-management behaviour still require testing on supported hardware.
Bluetooth-off, force-stop, reboot or process termination can interrupt a session.
The app never automatically restarts radio activity after process death.
