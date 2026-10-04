#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
MVN_BIN="${MVN_BIN:-$ROOT_DIR/mvnw}"

if [[ -n "${TPF_MAVEN_ARGS_FILE:-}" ]]; then
  maven_args=()
  while IFS= read -r -d '' argument; do maven_args+=("$argument"); done < "$TPF_MAVEN_ARGS_FILE"
else
  if [[ " ${MAVEN_ARGS:-} " != *" -Dmaven.repo.local="* ]]; then
    export MAVEN_ARGS="${MAVEN_ARGS:-} -Dmaven.repo.local=$ROOT_DIR/.m2/repository"
  fi
  read -r -a maven_args <<< "${MAVEN_ARGS}"
fi

"$MVN_BIN" "${maven_args[@]}" -B -f "$ROOT_DIR/pom.xml" -pl common -am install \
  -DskipTests \
  -Dquarkus.container-image.build=false \
  --no-transfer-progress
