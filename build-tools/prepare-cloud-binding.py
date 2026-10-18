#!/usr/bin/env python3
"""Validate a trusted Cloud CI transport export and prepare ordinary MP resources.

This validates syntax and projection only. It cannot authenticate arbitrary local JSON;
Cloud CI must authenticate the bootstrap business owner receipt before selecting input.
"""
import argparse
import hashlib
import json
import pathlib
import re
import urllib.parse

CLIENT_KEYS = (
    'process-csv-payments-input',
    'process-approved-payment-status',
    'process-unapproved-payment-status',
    'process-finalize-payment-output',
)
PROVIDER = 'org.pipelineframework.csv.iam.LambdaIamRequestSigning'
FIELDS = {'schemaVersion', 'accountId', 'region', 'organizationId', 'applicationId',
          'environmentId', 'ownerId', 'stateOwnerId', 'bootstrapBusinessOwnerReceiptSha256', 'operations'}
RESOURCE = pathlib.Path('META-INF/microprofile-config.properties')
EVIDENCE_RESOURCE = pathlib.Path('META-INF/csv-cloud-transport-evidence.json')


def exact_fields(value, fields, label):
    if not isinstance(value, dict) or set(value) != set(fields):
        raise ValueError(f'{label} requires exactly the approved fields')


def no_duplicates(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f'Duplicate field: {key}')
        value[key] = item
    return value


def nonempty(value, label):
    if not isinstance(value, str) or not value.strip() or len(value) > 256 or any(ord(c) < 32 for c in value):
        raise ValueError(f'{label} must be a bounded nonempty string')
    return value


def origin(value, region):
    value = nonempty(value, 'httpsOrigin')
    parsed = urllib.parse.urlsplit(value)
    hostname = parsed.hostname
    if (parsed.scheme != 'https' or not hostname
            or not re.fullmatch(r'[a-z0-9]+\.lambda-url\.' + re.escape(region) + r'\.on\.aws', hostname)
            or parsed.netloc != hostname or parsed.path not in ('', '/')
            or parsed.query or parsed.fragment or '?' in value or '#' in value):
        raise ValueError('httpsOrigin must be an exact HTTPS Lambda Function URL origin in the selected region')
    return 'https://' + hostname


def prepare(binding_file, output_directory, evidence_file):
    raw = pathlib.Path(binding_file).read_bytes()
    if len(raw) > 16384:
        raise ValueError('Cloud transport export exceeds 16 KiB')
    binding = json.loads(raw, object_pairs_hook=no_duplicates)
    exact_fields(binding, FIELDS, 'Transport export')
    if type(binding['schemaVersion']) is not int or binding['schemaVersion'] != 1:
        raise ValueError('Unsupported transport export schemaVersion')
    if not isinstance(binding['accountId'], str) or not re.fullmatch(r'[0-9]{12}', binding['accountId']):
        raise ValueError('accountId must contain exactly twelve digits')
    region = nonempty(binding['region'], 'region')
    if not re.fullmatch(r'[a-z]{2}(?:-[a-z]+)+-[0-9]+', region):
        raise ValueError('Invalid Cloud region')
    for key in ('organizationId', 'applicationId', 'environmentId', 'ownerId', 'stateOwnerId'):
        nonempty(binding[key], key)
    receipt = binding['bootstrapBusinessOwnerReceiptSha256']
    if not isinstance(receipt, str) or not re.fullmatch(r'[a-f0-9]{64}', receipt):
        raise ValueError('bootstrapBusinessOwnerReceiptSha256 must be lowercase SHA-256 hex')
    exact_fields(binding['operations'], CLIENT_KEYS, 'operations')
    properties = {'csv.iam.region': region}
    normalized = {}
    for key in CLIENT_KEYS:
        operation = binding['operations'][key]
        exact_fields(operation, {'httpsOrigin'}, 'operation ' + key)
        endpoint = origin(operation['httpsOrigin'], region)
        normalized[key] = endpoint
        properties['csv.iam.endpoints.' + key] = endpoint
        prefix = 'quarkus.rest-client.' + key + '.'
        properties[prefix + 'url'] = endpoint
        properties[prefix + 'providers'] = PROVIDER
        properties[prefix + 'follow-redirects'] = 'false'
    content = ('# Generated from a Cloud-verified nonsecret transport export.\n'
               + ''.join(key + '=' + properties[key] + '\n' for key in sorted(properties))).encode('utf-8')
    output = pathlib.Path(output_directory) / RESOURCE
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(content)
    evidence = {
        'schemaVersion': 1,
        'transportExportSha256': hashlib.sha256(raw).hexdigest(),
        'configSha256': hashlib.sha256(content).hexdigest(),
        'bootstrapBusinessOwnerReceiptSha256': receipt,
        'scope': {key: binding[key] for key in ('accountId', 'region', 'organizationId', 'applicationId',
                                               'environmentId', 'ownerId', 'stateOwnerId')},
        'origins': normalized,
        'resource': RESOURCE.as_posix(),
        'provenanceLimit': 'Syntax/projection validation only; Cloud CI must authenticate the actual owner receipt.',
    }
    evidence_path = pathlib.Path(evidence_file)
    evidence_path.parent.mkdir(parents=True, exist_ok=True)
    evidence_bytes = (json.dumps(evidence, sort_keys=True, indent=2) + '\n').encode('utf-8')
    evidence_path.write_bytes(evidence_bytes)
    (pathlib.Path(output_directory) / EVIDENCE_RESOURCE).write_bytes(evidence_bytes)
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binding-file', required=True)
    parser.add_argument('--output-directory', required=True)
    parser.add_argument('--evidence-file', required=True)
    args = parser.parse_args()
    prepare(args.binding_file, args.output_directory, args.evidence_file)


if __name__ == '__main__':
    main()
