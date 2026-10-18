#!/usr/bin/env python3
"""Project Cloud-observed post-native role identities into ordinary worker resources."""
import argparse
import hashlib
import json
import pathlib
import re
import importlib.util

spec = importlib.util.spec_from_file_location('cloud_binding', pathlib.Path(__file__).with_name('prepare-cloud-binding.py'))
shared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shared)


def prepare(binding_file, output_directory, evidence_file):
    raw = pathlib.Path(binding_file).read_bytes()
    if len(raw) > 4096:
        raise ValueError('Worker export exceeds 4 KiB')
    value = json.loads(raw, object_pairs_hook=shared.no_duplicates)
    shared.exact_fields(value, {'schemaVersion', 'accountId', 'allowedRoles'}, 'Worker export')
    if type(value['schemaVersion']) is not int or value['schemaVersion'] != 1:
        raise ValueError('Unsupported worker export schemaVersion')
    account = value['accountId']
    if not isinstance(account, str) or not re.fullmatch(r'[0-9]{12}', account):
        raise ValueError('Invalid worker accountId')
    if not isinstance(value['allowedRoles'], list) or len(value['allowedRoles']) != 2:
        raise ValueError('Worker export requires exactly the observed ActionRole and WorkerRole')
    roles = {}
    for role in value['allowedRoles']:
        shared.exact_fields(role, {'roleId', 'roleArn'}, 'Allowed role')
        role_id, arn = role['roleId'], role['roleArn']
        if not isinstance(role_id, str) or not re.fullmatch(r'[A-Za-z0-9]{1,128}', role_id):
            raise ValueError('Invalid immutable role ID')
        if not isinstance(arn, str) or not re.fullmatch(r'arn:[a-z0-9-]+:iam::' + account + r':role/(?:[A-Za-z0-9_+=,.@-]+/)*[A-Za-z0-9_+=,.@-]{1,64}', arn):
            raise ValueError('Invalid exact same-account role ARN')
        if role_id in roles or arn in roles.values():
            raise ValueError('Worker role IDs and ARNs must be distinct')
        roles[role_id] = arn
    properties = {'config_ordinal': '260', 'csv.worker.account-id': account, 'csv.worker.principal-arn': ''}
    properties.update({'csv.worker.allowed-role-arns.' + key: arn for key, arn in roles.items()})
    content = ('# Generated from Cloud-observed native role identities; no credentials.\n'
               + ''.join(key + '=' + properties[key] + '\n' for key in sorted(properties))).encode()
    output = pathlib.Path(output_directory) / shared.RESOURCE
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(content)
    evidence = {'schemaVersion': 1, 'workerExportSha256': hashlib.sha256(raw).hexdigest(),
                'configSha256': hashlib.sha256(content).hexdigest(), 'accountId': account,
                'allowedRoles': [{'roleId': key, 'roleArn': roles[key]} for key in sorted(roles)],
                'authority': 'Syntax/projection only; Cloud verifies actual IAM GetRole identities and final immutable code binding.'}
    encoded = (json.dumps(evidence, sort_keys=True, indent=2) + '\n').encode()
    (output.parent / 'csv-cloud-worker-evidence.json').write_bytes(encoded)
    pathlib.Path(evidence_file).write_bytes(encoded)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binding-file', required=True)
    parser.add_argument('--output-directory', required=True)
    parser.add_argument('--evidence-file', required=True)
    args = parser.parse_args()
    try:
        prepare(args.binding_file, args.output_directory, args.evidence_file)
    except (ValueError, OSError) as failure:
        parser.exit(2, str(failure) + '\n')
