# Public source and releases

The public Android component is
[OpenVespaTrack BLE Lab](https://github.com/benjaminvdz/openvespatrack-ble-lab),
part of [OpenVespaTrack](https://www.anthrobiosnexus.com/projects/openvespatrack/)
by AnthroBios Nexus. Download installers from its
[GitHub Releases](https://github.com/benjaminvdz/openvespatrack-ble-lab/releases).

## Public source boundary

This repository starts from a reviewed clean source snapshot. It contains
application code, synthetic regression fixtures, documentation, licensed fonts
and actual app screenshots. Private development history, historical validation
archives and unrelated captures are not part of this repository.

Run `python3 scripts/publication_audit.py` before release. The audit checks
the proposed source set for private research references, obvious credentials,
unreviewed archives and missing local documentation links. It does not rewrite
profiles, recordings, test evidence or Git history.

The two reviewed synthetic ZIP fixtures in `examples/reports/` and
`examples/capture-fixtures/` remain unchanged because they support regression
tests and tutorials. Their text members are inspected by the audit.

Technical identifiers such as protocol UUIDs, `0xFC65`, name prefixes, schema
keys, decoder IDs and stored enum values are compatibility data. Keep them
correct even when presentation labels change. Existing user evidence is not
rewritten merely to update terminology.

## Audited source attachment

Create a separate source snapshot for a release with:

```sh
python3 scripts/publication_audit.py --export /absolute/path/to/new-source-export
```

The destination must not exist. Export runs only after a successful audit,
copies the selected files without Git metadata and writes
`publication-manifest.json` with each file's SHA-256 and length.

The manifest is a generated release-archive artifact. Do not commit it as a
source file or reuse its hashes after changing the exported contents. The
named `openvespatrack-ble-lab-<version>-source.zip` attachment includes this
manifest. GitHub's automatic Source code ZIP/tar downloads contain the public
tag tree and do not add that generated manifest.

An export operation does not create a GitHub repository, publish a release or
change repository visibility. Do not import unrelated development history or
private device evidence into the public repository.

## APK identity and provenance

A release must attach a signed, installable APK, `BUILD-INFO.txt` and
`SHA256SUMS.txt`. An unsigned assembly or expiring Actions artifact does not
replace an installer.

Keep application ID `org.anthrobios.vespasim` for data identity. An in-place
upgrade also requires a matching signing certificate. Private signing keys
belong outside Git. Development-signed previews must be labelled explicitly;
their certificate fingerprint belongs in the build information. Export user
data before any deliberate reinstall across certificates.

The initial public 0.4.0-rc.2 APK retains the original validated build and
signing certificate. Its `BUILD-INFO.txt` preserves that build's source commit.
The public source snapshot preserves its application code; publication
documentation and workflow metadata can differ. The public import commit is
therefore not a claim that the existing APK was rebuilt from that commit.

## Release checks

Before publishing a new version:

- Record the tag, source commit, version code/name, minimum Android version,
  APK SHA-256 and signing certificate fingerprint.
- Verify the exact APK with `apksigner` and install/launch that file on an
  available device or emulator.
- Pass unit tests, lint, the public-source audit and available Android
  instrumentation. Keep build and device validation as release gates.
- Review real screenshots in both appearances and the supported languages.
  Use synthetic data and record capture conditions.
- Report independent physical beacon reception, screen-off duration and Stop
  tests separately. Mark unavailable hardware tests as not run.
- Confirm the public release and APK download in a signed-out session.
  Check documentation against the published UI and asset filenames.

The audit is a bounded review aid, not a guarantee that every secret or private
identifier has been detected. Review changes and archives before publishing.
