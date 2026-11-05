"""Exercise scale-lane aggregation and evidence preservation without containers."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ScaleLaneTest(unittest.TestCase):
    """Run the production suite launcher against deterministic fixture commands."""

    def test_failed_lanes_preserve_evidence_and_continue(self):
        """Keep separate failed-lane evidence after later profiles reset the run directory."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            container = root / "self-host/container"
            container.mkdir(parents=True)
            script = (ROOT / "scripts/system-test-suite.sh").read_text()
            script = script.replace(" self-host/container/test_system_test_scale_lanes.py", "")
            script = script.replace("    configure_testcontainers\n", "    : # container setup stubbed for this test\n")
            (root / "scripts/system-test-suite.sh").write_text(script)
            (container / "test_demo_client.py").write_text('import unittest\nclass FixtureTest(unittest.TestCase):\n    def test_ready(self):\n        """Provide the fixture preflight expected by the suite launcher."""\n        self.assertTrue(True)\n')
            demo = container / "run-container-ha-demo.sh"
            demo.write_text("""#!/usr/bin/env bash
set -eu
if [[ "$1" == --prepare-images ]]; then exit 0; fi
lane="$TPF_CSV_AWAIT_TRANSPORT/$TPF_CSV_ADMISSION_PROFILE"
echo "$lane" >> lanes.txt
rm -rf self-host/container/target/tpf-container-ha
mkdir -p self-host/container/target/tpf-container-ha/input
echo "$lane" > self-host/container/target/tpf-container-ha/input/lane.txt
[[ "$lane" == sqs/slow || "$lane" == kafka/burst ]] && exit 7
exit 0
""")
            demo.chmod(0o755)
            binaries = root / "bin"
            binaries.mkdir()
            docker = binaries / "docker"
            docker.write_text("#!/usr/bin/env bash\necho fixture-container-log\n")
            docker.chmod(0o755)
            sleep = binaries / "sleep"
            sleep.write_text("#!/usr/bin/env bash\nexit 0\n")
            sleep.chmod(0o755)
            env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ["PATH"])
            result = subprocess.run(
                ["bash", "scripts/system-test-suite.sh", "ha-scale"],
                cwd=root, env=env, capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            self.assertEqual((root / "lanes.txt").read_text().splitlines(),
                             ["sqs/slow", "sqs/burst", "kafka/slow", "kafka/burst"])
            preserved = container / "target/ha-scale-failures"
            lanes = {path.joinpath("input/lane.txt").read_text().strip()
                     for path in preserved.iterdir()}
            self.assertEqual(lanes, {"sqs/slow", "kafka/burst"})
            for path in preserved.iterdir():
                self.assertIn("fixture-container-log", (path / "compose.log").read_text())


if __name__ == "__main__":
    unittest.main()
