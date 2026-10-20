"""Synthetic external owner metadata tests; never deployment provenance."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('first_burn',Path(__file__).with_name('package-first-burn.py'))
P=importlib.util.module_from_spec(spec);spec.loader.exec_module(P)

class FirstBurnPackagingTest(unittest.TestCase):
    def test_variants_and_tests_preserve_existing_recipe(self):
        commands=P.plan('transport','/synthetic/transport.json')
        self.assertEqual([name for name,_ in commands],[None,'durable-action-lambda-svc',None,'ingress-lambda-svc','provider-lambda-svc'])
        self.assertIn('-Dtpf.build.coordination.host=AWS_DURABLE',commands[0][1]);self.assertIn('-Dtpf.build.function.http-bridge=false',commands[0][1])
        self.assertIn('-Dtpf.build.coordination.host=NATIVE',commands[2][1]);self.assertIn('-Dtpf.build.function.http-bridge=true',commands[2][1])
        for name,command in commands:
            self.assertIn('-o',command);self.assertIn('-Dmaven.repo.local='+str(P.ROOT/'.m2/repository'),command)
            if name:self.assertIn('verify',command);self.assertNotIn('-DskipTests',command)
        worker=P.plan('worker',worker='/synthetic/roles.json')
        self.assertIn('-Dtpf.build.coordination.host=NATIVE',worker[0][1]);self.assertEqual(worker[1][0],'worker-lambda-svc')
    def test_production_configuration_and_credentials_cannot_leak_into_build(self):
        with patch.dict(os.environ,{'AWS_PROFILE':'not-for-build','AWS_SECRET_ACCESS_KEY':'sensitive','CSV_PILOT_RESUME_TOKEN_PARAMETER_ARN':'not-for-build','PIPELINE_ORCHESTRATOR_RESUME_TOKEN_SECRET':'sensitive','MAVEN_ARGS':'-U','TPF_MAVEN_ARGS_FILE':'unreviewed','QUARKUS_PROFILE':'unreviewed','QUARKUS_CONFIG_LOCATIONS':'file:/unreviewed.properties','MAVEN_SKIP_RC':'false','MAVEN_DEBUG_OPTS':'-Dsecret=sensitive','MAVEN_OPTS':'-Dsecret=sensitive','JAVA_OPTS':'-Dsecret=sensitive','JAVA_TOOL_OPTIONS':'-Dsecret=sensitive','_JAVA_OPTIONS':'-Dsecret=sensitive','JDK_JAVA_OPTIONS':'-Dsecret=sensitive'}):
            env=P.environment()
        self.assertNotIn('QUARKUS_PROFILE',env);self.assertNotIn('QUARKUS_CONFIG_LOCATIONS',env);self.assertEqual(env['MAVEN_SKIP_RC'],'true')
        self.assertNotIn('AWS_PROFILE',env);self.assertNotIn('AWS_SECRET_ACCESS_KEY',env);self.assertNotIn('CSV_PILOT_RESUME_TOKEN_PARAMETER_ARN',env)
        self.assertNotIn('PIPELINE_ORCHESTRATOR_RESUME_TOKEN_SECRET',env);self.assertNotIn('MAVEN_ARGS',env)
        for key in ['MAVEN_DEBUG_OPTS','MAVEN_OPTS','JAVA_OPTS','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS']:self.assertNotIn(key,env)
        self.assertEqual(env['AWS_EC2_METADATA_DISABLED'],'true')
    def test_source_mutation_rejects_packaging_and_owned_outputs_are_excluded(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'source.java';source.write_text('original')
            output=root/'owned-output';output.mkdir();(output/'build.log').write_text('log')
            paths=b'source.java\0owned-output/build.log\0'
            with patch.object(P,'ROOT',root),patch.object(P.subprocess,'check_output',return_value=paths):
                expected=P.source_snapshot(output);self.assertEqual(set(expected['files']),{'source.java'})
                (output/'build.log').write_text('new log');P.assert_source_unchanged(expected,output)
                source.write_text('changed')
                with self.assertRaisesRegex(ValueError,'source changed'):P.assert_source_unchanged(expected,output)
    def test_driver_owned_arguments_reach_nested_bootstrap(self):
        with patch.dict(os.environ,{'TPF_MAVEN_ARGS_FILE':'unreviewed'}):
            self.assertEqual(P.environment('/reviewed/offline.nul')['TPF_MAVEN_ARGS_FILE'],'/reviewed/offline.nul')
    def test_export_is_frozen_independently_of_later_owner_edits(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'source.json';source.write_text('{}')
            frozen,sha=P.freeze_export(source,root);source.write_text('{"changed":true}')
            self.assertEqual(P.digest(frozen),sha);self.assertNotEqual(P.digest(source),sha)
    def fixture(self,root):
        receipt=root/'synthetic-owner-receipt';receipt.write_text('external test receipt, not AWS ownership')
        sha=P.digest(receipt)
        binding={'schemaVersion':1,'accountId':'207828640987','region':'us-east-2','organizationId':'synthetic-org','applicationId':'synthetic-app','environmentId':'synthetic-env','ownerId':'synthetic-owner','stateOwnerId':'synthetic-state','bootstrapBusinessOwnerReceiptSha256':sha,'operations':{key:{'httpsOrigin':'https://fixture.lambda-url.us-east-2.on.aws'} for key in P.module('cloud-binding').CLIENT_KEYS}}
        export=root/'synthetic-export.json';export.write_text(json.dumps(binding));return receipt,sha,binding,export
    def test_real_export_contract_is_projected_without_manufacturing_authentication(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);receipt,sha,_,export=self.fixture(root)
            evidence=P.validate_export('transport',export,receipt,sha,root/'output')
            self.assertIn('must authenticate',evidence['provenanceLimit']);self.assertEqual(len(evidence['origins']),4)
    def test_wrong_receipt_account_region_or_split_origin_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);receipt,sha,value,export=self.fixture(root)
            with self.assertRaises(ValueError):P.validate_export('transport',export,receipt,'0'*64,root/'bad')
            for key,wrong in [('accountId','111111111111'),('region','us-west-2')]:
                export.write_text(json.dumps(value|{key:wrong}))
                with self.subTest(key=key),self.assertRaises(ValueError):P.validate_export('transport',export,receipt,sha,root/'bad')
            changed=copy.deepcopy(value);changed['operations'][next(iter(changed['operations']))]['httpsOrigin']='https://other.lambda-url.us-east-2.on.aws';export.write_text(json.dumps(changed))
            with self.assertRaises(ValueError):P.validate_export('transport',export,receipt,sha,root/'bad')
    def test_worker_evidence_records_both_immutable_role_bindings(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);receipt=root/'synthetic-owner-receipt';receipt.write_text('local role fixture only')
            value={'schemaVersion':1,'accountId':'207828640987','allowedRoles':[
                {'roleId':'AROASYNTHETICACTION','roleArn':'arn:aws:iam::207828640987:role/fixture-action'},
                {'roleId':'AROASYNTHETICWORKER','roleArn':'arn:aws:iam::207828640987:role/fixture-worker'}]}
            export=root/'synthetic-worker.json';export.write_text(json.dumps(value))
            result=P.validate_export('worker',export,receipt,P.digest(receipt),root/'output')
            self.assertEqual(len(result['allowedRoles']),2);self.assertIn('Syntax/projection only',result['authority'])
    def test_unapproved_frozen_cache_receipt_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'not-a-public-receipt';path.write_text('{}')
            with self.assertRaisesRegex(ValueError,'Unapproved'):P.baseline(path)

if __name__=='__main__':unittest.main()
