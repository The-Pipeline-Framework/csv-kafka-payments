"""Prove emitted CSV telemetry after the small self-host run, using backend data."""
import argparse
import base64
import json
import math
import time
import urllib.parse
import urllib.request
from pathlib import Path

SPANS = {
    "csv-coordinator": "tpf.transition.dispatched",
    "csv-runtime": "tpf.await.provider.admitted",
    "csv-persistence": None,
    "csv-worker": "tpf.pipeline.run",
}
METRICS = {
    "csv-coordinator": "tpf_orchestrator_transition_dispatched_transitions_total",
    "csv-worker": "tpf_pipeline_run_count_total",
    "csv-runtime": "rpc_server_requests_total",
    "csv-persistence": "rpc_server_requests_total",
}
# Resource identity (job/service_name) identifies processes; execution identity must
# never become a metric dimension.
FORBIDDEN_LABELS = {"execution_id", "interaction_id", "correlation_id", "unit_id", "run_id", "item_id"}


def get_json(url):
    with urllib.request.urlopen(url, timeout=10) as response:
        return json.load(response)


def descendants(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from descendants(child)
    elif isinstance(value, list):
        for child in value:
            yield from descendants(child)


def valid_id(value):
    if not value:
        return False
    try:
        raw = bytes.fromhex(value)
    except ValueError:
        try:
            raw = base64.b64decode(value, validate=True)
        except ValueError:
            return False
    return bool(raw) and any(raw)


def completion_linked(span):
    return valid_id(span.get("parentSpanId", "")) or any(
        valid_id(link.get("traceId", "")) and valid_id(link.get("spanId", ""))
        for link in span.get("links", []))


def validate_samples(service, response):
    samples = response.get("data", {}).get("result", [])
    for sample in samples:
        labels = sample.get("metric", {})
        forbidden = {key for key in labels if any(identity in key for identity in FORBIDDEN_LABELS)}
        if forbidden:
            raise AssertionError(f"High-cardinality metric labels: {sorted(forbidden)}")
    values = [float(sample["value"][1]) for sample in samples
              if sample.get("metric", {}).get("job") == service
              or sample.get("metric", {}).get("service_name") == service]
    if not any(math.isfinite(value) and value > 0 for value in values):
        raise AssertionError(f"Missing positive framework metric from {service}")


def verify(tempo, prometheus, fetch=get_json):
    evidence = {"signals": [], "delivery": "verified"}
    for service, name in SPANS.items():
        query = '{ resource.service.name = "' + service + '"'
        query += (' && name = "' + name + '" }') if name else ' && span.rpc.system = "grpc" }'
        result = fetch(tempo + "/api/search?" + urllib.parse.urlencode({"q": query, "limit": 100}))
        if not result.get("traces"):
            raise AssertionError(f"Missing framework span {name} from {service}")
        evidence["signals"].append({"service": service, "span": name})
    search = fetch(tempo + "/api/search?" + urllib.parse.urlencode({"q": '{ name = "tpf.await.completion.admitted" }', "limit": 100}))
    if not search.get("traces"):
        raise AssertionError("Missing Await completion admission span")
    for trace in search["traces"]:
        document = fetch(tempo + "/api/traces/" + trace["traceID"])
        completions = [node for node in descendants(document) if node.get("name") == "tpf.await.completion.admitted"]
        if not completions or not all(completion_linked(span) for span in completions):
            raise AssertionError("Await completion lost its parent or durable origin link")
    for service, metric in METRICS.items():
        result = fetch(prometheus + "/api/v1/query?" + urllib.parse.urlencode({"query": metric}))
        validate_samples(service, result)
        evidence["signals"].append({"service": service, "metric": metric})
    return evidence


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tempo", default="http://localhost:3200")
    parser.add_argument("--prometheus", default="http://localhost:9090")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    deadline = time.monotonic() + args.timeout
    last_error = "No backend response"
    while time.monotonic() < deadline:
        try:
            evidence = verify(args.tempo, args.prometheus)
        except (AssertionError, OSError, ValueError) as error:
            last_error = str(error)
            time.sleep(2)
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(evidence, indent=2) + "\n")
            print(f"Verified coordinator, worker, runtime and Await continuity: {args.output}")
            return
    raise SystemExit("Telemetry export proof failed: " + last_error)


if __name__ == "__main__":
    main()
