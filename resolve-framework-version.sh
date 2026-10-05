#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAVEN_EXECUTABLE="${1:-$ROOT_DIR/mvnw}"
FRAMEWORK_POM="${2:-$ROOT_DIR/pom.xml}"
MAVEN_REPOSITORY="${3:-${MAVEN_REPOSITORY:-$ROOT_DIR/.m2/repository}}"

# Read the dependency selected by Maven, not an optional application property.
# This also respects BOM management and compatibility-set version overrides.
EFFECTIVE_POM="$(mktemp "${TMPDIR:-/tmp}/csv-effective-pom.XXXXXX")"
trap 'rm -f "$EFFECTIVE_POM"' EXIT
MAVEN_ARGS= "$MAVEN_EXECUTABLE" -q -N -f "$FRAMEWORK_POM" help:effective-pom \
  -Doutput="$EFFECTIVE_POM" -Dmaven.repo.local="$MAVEN_REPOSITORY" >&2

python3 - "$EFFECTIVE_POM" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
# Prefer a direct dependency; otherwise use the effective managed coordinate.
for path in ("m:dependencies/m:dependency", "m:dependencyManagement/m:dependencies/m:dependency"):
    versions = {
        dependency.findtext("m:version", default="", namespaces=ns).strip()
        for dependency in root.findall(path, ns)
        if dependency.findtext("m:groupId", namespaces=ns) == "org.pipelineframework"
        and dependency.findtext("m:artifactId", namespaces=ns) == "pipelineframework"
        and dependency.findtext("m:type", default="jar", namespaces=ns) == "jar"
    }
    if not versions:
        continue
    if len(versions) != 1 or any(not version or "${" in version or any(c.isspace() for c in version) for version in versions):
        sys.exit("Unable to resolve one concrete org.pipelineframework:pipelineframework:jar version from effective POM")
    print(next(iter(versions)))
    break
else:
    sys.exit("Effective POM does not select org.pipelineframework:pipelineframework:jar")
PY
