"""External Cloud binding syntax/projection controls; no TPF workflow fixtures."""
import copy
import hashlib
import importlib.util
import json
import pathlib
import tempfile
import subprocess
import zipfile
import unittest

SPEC = importlib.util.spec_from_file_location('cloud_binding', pathlib.Path(__file__).with_name('prepare-cloud-binding.py'))
BINDING = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BINDING)


class CloudBindingTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = pathlib.Path(self.directory.name)
        self.value = {'schemaVersion': 1, 'accountId': '000000000000', 'region': 'eu-west-1',
                      'organizationId': 'fixture-org', 'applicationId': 'fixture-app',
                      'environmentId': 'fixture-env', 'ownerId': 'fixture-owner', 'stateOwnerId': 'fixture-state',
                      'bootstrapBusinessOwnerReceiptSha256': hashlib.sha256(b'external-boundary-fixture').hexdigest(),
                      'operations': {key: {'httpsOrigin': 'https://fixture.lambda-url.eu-west-1.on.aws/'} for key in BINDING.CLIENT_KEYS}}

    def prepare(self, value=None, raw=None):
        path = self.root / 'binding.json'
        path.write_text(json.dumps(value or self.value) if raw is None else raw)
        return BINDING.prepare(path, self.root / 'resources', self.root / 'evidence.json')

    def test_deterministic_projection_and_embedded_digests(self):
        first = self.prepare()
        resource = self.root / 'resources' / BINDING.RESOURCE
        content = resource.read_bytes()
        second = self.prepare(dict(reversed(list(self.value.items()))))
        self.assertEqual(content, resource.read_bytes())
        self.assertEqual(first['configSha256'], second['configSha256'])
        self.assertNotEqual(first['transportExportSha256'], second['transportExportSha256'])
        self.assertEqual(hashlib.sha256(content).hexdigest(), first['configSha256'])
        embedded = json.loads((self.root / 'resources' / BINDING.EVIDENCE_RESOURCE).read_text())
        self.assertEqual(embedded, second)
        self.assertEqual(content.count(BINDING.PROVIDER.encode()), 4)
        self.assertNotIn(b'on.aws/', content)
        self.assertNotIn(b'AWS_SECRET', content)

    def test_unknown_fields_and_credentials_are_rejected(self):
        for field in ('AWS_SECRET_ACCESS_KEY', 'providers', 'deploymentId'):
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.prepare(self.value | {field: 'untrusted'})

    def test_missing_or_extra_client_keys_are_rejected(self):
        for operations in ({}, self.value['operations'] | {'foreign-client': {'httpsOrigin': 'https://example.invalid'}}):
            with self.subTest(operations=operations), self.assertRaises(ValueError):
                self.prepare(self.value | {'operations': operations})

    def test_unsafe_origins_fail_before_resource_emission(self):
        for origin in ('http://fixture.lambda-url.eu-west-1.on.aws', 'https://fixture.lambda-url.us-east-2.on.aws',
                       'https://fixture.lambda-url.eu-west-1.on.aws:443', 'https://user@fixture.lambda-url.eu-west-1.on.aws',
                       'https://fixture.lambda-url.eu-west-1.on.aws/path', 'https://fixture.lambda-url.eu-west-1.on.aws?',
                       'https://fixture.lambda-url.eu-west-1.on.aws#', 'https://example.invalid'):
            value = copy.deepcopy(self.value)
            value['operations'][BINDING.CLIENT_KEYS[0]]['httpsOrigin'] = origin
            with self.subTest(origin=origin), self.assertRaises(ValueError):
                self.prepare(value)
            self.assertFalse((self.root / 'resources' / BINDING.RESOURCE).exists())

    def test_duplicate_fields_and_nested_overrides_are_rejected(self):
        with self.assertRaises(ValueError):
            self.prepare(raw='{"schemaVersion":1,"schemaVersion":1}')
        value = copy.deepcopy(self.value)
        value['operations'][BINDING.CLIENT_KEYS[0]]['providers'] = 'untrusted'
        with self.assertRaises(ValueError):
            self.prepare(value)

    def test_scope_and_receipt_are_required_but_not_authenticated_here(self):
        for changes in ({'bootstrapBusinessOwnerReceiptSha256': 'not-a-digest'}, {'accountId': 'x'},
                        {'ownerId': '\n'}, {'schemaVersion': True}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.prepare(self.value | changes)
        self.assertIn('cannot authenticate', BINDING.__doc__)


class ProofArtifactRestorationTest(unittest.TestCase):
    def test_success_and_failure_restore_all_ordinary_targets(self):
        # Execute the real script's snapshot/EXIT trap with tiny package-flow fixtures.
        # This tests CI artifact ownership only, never TPF workflow or AWS semantics.
        script = (pathlib.Path(__file__).resolve().parents[1] / 'self-host/pilot/build-and-prove-iam.sh').read_text()
        start = script.index('# Save all ordinary artifacts')
        end = script.index("python3 -m unittest discover", start)
        modules = ('ingress-lambda-svc', 'durable-action-lambda-svc', 'worker-lambda-svc')
        mutation = """python3 - <<'PY'
from pathlib import Path
import zipfile
for module in ('ingress-lambda-svc', 'durable-action-lambda-svc', 'worker-lambda-svc'):
    with zipfile.ZipFile(Path(module)/'target/function.zip','w') as archive:
        archive.writestr('META-INF/csv-cloud-transport-evidence.json','{}')
        archive.writestr('fixture-only','never an ordinary deployment artifact')
PY
"""
        for status in (0, 23):
            with self.subTest(exit_status=status), tempfile.TemporaryDirectory() as directory:
                root = pathlib.Path(directory)
                originals = {}
                for module in modules:
                    target = root / module / 'target/function.zip'
                    target.parent.mkdir(parents=True)
                    with zipfile.ZipFile(target, 'w') as archive:
                        archive.writestr('ordinary-package', module)
                    originals[module] = target.read_bytes()
                result = subprocess.run(['bash', '-c', 'set -euo pipefail\n' + script[start:end] + mutation + f'exit {status}\n'], cwd=root, capture_output=True, text=True)
                self.assertEqual(result.returncode, status, result.stderr)
                receipt = json.loads((root / 'target/iam-package-restoration.json').read_text())
                self.assertEqual(receipt['status'], 'PASS')
                for module in modules:
                    self.assertEqual((root / module / 'target/function.zip').read_bytes(), originals[module])
                    fixture = root / 'target/iam-fixture-bound-packages' / module / 'function.zip'
                    with zipfile.ZipFile(fixture) as archive:
                        self.assertIn('fixture-only', archive.namelist())


if __name__ == '__main__':
    unittest.main()
