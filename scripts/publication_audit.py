#!/usr/bin/env python3
"""Audit and optionally export a clean source snapshot; never rewrite Git history."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import zipfile


ROOT_FILES = {
    ".gitattributes", ".gitignore", "AGENTS.md", "LICENSE", "NOTICE", "README.md",
    "CONTRIBUTING.md", "SECURITY.md", "build.gradle.kts", "settings.gradle.kts",
    "gradle.properties", "gradlew", "gradlew.bat",
}
ROOT_DIRS = {".github", "app", "core", "docs", "examples", "fixtures", "gradle", "scripts"}
EXCLUDED_DIRS = {".git", ".gradle", ".kotlin", ".idea", "build", "dist", "__pycache__"}
EXCLUDED_PREFIXES = ("docs/internal-archive/", "docs/validation/", "captures/", "private-profiles/")
OLD_SCREENSHOTS = {
    "docs/screenshots/capture-v030.png", "docs/screenshots/capture-replay-v030.png",
    "docs/screenshots/repeater-chart.png", "docs/screenshots/session-export.png",
}
REVIEWED_ZIPS = {
    "examples/reports/android-dry-run.zip",
    "examples/capture-fixtures/synthetic-nano-120.zip",
}
SELF_PATTERN_FILES = {"scripts/publication_audit.py", "scripts/test_publication_audit.py"}
TEXT_SUFFIXES = {".md", ".txt", ".kt", ".kts", ".xml", ".json", ".jsonl", ".py",
                 ".properties", ".pro", ".yml", ".yaml", ".sh", ".bat", ".csv", ".html"}
PRIVATE_REFERENCE = re.compile(
    r"\bBrain\b|\bHoornaar[ -]?Spotter\b|\bRange[ -]?Pro\b|\bVespa[ -]?Finder\b"
    r"|github\.com/benjaminvdz/ble-tagfinder(?:[/\s#?\"')]|$)", re.I
)
SECRET_PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
)
MAX_SOURCE_BYTES = 16 * 1024 * 1024
MAX_ZIP_BYTES = 32 * 1024 * 1024
MAX_ZIP_MEMBERS = 70
MANIFEST = "publication-manifest.json"


def excluded(name: str) -> bool:
    path = PurePosixPath(name)
    return (bool(set(path.parts) & EXCLUDED_DIRS) or name.startswith(EXCLUDED_PREFIXES)
            or name in OLD_SCREENSHOTS or name == MANIFEST
            or path.name == "local.properties" or path.suffix in {".pyc", ".log", ".iml"})


def candidate_names(root: Path) -> list[str]:
    # Git determines the intentional working source set; ignored local artifacts stay local.
    if (root / ".git").exists():
        raw = subprocess.check_output(
            ["git", "-C", str(root), "ls-files", "--cached", "--others", "--exclude-standard", "-z"]
        )
        return sorted(set(os.fsdecode(item) for item in raw.split(b"\0") if item))
    result = []
    for current, dirs, files in os.walk(root, followlinks=False):
        dirs[:] = sorted(d for d in dirs if d not in EXCLUDED_DIRS)
        for name in sorted(files):
            result.append((Path(current) / name).relative_to(root).as_posix())
        for name in dirs:
            path = Path(current) / name
            if path.is_symlink():
                result.append(path.relative_to(root).as_posix())
    return sorted(set(result))


def inspect_text(name: str, text: str, findings: list[str]) -> None:
    # These exact decoder IDs are serialized compatibility data, not prose labels.
    prose = text.replace("vespa-finder-fc65", "COMPAT_DECODER").replace("hoornaar-spotter-recognition", "COMPAT_DECODER")
    if name not in SELF_PATTERN_FILES and PRIVATE_REFERENCE.search(prose):
        findings.append(f"{name}: private or unreviewed research reference")
    if any(pattern.search(text) for pattern in SECRET_PATTERNS):
        findings.append(f"{name}: possible credential or private key")


def inspect_zip(path: Path, name: str, findings: list[str]) -> None:
    try:
        with zipfile.ZipFile(path) as archive:
            members = archive.infolist()
            if len(members) > MAX_ZIP_MEMBERS or sum(m.file_size for m in members) > MAX_ZIP_BYTES:
                findings.append(f"{name}: archive exceeds review limits")
                return
            seen = set()
            for member in members:
                inner = PurePosixPath(member.filename)
                label = f"{name}!{member.filename}"
                if (member.filename in seen or inner.is_absolute() or ".." in inner.parts
                        or "\\" in member.filename or ":" in member.filename
                        or stat.S_ISLNK(member.external_attr >> 16) or member.flag_bits & 1):
                    findings.append(f"{name}: unsafe, duplicate, linked or encrypted archive member")
                    continue
                seen.add(member.filename)
                if member.is_dir():
                    continue
                data = archive.read(member)
                try:
                    inspect_text(label, data.decode("utf-8"), findings)
                except UnicodeDecodeError:
                    findings.append(f"{label}: binary archive member requires review")
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as error:
        findings.append(f"{name}: unreadable archive ({type(error).__name__})")


def inspect_markdown_links(name: str, text: str, selected: set[str], findings: list[str]) -> None:
    # Check file links, excluding web links, fragments and GitHub's release route.
    targets = re.findall(r"!?\[[^\]\n]*\]\(([^)\n]+)\)", text)
    targets.extend(re.findall(r'''(?:src|href)\s*=\s*["']([^"']+)["']''', text, re.I))
    for value in targets:
        target = value.split(' "', 1)[0].strip("<>")
        if (not target or target.startswith(("#", "https://", "http://", "mailto:"))
                or target.startswith("../../releases")):
            continue
        target = target.split("#", 1)[0]
        parts = []
        unsafe = False
        for part in (PurePosixPath(name).parent / target).parts:
            if part == "..":
                if parts:
                    parts.pop()
                else:
                    unsafe = True
            elif part != ".":
                parts.append(part)
        resolved = "/".join(parts)
        if unsafe or not (resolved in selected or any(p.startswith(resolved.rstrip("/") + "/") for p in selected)):
            findings.append(f"{name}: local link is absent from public source: {target}")


def audit(root: Path) -> tuple[list[str], list[str], list[str]]:
    selected, omitted, findings = [], [], []
    for name in candidate_names(root):
        if excluded(name):
            omitted.append(name)
            continue
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts or "\\" in name:
            findings.append("Source set contains an unsafe path")
            continue
        if ((len(path.parts) == 1 and name not in ROOT_FILES)
                or (len(path.parts) > 1 and path.parts[0] not in ROOT_DIRS)):
            findings.append(f"{name}: file is outside the reviewed public source locations")
            continue
        if path.suffix.lower() in {".jks", ".keystore", ".pem", ".key", ".p12", ".pfx", ".apk", ".aab"} or path.name.startswith(".env"):
            findings.append(f"{name}: secret/signing/binary-distribution file cannot enter source export")
            continue
        local = root / name
        if local.is_symlink() or any(parent.is_symlink() for parent in local.parents if parent != root):
            findings.append(f"{name}: symbolic links require review")
            continue
        if not local.exists():  # A tracked file deleted in this working tree is not exported.
            continue
        if not local.is_file() or local.stat().st_size > MAX_SOURCE_BYTES:
            findings.append(f"{name}: unsupported file type or size")
            continue
        selected.append(name)
    selected_set = set(selected)
    for name in selected:
        path = root / name
        suffix = path.suffix.lower()
        if suffix == ".zip":
            if name not in REVIEWED_ZIPS:
                findings.append(f"{name}: archive is not an approved synthetic regression fixture")
            else:
                inspect_zip(path, name, findings)
        elif name == "gradle/wrapper/gradle-wrapper.jar":
            continue
        elif suffix in {".png", ".jpg", ".jpeg", ".webp"} and name.startswith("docs/screenshots/"):
            continue
        elif suffix in {".ttf", ".otf"} and name.startswith("app/src/main/res/font/"):
            continue
        elif suffix in TEXT_SUFFIXES or name in ROOT_FILES:
            try:
                content = path.read_text(encoding="utf-8")
                inspect_text(name, content, findings)
                if suffix == ".md":
                    inspect_markdown_links(name, content, selected_set, findings)
            except UnicodeError:
                findings.append(f"{name}: expected UTF-8 source text")
        else:
            findings.append(f"{name}: unreviewed source/binary file type")
    return selected, omitted, sorted(set(findings))


def export_snapshot(root: Path, destination: Path, selected: list[str]) -> None:
    if not destination.is_absolute():
        raise ValueError("Export destination must be absolute")
    if destination.exists() or destination.is_symlink():
        raise ValueError("Export destination must not already exist")
    destination.mkdir(parents=True, exist_ok=False)
    entries = []
    try:
        for name in selected:
            source, target = root / name, destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target, follow_symlinks=False)
            target.chmod(0o755 if source.stat().st_mode & 0o111 else 0o644)
            data = target.read_bytes()
            entries.append({"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
        (destination / MANIFEST).write_text(json.dumps({"format": "openvespatrack-public-source", "schemaVersion": 1,
            "files": entries}, indent=2) + "\n", encoding="utf-8")
    except Exception:
        shutil.rmtree(destination)
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--export", type=Path, metavar="ABSENT_DIRECTORY", help="export only after a successful audit")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1], help=argparse.SUPPRESS)
    args = parser.parse_args()
    root = args.root.resolve()
    selected, omitted, findings = audit(root)
    print(f"Public source audit: {len(selected)} selected files; {len(omitted)} historical/local files excluded.")
    if findings:
        for finding in findings:
            print(f"FAIL: {finding}")
        print("No export written.")
        return 1
    print("PASS: bounded source, archive and link checks.")
    if args.export:
        try:
            export_snapshot(root, args.export, selected)
        except (ValueError, OSError) as error:
            print(f"Export refused: {error}", file=sys.stderr)
            return 2
        print(f"Exported {len(selected)} files plus SHA-256 manifest to {args.export}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
