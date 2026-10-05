"""Failure diagnostics do not require the launcher's Compose environment."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("collect-ha-diagnostics.sh")


class HaDiagnosticsTest(unittest.TestCase):
    def collect(self, project="csv-payments-self-host-ha", fail_log=False, containers="aa11 bb22"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docker = root / "docker"
            docker.write_text("#!" + sys.executable + "\n" + '''import json, os, sys
args = sys.argv[1:]
with open(os.environ["TEST_CALLS"], "a") as output:
    output.write(json.dumps(args) + "\\n")
if args[0] == "ps":
    for container in os.environ["TEST_CONTAINERS"].split(): print(container)
elif args[0] == "inspect": print("worker image=sha256:test status=running exit=0 oom=false")
elif args[0] == "logs":
    print("worker diagnostic " + args[-1])
    if os.environ["TEST_FAIL_LOG"] == "true" and args[-1] == "aa11": raise SystemExit(1)
else: raise SystemExit("Unexpected Docker command")
''')
            docker.chmod(0o755)
            env = {key: value for key, value in os.environ.items()
                   if not key.startswith("TPF_") and key != "COMPOSE_PROJECT_NAME"}
            env.update(PATH=str(root) + os.pathsep + env["PATH"], TPF_RUN_DIR=str(root / "run"),
                       TEST_CALLS=str(root / "calls.jsonl"), TEST_CONTAINERS=containers,
                       TEST_FAIL_LOG=str(fail_log).lower())
            if project != "csv-payments-self-host-ha": env["COMPOSE_PROJECT_NAME"] = project
            result = subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True)
            calls = [json.loads(line) for line in (root / "calls.jsonl").read_text().splitlines()]
            files = {path.name: path.read_text() for path in (root / "run/container-logs").iterdir()}
            return result, calls, files

    def test_collects_logs_without_release_or_transport_environment(self):
        result, calls, files = self.collect()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("label=com.docker.compose.project=csv-payments-self-host-ha", calls[0])
        self.assertTrue(all(call[0] in {"ps", "inspect", "logs"} for call in calls))
        self.assertIn("worker diagnostic aa11", files["aa11.log"])
        self.assertIn("image=sha256:test", files["bb22.state.txt"])
        self.assertNotIn("Config.Env", str(calls))

    def test_respects_an_explicit_project(self):
        result, calls, _ = self.collect(project="isolated-ha-run")
        self.assertEqual(0, result.returncode)
        self.assertIn("label=com.docker.compose.project=isolated-ha-run", calls[0])

    def test_one_failed_log_does_not_prevent_other_capture(self):
        result, _, files = self.collect(fail_log=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("worker diagnostic bb22", files["bb22.log"])

    def test_missing_containers_are_reported(self):
        result, _, _ = self.collect(containers="")
        self.assertEqual(1, result.returncode)
        self.assertIn("No HA containers found", result.stderr)


if __name__ == "__main__": unittest.main()
