#!/usr/bin/env python3
"""Exercise real CLI registration with an injected short-lived proof bearer."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ORGANIZATION = "5245b6bda864febd1357a3f263375e5b6956ea8f9c53060a3244124b148e9892"


def main():
    token = os.environ.get("TPF_CREDENTIAL_PROOF")
    if not token:
        raise SystemExit("Inject the short-lived proof token; never pass it in arguments.")
    results = []
    for shape, module in [("monolith", "monolith-svc"), ("modular", "orchestrator-svc")]:
        descriptor = ROOT / module / "target/pipeline-release.json"
        before = descriptor.read_bytes()
        command = ["python3", str(ROOT / "scripts/cloud-proof-maven.py"), "--tpf", "deploy"]
        for environment in ["development", "staging"]:
            responses = []
            for attempt in range(2):
                process = subprocess.run([*command, "proof-" + environment, "--release", str(descriptor),
                                          "--output", "json"], cwd=ROOT, capture_output=True, text=True)
                if process.returncode:
                    # Do not relay arbitrary diagnostics that might carry credentials.
                    raise SystemExit("Native CLI proof failed: " + shape + "/" + environment)
                response = json.loads(process.stdout)
                assert response["status"] == "REGISTERED"
                assert response["descriptorDigest"] == "sha256:" + hashlib.sha256(before).hexdigest()
                for stage in ["physicalDeployment", "runtimeVerification", "activation"]:
                    assert response[stage] == "NOT_REQUESTED"
                responses.append(response)
            assert responses[0]["deploymentId"] == responses[1]["deploymentId"]
            results.append({"shape": shape, "retryStable": True, **responses[0]})
            print(shape, environment, responses[0]["deploymentId"], "retry stable")

        def rejected(organization, body, key):
            endpoint = ("http://localhost:8081/api/v1/organizations/" + organization
                        + "/applications/csv-kafka-payments/environments/development/deployments")
            request = urllib.request.Request(endpoint, data=body, headers={
                "Authorization": "Bearer " + token,
                "Content-Type": "application/vnd.tpf.pipeline-release+json",
                "Idempotency-Key": key, "X-TPF-Deployment-Mode": "CUSTOMER_MANAGED"})
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    return response.status
            except urllib.error.HTTPError as error:
                return error.code

        conflict = rejected(ORGANIZATION, before + b"\n", shape + "-live-conflict")
        tenant = rejected("org-foreign-proof", before, shape + "-live-cross-tenant")
        assert conflict == 409, conflict
        assert tenant == 401, tenant
        assert descriptor.read_bytes() == before
        results.append({"shape": shape, "immutableConflictHttp": conflict,
                        "crossTenantHttp": tenant, "originalBytesUnchanged": True})
        print(shape, "conflict", conflict, "cross-tenant", tenant, "original bytes unchanged")
    output = ROOT / ".tpf/cloud-proof-evidence.json"
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(results, indent=2) + "\n")
    print("Credential-free evidence:", output)


if __name__ == "__main__":
    main()
