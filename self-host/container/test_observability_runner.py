"""Exercise the proof launcher without starting containers or contacting backends."""
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parent


class ObservabilityRunnerTest(unittest.TestCase):
    def test_all_proof_runs_preserve_the_selected_maven_repository(self):
        for supplied_repository in (False, True):
            with self.subTest(supplied_repository=supplied_repository), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                container = root / "self-host/container"
                container.mkdir(parents=True)
                shutil.copyfile(SOURCE / "run-observability-proof.sh", container / "run-observability-proof.sh")
                launcher = container / "run-container-ha-demo.sh"
                launcher.write_text("#!" + sys.executable + "\n" + '''import json, os
with open(os.environ["PROOF_CALLS"], "a") as log:
    log.write(json.dumps({"maven": os.environ["TPF_MAVEN_ARGS"],
                          "transport": os.environ["TPF_CSV_AWAIT_TRANSPORT"],
                          "sdk_disabled": os.environ["TPF_CSV_WORKER_OTEL_SDK_DISABLED"]}) + "\\n")
''')
                launcher.chmod(0o755)
                (container / "verify_observability.py").write_text('''import os, sys
from pathlib import Path
if os.environ["TPF_CSV_WORKER_OTEL_SDK_DISABLED"] == "true":
    print("Missing framework span tpf.pipeline.run from csv-worker")
    raise SystemExit(1)
output = Path(sys.argv[sys.argv.index("--output") + 1])
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text("{}\\n")
''')
                (container / "test_verify_observability.py").write_text(
                    "import unittest\nclass FixturePreflightTest(unittest.TestCase):\n"
                    "    def test_fixture(self): self.assertTrue(True)\n")
                bin_dir = root / "bin"
                bin_dir.mkdir()
                docker = bin_dir / "docker"
                docker.write_text("#!/bin/sh\nexit 0\n")
                docker.chmod(0o755)
                calls_file = root / "calls.jsonl"
                env = os.environ.copy()
                env.pop("MAVEN_ARGS", None)
                env["PATH"] = str(bin_dir) + os.pathsep + env["PATH"]
                env["PROOF_CALLS"] = str(calls_file)
                repository = root / ("immutable-inputs" if supplied_repository else ".m2/repository")
                bom = "-Dpipelineframework.bom.version=26.10.1-system-test.exact"
                if supplied_repository:
                    env["MAVEN_ARGS"] = f"-ntp {bom} -Dmaven.repo.local={repository}"
                result = subprocess.run(["bash", str(container / "run-observability-proof.sh")],
                                        cwd=root, env=env, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                calls = [json.loads(line) for line in calls_file.read_text().splitlines()]
                self.assertEqual(5, len(calls))
                self.assertEqual({"sqs", "kafka"}, {call["transport"] for call in calls})
                self.assertEqual(1, sum(call["sdk_disabled"] == "true" for call in calls))
                for call in calls:
                    args = shlex.split(call["maven"])
                    self.assertEqual([f"-Dmaven.repo.local={repository}"],
                                     [arg for arg in args if arg.startswith("-Dmaven.repo.local=")])
                    if supplied_repository:
                        self.assertIn(bom, args)


if __name__ == "__main__":
    unittest.main()
