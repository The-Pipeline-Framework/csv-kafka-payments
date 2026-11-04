#!/usr/bin/env python3
"""Retain worker REST request lifecycle records, including destruction stacks."""
from collections import Counter
import re
import sys

HEADER = re.compile(r"\b(?:TRACE|DEBUG|INFO|WARN(?:ING)?|ERROR|FATAL)\s+\[([^\]]+)\]\s+\(([^)]+)\)\s+(.*)")
PROBE_CATEGORY = "org.pipelineframework.csv.orchestrator.WorkerRestScopeTraceFilter"
EVENT = re.compile(r"event=worker_rest_scope_(open|destroy|end) traceId=(\S+) stateId=([0-9a-f]+)")


def records(lines):
    header = None
    body = []
    for line in lines:
        match = HEADER.search(line)
        if match:
            if header:
                yield header, "".join(body)
            header, body = match, [line]
        elif header:
            body.append(line)
    if header:
        yield header, "".join(body)


def report(lines):
    counts = Counter()
    destroyed = set()
    failed = set()
    for header, stack in records(lines):
        category, _, message = header.groups()
        if category == PROBE_CATEGORY and (event := EVENT.match(message)):
            action, trace_id, state_id = event.groups()
            counts[action] += 1
            if action == "destroy":
                destroyed.add((trace_id, state_id))
            if action == "end" and re.search(r"\bstatus=5[0-9]{2}\b", message):
                failed.add((trace_id, state_id))
            print(stack, end="")
        elif (category == "org.pipelineframework.rest.RestExceptionMapper"
              and "ContextNotActiveException" in message and "CurrentVertxRequest" in message):
            counts["REST scope failures"] += 1
            print(stack, end="")

    print(f"Worker REST scope trace counts: {dict(counts)}")
    if not counts["open"] or not counts["destroy"]:
        print("Worker REST tracing did not capture request creation and destruction.", file=sys.stderr)
        return 1
    for trace_id, state_id in sorted(failed & destroyed):
        print(f"Worker HTTP 5xx correlated with destruction traceId={trace_id} stateId={state_id}")
    if failed - destroyed or (counts["REST scope failures"] and not failed):
        print("A failed REST response could not be matched to its CDI destruction.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(report(sys.stdin))
