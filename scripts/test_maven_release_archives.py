"""Promotable URI checks retain complete local-byte and digest validation."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


class MavenReleaseArchiveTest(unittest.TestCase):
    def test_maven_carrier_stages_verified_bytes_without_rewriting_descriptor(self):
        checker = Path(__file__).with_name("check-release-artifacts.py")
        for flags in ([], ["-O"]):
            with self.subTest(flags=flags), tempfile.TemporaryDirectory() as directory:
                module = Path(directory) / "application"
                target = module / "target"
                contract = {"pipelineId": "app", "contractVersion": "contract",
                            "steps": [{"authoredName": "Input"}]}
                content = {"quarkus-run.jar": b"launcher", "lib/runtime.jar": b"runtime",
                           "META-INF/pipeline/pipeline-contract.json": json.dumps(contract).encode()}
                for name, value in content.items():
                    path = target / ("classes" if name.startswith("META-INF/") else "quarkus-app") / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(value)
                artifact_directory = target / "pipeline-release-artifacts"
                artifact_directory.mkdir(parents=True)
                archive_build = artifact_directory / "carrier-build.zip"
                with zipfile.ZipFile(archive_build, "w") as output:
                    for name, value in content.items():
                        output.writestr(name, value)
                archive_bytes = archive_build.read_bytes()
                digest = "sha256:" + hashlib.sha256(archive_bytes).hexdigest()
                archive = artifact_directory / (digest.removeprefix("sha256:") + ".zip")
                archive_build.rename(archive)
                attachment = artifact_directory / "monolith-svc.zip"
                release = {"pipelineId": "app", "contractVersion": "contract", "artifacts": [{
                    "artifactId": "monolith-svc", "kind": "application-archive",
                    "uri": "maven:example:app:zip:application:1.0.0",
                    "digest": digest, "stepIds": ["Input"]}], "compiledTruthArtifactId": "monolith-svc"}
                descriptor = target / "pipeline-release.json"
                original = json.dumps(release).encode()
                descriptor.write_bytes(original)
                result = subprocess.run([sys.executable, *flags, str(checker), str(module)], capture_output=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(original, descriptor.read_bytes())
                self.assertEqual(archive_bytes, archive.read_bytes())
                self.assertFalse(attachment.exists())

                release["artifacts"][0]["digest"] = "sha256:../../outside"
                descriptor.write_text(json.dumps(release))
                invalid_digest = subprocess.run([sys.executable, *flags, str(checker),
                                                 "--stage-maven-attachments", str(module)], capture_output=True)
                self.assertNotEqual(0, invalid_digest.returncode)
                self.assertIn(b"invalid SHA-256 digest", invalid_digest.stderr)
                self.assertFalse(attachment.exists())
                descriptor.write_bytes(original)

                staged = subprocess.run([sys.executable, *flags, str(checker),
                                         "--stage-maven-attachments", str(module)], capture_output=True)
                self.assertEqual(0, staged.returncode, staged.stderr)
                self.assertEqual(archive_bytes, attachment.read_bytes())
                self.assertEqual(archive_bytes, archive.read_bytes())
                self.assertEqual(original, descriptor.read_bytes())

                with zipfile.ZipFile(archive, "a") as output:
                    output.writestr("unexpected.txt", b"changed")
                rejected = subprocess.run([sys.executable, *flags, str(checker),
                                           "--stage-maven-attachments", str(module)], capture_output=True)
                self.assertNotEqual(0, rejected.returncode)
                self.assertIn(b"digest", rejected.stderr)
                self.assertEqual(original, descriptor.read_bytes())
                self.assertEqual(archive_bytes, attachment.read_bytes())


if __name__ == "__main__":
    unittest.main()
