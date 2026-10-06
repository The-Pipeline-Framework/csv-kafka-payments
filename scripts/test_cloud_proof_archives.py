"""Promotable URI checks retain complete local-byte and digest validation."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


class CloudProofArchiveTest(unittest.TestCase):
    def test_maven_descriptor_checks_exact_producer_output_without_rewriting(self):
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
                archive = target / "pipeline-release-artifacts/application.zip"
                archive.parent.mkdir(parents=True)
                with zipfile.ZipFile(archive, "w") as output:
                    for name, value in content.items():
                        output.writestr(name, value)
                release = {"pipelineId": "app", "contractVersion": "contract", "artifacts": [{
                    "artifactId": "application", "kind": "application-archive",
                    "uri": "maven:example:app:zip:application:1.0.0",
                    "digest": "sha256:" + hashlib.sha256(archive.read_bytes()).hexdigest(), "stepIds": ["Input"]}]}
                descriptor = target / "pipeline-release.json"
                original = json.dumps(release).encode()
                descriptor.write_bytes(original)
                result = subprocess.run([sys.executable, *flags, str(checker), str(module)], capture_output=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(original, descriptor.read_bytes())
                with zipfile.ZipFile(archive, "a") as output:
                    output.writestr("unexpected.txt", b"changed")
                rejected = subprocess.run([sys.executable, *flags, str(checker), str(module)], capture_output=True)
                self.assertNotEqual(0, rejected.returncode)
                self.assertIn(b"digest", rejected.stderr)


if __name__ == "__main__":
    unittest.main()
