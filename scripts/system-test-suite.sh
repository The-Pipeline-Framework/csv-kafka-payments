#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
suite=${1:?usage: system-test-suite.sh smoke|ha|ha-scale|provider-reject|observability|native}
read -r -a maven_args <<< "${MAVEN_ARGS:-}" || true
cd "$repo_root"

configure_testcontainers() {
  export DOCKER_HOST="${DOCKER_HOST:-unix:///var/run/docker.sock}"
  if ! docker info >/dev/null 2>&1; then sudo systemctl start docker || true; fi
  for attempt in {1..10}; do
    docker info >/dev/null 2>&1 && break
    sleep 3
  done
  export DOCKER_API_VERSION="$(docker version --format '{{.Server.APIVersion}}')"
  [[ -n "$DOCKER_API_VERSION" ]] || { echo "Docker server API version is unavailable" >&2; return 1; }
  export TESTCONTAINERS_DOCKER_CLIENT_STRATEGY=org.testcontainers.dockerclient.UnixSocketClientProviderStrategy
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
  export TPF_CI_QUIET="${TPF_CI_QUIET:-true}"
  cat > "$HOME/.testcontainers.properties" <<EOF
docker.client.strategy=$TESTCONTAINERS_DOCKER_CLIENT_STRATEGY
docker.host=$DOCKER_HOST
EOF
  mkdir -p "$HOME/.cache/google-cloud-tools-java/jib"
}

case "$suite" in
  smoke)
    ./mvnw -B install -Dquarkus.container-image.build=false --no-transfer-progress "${maven_args[@]}"
    MAVEN_ARGS="${MAVEN_ARGS:-}" bash self-host/pilot/build-and-prove.sh
    ;;
  provider-reject)
    configure_testcontainers
    export MAVEN_OPTS="${MAVEN_OPTS:--Xmx1g -Xms512m}"
    for argument in "${maven_args[@]}"; do
      case "$argument" in
        -Dmaven.repo.local=*) export MAVEN_REPOSITORY="${argument#*=}" ;;
      esac
    done
    : "${MAVEN_REPOSITORY:?coordinated provider-reject proof requires the candidate Maven repository}"
    ./build-modular-telemetry-images.sh
    cp config/runtime-mapping/modular-strict.yaml config/pipeline.runtime.yaml
    ./mvnw -B --no-transfer-progress -f pom.xml -pl orchestrator-svc -am \
      -Dquarkus.container-image.build=false \
      -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false \
      -Dcsv.e2e.prebuilt.modular.images=true \
      -Dcsv.e2e.telemetry.enabled=true \
      -Dcsv.e2e.telemetry.happy-path-only=false \
      -Dcsv.e2e.input.file=input-csv-file-processing-svc/csv/payments_1k.csv \
      -Dcsv-payments.payment-provider.provider-reject-probability=0.08 \
      -Dtest=CsvPaymentsProviderRejectEndToEndIT test "${maven_args[@]}"
    ;;
  ha|ha-scale)
    cleanup_compose() {
      local exit_code=${1:-$?}
      local transport=${TPF_CSV_AWAIT_TRANSPORT:-sqs}
      local compose=(-f self-host/container/compose.yaml)
      # The demo runs in a child shell, so its Compose interpolation values do not
      # propagate here. Supply them before collecting logs from a failed lane.
      export TPF_REPO_ROOT="$repo_root"
      export TPF_CSV_RELEASE_VERSION="${TPF_CSV_RELEASE_VERSION:-cleanup}"
      if [[ "$transport" == kafka ]]; then compose+=(-f self-host/container/compose.kafka.yaml); fi
      if (( exit_code != 0 )); then
        if [[ "$suite" == ha-scale ]]; then
          # Java prints thread dumps for SIGQUIT; capture both ends of a stalled page.
          docker compose "${compose[@]}" kill --signal=SIGQUIT worker runtime >&2 || true
          sleep 2
        fi
        docker compose "${compose[@]}" ps >&2 || true
        docker compose "${compose[@]}" logs --no-color --tail=500 >&2 || true
        if [[ "$suite" == ha-scale ]]; then
          for table in tpf_await_interaction tpf_await_unit tpf_await_admission; do
            echo "HA scale diagnostic $table:" >&2
            docker compose "${compose[@]}" exec -T localstack \
              awslocal dynamodb scan --table-name "$table" --select COUNT --output json >&2 || true
          done
          find self-host/container/target/tpf-container-ha/input -maxdepth 3 -type f \
            -printf 'HA scale file %P %s bytes\n' >&2 || true
        fi
      fi
      if [[ "$suite" == ha-scale ]]; then
        echo "HA trace: ${transport}/${TPF_CSV_ADMISSION_PROFILE:-unknown} exit=${exit_code}"
        local trace
        trace="$(docker compose "${compose[@]}" logs --no-color --timestamps coordinator worker 2>&1 \
          | grep -E 'event=(sqs_work_|execution_lease_|execution_page_)|Scheduling async execution retry|ContextNotActiveException|PIPELINE FINISHED')" || true
        printf '%s\n' "$trace"
        if ! docker compose "${compose[@]}" logs --no-color --no-log-prefix --timestamps worker \
          | python3 self-host/container/report-worker-arc-trace.py; then
          if (( exit_code == 0 )); then
            exit_code=1
          fi
        fi
      fi
      docker compose "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
      return "$exit_code"
    }
    run_profile() {
      local transport=$1 profile=$2
      export TPF_CSV_AWAIT_TRANSPORT=$transport TPF_CSV_ADMISSION_PROFILE=$profile
      if [[ "$suite" == ha-scale ]]; then
        export TPF_KEEP_STACK=true TPF_CSV_QUEUE_TRACE_LEVEL=DEBUG
        export TPF_CSV_WORKER_ARC_TRACE_LEVEL=TRACE
        export TPF_CSV_RECORD_COUNT=10000
        export TPF_CSV_TRANSITION_TRANSPORT_DEADLINE=PT180S
        export TPF_CSV_FIXTURE_RUN_DEADLINE_SECONDS=900
      else
        unset TPF_CSV_RECORD_COUNT TPF_CSV_TRANSITION_TRANSPORT_DEADLINE TPF_CSV_FIXTURE_RUN_DEADLINE_SECONDS || true
      fi
      export TPF_MAVEN_ARGS="${MAVEN_ARGS:-}"
      export TPF_SKIP_CONTAINER_BUILD=false
      ./self-host/container/run-container-ha-demo.sh --prepare-images || return $?
      export TPF_SKIP_CONTAINER_BUILD=true TPF_KEEP_STACK_ON_FAILURE=true
      ./self-host/container/run-container-ha-demo.sh --ci
    }
    configure_testcontainers
    trap cleanup_compose EXIT INT TERM
    if [[ "$suite" == ha-scale ]]; then
      python3 -m unittest -v self-host/container/test_demo_client.py
    fi
    result=0
    for transport in sqs kafka; do
      for profile in slow burst; do
        if [[ "$suite" == ha-scale ]]; then
          # This diagnostic candidate isolates the Kafka burst REST response failure.
          [[ "$transport/$profile" == kafka/burst ]] || continue
          lane_status=0
          run_profile "$transport" "$profile" || lane_status=$?
          cleanup_compose "$lane_status" || result=1
        else
          run_profile "$transport" "$profile"
          cleanup_compose
        fi
      done
    done
    trap - EXIT INT TERM
    exit "$result"
    ;;
  observability)
    ./self-host/container/run-observability-proof.sh
    ;;
  native)
    ./mvnw -B clean install -Pnative -Dquarkus.container-image.build=false --no-transfer-progress "${maven_args[@]}"
    ;;
  *)
    echo "unknown suite: $suite" >&2
    exit 2
    ;;
esac
