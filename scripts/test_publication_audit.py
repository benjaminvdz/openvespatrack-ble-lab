"""Regression checks for publication boundaries, not a general secret scanner."""

import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from publication_audit import audit, export_snapshot, MANIFEST


class PublicationAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "source"
        self.root.mkdir()

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def archive(self, name, members):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w") as output:
            for member, content in members.items():
                output.writestr(member, content)

    def test_private_archive_is_preserved_but_not_selected(self):
        original = self.write("docs/internal-archive/old.md", "Brain historical evidence")
        self.write("README.md", "Reviewed source")
        selected, omitted, findings = audit(self.root)
        self.assertEqual(findings, [])
        self.assertEqual(selected, ["README.md"])
        self.assertIn("docs/internal-archive/old.md", omitted)
        self.assertEqual(original.read_text(), "Brain historical evidence")

    def test_secret_and_private_reference_block_without_echoing_secret(self):
        token = "ghp_" + "a" * 36
        self.write("README.md", "Brain internal notes " + token)
        _, _, findings = audit(self.root)
        self.assertTrue(any("private or unreviewed" in item for item in findings))
        self.assertTrue(any("credential" in item for item in findings))
        self.assertNotIn(token, "\n".join(findings))

    def test_signing_keys_and_unreviewed_archives_block(self):
        self.write("app/release.keystore", "not a real key")
        self.archive("examples/unreviewed.zip", {"notes.txt": "unknown"})
        _, _, findings = audit(self.root)
        self.assertTrue(any("signing" in item for item in findings))
        self.assertTrue(any("not an approved" in item for item in findings))

    def test_reviewed_zip_is_retained_and_members_checked(self):
        name = "examples/reports/android-dry-run.zip"
        self.archive(name, {"run.json": '{"mode":"DRY_RUN"}'})
        selected, _, findings = audit(self.root)
        self.assertEqual(findings, [])
        self.assertIn(name, selected)
        self.archive(name, {"run.json": '{"notes":"Brain internal"}'})
        self.assertTrue(audit(self.root)[2])

    def test_reviewed_zip_does_not_allow_unsafe_members(self):
        self.archive("examples/reports/android-dry-run.zip", {"../escape.txt": "text"})
        self.assertTrue(any("unsafe" in item for item in audit(self.root)[2]))

    def test_links_to_excluded_evidence_block(self):
        self.write("docs/validation/old.md", "private")
        for link in ('[old](docs/validation/old.md)', '<img src="docs/validation/old.md">'):
            with self.subTest(link=link):
                self.write("README.md", link + " [release](../../releases)")
                findings = audit(self.root)[2]
                self.assertEqual(len(findings), 1)
                self.assertIn("local link", findings[0])

    def test_compatibility_identifiers_are_not_prose(self):
        self.write("fixtures/protocol.json", '{"id":"vespa-finder-fc65","other":"hoornaar-spotter-recognition","prefix":"VESPA-RANGE","variant":"range_pro"}')
        self.assertEqual(audit(self.root)[2], [])

    def test_symlink_cannot_escape_source(self):
        self.write("README.md", "safe")
        (self.root / "NOTICE").symlink_to(Path(self.temp.name) / "elsewhere")
        self.assertTrue(any("symbolic" in item for item in audit(self.root)[2]))

    def test_export_is_exact_and_refuses_existing_destination(self):
        wrapper = self.write("gradlew", "#!/bin/sh\nexit 0\n")
        wrapper.chmod(0o755)
        self.write("README.md", "Reviewed source\n")
        selected, _, findings = audit(self.root)
        self.assertEqual(findings, [])
        destination = Path(self.temp.name) / "public"
        export_snapshot(self.root, destination, selected)
        manifest = json.loads((destination / MANIFEST).read_text())
        self.assertFalse((destination / ".git").exists())
        self.assertTrue((destination / "gradlew").stat().st_mode & 0o111)
        for record in manifest["files"]:
            data = (destination / record["path"]).read_bytes()
            self.assertEqual(data, (self.root / record["path"]).read_bytes())
            self.assertEqual(record["bytes"], len(data))
            self.assertEqual(record["sha256"], hashlib.sha256(data).hexdigest())
        with self.assertRaises(ValueError):
            export_snapshot(self.root, destination, selected)
        self.assertEqual(audit(destination)[2], [])


if __name__ == "__main__":
    unittest.main()
