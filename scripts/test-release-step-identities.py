#!/usr/bin/env python3
"""Check release ownership against the compiler's generated step identities."""
import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


class ReleaseStepIdentitiesTest(unittest.TestCase):
    def test_release_artifacts_own_exact_authored_contract_steps(self):
        contract_path = ROOT / "orchestrator-svc/target/classes/META-INF/pipeline/pipeline-contract.json"
        self.assertTrue(contract_path.is_file(), "Run the owner Maven install before this guard")
        contract = json.loads(contract_path.read_text())
        known_steps = {step["authoredName"] for step in contract["steps"]}
        self.assertTrue(known_steps, "The generated contract must contain pipeline steps")
        pom = ET.parse(ROOT / "orchestrator-svc/pom.xml").getroot()
        plugin = pom.find(".//m:plugin[m:artifactId='pipelineframework-release-maven-plugin']", NS)
        self.assertIsNotNone(plugin)
        owned_steps = []
        for artifact in plugin.findall("m:configuration/m:artifacts/m:artifact", NS):
            artifact_id = artifact.findtext("m:artifactId", namespaces=NS)
            for step in artifact.findall("m:stepIds/m:stepId", NS):
                self.assertIn(step.text, known_steps, f"{artifact_id}: unknown release step {step.text}")
                owned_steps.append(step.text)
        self.assertEqual(len(owned_steps), len(set(owned_steps)), "Release step ownership must be unique")
        self.assertEqual(known_steps, set(owned_steps), "Every contract step must have a release owner")


if __name__ == "__main__":
    unittest.main()
