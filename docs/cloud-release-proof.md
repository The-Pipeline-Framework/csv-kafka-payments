# Immutable Release-to-Cloud proof — 2026-10-06

This branch captures the isolated proof build, not production deployment.
The reactor version `0.0.0-cloud-proof-20261006` pins the already-published
GitHub Packages artifacts; do not rebuild and overwrite those coordinates.
TPF BOM and release plugin remain `26.10.1-SNAPSHOT`.

The release Maven plugin produced original descriptors and archives for both
monolith and five-host modular layouts. Both were remotely resolved and verified
by native CLI `26.10.1-SNAPSHOT` (source
`7144b49bf0ea10f5e1c7ca7403b7d4143d49c0f2`). No descriptor was hand-authored or
rewritten. Producer archive checks do not substitute for remote resolution.

`scripts/cloud-proof-maven.py` uses the operator's authenticated `gh` session
for package resolution. Its `--tpf` mode currently expects the macOS ARM64 native
distribution at `.m2/proof-cli/tpf-26.10.1-SNAPSHOT-osx-aarch_64/bin/tpf`.
It renders `${TPF_PROOF_ROOT}` in `cloud-proof-verify.yaml`; that marker is a
helper template, not a claim that the native CLI expands environment variables.
The public Maven settings reader does not expand `${env.*}`, so the helper
materializes owner-only settings in a temporary directory and removes them after
invocation. Never log the evaluated settings or put credentials in Git.

With the real isolated Cloud apps running at localhost:8080/8081 and an approved
short-lived SERVICE bearer injected as `TPF_CREDENTIAL_PROOF`, run:

```sh
python3 scripts/check-cloud-registration-proof.py
```

This is an opt-in live test, not a default unit test. It invokes the native CLI
for development and staging, repeats each association, rejects altered bytes
under the same immutable Release identity and a fresh command key (409), rejects
a foreign tenant (401), and checks original descriptor bytes are unchanged.
Credential-free results go to ignored `.tpf/cloud-proof-evidence.json`.

The live proof passed both shapes. Browser inspection showed two VERIFIED
Releases and four REGISTERED Deployments with semantic steps, types, cardinalities,
capabilities and placement. Physical deployment, runtime verification and
activation are NOT_REQUESTED. This is local CLI execution with real CI identity,
not a remotely executed GitHub Action or Maven deployment adapter.

Human device login succeeded but selected an older Organization without showing
a selector. Cloud correctly rejected it for the proof tenant; Organization-aware
human login remains an acceptance gate. Do not weaken tenant checks to bypass it.

Deterministic archive regressions:

```sh
python3 -m unittest discover -s scripts -p 'test_cloud_proof_archives.py'
```

Companion Cloud and infrastructure PRs retain exact descriptor/artifact hashes,
Terraform ownership and live resource IDs in
`docs/release-cloud-proof-evidence-20261006.md` and
`docs/release-proof-identity.md` respectively.
