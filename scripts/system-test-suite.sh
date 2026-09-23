#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
suite=${1:?usage: system-test-suite.sh smoke|ha|ha-scale|native}
read -r -a maven_args <<< "${MAVEN_ARGS:-}" || true
cd "$repo_root"

case "$suite" in
  smoke)
    ./mvnw -B install -Dquarkus.container-image.build=false --no-transfer-progress "${maven_args[@]}"
    ;;
  ha|ha-scale)
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
    cleanup_compose() {
      local exit_code=$?
      local transport=${TPF_CSV_AWAIT_TRANSPORT:-sqs}
      local compose=(-f self-host/container/compose.yaml)
      if [[ "$transport" == kafka ]]; then compose+=(-f self-host/container/compose.kafka.yaml); fi
      if (( exit_code != 0 )); then
        docker compose "${compose[@]}" ps >&2 || true
        docker compose "${compose[@]}" logs --no-color --tail=500 >&2 || true
      fi
      docker compose "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
      return "$exit_code"
    }
    run_profile() {
      local transport=$1 profile=$2
      export TPF_CSV_AWAIT_TRANSPORT=$transport TPF_CSV_ADMISSION_PROFILE=$profile
      if [[ "$suite" == ha-scale ]]; then
        export TPF_CSV_RECORD_COUNT=10000
        export TPF_CSV_TRANSITION_TRANSPORT_DEADLINE=PT180S
        export TPF_CSV_FIXTURE_RUN_DEADLINE_SECONDS=300
      else
        unset TPF_CSV_RECORD_COUNT TPF_CSV_TRANSITION_TRANSPORT_DEADLINE TPF_CSV_FIXTURE_RUN_DEADLINE_SECONDS || true
      fi
      export TPF_MAVEN_ARGS="${MAVEN_ARGS:-}"
      ./self-host/container/run-container-ha-demo.sh --prepare-images
      export TPF_SKIP_CONTAINER_BUILD=true TPF_KEEP_STACK_ON_FAILURE=true
      ./self-host/container/run-container-ha-demo.sh --ci
      cleanup_compose
    }
    configure_testcontainers
    trap cleanup_compose EXIT INT TERM
    if [[ "$suite" == ha-scale ]]; then
      python3 -m unittest -v self-host/container/test_demo_client.py
    fi
    for transport in sqs kafka; do
      for profile in slow burst; do
        run_profile "$transport" "$profile"
      done
    done
    ;;
  native)
    ./mvnw -B clean install -Pnative -Dquarkus.container-image.build=false --no-transfer-progress "${maven_args[@]}"
    ;;
  *)
    echo "unknown suite: $suite" >&2
    exit 2
    ;;
esac
