# Synthetic recording fixture

`synthetic-nano-120.zip` is a historical Android-exported test archive. It
contains **no real tag measurement**: 120 generated name advertisements, two
diagnostic records and an artificial RSSI pattern. Two RSSI values are
deliberately 127; the chart should leave gaps. The address
`AA:BB:CC:DD:EE:FF` and name `vn0CqrvM3e7/` are synthetic examples.

In **Record & replay**, open the recordings archive and import this ZIP.
Review the preview before saving a copy. Inspect the graph, the final twenty
observations and export/profile preparation. Import and offline playback do
not start radio activity. Replay needs a separate explicit start and does not
copy the original radio address, received RSSI or RF timing.

SHA-256: `610217c07a75d35f97a82a34da37ef133f762a4c35eb616d901e9af356cb425b`.

The archive is retained unchanged for regression tests. Historical creation
or import checks are not evidence that the current version has passed a
physical-radio test. See [testing](../../docs/TESTING.md) and
[export formats](../../docs/EXPORTS.md).
