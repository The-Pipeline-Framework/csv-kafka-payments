#!/usr/bin/env python3
"""Cold packaged worker authorization using synthetic AWS-authenticated contexts, no live AWS."""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import zipfile
from worker_proof import WorkerProof
if not __debug__:
    raise RuntimeError('This evidence proof requires assertions; remove Python -O/PYTHONOPTIMIZE')

ROOT = pathlib.Path(__file__).resolve().parents[2]
ACCOUNT = '000000000000'
ROLES = [('AROATESTIMMUTABLEACTION', 'action-role'), ('AROATESTIMMUTABLEWORKER', 'worker-role')]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--write-fixture-binding', type=pathlib.Path)
parser.add_argument('--expect-unbound', action='store_true')
args = parser.parse_args()
if args.write_fixture_binding:
    value = {'schemaVersion': 1, 'accountId': ACCOUNT, 'allowedRoles': [
        {'roleId': role_id, 'roleArn': f'arn:aws:iam::{ACCOUNT}:role/native/{name}'} for role_id, name in ROLES]}
    args.write_fixture_binding.parent.mkdir(parents=True, exist_ok=True)
    args.write_fixture_binding.write_text(json.dumps(value, sort_keys=True, indent=2) + '\n')
    sys.exit(0)
OUT = ROOT / ('target/worker-binding-unbound-proof' if args.expect_unbound else 'target/worker-binding-proof')
OUT.mkdir(parents=True, exist_ok=True)
package = ROOT / 'worker-lambda-svc/target/function.zip'
with zipfile.ZipFile(package) as archive:
    resource = 'META-INF/microprofile-config.properties'
    receipt = 'META-INF/csv-cloud-worker-evidence.json'
    if args.expect_unbound:
        assert resource not in archive.namelist() and receipt not in archive.namelist()
        embedded = None
    else:
        embedded = json.loads(archive.read(receipt))
        assert hashlib.sha256(archive.read(resource)).hexdigest() == embedded['configSha256']

fixture = WorkerProof.__new__(WorkerProof)
fixture.root, fixture.evidence = ROOT, OUT
fixture.java = pathlib.Path(os.environ['JAVA_HOME']) / 'bin'
fixture.env = {key: value for key, value in os.environ.items()
               if not key.startswith(('AWS_', 'QUARKUS_', 'CSV_', 'PIPELINE_', 'TPF_', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS'))}
fixture.env.update(AWS_ACCESS_KEY_ID='deterministic-worker-fixture', AWS_SECRET_ACCESS_KEY='not-live',
                   AWS_SESSION_TOKEN='deterministic-test-session', AWS_REGION='us-east-1', AWS_EC2_METADATA_DISABLED='true')
fixture.processes, fixture.logs, fixture.readers = [], [], []
fixture.run = lambda command: subprocess.run(command, check=True, text=True, capture_output=True).stdout
props = {'quarkus.http.port': '0', 'quarkus.http.ssl-port': '0', 'quarkus.otel.sdk.disabled': 'true',
         'pipeline.orchestrator.resume-token-secret': 'deterministic-local-resume-secret',
         'pipeline.orchestrator.sqs.region': 'us-east-1', 'pipeline.orchestrator.sqs.endpoint-override': 'http://127.0.0.1:4597'}
tests = []


def event(role_id=ROLES[0][0], name=ROLES[0][1], session='native-session-1', account=ACCOUNT):
    context = {'accountId': ACCOUNT, 'apiId': 'fixture', 'domainName': 'localhost', 'requestId': 'worker-binding',
               'routeKey': '$default', 'stage': '$default', 'timeEpoch': 0,
               'http': {'method': 'POST', 'path': '/api/v1/__proof_unknown', 'protocol': 'HTTP/1.1', 'sourceIp': '127.0.0.1', 'userAgent': 'proof'},
               'authorizer': {'iam': {'accountId': account, 'userArn': f'arn:aws:sts::{account}:assumed-role/{name}/{session}', 'userId': f'{role_id}:{session}'}}}
    return {'version': '2.0', 'routeKey': '$default', 'rawPath': '/api/v1/__proof_unknown', 'rawQueryString': '',
            'headers': {'host': 'localhost', 'content-type': 'application/json', 'x-forwarded-proto': 'https'},
            'requestContext': context, 'body': '{}', 'isBase64Encoded': False}


def check(target, name, request, expected):
    response = fixture.invoke(target, request)
    tests.append({'test': name, 'statusCode': response.get('statusCode'), 'expected': expected})
    assert response.get('statusCode') == expected, (name, response)


try:
    target = fixture.launch('cold-worker', package, props)
    if args.expect_unbound:
        check(target, 'unbound-no-stale-role-configuration', event(), 503)
        legacy = fixture.launch('legacy-worker', package, props | {'csv.worker.account-id': ACCOUNT,
                              'csv.worker.principal-arn': f'arn:aws:iam::{ACCOUNT}:role/local-worker-fixture'})
        request = event()
        request['requestContext']['authorizer']['iam']['userArn'] = f'arn:aws:iam::{ACCOUNT}:role/local-worker-fixture'
        check(legacy, 'legacy-exact-principal', request, 404)
        request['requestContext']['authorizer']['iam']['userArn'] += '-foreign'
        check(legacy, 'legacy-foreign-principal', request, 403)
    else:
        for role_id, name in ROLES:
            for session in ['native-session-1', 'native-session-2']:
                check(target, name + '-' + session, event(role_id, name, session), 404)
        check(target, 'foreign-account', event(account='111111111111'), 403)
        check(target, 'foreign-role', event(name='foreign-role'), 403)
        check(target, 'foreign-immutable-id', event(role_id=ROLES[0][0] + 'EXTRA'), 403)
        request = event()
        request['requestContext']['authorizer']['iam']['userId'] = ROLES[0][0] + ':different-session'
        check(target, 'mismatched-session', request, 403)
        for arn in [f'arn:aws:iam::{ACCOUNT}:root', f'arn:aws:iam::{ACCOUNT}:role/native/action-role']:
            request = event()
            request['requestContext']['authorizer']['iam']['userArn'] = arn
            check(target, 'root-or-sessionless-' + arn.rsplit(':', 1)[-1], request, 403)
        request = event()
        del request['requestContext']['authorizer']['iam']['userId']
        # Pinned Quarkus cannot authenticate an IAM principal without userId.
        check(target, 'missing-role-id-authentication-denied', request, 401)
        request = event()
        del request['requestContext']['authorizer']
        check(target, 'anonymous', request, 401)
        request = event()
        request['rawPath'] = request['requestContext']['http']['path'] = '/tpf/executions'
        check(target, 'control-plane-denied', request, 403)
        for name, override in [('malformed-role', {'csv.worker.allowed-role-arns.' + ROLES[0][0]: '*'}),
                               ('mixed-modes', {'csv.worker.principal-arn': f'arn:aws:iam::{ACCOUNT}:role/local-worker-fixture'})]:
            invalid = fixture.launch(name, package, props | override)
            check(invalid, name + '-fails-closed', event(), 503)
    result = {'status': 'PASS', 'scope': 'Actual packaged Lambda HTTP adapter and routes, synthetic authenticated AWS contexts; actual Function URL caller shape remains a live gate.',
              'packageSha256': hashlib.sha256(package.read_bytes()).hexdigest(), 'embeddedConfiguration': embedded,
              'positiveIdentityLaunchOverrides': 'none' if not args.expect_unbound else 'legacy-mode probe only', 'tests': tests}
    (OUT / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'status': 'PASS', 'tests': len(tests), 'evidence': str(OUT / 'result.json')}))
finally:
    for process in fixture.processes:
        process.stdin.close()
        try: process.wait(timeout=15)
        except subprocess.TimeoutExpired: process.kill(); process.wait()
    for reader in fixture.readers: reader.join(timeout=2)
    for log in fixture.logs: log.close()
