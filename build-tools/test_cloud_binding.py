"""External Cloud binding syntax/projection controls; no TPF workflow fixtures."""
import copy
import ast
import hashlib
import importlib.util
import json
import os
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
    def test_real_bash_header_accepts_empty_configured_and_file_arguments(self):
        script=(pathlib.Path(__file__).resolve().parents[1]/'self-host/pilot/build-and-prove-iam.sh').read_text()
        header=script[:script.index('# Save all ordinary artifacts')]
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory)
            probe=root/'self-host/pilot/probe.sh'
            probe.parent.mkdir(parents=True)
            probe.write_text(header+'printf "%s\\0" "${args[@]}"\n')
            environment={key:value for key,value in os.environ.items() if key not in ('MAVEN_ARGS','TPF_MAVEN_ARGS_FILE')}
            cache='-Dmaven.repo.local='+str(root/'.m2/repository')
            cases=[({},[cache]),({'MAVEN_ARGS':''},[cache]),({'MAVEN_ARGS':'-ntp -Dexample=true'},['-ntp','-Dexample=true',cache])]
            arguments=root/'args.nul'
            arguments.write_bytes(b'-Dexample=contains spaces\0-Dmaven.repo.local=explicit-cache\0')
            cases.append(({'TPF_MAVEN_ARGS_FILE':str(arguments)},['-Dexample=contains spaces','-Dmaven.repo.local=explicit-cache']))
            for changes,expected in cases:
                with self.subTest(changes=changes):
                    result=subprocess.run(['bash',str(probe)],env=environment|changes,capture_output=True)
                    self.assertEqual(result.returncode,0,result.stderr.decode())
                    self.assertEqual(result.stdout.decode().rstrip('\0').split('\0'),expected)

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
    target=Path(module)/'target'
    owned=('META-INF/microprofile-config.properties','META-INF/csv-cloud-transport-evidence.json','META-INF/csv-cloud-worker-evidence.json')
    for relative in ['cloud-binding-evidence.json']+[directory+'/'+name for directory in ('classes','cloud-binding-resources') for name in owned]:
        path=target/relative
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text('proof-created resource')
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
                    legitimate=target.parent/'classes/META-INF/microprofile-config.properties'
                    legitimate.parent.mkdir(parents=True,exist_ok=True)
                    legitimate.write_text('original legitimate application resource')
                    (target.parent/'classes/META-INF/unrelated.txt').write_text('preserve unrelated output')
                for rerun in range(2):
                    result = subprocess.run(['bash', '-c', 'set -euo pipefail\n' + script[start:end] + mutation + f'exit {status}\n'], cwd=root, capture_output=True, text=True)
                    self.assertEqual(result.returncode, status, result.stderr)
                    receipt = json.loads((root / 'target/iam-package-restoration.json').read_text())
                    self.assertEqual(receipt['status'], 'PASS')
                    for module in modules:
                        target=root/module/'target'
                        self.assertEqual((target/'function.zip').read_bytes(), originals[module])
                        self.assertEqual((target/'classes/META-INF/microprofile-config.properties').read_text(),'original legitimate application resource')
                        self.assertEqual((target/'classes/META-INF/unrelated.txt').read_text(),'preserve unrelated output')
                        self.assertFalse((target/'cloud-binding-evidence.json').exists())
                        for directory in ('classes','cloud-binding-resources'):
                            for name in ('csv-cloud-transport-evidence.json','csv-cloud-worker-evidence.json'):
                                self.assertFalse((target/directory/'META-INF'/name).exists())
                        self.assertFalse((target/'cloud-binding-resources/META-INF/microprofile-config.properties').exists())
                        fixture=root/'target/iam-fixture-bound-packages'/module/'function.zip'
                        with zipfile.ZipFile(fixture) as archive:self.assertIn('fixture-only',archive.namelist())

    def test_rerun_selects_action_variant_before_packaging(self):
        root_source=pathlib.Path(__file__).resolve().parents[1]
        script=(root_source/'self-host/pilot/build-and-prove-iam.sh').read_text()
        start=script.index('# A previous proof leaves NATIVE installed.')
        end=script.index('./mvnw -B -ntp -f ingress-lambda-svc/pom.xml',start)
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory)
            (root/'variant').write_text('NATIVE')
            # CLI boundary fixture records build selection only; it builds no TPF code.
            maven=root/'mvnw'
            maven.write_text('''#!/usr/bin/env python3
from pathlib import Path
import sys
args=sys.argv[1:]
if '-pl' in args:
    variant=next(a.split('=',1)[1] for a in args if a.startswith('-Dtpf.build.coordination.host='))
    projects=args[args.index('-pl')+1].split(',')
    assert 'orchestrator-svc' in projects
    if variant=='NATIVE':assert 'pipeline-runtime-svc' in projects,'Worker HTTP producer was not rebuilt'
    Path('variant').write_text(variant)
    with Path('calls').open('a') as output:output.write(variant+'\\n')
elif 'durable-action-lambda-svc/pom.xml' in args:
    assert Path('variant').read_text()=='AWS_DURABLE','Action packaged with wrong installed variant'
    with Path('calls').open('a') as output:output.write('ACTION\\n')
else:raise AssertionError(args)
''')
            maven.chmod(0o755)
            for rerun in range(2):
                result=subprocess.run(['bash','-c','set -euo pipefail\nargs=(-Dmaven.repo.local=fixture); binding=fixture; pilot_repo=$PWD\n'+script[start:end]],cwd=root,capture_output=True,text=True)
                self.assertEqual(result.returncode,0,result.stderr)
            self.assertEqual((root/'calls').read_text().splitlines(),['AWS_DURABLE','ACTION','NATIVE']*2)

    def test_local_proof_rejects_bound_package_before_mutation(self):
        source=pathlib.Path(__file__).resolve().parents[1]/'self-host/pilot/run-local-proof.py'
        tree=ast.parse(source.read_text())
        start=next(i for i,node in enumerate(tree.body) if isinstance(node,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='ZIP' for t in node.targets))
        end=next(i for i,node in enumerate(tree.body[start:],start) if isinstance(node,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='CP' for t in node.targets))
        guard=compile(ast.Module(body=tree.body[start:end],type_ignores=[]),str(source),'exec')
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory)
            cases = [
                (False, 'ordinary', '{}'),
                (True, 'META-INF/csv-cloud-transport-evidence.json', '{}'),
                (True, 'META-INF/microprofile-config.properties', 'csv.iam.endpoints.process-csv-payments-input=https://fixture.lambda-url.eu-west-1.on.aws'),
                (True, 'META-INF/microprofile-config.properties', 'quarkus.rest-client.process-csv-payments-input.providers=org.pipelineframework.csv.iam.LambdaIamRequestSigning'),
                (False, 'META-INF/microprofile-config.properties', 'application.name=ordinary-unbound'),
            ]
            for index, (bound, entry, contents) in enumerate(cases):
                package=root/f'package-{index}.zip'
                with zipfile.ZipFile(package,'w') as archive:
                    archive.writestr(entry, contents)
                args=type('Args',(),{'ingress_package':package})()
                context={'args':args,'ROOT':root,'EVIDENCE':root/'evidence','zipfile':zipfile}
                if bound:
                    with self.assertRaisesRegex(RuntimeError,'Ingress package carries a Cloud binding'):exec(guard,context)
                else:exec(guard,context)
                self.assertFalse((root/'evidence').exists())


if __name__ == '__main__':
    unittest.main()
