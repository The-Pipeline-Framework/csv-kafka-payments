#!/usr/bin/env python3
import hashlib
import json
import os
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1])
pr_path = pathlib.Path(sys.argv[2])
base_repo = "The-Pipeline-Framework/csv-kafka-payments"
allowed_files = {"build-metadata.json", "build-metadata.sha256"}
actual_files = {p.name for p in root.iterdir() if p.is_file() and not p.is_symlink()}
assert actual_files == allowed_files, f"unexpected build artifact files: {actual_files ^ allowed_files}"
assert all(p.is_file() and not p.is_symlink() for p in root.iterdir()), "non-regular build artifact entry"
checksum_line = (root / "build-metadata.sha256").read_text()
assert re.fullmatch(r"[0-9a-f]{64}  build-metadata\.json\n", checksum_line), "invalid checksum file"
assert hashlib.sha256((root / "build-metadata.json").read_bytes()).hexdigest() == checksum_line[:64], "metadata checksum mismatch"
metadata = json.loads((root / "build-metadata.json").read_text())
required = {"schemaVersion", "repository", "sourceRepository", "component", "sourceSha", "pullRequestNumber", "candidateVersion", "provenance", "mavenArtifacts", "images"}
assert set(metadata) == required, "unexpected/missing metadata keys"
assert metadata["schemaVersion"] == 1 and metadata["repository"] == base_repo
assert metadata["component"] == "csvPayments"
assert metadata["mavenArtifacts"] == [] and metadata["images"] == [], "source component artifacts must be empty"
sha = metadata["sourceSha"]
assert re.fullmatch(r"[0-9a-f]{40}", sha), "invalid source SHA"
build = metadata["provenance"]["build"]
expected_build = {
    "repository": base_repo,
    "runId": int(os.environ["BUILD_RUN_ID"]),
    "runAttempt": int(os.environ["BUILD_RUN_ATTEMPT"]),
    "workflowPath": ".github/workflows/tpf-candidate-build.yml",
    "event": os.environ["BUILD_RUN_EVENT"],
}
assert build == expected_build, "build provenance differs from workflow_run"
assert os.environ["BUILD_RUN_PATH"].split("@", 1)[0] == ".github/workflows/tpf-candidate-build.yml"
assert os.environ["BUILD_RUN_REPOSITORY"] == base_repo
version_raw = ET.parse("pom.xml").getroot().findtext("{http://maven.apache.org/POM/4.0.0}version", "")
assert re.fullmatch(r"\d+\.\d+\.\d+-SNAPSHOT", version_raw), "invalid trusted root POM version"
base_version = version_raw.removesuffix("-SNAPSHOT")
event = os.environ["BUILD_RUN_EVENT"]
if event == "pull_request":
    number = metadata["pullRequestNumber"]
    assert type(number) is int and number > 0, "PR number must be a positive integer"
    associated = json.loads(os.environ.get("BUILD_ASSOCIATED_PR_NUMBERS", "[]"))
    assert isinstance(associated, list) and number in associated, "PR missing from workflow_run association"
    assert metadata["candidateVersion"] == f"{base_version}-pr.{number}.{sha[:12]}", "invalid PR candidate version"
    pr = json.loads(pr_path.read_text())
    assert pr["state"] == "open" and pr["number"] == number, "candidate PR is not open/current"
    head = pr["head"]
    assert head["sha"] == sha == os.environ["BUILD_RUN_HEAD_SHA"], "PR head changed since exact-head build"
    source_repo = head["repo"]["full_name"]
    assert source_repo.lower() == metadata["sourceRepository"].lower(), "source repository mismatch"
    assert os.environ["BUILD_RUN_HEAD_BRANCH"] == head["ref"], "workflow branch differs from PR head"
    if source_repo.lower() != base_repo.lower():
        labels = {label["name"] for label in pr.get("labels", [])}
        assert "safe-to-system-test" in labels, "fork PR lacks safe-to-system-test label"
elif event == "push":
    assert metadata["pullRequestNumber"] is None
    assert metadata["sourceRepository"].lower() == base_repo.lower()
    assert os.environ["BUILD_RUN_HEAD_BRANCH"] == "main", "main candidate is not from main"
    assert sha == os.environ["BUILD_RUN_HEAD_SHA"], "main candidate SHA mismatch"
    assert metadata["candidateVersion"] == f"{base_version}-main.{sha[:12]}", "invalid main candidate version"
else:
    raise AssertionError(f"unsupported build event: {event}")
print("candidate source provenance and checksum are valid")
