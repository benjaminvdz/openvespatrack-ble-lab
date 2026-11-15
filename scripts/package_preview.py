#!/usr/bin/env python3
"""Package a verified development-signed preview; never publish unsigned APKs."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

root = Path(__file__).resolve().parents[1]
metadata = json.loads((root / "app/build/outputs/apk/debug/output-metadata.json").read_text())
version = metadata["elements"][0]["versionName"]
if not version or any(c not in "0123456789abcdefghijklmnopqrstuvwxyz.-" for c in version):
    raise SystemExit("Unexpected version")
dist = root / "dist"
dist.mkdir(exist_ok=True)
apk = dist / f"openvespatrack-ble-lab-{version}-preview.apk"
shutil.copy2(root / "app/build/outputs/apk/debug/app-debug.apk", apk)
sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
certificate = subprocess.check_output([str(sdk / "build-tools/36.0.0/apksigner"),
    "verify", "--verbose", "--print-certs", str(apk)], text=True)
with tempfile.TemporaryDirectory() as temp:
    source = Path(temp) / "source"
    subprocess.run(["python3", str(root / "scripts/publication_audit.py"), "--export", str(source)], check=True, cwd=root)
    archive = dist / f"openvespatrack-ble-lab-{version}-source.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as output:
        for path in sorted(source.rglob("*")):
            if path.is_file():
                output.write(path, Path(f"openvespatrack-ble-lab-{version}") / path.relative_to(source))
(dist / "BUILD-INFO.txt").write_text(
    f"OpenVespaTrack BLE Lab {version}\n"
    f"Source commit: {os.environ.get('VESPA_GIT_COMMIT', 'unknown')}\n"
    f"Version code: {metadata['elements'][0]['versionCode']}\n"
    "Minimum Android: 8.0 / API 26\n"
    "Distribution: development-signed preview (not a production signing key).\n"
    "An update requires the same signing certificate; back up before reinstalling.\n"
    "Physical BLE reception and long-running screen-off tests remain required.\n\n" + certificate,
    encoding="utf-8")
(dist / "SHA256SUMS.txt").write_text("".join(
    f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n"
    for path in sorted(dist.iterdir()) if path.is_file() and path.name != "SHA256SUMS.txt"), encoding="utf-8")
print(f"Prepared {apk.name}, audited source, signing fingerprint and checksums in dist/")
