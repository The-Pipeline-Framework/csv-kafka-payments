import copy
import importlib.util
import json
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('worker_binding', pathlib.Path(__file__).with_name('prepare-worker-binding.py'))
binding = importlib.util.module_from_spec(spec)
spec.loader.exec_module(binding)


class WorkerBindingTest(unittest.TestCase):
    def setUp(self):
        self.value = {'schemaVersion': 1, 'accountId': '000000000000', 'allowedRoles': [
            {'roleId': 'AROATESTIMMUTABLEACTION', 'roleArn': 'arn:aws:iam::000000000000:role/native/action-role'},
            {'roleId': 'AROATESTIMMUTABLEWORKER', 'roleArn': 'arn:aws:iam::000000000000:role/native/worker-role'}]}

    def prepare(self, value):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / 'input.json').write_text(json.dumps(value))
            binding.prepare(root / 'input.json', root / 'resources', root / 'evidence.json')
            return (root / 'resources/META-INF/microprofile-config.properties').read_bytes()

    def test_order_is_deterministic_and_precedence_explicit(self):
        expected = self.prepare(self.value)
        self.value['allowedRoles'].reverse()
        self.assertEqual(expected, self.prepare(self.value))
        self.assertIn(b'config_ordinal=260\n', expected)
        self.assertIn(b'csv.worker.principal-arn=\n', expected)

    def test_foreign_wildcard_and_duplicate_roles_are_rejected(self):
        for arn in ['*', 'arn:aws:iam::111111111111:role/native/action-role', 'arn:aws:iam::000000000000:role/*']:
            value = copy.deepcopy(self.value)
            value['allowedRoles'][0]['roleArn'] = arn
            with self.assertRaises(ValueError): self.prepare(value)
        self.value['allowedRoles'][1] = self.value['allowedRoles'][0]
        with self.assertRaises(ValueError): self.prepare(self.value)

    def test_exact_two_pairs_and_no_credentials(self):
        value = copy.deepcopy(self.value)
        value['allowedRoles'].pop()
        with self.assertRaises(ValueError): self.prepare(value)
        self.value['credentials'] = 'forbidden'
        with self.assertRaises(ValueError): self.prepare(self.value)
