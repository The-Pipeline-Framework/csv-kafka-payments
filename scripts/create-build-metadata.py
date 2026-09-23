#!/usr/bin/env python3
import json
import os
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1])
repo = os.environ["GITHUB_REPOSITORY"]
source_repo = os.environ["SOURCE_REPOSITORY"]
source_sha = os.environ["SOURCE_SHA"].lower()
event = os.environ["GITHUB_EVENT_NAME"]
pr_text = os.environ.get("PULL_REQUEST_NUMBER", "")
pom_version = ET.parse("pom.xml").getroot().findtext("{http://maven.apache.org/POM/4.0.0}version", "")
if not re.fullmatch(r"\d+\.\d+\.\d+-SNAPSHOT", pom_version):
    raise SystemExit("root POM version must be a stable three-part snapshot version")
base_version = pom_version.removesuffix("-SNAPSHOT")
if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
    raise SystemExit("source SHA must be a full lowercase commit SHA")
if event == "pull_request":
    if not pr_text.isdigit() or int(pr_text) <= 0:
        raise SystemExit("positive pull request number required")
    pr_number = int(pr_text)
    version = f"{base_version}-pr.{pr_number}.{source_sha[:12]}"
elif event == "push":
    pr_number = None
    version = f"{base_version}-main.{source_sha[:12]}"
else:
    raise SystemExit(f"unsupported build event: {event}")
if repo.lower() != "The-Pipeline-Framework/csv-kafka-payments".lower():
    raise SystemExit("unexpected base repository")
if event == "push" and source_repo.lower() != repo.lower():
    raise SystemExit("main builds must originate from the base repository")

metadata = {
    "schemaVersion": 1,
    "repository": repo,
    "sourceRepository": source_repo,
    "component": "csvPayments",
    "sourceSha": source_sha,
    "pullRequestNumber": pr_number,
    "candidateVersion": version,
    "provenance": {
        "build": {
            "repository": repo,
            "runId": int(os.environ["GITHUB_RUN_ID"]),
            "runAttempt": int(os.environ["GITHUB_RUN_ATTEMPT"]),
            "workflowPath": ".github/workflows/tpf-candidate-build.yml",
            "event": event,
        }
    },
    "mavenArtifacts": [],
    "images": [],
}
root.mkdir(parents=True, exist_ok=True)
(root / "build-metadata.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")
