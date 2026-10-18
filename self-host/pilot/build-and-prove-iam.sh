#!/usr/bin/env bash
# Run after build-and-prove.sh, whose final installed orchestrator is AWS_DURABLE.
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
  # This proof owns its disclosed fixture input, never a deployment binding.
  [[ "$argument" != -Dcsv.cloud.binding-file=* && "$argument" != -Dcsv.cloud.worker-binding-file=* ]] || { echo 'Do not select a live binding for the fixture' >&2; exit 1; }
done
if [[ "$has_cache" == false ]]; then args+=("-Dmaven.repo.local=$pilot_repo/.m2/repository"); fi
# Save all ordinary artifacts before the first fixture-bound build, including action.
# Never overwrite an original snapshot on a rerun with a previously bound artifact.
python3 - <<'PY'
from pathlib import Path
import shutil, zipfile
for module in ('ingress-lambda-svc', 'durable-action-lambda-svc', 'worker-lambda-svc'):
    source=Path(module)/'target/function.zip'
    with zipfile.ZipFile(source) as archive:
        if any(name.startswith('META-INF/csv-cloud-') for name in archive.namelist()):
            raise RuntimeError('IAM proof requires ordinary unbound starting artifacts: '+module)
for module in ('ingress-lambda-svc', 'durable-action-lambda-svc', 'worker-lambda-svc'):
    destination=Path('target/iam-original-packages')/module/'function.zip'
    destination.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(Path(module)/'target/function.zip',destination)
shutil.rmtree('target/iam-fixture-bound-packages',ignore_errors=True)
PY
restore_normal_packages() {
  proof_status=$?
  trap - EXIT
  # Restoration is unconditional even if evidence capture fails.
  if ! python3 - <<'PY'
from pathlib import Path
import hashlib, json, shutil, zipfile
records=[];failures=[]
for module in ('ingress-lambda-svc', 'durable-action-lambda-svc', 'worker-lambda-svc'):
    normal=Path(module)/'target/function.zip'
    original=Path('target/iam-original-packages')/module/'function.zip'
    try:
        with zipfile.ZipFile(normal) as archive:
            receipts=[name for name in archive.namelist() if name.startswith('META-INF/csv-cloud-') and name.endswith('-evidence.json')]
            if receipts:
                evidence=Path('target/iam-fixture-bound-packages')/module
                evidence.mkdir(parents=True,exist_ok=True)
                shutil.copyfile(normal,evidence/'function.zip')
                for name in receipts:
                    (evidence/Path(name).name).write_bytes(archive.read(name))
                records.append({'module':module,'fixtureBoundPackage':str(evidence/'function.zip'),
                                'fixtureBoundSha256':hashlib.sha256(normal.read_bytes()).hexdigest()})
    except Exception as failure:
        failures.append(module+': '+str(failure))
    finally:
        try:
            shutil.copyfile(original,normal)
            # Bound metadata is retained beside the labelled evidence ZIP, never
            # beside an ordinary artifact whose bytes no longer contain it.
            (normal.parent/'cloud-binding-evidence.json').unlink(missing_ok=True)
            records.append({'module':module,'restoredOrdinaryPackage':str(normal),
                            'restoredSha256':hashlib.sha256(normal.read_bytes()).hexdigest()})
        except Exception as failure:
            failures.append('RESTORATION FAILED '+module+': '+str(failure))
Path('target/iam-package-restoration.json').write_text(json.dumps({'status':'PASS' if not failures else 'FAIL',
    'scope':'Fixture-bound evidence only; ordinary artifact targets restored on success or failure.',
    'packages':records,'failures':failures},indent=2)+'\n')
if failures:raise RuntimeError('; '.join(failures))
PY
  then
    echo 'IAM proof artifact restoration failed; inspect target/iam-package-restoration.json' >&2
    exit 1
  fi
  exit "$proof_status"
}
trap restore_normal_packages EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
python3 -m unittest discover -s build-tools -p 'test_*binding.py'
python3 self-host/pilot/prove-iam-signing.py --write-fixture-binding target/iam-fixture-transport.json
binding="-Dcsv.cloud.binding-file=$pilot_repo/target/iam-fixture-transport.json"
# Preserve the unbound artifact whose digest belongs to the native worker/S3 receipt.
cp ingress-lambda-svc/target/function.zip target/pilot-local-proof/worker-variant/ingress-function.zip
./mvnw -B -ntp -f durable-action-lambda-svc/pom.xml verify \
  -Dquarkus.container-image.build=false "${args[@]}" "$binding"
# Only regenerate the application module affected by the existing HTTP bridge variant.
./mvnw -B -ntp -pl orchestrator-svc clean install -DskipTests -DskipITs=true \
  -Dcsv.runtime.layout=pipeline-runtime -Dtpf.build.transport=REST \
  -Dtpf.build.platform=FUNCTION -Dtpf.build.coordination.host=NATIVE \
  -Dtpf.build.function.http-bridge=true -Dtpf.build.lambda.scope=compile \
  "-Dpipeline.configArg=-Apipeline.config=$pilot_repo/config/pipeline.pilot.yaml" \
  -Dquarkus.container-image.build=false "${args[@]}"
./mvnw -B -ntp -f ingress-lambda-svc/pom.xml verify \
  -Dquarkus.container-image.build=false "${args[@]}" "$binding"
# No clean: prove an ordinary unbound rebuild removes both resources and registration.
./mvnw -B -ntp -f ingress-lambda-svc/pom.xml package -DskipTests \
  -Dquarkus.container-image.build=false "${args[@]}" -Dcsv.cloud.binding-file=
python3 self-host/pilot/prove-iam-signing.py --expect-unbound
./mvnw -B -ntp -f ingress-lambda-svc/pom.xml package -DskipTests \
  -Dquarkus.container-image.build=false "${args[@]}" "$binding"
python3 self-host/pilot/prove-iam-signing.py
python3 self-host/pilot/prove-worker-binding.py --write-fixture-binding target/worker-fixture-binding.json
worker_binding="-Dcsv.cloud.worker-binding-file=$pilot_repo/target/worker-fixture-binding.json"
cp worker-lambda-svc/target/function.zip target/pilot-local-proof/worker-variant/worker-function.zip
./mvnw -B -ntp -f worker-lambda-svc/pom.xml verify \
  -Dquarkus.container-image.build=false "${args[@]}" "$worker_binding"
./mvnw -B -ntp -f worker-lambda-svc/pom.xml package -DskipTests \
  -Dquarkus.container-image.build=false "${args[@]}" -Dcsv.cloud.worker-binding-file=
python3 self-host/pilot/prove-worker-binding.py --expect-unbound
./mvnw -B -ntp -f worker-lambda-svc/pom.xml package -DskipTests \
  -Dquarkus.container-image.build=false "${args[@]}" "$worker_binding"
python3 self-host/pilot/prove-worker-binding.py
