"""Standalone Actions and central system tests must use the same scale limits."""
from pathlib import Path
import os
import re
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]


class HaScalePolicyTest(unittest.TestCase):
    def run_bootstrap_index_path(self, *, active_after, table_active_after=1,
                                 update_conflicts=0, readiness_has_all_indexes=True,
                                 timeout=3):
        shell = textwrap.dedent(r"""
            source "$TPF_BOOTSTRAP"
            read_mock_state() {
              read -r update_attempts update_succeeded update_conflicts_remaining \
                index_status_probes table_status_probes < "$MOCK_STATE"
            }
            write_mock_state() {
              printf '%s %s %s %s %s\n' "$update_attempts" "$update_succeeded" \
                "$update_conflicts_remaining" "$index_status_probes" "$table_status_probes" \
                > "$MOCK_STATE"
            }

            awslocal() {
              local args="$*"
              read_mock_state
              if [[ "${args}" == *"dynamodb update-table"* ]]; then
                update_attempts=$((update_attempts + 1))
                if (( table_status_probes < MOCK_TABLE_ACTIVE_AFTER )); then
                  write_mock_state
                  echo "update-table called before table ACTIVE"
                  return 1
                fi
                if (( update_conflicts_remaining > 0 )); then
                  update_conflicts_remaining=$((update_conflicts_remaining - 1))
                  write_mock_state
                  echo "An error occurred (ResourceInUseException): table update is in progress"
                  return 1
                fi
                update_succeeded=1
                write_mock_state
                return 0
              fi
              if [[ "${args}" == *"Table.TableStatus"* ]]; then
                table_status_probes=$((table_status_probes + 1))
                write_mock_state
                if (( table_status_probes >= MOCK_TABLE_ACTIVE_AFTER )); then
                  echo ACTIVE
                else
                  echo UPDATING
                fi
                return 0
              fi
              if [[ "${args}" == *"IndexStatus | [0]"* ]]; then
                if [[ "${args}" == *"await-interaction-continuation-work"* ]]; then
                  if (( update_succeeded == 0 )); then
                    echo None
                  else
                    index_status_probes=$((index_status_probes + 1))
                    write_mock_state
                    if (( index_status_probes >= MOCK_ACTIVE_AFTER )); then
                      echo ACTIVE
                    else
                      echo CREATING
                    fi
                  fi
                else
                  echo ACTIVE
                fi
                return 0
              fi
              if [[ "${args}" == *"IndexStatus=='ACTIVE'].IndexName"* ]]; then
                if [[ "${MOCK_READY_INDEXES}" == "true" ]]; then
                  echo "await-interaction-by-unit await-interaction-pending-by-tenant await-interaction-pending-by-assignee await-interaction-pending-by-group await-interaction-pending-by-step await-interaction-pending-by-deadline await-interaction-continuation-work await-interaction-by-execution"
                else
                  echo "await-interaction-by-unit await-interaction-pending-by-tenant await-interaction-pending-by-assignee await-interaction-pending-by-group await-interaction-pending-by-step await-interaction-pending-by-deadline await-interaction-by-execution"
                fi
                return 0
              fi
              return 0
            }
            sleep() { :; }

            if ! ensure_await_interaction_index \
              await-interaction-continuation-work query_continuation_key query_continuation_due_epoch_ms N; then
              echo ENSURE_FAILED
              exit 0
            fi
            if wait_for_await_interaction_indexes; then
              echo READINESS_EXIT=0
            else
              echo "READINESS_EXIT=$?"
            fi
            read_mock_state
            echo "UPDATE_ATTEMPTS=${update_attempts}"
          """)
        with tempfile.TemporaryDirectory() as temporary_directory:
            state_file = Path(temporary_directory) / "mock-state"
            state_file.write_text(f"0 0 {update_conflicts} 0 0\n")
            env = os.environ.copy()
            env.update({
                "TPF_BOOTSTRAP": str(ROOT / "self-host/container/bootstrap-localstack.sh"),
                "MOCK_STATE": str(state_file),
                "DYNAMODB_INDEX_WAIT_SECONDS": str(timeout),
                "MOCK_ACTIVE_AFTER": str(active_after),
                "MOCK_TABLE_ACTIVE_AFTER": str(table_active_after),
                "MOCK_READY_INDEXES": str(readiness_has_all_indexes).lower(),
            })
            return subprocess.run(["bash", "-c", shell], env=env, text=True,
                                  capture_output=True, check=False)

    def test_standalone_scale_matches_the_system_test_policy(self):
        workflow = (ROOT / ".github/workflows/self-host-ha-scale.yml").read_text()
        launcher = (ROOT / "scripts/system-test-suite.sh").read_text()
        for name in ("TPF_CSV_RECORD_COUNT", "TPF_CSV_TRANSITION_TRANSPORT_DEADLINE",
                     "TPF_CSV_FIXTURE_RUN_DEADLINE_SECONDS"):
            workflow_values = re.findall(rf'^\s+{name}: "([^"\n]+)"$', workflow, re.MULTILINE)
            launcher_values = re.findall(rf'^\s+export {name}=([^\s]+)$', launcher, re.MULTILINE)
            self.assertEqual(1, len(workflow_values), name)
            self.assertEqual(1, len(launcher_values), name)
            self.assertEqual(launcher_values, workflow_values, f"{name}: standalone scale policy drift")
        self.assertIn('TPF_CSV_FIXTURE_RUN_DEADLINE_SECONDS:-300',
                      (ROOT / "self-host/container/run-container-ha-demo.sh").read_text())

    def test_localstack_bootstrap_provisions_await_continuation_indexes(self):
        bootstrap = (ROOT / "self-host/container/bootstrap-localstack.sh").read_text()
        for index, hash_key, range_key in (
            ("await-interaction-continuation-work", "query_continuation_key", "query_continuation_due_epoch_ms"),
            ("await-interaction-by-execution", "query_execution_key", "query_execution_sort"),
        ):
            definition = next(line for line in bootstrap.splitlines() if f"IndexName={index}," in line)
            self.assertIn(f"AttributeName={hash_key},KeyType=HASH", definition)
            self.assertIn(f"AttributeName={range_key},KeyType=RANGE", definition)
            self.assertIn(f"AttributeName={hash_key},AttributeType=S", bootstrap)
            self.assertIn(f"AttributeName={range_key},AttributeType={'N' if range_key.endswith('_ms') else 'S'}", bootstrap)
        self.assertIn("wait_for_await_interaction_indexes", bootstrap)
        self.assertIn("ensure_await_interaction_index", bootstrap)
        self.assertIn("dynamodb update-table", bootstrap)
        self.assertIn("IndexStatus=='ACTIVE'", bootstrap)
        self.assertIn("--output text | tr", bootstrap)

    def test_existing_table_migration_retries_conflict_and_waits_for_active_index(self):
        result = self.run_bootstrap_index_path(
            active_after=2, table_active_after=2, update_conflicts=1)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("READINESS_EXIT=0", result.stdout)
        self.assertIn("UPDATE_ATTEMPTS=2", result.stdout)

    def test_index_readiness_timeout_returns_failure(self):
        result = self.run_bootstrap_index_path(
            active_after=1, readiness_has_all_indexes=False, timeout=2)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("READINESS_EXIT=1", result.stdout)
        self.assertIn("missing active indexes after 2s", result.stderr)


if __name__ == "__main__":
    unittest.main()
