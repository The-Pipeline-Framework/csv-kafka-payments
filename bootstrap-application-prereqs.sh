#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
MVN_BIN="${MVN_BIN:-$ROOT_DIR/mvnw}"

if [[ " ${MAVEN_ARGS:-} " != *" -Dmaven.repo.local="* ]]; then
  export MAVEN_ARGS="${MAVEN_ARGS:-} -Dmaven.repo.local=$ROOT_DIR/.m2/repository"
fi

"$MVN_BIN" -B -f "$ROOT_DIR/pom.xml" -pl common -am install \
  -DskipTests \
  -Dquarkus.container-image.build=false \
  --no-transfer-progress
