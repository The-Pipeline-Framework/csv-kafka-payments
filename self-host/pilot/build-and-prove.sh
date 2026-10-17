#!/usr/bin/env bash
set -euo pipefail
pilot_repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$pilot_repo"
args=()
if [[ -n "${TPF_MAVEN_ARGS_FILE:-}" ]]; then
  while IFS= read -r -d '' argument; do args+=("$argument"); done < "$TPF_MAVEN_ARGS_FILE"
else
  read -r -a args <<< "${MAVEN_ARGS:-}" || true
fi
has_cache=false
for argument in "${args[@]}"; do
  [[ "$argument" != -Dmaven.repo.local=* ]] || has_cache=true
done
if [[ "$has_cache" == false ]]; then args+=("-Dmaven.repo.local=$pilot_repo/.m2/repository"); fi
export PIPELINE_CONFIG="$pilot_repo/config/pipeline.pilot.yaml" PIPELINE_TRANSPORT=REST
./build-pipeline-runtime.sh \
  -pl common,payments-processing-svc,pipeline-runtime-svc,orchestrator-svc \
  -DskipTests -DskipITs=true -Dtpf.build.platform=FUNCTION \
  -Dtpf.build.function.http-bridge=true -Dtpf.build.lambda.scope=compile \
  -Dquarkus.container-image.build=false \
  -Dtpf.await.kafka.reactive-messaging.enabled=false \
  -Dcsv-payments.payment-provider.enabled=false \
  -Dcsv-payments.payment-provider-sqs.enabled=true "${args[@]}"
for module in ingress-lambda-svc worker-lambda-svc provider-lambda-svc; do
  ./mvnw -B -ntp -f "$module/pom.xml" clean verify \
    -Dquarkus.container-image.build=false "${args[@]}"
done
# This is a test fixture dependency, never installed into the application Maven cache.
# Candidate CI may materialize its own compatible BOM; use the exact previously signed
# public native handler JAR to preserve this proof's declared host byte authority.
export TPF_PILOT_HOST_FIXTURE_URL='https://central.sonatype.com/repository/maven-snapshots/org/pipelineframework/pipelineframework-aws-durable-coordination-host/26.10.1-SNAPSHOT/pipelineframework-aws-durable-coordination-host-26.10.1-20261015.182717-11.jar'
export TPF_PILOT_HOST_FIXTURE_SHA256='1c6e89433cd7687c301487638cb46ce85d94a5714683b9b865eb349240e31b6b'
python3 - <<'PY'
from pathlib import Path
import hashlib, os, tempfile, urllib.request
p=Path('target/pilot-published-host.jar')
sha=os.environ['TPF_PILOT_HOST_FIXTURE_SHA256']
if p.exists() and hashlib.sha256(p.read_bytes()).hexdigest()!=sha:p.unlink()
if not p.exists():
    p.parent.mkdir(exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=p.parent,delete=False) as output:
        temporary=Path(output.name)
        try:
            with urllib.request.urlopen(os.environ['TPF_PILOT_HOST_FIXTURE_URL'],timeout=60) as response:
                output.write(response.read())
            output.flush()
            if hashlib.sha256(temporary.read_bytes()).hexdigest()!=sha:
                raise RuntimeError('Published native host fixture digest mismatch')
            temporary.replace(p)
        finally:
            temporary.unlink(missing_ok=True)
PY
python3 self-host/pilot/run-local-proof.py --execute-workers --probe-part-limit \
  --host-jar "$pilot_repo/target/pilot-published-host.jar" \
  --host-sha256 "$TPF_PILOT_HOST_FIXTURE_SHA256"
