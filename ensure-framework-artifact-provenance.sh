#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAVEN_REPOSITORY="${MAVEN_REPOSITORY:-$ROOT_DIR/.m2/repository}"
PROVENANCE_FILE="$MAVEN_REPOSITORY/org/pipelineframework/tpf-worktree-provenance.properties"
FRAMEWORK_VERSION="$(
  "$ROOT_DIR/resolve-framework-version.sh" \
    "$ROOT_DIR/mvnw" "$ROOT_DIR/pom.xml" "$MAVEN_REPOSITORY"
)"
FRAMEWORK_COORDINATE="org.pipelineframework:pipelineframework:${FRAMEWORK_VERSION}"
FRAMEWORK_RUNTIME_JAR="$MAVEN_REPOSITORY/org/pipelineframework/pipelineframework/${FRAMEWORK_VERSION}/pipelineframework-${FRAMEWORK_VERSION}.jar"

"$ROOT_DIR/mvnw" -q dependency:get \
  -Dartifact="$FRAMEWORK_COORDINATE" \
  -Dtransitive=false \
  -Dmaven.repo.local="$MAVEN_REPOSITORY"

if [[ ! -f "$FRAMEWORK_RUNTIME_JAR" ]]; then
  echo "Resolved framework runtime JAR not found: $FRAMEWORK_RUNTIME_JAR" >&2
  exit 1
fi

FRAMEWORK_RUNTIME_SHA256="$($ROOT_DIR/hash-jar-content.sh "$FRAMEWORK_RUNTIME_JAR")"

mkdir -p "$(dirname "$PROVENANCE_FILE")"
{
  printf 'framework.version=%s\n' "$FRAMEWORK_VERSION"
  printf 'framework.commit=released-artifact\n'
  printf 'framework.source.fingerprint=%s\n' "$FRAMEWORK_COORDINATE"
  printf 'framework.runtime.sha256=%s\n' "$FRAMEWORK_RUNTIME_SHA256"
} > "$PROVENANCE_FILE"

echo "Framework artifact provenance:"
sed 's/^/  /' "$PROVENANCE_FILE"
