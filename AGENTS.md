# CSV Kafka Payments

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
