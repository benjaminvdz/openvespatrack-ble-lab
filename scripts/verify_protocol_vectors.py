#!/usr/bin/env python3
"""Check the shared FC65 vectors through the real BLE Tagfinder HCI importer/decoder.

The Kotlin unit test first checks Protocol.payload against these same fixed vectors.
This script does NOT reimplement the decoder and does NOT claim an RF hardware test.
Python standard library only. All generated captures/databases live in a temporary dir.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import zipfile


BTSNOOP_UNIX_OFFSET_US = 0x00DCDDB30F2F8000


def hci_capture(vectors: dict) -> bytes:
    """Produce standard UART btsnoop containing one legacy AD report per vector."""
    output = bytearray(b"btsnoop\0" + struct.pack(">II", 1, 1002))
    for i, case in enumerate(vectors["vectors"]):
        payload = bytes.fromhex(case["payloadHex"])
        manufacturer = struct.pack("<H", vectors["companyId"]) + payload
        ad = bytes([len(manufacturer) + 1, 0xFF]) + manufacturer
        assert len(ad) <= 31, f"{case['id']}: this test fixture uses legacy advertising"
        # One non-connectable legacy report, a synthetic random address and local RSSI.
        parameters = bytes([2, 1, 3, 1]) + bytes([6, 5, 4, 3, 2, 1])
        parameters += bytes([len(ad)]) + ad + bytes([vectors["localRssi"] & 0xFF])
        event = bytes([4, 0x3E, len(parameters)]) + parameters
        timestamp = BTSNOOP_UNIX_OFFSET_US + 1_700_000_000_000_000 + i * 500_000
        output += struct.pack(">IIIIQ", len(event), len(event), 1, 0, timestamp) + event
    return bytes(output)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=Path, help="Built ble-tagfinder executable")
    parser.add_argument("--fixture", type=Path,
                        default=Path(__file__).resolve().parents[1] / "fixtures/protocol-vectors.json")
    parser.add_argument("--report", type=Path, help="Optional new JSON result file (no overwrite)")
    parser.add_argument("--name-profile", type=Path,
                        default=Path(__file__).resolve().parents[1] / "examples/nano-name-recognition.json")
    parser.add_argument("--nano-profile", type=Path,
                        default=Path(__file__).resolve().parents[1] / "examples/nano-direct-vn.json")
    args = parser.parse_args()
    binary = args.binary.resolve(strict=True)
    fixture_bytes = args.fixture.read_bytes()
    fixture = json.loads(fixture_bytes)
    require(fixture["schemaVersion"] == 1, "Unsupported fixture version")
    cases = fixture["vectors"]
    require(len(cases) > 0, "No protocol vectors supplied")
    require(len({case["id"] for case in cases}) == len(cases), "Duplicate vector IDs")
    capture = hci_capture(fixture)

    with tempfile.TemporaryDirectory(prefix="vespa-cross-protocol-") as temporary:
        work = Path(temporary)
        db = work / "vectors.sqlite3"
        source = work / "synthetic-vectors.btsnoop"
        bundle = work / "evidence.zip"
        source.write_bytes(capture)

        def cli(*arguments: str) -> str:
            result = subprocess.run([str(binary), "--database", str(db), *arguments],
                                    text=True, capture_output=True, timeout=30, check=False)
            if result.returncode:
                raise RuntimeError(f"BLE Tagfinder failed ({result.returncode}): {result.stderr}")
            return result.stdout

        imported = json.loads(cli("import-btsnoop", str(source), "--name", "SYNTHETIC Android FC65 vectors"))
        require(imported["observations"] == len(cases), "HCI import dropped a vector")
        session_id = imported["session_id"]
        replay_text = cli("replay", session_id)
        require(replay_text == cli("replay", session_id), "Decoder replay is not deterministic")
        replay = [json.loads(line) for line in replay_text.splitlines()]
        require(len(replay) == len(cases), "Replay record count differs")
        for index, (case, row) in enumerate(zip(cases, replay), 1):
            prefix = f"{case['id']}: "
            require(row["sequence"] == index, prefix + "observation order changed")
            results = [result for result in row["results"] if result["decoder"] == "vespa-finder-fc65"]
            require(len(results) == 1, prefix + "expected exactly one FC65 interpretation")
            result = results[0]
            expected = case["expectedDecode"]
            require(result["kind"] == expected["kind"], prefix + f"kind {result['kind']} != {expected['kind']}")
            fields = result["fields"]
            require(fields["payload_hex"] == case["payloadHex"].lower(), prefix + "original payload changed")
            require(fields["company_id"] == fixture["companyId"], prefix + "wrong manufacturer-ID")
            require(fields["local_rssi_dbm"] == fixture["localRssi"], prefix + "local RSSI was replaced")
            require(result["warnings"], prefix + "protocol uncertainty was not preserved")
            for name, value in expected["fields"].items():
                require(name in fields, prefix + f"missing field {name}")
                require(fields[name] == value, prefix + f"{name}: {fields[name]!r} != {value!r}")

        cli("export", session_id, str(bundle))
        with zipfile.ZipFile(bundle) as archive:
            require(archive.read("captures/original.btsnoop") == capture, "Source capture was not preserved")
            manifest = json.loads(archive.read("manifest.json"))
            require(manifest["observation_count"] == len(cases), "Wrong exported observation count")
            for member in manifest["members"]:
                content = archive.read(member["name"])
                require(len(content) == member["bytes"], f"Wrong member length: {member['name']}")
                require(hashlib.sha256(content).hexdigest() == member["sha256"],
                        f"Checksum mismatch: {member['name']}")
            observations = [json.loads(line) for line in archive.read("observations.jsonl").splitlines()]
            require(len(observations) == len(cases), "Exported observation records are missing or duplicated")
            for case, observation in zip(cases, observations):
                raw = observation["manufacturer_data"][str(fixture["companyId"])]
                require(bytes(raw).hex() == case["payloadHex"].lower(), case["id"] + ": stored payload changed")
        # A separate name-only advertising probe must not acquire FC65 semantics.
        profile_bytes = args.name_profile.read_bytes()
        profile = json.loads(profile_bytes)
        require(all(track["kind"] == "NAME_ONLY" for track in profile["tracks"]),
                "Recognition profile must contain name-only tracks")
        local_name = profile["radio"]["localName"]
        name_bytes = local_name.encode("utf-8")
        name_ad = bytes([len(name_bytes) + 1, 0x09]) + name_bytes
        require(len(name_ad) <= 31, "Name-only probe must fit legacy advertising")
        params = bytes([2, 1, 3, 1]) + bytes([16, 15, 14, 13, 12, 11])
        params += bytes([len(name_ad)]) + name_ad + bytes([186])
        name_event = bytes([4, 0x3E, len(params)]) + params
        name_capture = b"btsnoop\0" + struct.pack(">II", 1, 1002)
        name_capture += struct.pack(">IIIIQ", len(name_event), len(name_event), 1, 0,
                                    BTSNOOP_UNIX_OFFSET_US + 1_700_000_000_000_000) + name_event
        name_source = work / "synthetic-name-only.btsnoop"
        name_source.write_bytes(name_capture)
        name_session = json.loads(cli("import-btsnoop", str(name_source), "--name",
                                      "SYNTHETIC Nano name recognition only"))
        require(name_session["observations"] == 1, "Name-only report was dropped")
        name_replay_text = cli("replay", name_session["session_id"])
        require(name_replay_text == cli("replay", name_session["session_id"]),
                "Name-only replay is not deterministic")
        name_results = json.loads(name_replay_text)["results"]
        require(len(name_results) == 1 and name_results[0]["kind"] == "candidate"
                and name_results[0]["decoder"] == "hoornaar-spotter-recognition",
                "Name-only probe must remain a name-recognition candidate, not an FC65 tag")
        require(name_results[0]["fields"]["name_match"] is True
                and name_results[0]["fields"]["uuid_match"] is False,
                "Name-only recognition must not invent service UUIDs")
        name_bundle = work / "name-only-evidence.zip"
        cli("export", name_session["session_id"], str(name_bundle))
        with zipfile.ZipFile(name_bundle) as archive:
            observation = json.loads(archive.read("observations.jsonl"))
            require(observation["name"] == local_name, "Advertised local name changed")
            require(observation["manufacturer_data"] == {}, "Invented manufacturer payload")
            require(observation["service_data"] == {}, "Invented service payload")
            require(observation["rssi"] == -70, "Name-only local RSSI changed")
            require(archive.read("captures/original.btsnoop") == name_capture,
                    "Original name-only capture was not preserved")
            for member in json.loads(archive.read("manifest.json"))["members"]:
                content = archive.read(member["name"])
                require(len(content) == member["bytes"]
                        and hashlib.sha256(content).hexdigest() == member["sha256"],
                        "Name-only export checksum mismatch")

        nano_profile_bytes = args.nano_profile.read_bytes()
        nano_profile = json.loads(nano_profile_bytes)
        require(nano_profile["radio"]["localName"] == "vn0CqrvM3e7/",
                "Direct Nano preset must use the independently calculated example name")
        require(all(t["kind"] == "NAME_ONLY" for t in nano_profile["tracks"]),
                "Direct Nano preset must not add FC65 payloads")
        nano_cases = [
            ("vn00AAAAAAAA", "00:00:00:00:00:00", 0),
            ("vnFFAAAAAAAB", "00:00:00:00:00:01", 255),
            ("vnABAQIDBAUG", "01:02:03:04:05:06", 171),
            ("vn0CqrvM3e7/", "AA:BB:CC:DD:EE:FF", 12),
            ("vnFF////////", "FF:FF:FF:FF:FF:FF", 255),
        ]
        nano_capture = bytearray(b"btsnoop\0" + struct.pack(">II", 1, 1002))
        for index, (name, _, _) in enumerate(nano_cases):
            encoded = name.encode("ascii")
            ad = bytes([len(encoded) + 1, 0x09]) + encoded
            params = bytes([2, 1, 3, 1]) + bytes([16, 15, 14, 13, 12, 11])
            params += bytes([len(ad)]) + ad + bytes([186])
            event = bytes([4, 0x3E, len(params)]) + params
            nano_capture += struct.pack(">IIIIQ", len(event), len(event), 1, 0,
                BTSNOOP_UNIX_OFFSET_US + 1_700_000_000_000_000 + index * 500_000) + event
        nano_source = work / "synthetic-vn-names.btsnoop"
        nano_source.write_bytes(nano_capture)
        nano_session = json.loads(cli("import-btsnoop", str(nano_source), "--name", "SYNTHETIC vn identity vectors"))
        require(nano_session["observations"] == len(nano_cases), "Nano identity report lost")
        nano_replay = cli("replay", nano_session["session_id"])
        require(nano_replay == cli("replay", nano_session["session_id"]), "Nano replay changed")
        nano_rows = list(map(json.loads, nano_replay.splitlines()))
        require(len(nano_rows) == len(nano_cases), "Nano replay record count changed")
        for case, row in zip(nano_cases, nano_rows):
            name, logical_address, parameter = case
            decoded = [r for r in row["results"] if r["decoder"] == "hoornaar-nano-name"]
            require(len(decoded) == 1 and decoded[0]["kind"] == "nano_name", "Nano name not decoded")
            fields = decoded[0]["fields"]
            require(fields["original_name"] == name, "Nano name not preserved")
            require(fields["logical_tag_address"] == logical_address, "Logical Nano identity changed")
            require(fields["parameter"] == parameter and fields["parameter_meaning"] is None,
                    "Nano parameter changed or acquired invented semantics")
            require(fields["radio_address"] != logical_address and fields["local_rssi_dbm"] == -70,
                    "Logical identity/radio address or local RSSI was conflated")
            require(not any(r["decoder"] == "vespa-finder-fc65" for r in row["results"]),
                    "Direct Nano name acquired an invented FC65 payload")
        nano_bundle = work / "nano-identity-evidence.zip"
        cli("export", nano_session["session_id"], str(nano_bundle))
        with zipfile.ZipFile(nano_bundle) as archive:
            require(archive.read("captures/original.btsnoop") == nano_capture, "Nano capture changed")
            rows = [json.loads(line) for line in archive.read("observations.jsonl").splitlines()]
            require(len(rows) == len(nano_cases), "Nano export count changed")
            require(all(not row["manufacturer_data"] and not row["service_data"] for row in rows),
                    "Nano direct export invented payload data")

        report = {
            "passed": True,
            "vectors": len(cases),
            "fixtureSha256": hashlib.sha256(fixture_bytes).hexdigest(),
            "tagfinderBinarySha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
            "tagfinderVersion": cli("--version").strip(),
            "nanoDirectNameVectors": len(nano_cases),
            "nanoDirectPresetSha256": hashlib.sha256(nano_profile_bytes).hexdigest(),
            "nameOnlyRecognition": {
                "name": local_name, "passed": True,
                "profileSha256": hashlib.sha256(profile_bytes).hexdigest(),
                "manufacturerDataAbsent": True, "serviceDataAbsent": True,
                "nanoFirmwareEmulated": False,
            },
            "checks": ["UART btsnoop import", "FC65 payload decoding", "original byte preservation",
                       "RSSI provenance", "deterministic replay", "original capture attachment", "ZIP checksums"],
            "hardwareValidated": False,
            "note": "Synthetic cross-implementation evidence; Kotlin Protocol.payload is verified by the core unit test.",
        }
        encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.report:
            with args.report.open("x", encoding="utf-8") as destination:
                destination.write(encoded)
        print(encoded, end="")


if __name__ == "__main__":
    main()
