"""Standalone Actions and central system tests must use the same scale limits."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class HaScalePolicyTest(unittest.TestCase):
    def test_standalone_scale_matches_the_system_test_policy(self):
        workflow = (ROOT / ".github/workflows/self-host-ha-scale.yml").read_text()
        launcher = (ROOT / "scripts/system-test-suite.sh").read_text()
        for name in ("TPF_CSV_RECORD_COUNT", "TPF_CSV_TRANSITION_TRANSPORT_DEADLINE",
                     "TPF_CSV_FIXTURE_RUN_DEADLINE_SECONDS"):
            workflow_values = re.findall(rf'^\s+{name}: "([^"\n]+)"$', workflow, re.MULTILINE)
            launcher_values = re.findall(rf'^\s+export {name}=([^\s]+)$', launcher, re.MULTILINE)
            self.assertEqual(1, len(workflow_values), name)
            self.assertEqual(1, len(launcher_values), name)
            self.assertEqual(launcher_values, workflow_values, f"{name}: standalone scale policy drift")
        self.assertIn('TPF_CSV_FIXTURE_RUN_DEADLINE_SECONDS:-300',
                      (ROOT / "self-host/container/run-container-ha-demo.sh").read_text())


if __name__ == "__main__":
    unittest.main()
