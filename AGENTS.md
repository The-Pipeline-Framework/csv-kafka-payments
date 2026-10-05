# CSV Kafka Payments

## Development dependency policy

- Development on `main` follows the active TPF snapshot line, currently `26.10.1-SNAPSHOT`; do not pin another TPF component to `26.9.4` or an older snapshot line.
- Maven requires a version: do not use `LATEST`, `RELEASE`, or version ranges to stand in for Git `main`. Select TPF dependencies and annotation processors through `pipelineframework.bom.version`; do not add independent compiler/runtime/Connector version choices. The imported BOM cannot manage Maven plugins, so `tpf.release.maven-plugin.version` is the separate release-plugin version and compatibility-test override point. Keep both properties declared exactly once for system-test materialisation.
- `.mvn/maven.config` includes `-U` so cached snapshot metadata is refreshed. Every Maven invocation still needs the isolated local repository required below.
- Maven-producing repositories publish Central snapshots on pushes to `main` and manually for recovery. Nightly full-train testing remains separate; snapshot publication is not scheduled nightly. Publication is asynchronous, not an atomic cross-repository transaction; a green merge alone does not mean publication completed. Check the publisher before retrying dependent builds.
- Coordinated changes use compatibility sets before merge. Those runs continue to use exact immutable candidate/baseline versions, not floating snapshots. No composite source reactor or extra Maven profile is introduced.
- Freeze compatible published coordinates for a stable release. Never alter an already published release, connector contract identity, or pipeline release pin to make development float.

This repository owns the production-grade CSV payment-processing application built with The Pipeline Framework.
It is an application consumer of released TPF artifacts; it does not own framework, compiler, runtime, connector,
Block, or Expansion semantics.

## Architecture

- `config/`: canonical pipeline, IDL, and runtime-placement configuration.
- `common/`: shared application types and generated protobuf contracts.
- `input-csv-file-processing-svc/`: CSV object admission and parsing.
- `payments-processing-svc/`: Kafka-backed external payment-provider simulation.
- `payment-status-svc/`: approved/rejected status handling.
- `persistence-svc/`: application persistence host.
- `orchestrator-svc/`: coordinator, end-to-end tests, replay, and observability evidence.
- `pipeline-runtime-svc/`: grouped pipeline runtime layout.
- `monolith-svc/`: single-process runtime layout.
- `self-host/container/`: containerized HA reference using Kafka or SQS await completion.

Keep transport, runtime layout, and build topology distinct. Kafka owns the durable external-provider request and
completion boundary; it is not a TPF step transport.

## Cross-repository system tests

Owner-local verification is the first gate. `.github/tpf-system-tests.json` owns the stable smoke, HA, HA-scale and
native suite commands. `TPF Candidate Build` and the trusted publisher create an immutable, source-only candidate
manifest; `tpf/system-tests` records compatibility evidence on that exact source SHA.

For an ordinary single-repository pull request, use the candidate publisher and singleton system-test path above.
For a coordinated change, give every participating pull request the same head-branch name under the same GitHub
owner. Candidate intake discovers those open component pull requests and dispatches one deterministic compatibility
set automatically; later intake events for the same set cancel the older in-flight run. Do **not** wait for
participating candidate publishers and do not merge or publish snapshots one repository at a time.

Use the manual
[`TPF System Tests — Compatibility Set`](https://github.com/The-Pipeline-Framework/pipelineframework/actions/workflows/system-test-compatibility-set.yml)
entry point only when a coordinated set intentionally uses different branch names. Supply one stable set ID and
2–10 pull-request URLs, one per line. Both paths pin each exact PR head and current base, build the participating
Maven reactors and derived downstream closure in dependency order into one isolated repository, and report one
`tpf/system-tests` result to every participating SHA. Do not manually add unchanged downstream repositories or
publish intermediate snapshots; the coordinator derives current-main and downstream closure targets. Do not
substitute snapshots, floating branch heads, source checkouts or a composite Maven reactor. See the canonical
[cross-repository system-test runbook](https://github.com/The-Pipeline-Framework/pipelineframework/blob/main/docs/evolve/cross-repository-system-tests.md).

Repository setup requires repository-scoped dispatch credentials. If the workflow exposes them as
`SYSTEM_TEST_APP_ID` and `SYSTEM_TEST_APP_PRIVATE_KEY`, they must belong to a dispatch-only App installed solely on
`pipelineframework`, never the coordinator App. This source-only publisher does not need Maven package authority;
fork publication additionally requires the
`safe-to-system-test` label. Never expose dispatch or status credentials to owner-suite jobs.

## Build

Every Maven command must use the repository-local cache:

    -Dmaven.repo.local="$PWD/.m2/repository"

Common gates:

    ./mvnw -B test -Dquarkus.container-image.build=false -Dmaven.repo.local="$PWD/.m2/repository"
    ./build-pipeline-runtime.sh -DskipTests -Dquarkus.container-image.build=false
    ./build-monolith.sh -DskipTests -Dquarkus.container-image.build=false

TPF compiler, runtime, and connector dependencies are released artifacts. Do not add source-tree fallbacks or clone
other TPF repositories during the build.

Maven profiles must not select a different source universe, module graph, or build topology. `central-publishing`
is the only permitted publication profile, should this application ever publish artifacts.
