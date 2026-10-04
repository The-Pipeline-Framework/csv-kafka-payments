#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$repo_root"
export TPF_CSV_OBSERVABILITY=true
export TPF_CSV_OTEL_ENABLED=true TPF_CSV_OTEL_SDK_DISABLED=false
export TPF_CSV_OTEL_OPTIONS='-Dpipeline.telemetry.enabled=true -Dpipeline.telemetry.metrics.enabled=true -Dpipeline.telemetry.tracing.enabled=true -Dquarkus.otel.exporter.otlp.endpoint=http://lgtm:4318 -Dquarkus.otel.exporter.otlp.protocol=http/protobuf -Dquarkus.otel.metric.export.interval=1s -Dquarkus.otel.traces.sampler=always_on'
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-csv-telemetry-proof}"
export IMAGE_TAG="${IMAGE_TAG:-observability-proof}"
export TPF_REPO_ROOT="$repo_root"
export TPF_CSV_COORDINATOR_IMAGE="${IMAGE_REGISTRY:-localhost}/${IMAGE_GROUP:-csv-payments}/orchestrator-svc:$IMAGE_TAG"
export TPF_CSV_WORKER_IMAGE="$TPF_CSV_COORDINATOR_IMAGE"
export TPF_CSV_RUNTIME_IMAGE="${IMAGE_REGISTRY:-localhost}/${IMAGE_GROUP:-csv-payments}/pipeline-runtime-svc:$IMAGE_TAG"
export TPF_CSV_PERSISTENCE_IMAGE="${IMAGE_REGISTRY:-localhost}/${IMAGE_GROUP:-csv-payments}/persistence-svc:$IMAGE_TAG"
export TPF_CSV_RECORD_COUNT=12 TPF_CSV_ADMISSION_PROFILE=burst
export TPF_KEEP_STACK=true TPF_KEEP_STACK_ON_FAILURE=true
export TPF_MAVEN_ARGS="${MAVEN_ARGS:-} -Dmaven.repo.local=$PWD/.m2/repository"
compose_files=(-f self-host/container/compose.yaml -f self-host/container/compose.observability.yaml)
cleanup() {
  local status=$?
  if (( status != 0 )); then
    docker compose "${compose_files[@]}" logs --no-color --tail=500 >&2 || true
  fi
  docker compose "${compose_files[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  return "$status"
}
trap cleanup EXIT INT TERM
python3 -m unittest discover -s self-host/container -p test_verify_observability.py
for transport in sqs kafka; do
  export TPF_CSV_AWAIT_TRANSPORT=$transport
  compose_files=(-f self-host/container/compose.yaml -f self-host/container/compose.observability.yaml)
  if [[ "$transport" == kafka ]]; then compose_files+=(-f self-host/container/compose.kafka.yaml); fi
  unset TPF_SKIP_CONTAINER_BUILD || true
  ./self-host/container/run-container-ha-demo.sh --prepare-images
  export TPF_SKIP_CONTAINER_BUILD=true
  ./self-host/container/run-container-ha-demo.sh --ci
  python3 self-host/container/verify_observability.py \
    --tempo "http://localhost:${TPF_TEMPO_PORT:-3200}" \
    --prometheus "http://localhost:${TPF_PROMETHEUS_PORT:-9090}" \
    --output "target/telemetry-proof/$transport.json"
  docker compose "${compose_files[@]}" down -v --remove-orphans
  if [[ "$transport" == sqs ]]; then
    # A fresh backend prevents successful earlier worker data from masking disablement.
    export TPF_CSV_WORKER_OTEL_SDK_DISABLED=true
    compose_files=(-f self-host/container/compose.yaml -f self-host/container/compose.observability.yaml)
    ./self-host/container/run-container-ha-demo.sh --ci
    if python3 self-host/container/verify_observability.py --timeout 30 \
      --tempo "http://localhost:${TPF_TEMPO_PORT:-3200}" \
      --prometheus "http://localhost:${TPF_PROMETHEUS_PORT:-9090}" \
      --output target/telemetry-proof/worker-mismatch.json \
      > target/telemetry-proof/worker-mismatch.log 2>&1; then
      echo 'ERROR: telemetry proof accepted a worker with its SDK disabled' >&2
      exit 1
    fi
    if ! grep -q 'Missing framework span .* from csv-worker' target/telemetry-proof/worker-mismatch.log; then
      cat target/telemetry-proof/worker-mismatch.log >&2
      exit 1
    fi
    echo 'Verified that successful coordinator/runtime export cannot mask a disabled worker SDK.'
    docker compose "${compose_files[@]}" down -v --remove-orphans
    unset TPF_CSV_WORKER_OTEL_SDK_DISABLED
  fi

done
