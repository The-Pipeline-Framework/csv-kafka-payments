#!/usr/bin/env python3
"""Check a locally produced Release against its complete Quarkus fast-JAR output."""
import hashlib
import json
from pathlib import Path
import sys
from urllib.parse import unquote, urlparse
import zipfile

for module in sys.argv[1:]:
    target = Path(module) / "target"
    release = json.loads((target / "pipeline-release.json").read_text())
    artifacts = release["artifacts"]
    assert len(artifacts) == 1, artifacts
    artifact = artifacts[0]
    assert artifact["kind"] == "application-archive", artifact
    uri = urlparse(artifact["uri"])
    assert uri.scheme == "file" and not uri.netloc, artifact
    archive = Path(unquote(uri.path))
    assert archive.is_file(), archive
    digest = "sha256:" + hashlib.sha256(archive.read_bytes()).hexdigest()
    assert digest == artifact["digest"], (digest, artifact["digest"])
    with zipfile.ZipFile(archive) as packaged:
        assert packaged.testzip() is None, "Corrupt ZIP member"
        members = packaged.namelist()
        assert len(members) == len(set(members)), "Duplicate archive entries"
        assert "quarkus-run.jar" in members, "Missing Quarkus launcher"
        assert any(name.startswith("lib/") for name in members), "Missing runtime dependencies"
        for source in (target / "quarkus-app").rglob("*"):
            if source.is_file():
                name = source.relative_to(target / "quarkus-app").as_posix()
                assert packaged.read(name) == source.read_bytes(), name
        contract_bytes = packaged.read("META-INF/pipeline/pipeline-contract.json")
        assert contract_bytes == (target / "classes/META-INF/pipeline/pipeline-contract.json").read_bytes()
        contract = json.loads(contract_bytes)
        assert release["pipelineId"] == contract["pipelineId"]
        assert release["contractVersion"] == contract["contractVersion"]
        steps = [step["authoredName"] for step in contract["steps"]]
        assert steps and len(steps) == len(set(steps)), steps
        assert artifact["stepIds"] == steps, (artifact["stepIds"], steps)
    print(f"{module}: complete {archive.stat().st_size} byte archive, {len(steps)} unique authored steps, digest verified")
