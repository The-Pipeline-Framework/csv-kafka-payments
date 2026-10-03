#!/usr/bin/env python3
"""Check a locally produced Release against its complete Quarkus fast-JAR output."""
import hashlib
import json
from pathlib import Path
import sys
from urllib.parse import unquote, urlparse
import zipfile


def require(condition, context):
    if not condition:
        raise SystemExit(f"Release artifact verification failed: {context}")


require(len(sys.argv) > 1, "supply at least one application module")
for module in sys.argv[1:]:
    target = Path(module) / "target"
    release = json.loads((target / "pipeline-release.json").read_text())
    artifacts = release["artifacts"]
    require(artifacts, "expected at least one artifact")
    carrier_id = release.get("compiledTruthArtifactId", artifacts[0].get("artifactId"))
    require(len(artifacts) == 1 or carrier_id, "multi-artifact Release requires compiledTruthArtifactId")
    module_ids = {"input-csv-file-processing-svc", "payment-status-svc", "payments-processing-svc",
                  "persistence-svc", "orchestrator-svc"}
    if len(artifacts) > 1:
        artifact_ids = [artifact["artifactId"] for artifact in artifacts]
        require(len(artifact_ids) == len(module_ids) and set(artifact_ids) == module_ids,
                f"modular Release must include every host exactly once: {artifact_ids}")
    contract = {}
    for artifact in artifacts:
        carrier = artifact.get("artifactId") == carrier_id
        if len(artifacts) == 1:
            source_target = target
        else:
            artifact_id = artifact["artifactId"]
            require(artifact_id in module_ids, f"unknown module artifact: {artifact_id}")
            source_target = Path(module).parent / artifact_id / "target"
        require(artifact["kind"] == "application-archive", artifact)
        uri = urlparse(artifact["uri"])
        require(uri.scheme == "file" and not uri.netloc, artifact)
        archive = Path(unquote(uri.path))
        require(archive.is_file(), f"archive missing: {archive}")
        digest = "sha256:" + hashlib.sha256(archive.read_bytes()).hexdigest()
        require(digest == artifact["digest"], f"digest {digest} != {artifact['digest']}")
        with zipfile.ZipFile(archive) as packaged:
            require(packaged.testzip() is None, "Corrupt ZIP member")
            members = packaged.namelist()
            require(len(members) == len(set(members)), "Duplicate archive entries")
            require("quarkus-run.jar" in members, "Missing Quarkus launcher")
            require(any(name.startswith("lib/") for name in members), "Missing runtime dependencies")
            expected = {}
            sources = [(source_target / "quarkus-app", "")]
            if carrier:
                sources.append((target / "classes/META-INF/pipeline", "META-INF/pipeline/"))
            for directory, prefix in sources:
                require(directory.is_dir(), f"expected source directory missing: {directory}")
                for source in directory.rglob("*"):
                    if source.is_file():
                        name = prefix + source.relative_to(directory).as_posix()
                        content = source.read_bytes()
                        require(name not in expected or expected[name] == content,
                                f"conflicting source bytes: {name}")
                        expected[name] = content
            actual = {entry.filename for entry in packaged.infolist() if not entry.is_dir()}
            require(actual == set(expected),
                    f"archive contents differ: missing={sorted(set(expected) - actual)}, "
                    f"unexpected={sorted(actual - set(expected))}")
            for name, content in expected.items():
                require(packaged.read(name) == content, f"packaged bytes differ: {name}")
            if carrier:
                contract = json.loads(packaged.read("META-INF/pipeline/pipeline-contract.json"))
        print(f"{artifact.get('artifactId', module)}: complete {archive.stat().st_size} byte archive, digest verified")
    require(contract, "compiled metadata carrier not found")
    require(release["pipelineId"] == contract["pipelineId"], "pipelineId differs from contract")
    require(release["contractVersion"] == contract["contractVersion"], "contractVersion differs from contract")
    steps = [step["authoredName"] for step in contract["steps"]]
    require(steps and len(steps) == len(set(steps)), f"invalid authored steps: {steps}")
    artifact_steps = [step for artifact in artifacts for step in artifact["stepIds"]]
    require(artifact_steps == steps, f"artifact steps {artifact_steps} != {steps}")
    print(f"{module}: {len(artifacts)} complete archives, {len(steps)} unique authored steps verified")
