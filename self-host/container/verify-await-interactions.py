#!/usr/bin/env python3
"""Validate completed await item coverage, allowing retained retry interactions."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys


TERMINAL_STATUSES = {"COMPLETED", "FAILED", "TIMED_OUT", "CANCELLED", "EXPIRED"}


def verify_interactions(snapshot, flow_log, tenant_id, expected_count):
    executions = re.findall(r"^Submitted CSV execution (\S+) for file .+$", flow_log, re.MULTILINE)
    if len(executions) != 1:
        raise ValueError("Expected exactly one submitted CSV execution in the flow log")
    execution_id = executions[0]
    if expected_count < 1:
        raise ValueError("Expected record count must be positive")
    if snapshot.get("LastEvaluatedKey"):
        raise ValueError("Await interaction snapshot is incomplete; fetch all scan pages")
    rows = [row for row in snapshot["Items"]
            if row.get("tenant_id", {}).get("S") == tenant_id
            and row.get("execution_id", {}).get("S") == execution_id]
    statuses = Counter()
    completed = set()
    steps = set()
    interaction_ids = set()
    for row in rows:
        interaction_id = row["interaction_id"]["S"]
        if not interaction_id or interaction_id in interaction_ids:
            raise ValueError(f"Invalid or duplicate interaction ID: {interaction_id}")
        interaction_ids.add(interaction_id)
        steps.add(row["step_id"]["S"])
        # pipeline.yaml defines csvId in the await idempotency fields. Generated
        # fixture IDs are 1..N; unlike item_index they do not reset per page or
        # depend on parallel arrival order during retries.
        matches = re.findall(r":csvId=([0-9]+):recipient=", row["idempotency_key"]["S"])
        if len(matches) != 1:
            raise ValueError("Await idempotency key must identify exactly one fixture CSV ID")
        csv_id = int(matches[0])
        if str(csv_id) != matches[0] or not 1 <= csv_id <= expected_count:
            raise ValueError(f"Unexpected await CSV ID: {matches[0]}")
        status = row["status"]["S"]
        statuses[status] += 1
        if status not in TERMINAL_STATUSES:
            raise ValueError(f"Await interaction {interaction_id} is not terminal: {status}")
        if status == "COMPLETED":
            completed.add(csv_id)
    # This fixture has one itemized await step. Retries may retain several
    # interactions for a payment; row count is not the number of logical payments.
    if len(steps) != 1 or "" in steps:
        raise ValueError(f"Expected one await step for execution {execution_id}; found {sorted(steps)}")
    missing = sorted(set(range(1, expected_count + 1)) - completed)
    if missing:
        raise ValueError(f"Execution {execution_id} is missing {len(missing)} completed await items; "
                         f"first CSV IDs={missing[:10]}, statuses={dict(statuses)}")
    return {"executionId": execution_id, "tenantId": tenant_id,
            "completedItemCount": len(completed), "interactionCount": len(rows),
            "retainedExtraInteractions": len(rows) - expected_count,
            "statuses": dict(sorted(statuses.items()))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--flow-log", type=Path, required=True)
    parser.add_argument("--tenant-id", required=True)
    parser.add_argument("--record-count", type=int, required=True)
    args = parser.parse_args()
    try:
        report = verify_interactions(json.load(sys.stdin), args.flow_log.read_text(),
                                     args.tenant_id, args.record_count)
    except (ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"Durable await validation failed: {error}\n")
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
