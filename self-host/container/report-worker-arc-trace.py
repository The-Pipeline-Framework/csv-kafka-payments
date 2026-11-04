#!/usr/bin/env python3
"""Keep the CDI destruction stack associated with a failed REST response."""
from collections import Counter, deque
import re
import sys

HEADER = re.compile(r"\b(?:TRACE|DEBUG|INFO|WARN(?:ING)?|ERROR|FATAL)\s+\[([^\]]+)\]\s+\(([^)]+)\)\s+(.*)")
LIFECYCLE = re.compile(r"^(Activate|Deactivate|Destroy)(?:\s+([0-9a-f]+)\b)?")


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
    activation_by_thread = {}
    first_destroy = {}
    recent = deque(maxlen=8)

    for header, stack in records(lines):
        category, thread, message = header.groups()
        if category == "io.quarkus.arc.requestContext":
            lifecycle = LIFECYCLE.match(message)
            if not lifecycle:
                continue
            event, state = lifecycle.groups()
            counts[event] += 1
            recent.append(stack)
            if event == "Activate":
                activation_by_thread[thread] = (state, stack)
            elif event == "Deactivate":
                activation_by_thread.pop(thread, None)
            elif event == "Destroy" and state:
                first_destroy.setdefault(state, stack)
        elif (category == "org.pipelineframework.rest.RestExceptionMapper"
              and "ContextNotActiveException" in message and "CurrentVertxRequest" in message):
            counts["REST scope failures"] += 1
            print("\nWorker REST response failed with an inactive request scope")
            print(stack, end="")
            state, activation = activation_by_thread.get(thread, (None, ""))
            if state and "ResourceResponseFilterHandler" in activation and state in first_destroy:
                print(f"Response filter reactivated CDI state {state}")
                print(activation, end="")
                print(f"First observed destruction of CDI state {state}")
                print(first_destroy[state], end="")
            else:
                print("No matching prior destruction for the response-filter activation. Recent Arc events:")
                print("".join(recent), end="")

    print(f"Worker Arc lifecycle counts: {dict(counts)}")
    if not counts["Activate"] or not counts["Destroy"]:
        print("Worker Arc lifecycle tracing did not produce activation and destruction events.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(report(sys.stdin))
