#!/usr/bin/env python3
import hashlib
import json
import os
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "The-Pipeline-Framework/csv-kafka-payments"
SHA = "0123456789abcdef0123456789abcdef01234567"
VERSION = "26.9.4"

for event, number in (("pull_request", 42), ("push", None)):
    with tempfile.TemporaryDirectory(prefix="csv-candidate-fixture-") as temp:
        root = pathlib.Path(temp)
        pr_path = root.parent / f"{root.name}-current-pr.json"
        env = os.environ | {
            "GITHUB_REPOSITORY": BASE,
            "SOURCE_REPOSITORY": "Contributor/csv-kafka-payments" if number else BASE,
            "SOURCE_SHA": SHA,
            "GITHUB_EVENT_NAME": event,
            "PULL_REQUEST_NUMBER": str(number or ""),
            "GITHUB_RUN_ID": "1234",
            "GITHUB_RUN_ATTEMPT": "2",
        }
        subprocess.run(["python3", str(ROOT / "scripts/create-build-metadata.py"), str(root)], cwd=ROOT, env=env, check=True)
        meta_bytes = (root / "build-metadata.json").read_bytes()
        metadata = json.loads(meta_bytes)
        assert metadata["mavenArtifacts"] == metadata["images"] == []
        assert metadata["candidateVersion"] == f"{VERSION}-{'pr.' + str(number) if number else 'main'}.{SHA[:12]}"
        (root / "build-metadata.sha256").write_text(f"{hashlib.sha256(meta_bytes).hexdigest()}  build-metadata.json\n")
        pr_path.write_text(json.dumps({
            "state": "open", "number": number,
            "head": {"sha": SHA, "ref": "fixture-branch", "repo": {"full_name": env["SOURCE_REPOSITORY"]}},
            "labels": [{"name": "safe-to-system-test"}],
        }))
        validation_env = env | {
            "BUILD_RUN_ID": "1234", "BUILD_RUN_ATTEMPT": "2", "BUILD_RUN_EVENT": event,
            "BUILD_RUN_HEAD_SHA": SHA, "BUILD_RUN_HEAD_BRANCH": "fixture-branch" if number else "main",
            "BUILD_RUN_PATH": ".github/workflows/tpf-candidate-build.yml@refs/heads/main",
            "BUILD_RUN_REPOSITORY": BASE, "BUILD_ASSOCIATED_PR_NUMBERS": "[42]" if number else "[]",
        }
        subprocess.run(["python3", str(ROOT / "scripts/validate-candidate-source.py"), str(root), str(pr_path)], cwd=ROOT, env=validation_env, check=True)
        if number:
            for changes, label in (({"BUILD_ASSOCIATED_PR_NUMBERS": "[]"}, "association"),):
                rejected = subprocess.run(["python3", str(ROOT / "scripts/validate-candidate-source.py"), str(root), str(pr_path)], cwd=ROOT, env=validation_env | changes, capture_output=True)
                assert rejected.returncode != 0, f"missing {label} was accepted"
            pr = json.loads(pr_path.read_text())
            pr["labels"] = []
            pr_path.write_text(json.dumps(pr))
            rejected = subprocess.run(["python3", str(ROOT / "scripts/validate-candidate-source.py"), str(root), str(pr_path)], cwd=ROOT, env=validation_env, capture_output=True)
            assert rejected.returncode != 0, "unlabelled fork was accepted"
            pr["labels"] = [{"name": "safe-to-system-test"}]
            pr["head"]["sha"] = "fedcba9876543210fedcba9876543210fedcba98"
            pr_path.write_text(json.dumps(pr))
            rejected = subprocess.run(["python3", str(ROOT / "scripts/validate-candidate-source.py"), str(root), str(pr_path)], cwd=ROOT, env=validation_env, capture_output=True)
            assert rejected.returncode != 0, "stale PR head was accepted"
        if event == "pull_request":
            final_env = env | {"GITHUB_RUN_ID": "5678", "GITHUB_RUN_ATTEMPT": "1"}
            subprocess.run(["python3", str(ROOT / "scripts/finalize-candidate-manifest.py"), str(root)], cwd=ROOT, env=final_env, check=True)
            manifest_bytes = (root / "candidate-manifest/candidate-manifest.json").read_bytes()
            manifest = json.loads(manifest_bytes)
            assert set(manifest) == {"schemaVersion", "repository", "component", "sourceSha", "pullRequestNumber", "candidateVersion", "provenance", "mavenArtifacts", "images"}
            assert manifest["images"] == manifest["mavenArtifacts"] == []
            assert manifest["provenance"]["build"]["event"] == event
            assert manifest["provenance"]["publication"]["event"] == "workflow_run"
            event_path = root / "candidate-event/event.json"
            event_doc = json.loads(event_path.read_text())
            assert event_doc["manifest_sha256"] == hashlib.sha256(manifest_bytes).hexdigest()
            assert set(event_doc) == {"schema_version", "source_repository", "source_sha", "pull_request_number", "component", "candidate_version", "publication_run_id", "manifest_sha256", "compatibility_set_id"}
            fakebin = root / "bin"
            fakebin.mkdir()
            capture = root / "dispatch.json"
            gh = fakebin / "gh"
            gh.write_text("#!/usr/bin/env python3\nimport pathlib,sys\npathlib.Path(%r).write_text(sys.stdin.read())\nassert sys.argv[1:]==['api','repos/The-Pipeline-Framework/pipelineframework/dispatches','--input','-']\n" % str(capture))
            gh.chmod(0o755)
            subprocess.run(["python3", str(ROOT / "scripts/dispatch-candidate-event.py"), str(event_path)], cwd=ROOT, env=env | {"PATH": f"{fakebin}:{os.environ['PATH']}"}, check=True)
            dispatch = json.loads(capture.read_text())
            assert dispatch == {"event_type": "tpf-candidate-v1", "client_payload": event_doc}
            (root / "build-metadata.sha256").write_text("0" * 64 + "  build-metadata.json\n")
            rejected = subprocess.run(["python3", str(ROOT / "scripts/validate-candidate-source.py"), str(root), str(pr_path)], cwd=ROOT, env=validation_env, capture_output=True)
            assert rejected.returncode != 0, "corrupted metadata checksum was accepted"
print("PR/main producer, rejection, final-manifest, and event-dispatch fixtures passed")
