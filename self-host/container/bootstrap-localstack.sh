#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/compose.yaml"

export TPF_REPO_ROOT="${TPF_REPO_ROOT:-${REPO_ROOT}}"
export AWS_REGION="${AWS_REGION:-us-east-1}"

compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

compose_up() {
  if [[ "${TPF_CI_QUIET:-false}" == "true" ]]; then
    compose up --quiet-pull "$@"
    return
  fi
  compose up "$@"
}

awslocal() {
  compose exec -T localstack awslocal "$@"
}

wait_for_localstack() {
  # Override LOCALSTACK_WAIT_SECONDS on slower hosts.
  local timeout_seconds="${LOCALSTACK_WAIT_SECONDS:-300}"
  echo "Waiting for LocalStack..."
  for _ in $(seq 1 "${timeout_seconds}"); do
    if compose exec -T localstack curl -fsS "http://localhost:4566/_localstack/health" >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  echo "Timed out waiting for LocalStack after ${timeout_seconds}s." >&2
  compose logs localstack >&2 || true
  exit 1
}

create_table_if_missing() {
  local table_name="$1"
  shift
  if awslocal dynamodb describe-table --table-name "${table_name}" >/dev/null 2>&1; then
    echo "DynamoDB table exists: ${table_name}"
    return
  fi
  echo "Creating DynamoDB table: ${table_name}"
  awslocal dynamodb create-table --table-name "${table_name}" "$@" >/dev/null
  awslocal dynamodb wait table-exists --table-name "${table_name}"
}

wait_for_await_interaction_indexes() {
  local table_name="tpf_await_interaction"
  local required_indexes=(
    await-interaction-by-unit
    await-interaction-pending-by-tenant
    await-interaction-pending-by-assignee
    await-interaction-pending-by-group
    await-interaction-pending-by-step
    await-interaction-pending-by-deadline
    await-interaction-continuation-work
    await-interaction-by-execution
  )
  local timeout_seconds="${DYNAMODB_INDEX_WAIT_SECONDS:-120}"
  local active_indexes=""
  local missing_indexes=()
  local elapsed=0

  for ((elapsed = 0; elapsed < timeout_seconds; elapsed++)); do
    active_indexes="$(awslocal dynamodb describe-table --table-name "${table_name}" \
      --query "Table.GlobalSecondaryIndexes[?IndexStatus=='ACTIVE'].IndexName" --output text | tr '\t' ' ')"
    missing_indexes=()
    for index_name in "${required_indexes[@]}"; do
      if [[ " ${active_indexes} " != *" ${index_name} "* ]]; then
        missing_indexes+=("${index_name}")
      fi
    done
    if (( ${#missing_indexes[@]} == 0 )); then
      return
    fi
    sleep 1
  done

  echo "DynamoDB table ${table_name} is missing active indexes after ${timeout_seconds}s: ${missing_indexes[*]}" >&2
  awslocal dynamodb describe-table --table-name "${table_name}" >&2 || true
  return 1
}

ensure_await_interaction_index() {
  local index_name="$1"
  local hash_key="$2"
  local range_key="$3"
  local range_type="$4"
  local timeout_seconds="${DYNAMODB_INDEX_WAIT_SECONDS:-120}"
  local index_status=""

  index_status="$(awslocal dynamodb describe-table --table-name tpf_await_interaction \
    --query "Table.GlobalSecondaryIndexes[?IndexName=='${index_name}'].IndexStatus | [0]" --output text)"
  if [[ "${index_status}" == "None" || -z "${index_status}" ]]; then
    echo "Adding missing DynamoDB index: ${index_name}"
    awslocal dynamodb update-table --table-name tpf_await_interaction \
      --attribute-definitions \
        "AttributeName=${hash_key},AttributeType=S" \
        "AttributeName=${range_key},AttributeType=${range_type}" \
      --global-secondary-index-updates \
        "[{\"Create\":{\"IndexName\":\"${index_name}\",\"KeySchema\":[{\"AttributeName\":\"${hash_key}\",\"KeyType\":\"HASH\"},{\"AttributeName\":\"${range_key}\",\"KeyType\":\"RANGE\"}],\"Projection\":{\"ProjectionType\":\"ALL\"}}}]" \
      >/dev/null
  fi

  for ((elapsed = 0; elapsed < timeout_seconds; elapsed++)); do
    index_status="$(awslocal dynamodb describe-table --table-name tpf_await_interaction \
      --query "Table.GlobalSecondaryIndexes[?IndexName=='${index_name}'].IndexStatus | [0]" --output text)"
    if [[ "${index_status}" == "ACTIVE" ]]; then
      return
    fi
    sleep 1
  done

  echo "DynamoDB index ${index_name} did not become ACTIVE after ${timeout_seconds}s (status: ${index_status})." >&2
  awslocal dynamodb describe-table --table-name tpf_await_interaction >&2 || true
  return 1
}

create_queue_if_missing() {
  local queue_name="$1"
  if awslocal sqs get-queue-url --queue-name "${queue_name}" >/dev/null 2>&1; then
    echo "SQS queue exists: ${queue_name}"
    return
  fi
  echo "Creating SQS queue: ${queue_name}"
  awslocal sqs create-queue --queue-name "${queue_name}" >/dev/null
}

configure_queue_redrive() {
  local queue_name="$1"
  local dead_letter_queue_name="$2"
  local queue_url
  local dead_letter_queue_url
  local dead_letter_queue_arn
  local queue_attributes

  queue_url="$(awslocal sqs get-queue-url --queue-name "${queue_name}" --query 'QueueUrl' --output text)"
  dead_letter_queue_url="$(awslocal sqs get-queue-url --queue-name "${dead_letter_queue_name}" --query 'QueueUrl' --output text)"
  dead_letter_queue_arn="$(awslocal sqs get-queue-attributes --queue-url "${dead_letter_queue_url}" \
    --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)"
  queue_attributes="$(printf '%s%s%s%s%s' \
    '{"RedrivePolicy":"' \
    '{\"deadLetterTargetArn\":\"' \
    "${dead_letter_queue_arn}" \
    '\",\"maxReceiveCount\":\"5\"}' \
    '"}')"
  awslocal sqs set-queue-attributes --queue-url "${queue_url}" \
    --attributes "${queue_attributes}" >/dev/null
}

create_bucket_if_missing() {
  local bucket="$1"
  if awslocal s3api head-bucket --bucket "${bucket}" >/dev/null 2>&1; then
    echo "S3 bucket exists: ${bucket}"
    return
  fi
  echo "Creating S3 bucket: ${bucket}"
  awslocal s3api create-bucket --bucket "${bucket}" >/dev/null
}

compose_up -d localstack postgres
wait_for_localstack

create_table_if_missing tpf_execution \
  --attribute-definitions \
    AttributeName=tenant_id,AttributeType=S \
    AttributeName=execution_id,AttributeType=S \
  --key-schema \
    AttributeName=tenant_id,KeyType=HASH \
    AttributeName=execution_id,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST

create_table_if_missing tpf_execution_key \
  --attribute-definitions AttributeName=tenant_execution_key,AttributeType=S \
  --key-schema AttributeName=tenant_execution_key,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST

create_table_if_missing tpf_execution_payload \
  --attribute-definitions \
    AttributeName=payload_id,AttributeType=S \
    AttributeName=payload_part,AttributeType=S \
  --key-schema \
    AttributeName=payload_id,KeyType=HASH \
    AttributeName=payload_part,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST

create_table_if_missing tpf_await_unit \
  --attribute-definitions \
    AttributeName=tenant_id,AttributeType=S \
    AttributeName=unit_id,AttributeType=S \
  --key-schema \
    AttributeName=tenant_id,KeyType=HASH \
    AttributeName=unit_id,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST

create_table_if_missing tpf_await_admission \
  --attribute-definitions \
    AttributeName=scope_key,AttributeType=S \
    AttributeName=slot,AttributeType=N \
  --key-schema \
    AttributeName=scope_key,KeyType=HASH \
    AttributeName=slot,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST

create_table_if_missing tpf_await_interaction \
  --attribute-definitions \
    AttributeName=tenant_id,AttributeType=S \
    AttributeName=interaction_id,AttributeType=S \
    AttributeName=query_unit_key,AttributeType=S \
    AttributeName=query_unit_sort,AttributeType=S \
    AttributeName=query_pending_tenant_key,AttributeType=S \
    AttributeName=query_pending_assignee_key,AttributeType=S \
    AttributeName=query_pending_group_key,AttributeType=S \
    AttributeName=query_pending_step_key,AttributeType=S \
    AttributeName=query_pending_deadline_sort,AttributeType=S \
    AttributeName=query_deadline_key,AttributeType=S \
    AttributeName=query_deadline_sort,AttributeType=S \
    AttributeName=query_continuation_key,AttributeType=S \
    AttributeName=query_continuation_due_epoch_ms,AttributeType=N \
    AttributeName=query_execution_key,AttributeType=S \
    AttributeName=query_execution_sort,AttributeType=S \
  --key-schema \
    AttributeName=tenant_id,KeyType=HASH \
    AttributeName=interaction_id,KeyType=RANGE \
  --global-secondary-indexes \
    'IndexName=await-interaction-by-unit,KeySchema=[{AttributeName=query_unit_key,KeyType=HASH},{AttributeName=query_unit_sort,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
    'IndexName=await-interaction-pending-by-tenant,KeySchema=[{AttributeName=query_pending_tenant_key,KeyType=HASH},{AttributeName=query_pending_deadline_sort,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
    'IndexName=await-interaction-pending-by-assignee,KeySchema=[{AttributeName=query_pending_assignee_key,KeyType=HASH},{AttributeName=query_pending_deadline_sort,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
    'IndexName=await-interaction-pending-by-group,KeySchema=[{AttributeName=query_pending_group_key,KeyType=HASH},{AttributeName=query_pending_deadline_sort,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
    'IndexName=await-interaction-pending-by-step,KeySchema=[{AttributeName=query_pending_step_key,KeyType=HASH},{AttributeName=query_pending_deadline_sort,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
    'IndexName=await-interaction-pending-by-deadline,KeySchema=[{AttributeName=query_deadline_key,KeyType=HASH},{AttributeName=query_deadline_sort,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
    'IndexName=await-interaction-continuation-work,KeySchema=[{AttributeName=query_continuation_key,KeyType=HASH},{AttributeName=query_continuation_due_epoch_ms,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
    'IndexName=await-interaction-by-execution,KeySchema=[{AttributeName=query_execution_key,KeyType=HASH},{AttributeName=query_execution_sort,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
  --billing-mode PAY_PER_REQUEST
ensure_await_interaction_index await-interaction-continuation-work query_continuation_key query_continuation_due_epoch_ms N
ensure_await_interaction_index await-interaction-by-execution query_execution_key query_execution_sort S
wait_for_await_interaction_indexes

create_table_if_missing tpf_await_interaction_key \
  --attribute-definitions AttributeName=lookup_key,AttributeType=S \
  --key-schema AttributeName=lookup_key,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST

create_table_if_missing tpf_release_registry \
  --attribute-definitions \
    AttributeName=registry_key,AttributeType=S \
    AttributeName=registry_sort,AttributeType=S \
  --key-schema \
    AttributeName=registry_key,KeyType=HASH \
    AttributeName=registry_sort,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST

create_table_if_missing tpf_worker_registry \
  --attribute-definitions \
    AttributeName=registry_key,AttributeType=S \
    AttributeName=registry_sort,AttributeType=S \
  --key-schema \
    AttributeName=registry_key,KeyType=HASH \
    AttributeName=registry_sort,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST

create_queue_if_missing tpf-work
create_queue_if_missing tpf-execution-dlq
create_queue_if_missing csv-payment-await-requests-dlq
create_queue_if_missing csv-payment-await-requests
create_queue_if_missing csv-payment-await-results
configure_queue_redrive csv-payment-await-requests csv-payment-await-requests-dlq
create_bucket_if_missing tpf-release-artifacts

echo "CSV LocalStack bootstrap complete."
