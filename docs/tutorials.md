# Tutorials

[Download APK](https://github.com/benjaminvdz/openvespatrack-ble-lab/releases) · [Installation](INSTALLATION.md) · [Nederlands](tutorials-nl.md) · [Français](tutorials-fr.md)

## Use a phone as a test beacon

**You need:** one Android phone that supports BLE advertising and a separate
BLE receiver, such as another phone or BLE Tagfinder on a computer.

1. Open **Settings & about** and choose your language and appearance.
2. Open **Test beacon**. Note its `OVT-` identity.
3. Enable Bluetooth, choose **Start beacon** and grant the requested permissions.
   Allow notifications so Stop remains easy to reach.
4. On a second device running the newer BLE Lab, select **Record & replay → OpenVespaTrack test beacons → Find devices** and match the `OVT-` identity. In 0.4.0-rc.2 or another scanner, select **all BLE devices** with no name filter and inspect **Service Data** for UUID `6f8a5b00-7d3e-4c6a-9b21-0a5c2d7e9101`. The `OVT-` identity is encoded in this data.
5. Confirm reception nearby. Leave the phone at a known test point and move
   the receiver to compare reception. Note obstacles, orientation and distance.
6. Turn the transmitting phone's screen off for a trial. Check continued
   reception on the other device; do not infer it from the sender's status.
7. Finish with **Stop** in the app or notification and verify that the receiver
   stops seeing fresh observations.

There is no scenario timer in this mode. Android can still interrupt it after
Bluetooth-off, permission loss, force-stop, reboot or battery restrictions.
It never resumes automatically after process restart. RSSI is reception
strength, not a measured distance or direction.

If Stop cannot be confirmed, retry **Stop** or turn Bluetooth off. The app
keeps the radio reserved until cleanup is confirmed; do not treat that state
as proven silence.

## Try the supplied example

Download the [synthetic recording ZIP](../examples/capture-fixtures/synthetic-nano-120.zip).
Open **Record & replay**, import the ZIP from the recordings archive, review
the preview and save a copy. Inspect the timeline and export it again without
starting BLE. Its 120 observations and RSSI pattern are generated test data,
including two deliberately invalid RSSI values; they are not a real tag capture.

## Make and keep a recording

1. Stop the beacon or any other radio test.
2. Open **Record & replay**, choose an appropriate filter, search and select the
   intended recently seen device.
3. Name the recording, add notes and start recording that device.
4. Stop after the measurement. Open the saved recording to inspect original
   bytes, measured RSSI and diagnostic events.
5. Export a **ZIP** through Android's file picker and keep it outside the app.

Reviewing or playing a saved timeline is offline. It sends no signal.
Back up important data before uninstalling or changing signing certificates.

## Test replay safely and reproducibly

From a stopped recording, prepare one supported advertisement or a selected
series. Review the preview and save the replay profile. Run it without radio
first. Actual transmission needs a separate explicit BLE start.

Replay reconstructs supported advertisement content with new fixed slots.
It does not copy the original transmitter address, RF timing, received RSSI or
GATT services. Keep its exported source evidence when comparing results.

## Explore without an adapter

Open the **Test** tab, choose a synthetic profile and select **Start dry-run**.
The app executes the planned scenario without Bluetooth. Keep the app visible;
use Pause, Resume and Stop to inspect its behaviour. The scenario graph is
synthetic payload data and must not be reported as a physical measurement.
