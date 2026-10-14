#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -n "${TPF_MAVEN_ARGS_FILE:-}" ]]; then
  maven_args=()
  while IFS= read -r -d '' argument; do maven_args+=("$argument"); done < "$TPF_MAVEN_ARGS_FILE"
else
  read -r -a maven_args <<< "${MAVEN_ARGS:-}"
fi
for argument in "$@"; do
  case "$argument" in
    -DskipTests*|-DskipUnitTests*|-Dmaven.test.skip*)
      echo "Provider Lambda verification must run its tests: $argument" >&2
      exit 2
      ;;
  esac
done

export PIPELINE_CONFIG="$ROOT_DIR/config/pipeline.container-sqs.yaml"
export PIPELINE_TRANSPORT=REST
"$ROOT_DIR/build-pipeline-runtime.sh" \
  -pl common,payments-processing-svc \
  -Dtest=PaymentProviderSqsAwaitMockTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dquarkus.container-image.build=false \
  -Dtpf.build.platform=FUNCTION -Dtpf.build.lambda.scope=compile "$@"

"$ROOT_DIR/mvnw" "${maven_args[@]}" \
  -Dmaven.repo.local="$ROOT_DIR/.m2/repository" \
  -f "$ROOT_DIR/provider-lambda-svc/pom.xml" \
  -Dquarkus.container-image.build=false -Dquarkus.profile=lambda \
  -Dtpf.build.platform=FUNCTION -Dtpf.build.transport=REST \
  -Dtpf.build.lambda.scope=compile clean verify "$@"
