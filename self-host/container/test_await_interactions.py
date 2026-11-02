"""Retry history must not replace proof of every logical payment's completion."""
import importlib.util
import json
import subprocess
import sys
import tempfile
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "await_interactions", Path(__file__).with_name("verify-await-interactions.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)
FLOW_LOG = "Submitted CSV execution execution-1 for file /input/payments.csv\n"


def interaction(csv_id, interaction_id, status="COMPLETED", execution="execution-1", tenant="csv-demo"):
    fields = {"tenant_id": tenant, "execution_id": execution, "step_id": "payment-await",
              "interaction_id": interaction_id, "status": status,
              "idempotency_key": f"execution:page:0:csvId={csv_id}:recipient=fixture:item=0"}
    return {key: {"S": value} for key, value in fields.items()}


class AwaitInteractionTest(unittest.TestCase):
    def verify(self, rows, count=3, **snapshot):
        return verifier.verify_interactions({"Items": rows, **snapshot}, FLOW_LOG, "csv-demo", count)

    def completed(self):
        return [interaction(i, f"interaction-{i}") for i in range(1, 4)]

    def test_exact_completed_coverage(self):
        self.assertEqual(3, self.verify(self.completed())["completedItemCount"])

    def test_completed_retry_rows_do_not_inflate_logical_payment_count(self):
        report = self.verify(self.completed() + [interaction(1, "retry-1"), interaction(2, "retry-2")])
        self.assertEqual(3, report["completedItemCount"])
        self.assertEqual(5, report["interactionCount"])
        self.assertEqual(2, report["retainedExtraInteractions"])

    def test_timed_out_attempt_can_be_replaced_by_completed_attempt(self):
        report = self.verify(self.completed() + [interaction(1, "old", "TIMED_OUT")])
        self.assertEqual({"COMPLETED": 3, "TIMED_OUT": 1}, report["statuses"])

    def test_equal_or_greater_row_count_cannot_hide_missing_completion(self):
        for rows in ([interaction(1, "a"), interaction(1, "b"), interaction(2, "c")],
                     self.completed()[:2] + [interaction(3, "failed", "FAILED"), interaction(1, "extra")]):
            with self.subTest(rows=rows), self.assertRaisesRegex(ValueError, "missing 1 completed"):
                self.verify(rows)

    def test_pending_and_unknown_statuses_are_rejected(self):
        for status in ("WAITING", "DISPATCHING", "DISPATCHED", "COMPLETION_OBSERVED", "UNKNOWN"):
            with self.subTest(status=status), self.assertRaisesRegex(ValueError, "not terminal"):
                self.verify(self.completed() + [interaction(1, "pending", status)])

    def test_other_executions_and_tenants_cannot_supply_missing_coverage(self):
        for extra in (interaction(3, "foreign", execution="other"), interaction(3, "foreign", tenant="other")):
            with self.subTest(extra=extra), self.assertRaisesRegex(ValueError, "missing 1 completed"):
                self.verify(self.completed()[:2] + [extra])
        self.assertEqual(3, self.verify(self.completed() + [interaction(4, "foreign", execution="other")])["interactionCount"])

    def test_unexpected_or_missing_csv_identity_is_rejected(self):
        for csv_id in (0, 4, "01", "invalid"):
            with self.subTest(csv_id=csv_id), self.assertRaises(ValueError):
                self.verify(self.completed() + [interaction(csv_id, "bad")])

    def test_multiple_steps_or_duplicate_interaction_ids_are_rejected(self):
        extra = interaction(1, "extra")
        extra["step_id"]["S"] = "other-step"
        with self.assertRaisesRegex(ValueError, "one await step"):
            self.verify(self.completed() + [extra])
        with self.assertRaisesRegex(ValueError, "duplicate interaction"):
            self.verify(self.completed() + [interaction(1, "interaction-1")])

    def test_truncated_scan_and_missing_submission_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "incomplete"):
            self.verify(self.completed(), LastEvaluatedKey={"key": {"S": "more"}})
        for log in ("", FLOW_LOG + FLOW_LOG):
            with self.subTest(log=log), self.assertRaisesRegex(ValueError, "exactly one submitted"):
                verifier.verify_interactions({"Items": self.completed()}, log, "csv-demo", 3)

    def test_cli_emits_summary_and_fails_missing_coverage(self):
        with tempfile.TemporaryDirectory() as temporary:
            flow_log = Path(temporary) / "flow.log"
            flow_log.write_text(FLOW_LOG)
            command = [sys.executable, spec.origin, "--flow-log", str(flow_log),
                       "--tenant-id", "csv-demo", "--record-count", "3"]
            for rows, expected_exit in ((self.completed(), 0), (self.completed()[:2], 1)):
                result = subprocess.run(command, input=json.dumps({"Items": rows}),
                                        text=True, capture_output=True)
                self.assertEqual(expected_exit, result.returncode, result.stderr)
                if expected_exit == 0:
                    self.assertEqual(3, json.loads(result.stdout)["completedItemCount"])
                else:
                    self.assertIn("missing 1 completed", result.stderr)

    def test_paged_items_use_csv_identity_instead_of_restarting_item_index(self):
        rows = [interaction(i, f"interaction-{i}") for i in range(1, 2001)]
        self.assertEqual(2000, self.verify(rows, count=2000)["completedItemCount"])


if __name__ == "__main__":
    unittest.main()
