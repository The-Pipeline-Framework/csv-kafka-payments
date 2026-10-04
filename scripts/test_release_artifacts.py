"""Exercise valid archives and rejection paths, including optimized Python."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

VERIFIER = Path(__file__).with_name("check-release-artifacts.py")


class ReleaseArtifactTest(unittest.TestCase):
    def test_missing_module_fails(self):
        for flags in ([], ["-O"]):
            with self.subTest(flags=flags):
                result = subprocess.run([sys.executable, *flags, str(VERIFIER)], capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("supply at least one", result.stderr)

    def test_archive_checks_remain_active_under_optimization(self):
        scenarios = {
            "valid": "",
            "extra": "unexpected=['extra.txt']",
            "missing_metadata": "missing=['META-INF/pipeline/roles.json']",
            "changed_metadata": "packaged bytes differ: META-INF/pipeline/roles.json",
            "changed_runtime": "packaged bytes differ: lib/runtime.jar",
            "bad_digest": "digest",
            "duplicate": "Duplicate archive entries",
            "bad_identity": "pipelineId differs",
            "bad_steps": "artifact steps",
        }
        for flags in ([], ["-O"]):
            for scenario, diagnostic in scenarios.items():
                with self.subTest(flags=flags, scenario=scenario), tempfile.TemporaryDirectory(prefix="release artifact ") as directory:
                    module = Path(directory) / "application with spaces"
                    target = module / "target"
                    contract = {"pipelineId": "example.app", "contractVersion": "sha256:contract", "steps": [{"authoredName": "Process input"}]}
                    expected = {"quarkus-run.jar": b"launcher", "lib/runtime.jar": b"runtime",
                                "META-INF/pipeline/pipeline-contract.json": json.dumps(contract).encode(),
                                "META-INF/pipeline/roles.json": b"roles"}
                    for name, content in expected.items():
                        root = target / ("classes" if name.startswith("META-INF/") else "quarkus-app")
                        path = root / name
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_bytes(content)
                    packaged = dict(expected)
                    if scenario == "extra": packaged["extra.txt"] = b"unexpected"
                    if scenario == "missing_metadata": del packaged["META-INF/pipeline/roles.json"]
                    if scenario == "changed_metadata": packaged["META-INF/pipeline/roles.json"] = b"changed"
                    if scenario == "changed_runtime": packaged["lib/runtime.jar"] = b"changed"
                    archive = target / "application.zip"
                    with zipfile.ZipFile(archive, "w") as output:
                        output.writestr("empty-directory/", b"")
                        for name, content in packaged.items(): output.writestr(name, content)
                        if scenario == "duplicate": output.writestr("lib/runtime.jar", b"duplicate")
                    release = {"pipelineId": contract["pipelineId"], "contractVersion": contract["contractVersion"],
                               "artifacts": [{"kind": "application-archive", "uri": archive.as_uri(),
                                              "digest": "sha256:" + hashlib.sha256(archive.read_bytes()).hexdigest(),
                                              "stepIds": ["Process input"]}]}
                    if scenario == "bad_digest": release["artifacts"][0]["digest"] = "sha256:wrong"
                    if scenario == "bad_identity": release["pipelineId"] = "other.app"
                    if scenario == "bad_steps": release["artifacts"][0]["stepIds"] = ["Other step"]
                    (target / "pipeline-release.json").write_text(json.dumps(release))
                    result = subprocess.run([sys.executable, *flags, str(VERIFIER), str(module)], capture_output=True, text=True)
                    if scenario == "valid":
                        self.assertEqual(0, result.returncode, result.stderr)
                        self.assertIn("digest verified", result.stdout)
                    else:
                        self.assertNotEqual(0, result.returncode, result.stdout)
                        self.assertIn(diagnostic, result.stderr)


    def test_modular_closure_checks_each_host_and_compiled_metadata(self):
        modules = ["input-csv-file-processing-svc", "payment-status-svc", "payments-processing-svc",
                   "persistence-svc", "orchestrator-svc"]
        for scenario in ("valid", "changed_host", "missing_host", "duplicate_step", "missing_carrier_metadata"):
            for flags in ([], ["-O"]):
                with self.subTest(scenario=scenario, flags=flags), tempfile.TemporaryDirectory(prefix="modular release ") as directory:
                    root = Path(directory)
                    carrier = root / "orchestrator-svc"
                    contract = {"pipelineId": "example.app", "contractVersion": "sha256:contract",
                                "steps": [{"authoredName": "Process input"}, {"authoredName": "Store"}]}
                    metadata = json.dumps(contract).encode()
                    artifacts = []
                    for index, module in enumerate(modules):
                        target = root / module / "target"
                        files = {"quarkus-run.jar": b"launcher", "lib/runtime.jar": module.encode()}
                        for name, content in files.items():
                            path = target / "quarkus-app" / name
                            path.parent.mkdir(parents=True, exist_ok=True)
                            path.write_bytes(content)
                        if module == "orchestrator-svc":
                            path = target / "classes/META-INF/pipeline/pipeline-contract.json"
                            path.parent.mkdir(parents=True, exist_ok=True)
                            path.write_bytes(metadata)
                            if scenario != "missing_carrier_metadata":
                                files["META-INF/pipeline/pipeline-contract.json"] = metadata
                        if scenario == "changed_host" and index == 2:
                            files["lib/runtime.jar"] = b"changed"
                        archive = target / "application.zip"
                        with zipfile.ZipFile(archive, "w") as output:
                            for name, content in files.items():
                                output.writestr(name, content)
                        artifacts.append({"artifactId": module, "kind": "application-archive", "uri": archive.as_uri(),
                                          "digest": "sha256:" + hashlib.sha256(archive.read_bytes()).hexdigest(),
                                          "stepIds": ["Process input"] if index == 0 else ["Store"] if index == 1 else []})
                    if scenario == "missing_host":
                        artifacts.pop(2)
                    if scenario == "duplicate_step":
                        artifacts[2]["stepIds"] = ["Store"]
                    release = {"pipelineId": contract["pipelineId"], "contractVersion": contract["contractVersion"],
                               "compiledTruthArtifactId": "orchestrator-svc", "artifacts": artifacts}
                    (carrier / "target/pipeline-release.json").write_text(json.dumps(release))
                    result = subprocess.run([sys.executable, *flags, str(VERIFIER), str(carrier)], capture_output=True, text=True)
                    if scenario == "valid":
                        self.assertEqual(0, result.returncode, result.stderr)
                        self.assertIn("5 complete archives", result.stdout)
                    else:
                        self.assertNotEqual(0, result.returncode, result.stdout)
                        diagnostic = {"changed_host": "packaged bytes differ", "missing_host": "every host exactly once",
                                      "duplicate_step": "artifact steps", "missing_carrier_metadata": "archive contents differ"}[scenario]
                        self.assertIn(diagnostic, result.stderr)


if __name__ == "__main__":
    unittest.main()
