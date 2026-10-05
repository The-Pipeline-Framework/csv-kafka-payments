#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
log_dir="${TPF_RUN_DIR:-${SCRIPT_DIR}/target/tpf-container-ha}/container-logs"
project="${COMPOSE_PROJECT_NAME:-csv-payments-self-host-ha}"
mkdir -p "${log_dir}"
# Later Actions steps have lost the launcher's Compose interpolation variables.
# Read existing containers instead; never inspect their secret-bearing environment.
docker ps -a --no-trunc -q --filter "label=com.docker.compose.project=${project}" > "${log_dir}/container-ids.txt"
if [[ ! -s "${log_dir}/container-ids.txt" ]]; then
  echo "No HA containers found for Compose project ${project}." >&2
  exit 1
fi
result=0
while IFS= read -r container; do
  if [[ ! "${container}" =~ ^[a-f0-9]+$ ]]; then
    echo "Invalid Docker container ID in diagnostic listing." >&2
    exit 1
  fi
  docker inspect --format '{{.Name}} image={{.Image}} status={{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}}' \
    "${container}" > "${log_dir}/${container}.state.txt" 2>&1 || result=1
  docker logs --timestamps --tail=500 "${container}" > "${log_dir}/${container}.log" 2>&1 || result=1
  cat "${log_dir}/${container}.state.txt" "${log_dir}/${container}.log"
done < "${log_dir}/container-ids.txt"
exit "${result}"
