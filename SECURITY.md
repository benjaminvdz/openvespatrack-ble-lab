# Security and private reports

Use [the AnthroBios Nexus contact page](https://www.anthrobiosnexus.com/get-in-touch/)
to report a suspected vulnerability. Include the affected release and safe
reproduction steps. Do not include signing keys, credentials or unredacted
recordings in public issues.

The app accepts profile JSON and its own bounded recording-ZIP format.
Unsupported schemas and malformed archives should fail visibly. Import must
not execute HTML or start radio activity. Reports must escape user text.

Recordings remain local until explicit export. Exports can contain radio
addresses, device names, timestamps, phone metadata and notes. Checksums provide
integrity checks, not proof of a tag's identity or authentic RF reception.

Keep signing keys outside Git. If a secret is disclosed, rotate it; deleting
its latest file does not remove it from history.

This is a release-candidate research tool. Describe failures with their observed
conditions and distinguish controller acceptance from independent reception.
