#!/usr/bin/env python3
"""Prove lifecycle selection and mapping restoration without publishing anything."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class ReleaseBuildTest(unittest.TestCase):
    def test_install_and_deploy_use_one_lifecycle_under_the_monolith_mapping(self):
        for publication in (False, True):
            with self.subTest(publication=publication), tempfile.TemporaryDirectory(prefix="release build ") as directory:
                work = Path(directory)
                mapping = work / "config/runtime-mapping"
                mapping.mkdir(parents=True)
                (mapping / "monolith.yaml").write_text("layout: monolith\n")
                active = work / "config/pipeline.runtime.yaml"
                active.write_text("original mapping\n")
                shutil.copyfile(ROOT / "build-monolith.sh", work / "build-monolith.sh")
                bootstrap = work / "bootstrap-application-prereqs.sh"
                bootstrap.write_text("#!/bin/sh\nexit 0\n")
                bootstrap.chmod(0o755)
                launcher = work / "mvnw"
                launcher.write_text("#!" + sys.executable + "\nimport json,os,sys\nfrom pathlib import Path\nwith open(os.environ['TPF_TEST_LOG'],'a') as log:\n json.dump({'args':sys.argv[1:],'transport':os.environ.get('PIPELINE_TRANSPORT'),'mapping':Path('config/pipeline.runtime.yaml').read_text()},log);log.write('\\n')\n")
                launcher.chmod(0o755)
                log = work / "calls.jsonl"
                args = ["--deploy"] if publication else []
                repo = "-Dmaven.repo.local=" + str(work / ".m2/repository")
                test_env = os.environ.copy()
                test_env.pop("PIPELINE_TRANSPORT", None)
                test_env["TPF_TEST_LOG"] = str(log)
                result = subprocess.run(["bash", "build-monolith.sh", *args, repo,
                    "-Dtpf.release.skip=false", "-Dtpf.release.version=release-1"], cwd=work,
                    env=test_env, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                calls = [json.loads(line) for line in log.read_text().splitlines()]
                self.assertEqual(2,len(calls))
                self.assertTrue(all(call["transport"] == "LOCAL" for call in calls))
                final = calls[-1]["args"]
                lifecycle = [arg for arg in final if arg in ("install", "deploy")]
                self.assertEqual(["deploy" if publication else "install"], lifecycle)
                self.assertIn("-Dtpf.release.version=release-1", final)
                self.assertIn(repo, final)
                self.assertNotIn("--deploy", final)
                self.assertTrue(all(call["mapping"] == "layout: monolith\n" for call in calls))
                self.assertEqual("original mapping\n", active.read_text())

if __name__ == "__main__": unittest.main()
