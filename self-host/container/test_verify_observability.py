import unittest
from urllib.parse import parse_qs, urlparse
from verify_observability import verify, validate_samples, SPANS


class ObservabilityProofTest(unittest.TestCase):
    def fetch(self, url, missing_worker=False, orphan=False):
        path = urlparse(url).path
        query = parse_qs(urlparse(url).query)
        if path == "/api/search":
            text = query["q"][0]
            if missing_worker and "csv-worker" in text:
                return {"traces": []}
            return {"traces": [{"traceID": "abc"}]}
        if path.startswith("/api/traces/"):
            return {"batches": [{"scopeSpans": [{"spans": [{
                "name": "tpf.await.completion.admitted",
                "parentSpanId": "" if orphan else "0000000000000001",
            }]}]}]}
        return {"data": {"result": [
            {"metric": {"job": service}, "value": [1, "12"]} for service in SPANS]}}

    def test_all_processes_and_completion_continuity_are_required(self):
        self.assertEqual("verified", verify("", "", self.fetch)["delivery"])
        with self.assertRaisesRegex(AssertionError, "csv-worker"):
            verify("", "", lambda url: self.fetch(url, missing_worker=True))
        with self.assertRaisesRegex(AssertionError, "lost its parent"):
            verify("", "", lambda url: self.fetch(url, orphan=True))

    def test_metric_proof_rejects_missing_worker_and_execution_labels(self):
        with self.assertRaisesRegex(AssertionError, "csv-worker"):
            validate_samples("csv-worker", {"data": {"result": [
                {"metric": {"job": "csv-runtime"}, "value": [1, "12"]}]}})
        with self.assertRaisesRegex(AssertionError, "High-cardinality"):
            validate_samples("csv-worker", {"data": {"result": [
                {"metric": {"job": "csv-worker", "execution_id": "123"}, "value": [1, "12"]}]}})


if __name__ == "__main__":
    unittest.main()
