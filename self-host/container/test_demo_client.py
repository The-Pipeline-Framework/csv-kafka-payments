#!/usr/bin/env python3
import importlib.util
import hashlib
import json
import zipfile
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("demo-client.py")
MODULE_SPEC = importlib.util.spec_from_file_location("csv_payments_demo_client", MODULE_PATH)
demo_client = importlib.util.module_from_spec(MODULE_SPEC)
MODULE_SPEC.loader.exec_module(demo_client)


class DemoClientReleaseTest(unittest.TestCase):

    def test_release_identifies_compiled_truth_and_encodes_file_uri(self):
        with tempfile.TemporaryDirectory(prefix="release with spaces ") as directory:
            artifact = Path(directory) / "pipeline runtime.jar"
            output = Path(directory) / "pipeline-release.json"
            contract = {"pipelineId": "org.pipelineframework.csv", "contractVersion": "contract-1",
                        "steps": [{"authoredName": "ProcessCsvPaymentsInput", "index": 0,
                                   "inputTypeId": demo_client.V3_CSV_INPUT_FILE_TYPE}],
                        "capabilities": {"localTransitionExecution": True,
                                         "transitionWorkerProtocols": ["local", "rest"]}}
            with zipfile.ZipFile(artifact, "w") as archive:
                archive.writestr("META-INF/pipeline/pipeline-contract.json", json.dumps(contract))
            demo_client.create_release(SimpleNamespace(
                artifact_path=str(artifact), output=str(output),
                pipeline_id=contract["pipelineId"], release_version="release-1"))
            descriptor = json.loads(output.read_text())
            release_artifact = descriptor["artifacts"][0]
            self.assertEqual(descriptor["compiledTruthArtifactId"], release_artifact["artifactId"])
            self.assertEqual(release_artifact["uri"], artifact.resolve().as_uri())
            self.assertIn("%20", release_artifact["uri"])
            self.assertEqual(release_artifact["digest"], "sha256:" + hashlib.sha256(artifact.read_bytes()).hexdigest())
            self.assertEqual(release_artifact["stepIds"], ["ProcessCsvPaymentsInput"])
            self.assertEqual(release_artifact["capabilities"], ["local", "rest"])
            self.assertEqual(demo_client.pipeline_input_type(output), demo_client.V3_CSV_INPUT_FILE_TYPE)

    def test_pipeline_input_rejects_nonlocal_artifact_uris(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "pipeline-release.json"
            for uri in ["https://example.com/runtime.jar", "file://remote/runtime.jar",
                        "file:///runtime.jar?query", "file:///runtime.jar#fragment"]:
                with self.subTest(uri=uri):
                    output.write_text(json.dumps({"artifacts": [{"uri": uri}]}))
                    with self.assertRaisesRegex(RuntimeError, "local file:"):
                        demo_client.pipeline_input_type(output)


class DemoClientSubmissionTest(unittest.TestCase):

    def test_submission_supplies_content_identity_for_canonical_input(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "payments.csv"
            args = SimpleNamespace(pipeline_id="csv", release_descriptor_path="release.json",
                                   idempotency_key="fixture", base_url="http://coordinator",
                                   tenant_id="tenant", control_plane_token="fixture-token")
            for input_type in [demo_client.V3_CSV_INPUT_FILE_TYPE,
                               demo_client.V2_CSV_INPUT_FILE_TRANSPORT_TYPE]:
                for content in [b"first snapshot", b"changed snapshot"]:
                    with self.subTest(input_type=input_type, content=content):
                        path.write_bytes(content)
                        with patch.object(demo_client, "pipeline_input_transport_type", return_value=input_type), \
                                patch.object(demo_client, "request", return_value={"executionId": "exec-1"}) as request:
                            demo_client.submit_csv_input_file(args, path, demo_client.time.time() + 30)
                        envelope = request.call_args.kwargs["body"]["inputPayload"]
                        payload = json.loads(envelope["payload"])
                        self.assertEqual(envelope["payloadTypeId"], input_type)
                        self.assertEqual(payload["filepath"], str(path.resolve()))
                        if input_type == demo_client.V3_CSV_INPUT_FILE_TYPE:
                            self.assertEqual(payload["sourceIdentity"], hashlib.sha256(content).hexdigest())
                        else:
                            self.assertNotIn("sourceIdentity", payload)


class DemoClientOutputValidationTest(unittest.TestCase):

    def test_generated_output_requires_each_input_id_once(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "payments_3.csv.out"
            output.write_text(
                "'CSV ID','RECIPIENT'\n'1','one'\n'2','two'\n'3','three'\n", encoding="utf-8")

            demo_client.assert_output_record_count(output, 3)

    def test_generated_output_rejects_duplicate_input_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "payments_3.csv.out"
            output.write_text(
                "'CSV ID','RECIPIENT'\n'1','one'\n'2','two'\n'2','three'\n", encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "duplicate CSV ID"):
                demo_client.assert_output_record_count(output, 3)

    def test_generated_output_rejects_missing_input_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "payments_3.csv.out"
            output.write_text(
                "'CSV ID','RECIPIENT'\n'1','one'\n'2','two'\n'4','four'\n", encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "does not preserve the generated input IDs"):
                demo_client.assert_output_record_count(output, 3)

    def test_generated_output_rejects_padded_input_id(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "payments_3.csv.out"
            output.write_text(
                "'CSV ID','RECIPIENT'\n' 1 ','one'\n'2','two'\n'3','three'\n", encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "does not preserve the generated input IDs"):
                demo_client.assert_output_record_count(output, 3)

    def test_wait_status_reports_remote_outcome_unknown_without_waiting_for_fixture_expiry(self):
        args = SimpleNamespace(
            base_url="http://coordinator:8082",
            tenant_id="tenant-1",
            control_plane_token="token",
        )
        record = {"status": "REMOTE_OUTCOME_UNKNOWN", "executionId": "exec-1"}

        with patch.object(demo_client, "request", return_value=record), \
                patch.object(demo_client.time, "time", side_effect=[100.0, 100.0]):
            with self.assertRaisesRegex(RuntimeError, "remote outcome unknown"):
                demo_client.wait_status(args, "exec-1", 400.0)

    def test_wait_status_caps_request_timeout_at_remaining_fixture_time(self):
        args = SimpleNamespace(
            base_url="http://coordinator:8082",
            tenant_id="tenant-1",
            control_plane_token="token",
        )
        record = {"status": "SUCCEEDED", "executionId": "exec-1"}

        with patch.object(demo_client, "request", return_value=record) as request, \
                patch.object(demo_client.time, "time", side_effect=[100.0, 100.0]):
            demo_client.wait_status(args, "exec-1", 103.0)

        self.assertEqual(3.0, request.call_args.kwargs["timeout"])

    def test_wait_status_rejects_success_returned_after_fixture_deadline(self):
        args = SimpleNamespace(
            base_url="http://coordinator:8082",
            tenant_id="tenant-1",
            control_plane_token="token",
        )
        record = {"status": "SUCCEEDED", "executionId": "exec-1"}

        with patch.object(demo_client, "request", return_value=record), \
                patch.object(demo_client.time, "time", side_effect=[100.0, 104.0]):
            with self.assertRaisesRegex(RuntimeError, "Fixture deadline elapsed"):
                demo_client.wait_status(args, "exec-1", 103.0)

    def test_run_flow_shares_absolute_fixture_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            input_file = Path(directory) / "payments.csv"
            input_file.touch()
            args = SimpleNamespace(output_dir=directory, defer_output_validation=False, record_count=3)

            with patch.object(demo_client, "fixture_deadline", return_value=123.0), \
                    patch.object(demo_client, "prepare_input", return_value=input_file), \
                    patch.object(demo_client, "submit_csv_input_file", return_value="exec-1") as submit, \
                    patch.object(demo_client, "wait_status") as wait_status, \
                    patch.object(demo_client, "inspect_result") as inspect_result, \
                    patch.object(demo_client, "validate_output_path") as validate_output:
                demo_client.run_flow(args)

            submit.assert_called_once_with(args, input_file, 123.0)
            wait_status.assert_called_once_with(args, "exec-1", 123.0)
            inspect_result.assert_called_once_with(args, "exec-1", 123.0)
            validate_output.assert_called_once_with(Path(directory).resolve(), "payments.csv.out", 3, 123.0)


if __name__ == "__main__":
    unittest.main()
