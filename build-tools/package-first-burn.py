#!/usr/bin/env python3
"""Build existing CSV Lambda variants from authenticated owner exports and frozen public cache.

No AWS calls/publication. Owner export authentication remains Cloud's responsibility.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[1]
BASELINE_SHA256='c5bba839509d176d7666fd2eba9a8de53cccb68b8367b1270c43b6b324399bbe'
PUBLIC_SOURCES={'931196a7bf7aa1102129da98f5344c5fd7b82f7b','2d11cd2fbd5bb220ac70ebf0a60567db1c4a9980'}

def digest(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def module(name):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name('prepare-'+name+'.py'))
    loaded=importlib.util.module_from_spec(spec);spec.loader.exec_module(loaded);return loaded


def baseline(path):
    if digest(path)!=BASELINE_SHA256:raise ValueError('Unapproved public baseline receipt digest')
    value=json.loads(Path(path).read_text())
    if value['status']!='PREPARED_AUTHENTICATED_NORMAL_PUBLIC_CACHE' or {p['source'] for p in value['publications']}!=PUBLIC_SOURCES:
        raise ValueError('Public baseline source mismatch')
    for item in value['cacheFiles']:
        original=Path(item['path']);relative=original.relative_to(value['cache'])
        if relative.is_absolute() or '..' in relative.parts:raise ValueError('Unsafe baseline cache path')
        actual=ROOT/'.m2/repository'/relative
        if not actual.is_file() or digest(actual)!=item['sha256']:raise ValueError('Frozen Maven cache differs: '+str(relative))
    return value


def plan(stage,transport=None,worker=None):
    common=['-o','-B','-ntp','-Dmaven.repo.local='+str(ROOT/'.m2/repository'),'-Dquarkus.container-image.build=false','-Dpipelineframework.bom.version=26.10.1-20261018.162140-18','-Dtpf.release.maven-plugin.version=26.10.1-20261018.170624-21']
    producer=['./build-pipeline-runtime.sh','-pl','common,payments-processing-svc,pipeline-runtime-svc,orchestrator-svc',
        '-DskipTests','-DskipITs=true','-Dtpf.build.platform=FUNCTION','-Dtpf.build.lambda.scope=compile',
        '-Dtpf.await.kafka.reactive-messaging.enabled=false','-Dcsv-payments.payment-provider.enabled=false',
        '-Dcsv-payments.payment-provider-sqs.enabled=true']
    def variant(host,bridge):return producer+['-Dtpf.build.coordination.host='+host,'-Dtpf.build.function.http-bridge='+bridge]+common
    def package(name,binding=None):return ['./mvnw','-f',name+'/pom.xml','clean','verify']+common+([binding] if binding else [])
    if stage=='transport':
        if not transport:raise ValueError('Transport export required')
        binding='-Dcsv.cloud.binding-file='+str(Path(transport).resolve())
        return [(None,variant('AWS_DURABLE','false')),
                ('durable-action-lambda-svc',package('durable-action-lambda-svc',binding)),
                (None,variant('NATIVE','true')),
                ('ingress-lambda-svc',package('ingress-lambda-svc',binding)),
                ('provider-lambda-svc',package('provider-lambda-svc'))]
    if stage=='worker':
        if not worker:raise ValueError('Observed native role export required')
        return [(None,variant('NATIVE','true')),('worker-lambda-svc',package('worker-lambda-svc','-Dcsv.cloud.worker-binding-file='+str(Path(worker).resolve())))]
    raise ValueError('Unknown package stage')


def validate_export(stage,export,receipt,expected_receipt_sha,output):
    if not expected_receipt_sha or digest(receipt)!=expected_receipt_sha:raise ValueError('Owner receipt digest mismatch')
    value=json.loads(Path(export).read_text())
    if value.get('accountId')!='207828640987':raise ValueError('First-burn account differs from approved intent')
    if stage=='transport':
        if value.get('region')!='us-east-2' or value.get('bootstrapBusinessOwnerReceiptSha256')!=expected_receipt_sha:
            raise ValueError('Transport owner scope or receipt differs')
        if len({entry['httpsOrigin'].rstrip('/') for entry in value['operations'].values()})!=1:
            raise ValueError('First burn uses one grouped worker origin')
        result=module('cloud-binding').prepare(export,output/'checked-export',output/'checked-transport.json')
    else:
        module('worker-binding').prepare(export,output/'checked-export',output/'checked-worker.json')
        result=json.loads((output/'checked-worker.json').read_text())
    return result


def environment(maven_args_file=None):
    env={key:value for key,value in os.environ.items() if not key.startswith(('AWS_','CSV_PILOT_'))
        and key not in ('MAVEN_ARGS','MAVEN_OPTS','JAVA_OPTS','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS',
            'TPF_MAVEN_ARGS_FILE','PIPELINE_ORCHESTRATOR_RESUME_TOKEN_SECRET','MVN_BIN')}
    env.update(PIPELINE_CONFIG=str(ROOT/'config/pipeline.pilot.yaml'),PIPELINE_TRANSPORT='REST',AWS_EC2_METADATA_DISABLED='true')
    if maven_args_file is not None:env['TPF_MAVEN_ARGS_FILE']=str(Path(maven_args_file).resolve())
    return env


def freeze_export(source,output):
    export_sha=digest(source);frozen=output/'frozen-owner-export.json'
    shutil.copyfile(source,frozen)
    if digest(frozen)!=export_sha:raise ValueError('Export changed during freeze')
    return frozen,export_sha


def source_snapshot(exclude=None):
    paths=subprocess.check_output(['git','ls-files','-c','-o','--exclude-standard','-z'],cwd=ROOT).decode().split('\0')
    values={name:digest(ROOT/name) for name in sorted(set(paths)) if name and (ROOT/name).is_file()
        and (exclude is None or not (ROOT/name).resolve().is_relative_to(Path(exclude).resolve()))}
    return {'files':values,'sha256':hashlib.sha256(json.dumps(values,sort_keys=True).encode()).hexdigest()}


def assert_source_unchanged(expected,output):
    if source_snapshot(output)!=expected:raise ValueError('Application source changed during staged packaging')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage',choices=['transport','worker'],required=True)
    parser.add_argument('--export',type=Path,required=True)
    parser.add_argument('--owner-receipt',type=Path,required=True)
    parser.add_argument('--owner-receipt-sha256',required=True)
    parser.add_argument('--baseline-receipt',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--plan-only',action='store_true',help='Validate and emit commands, produce no Lambda or deployment receipt')
    args=parser.parse_args()
    baseline(args.baseline_receipt)
    if args.output.exists() and any(args.output.iterdir()):raise ValueError('Use a fresh output directory; immutable packages are never overwritten')
    args.output.mkdir(parents=True,exist_ok=True)
    frozen,export_sha=freeze_export(args.export,args.output)
    checked=validate_export(args.stage,frozen,args.owner_receipt,args.owner_receipt_sha256,args.output)
    commands=plan(args.stage,frozen if args.stage=='transport' else None,frozen if args.stage=='worker' else None)
    settings=args.output/'frozen-maven-settings.xml'
    settings.write_text('<settings><mirrors><mirror><id>central-snapshots</id><url>https://central.sonatype.com/repository/maven-snapshots/</url><mirrorOf>central-snapshot-plugins</mirrorOf></mirror></mirrors></settings>\n')
    commands=[(name,command+['-s',str(settings.resolve())]) for name,command in commands]
    maven_args=args.output/'frozen-maven-args.nul'
    maven_args.write_bytes(('\0'.join(['-o','-s',str(settings.resolve()),'-Dmaven.repo.local='+str(ROOT/'.m2/repository'),'-Dquarkus.container-image.build=false','-Dpipelineframework.bom.version=26.10.1-20261018.162140-18','-Dtpf.release.maven-plugin.version=26.10.1-20261018.170624-21'])+'\0').encode())
    manifest={'schemaVersion':1,'stage':args.stage,'status':'VALIDATED_COMMAND_PLAN_ONLY' if args.plan_only else 'BUILDING',
        'baselineReceiptSha256':BASELINE_SHA256,'ownerReceiptSha256':args.owner_receipt_sha256,
        'exportSha256':export_sha,'sourceHead':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        'sourceSnapshot':source_snapshot(args.output),'exportEvidence':checked,'commands':[command for _,command in commands],'packages':[],
        'authorityLimit':'Projection/build only; Cloud authenticates resource ownership and actual numbered versions before admission.',
        'awsActions':False}
    (args.output/'build-intent.json').write_text(json.dumps(manifest,indent=2)+'\n')
    if args.plan_only:return
    for index,(name,command) in enumerate(commands):
        assert_source_unchanged(manifest['sourceSnapshot'],args.output)
        if digest(frozen)!=export_sha:raise ValueError('Frozen export changed before build')
        with (args.output/f'build-{index}.log').open('w') as log:subprocess.run(command,cwd=ROOT,env=environment(maven_args),stdout=log,stderr=subprocess.STDOUT,check=True)
        assert_source_unchanged(manifest['sourceSnapshot'],args.output)
        baseline(args.baseline_receipt)
        if name:
            source=ROOT/name/'target/function.zip';destination=args.output/(name+'-function.zip')
            with zipfile.ZipFile(source) as package:
                evidence='META-INF/csv-cloud-worker-evidence.json' if args.stage=='worker' else 'META-INF/csv-cloud-transport-evidence.json'
                if name!='provider-lambda-svc' and evidence not in package.namelist():raise ValueError('Package missing compiled binding evidence')
            shutil.copyfile(source,destination)
            manifest['packages'].append({'module':name,'path':str(destination.resolve()),'bytes':destination.stat().st_size,'sha256':digest(destination)})
    manifest['status']='PACKAGES_BUILT_NOT_DEPLOYED'
    (args.output/'package-receipt.json').write_text(json.dumps(manifest,indent=2)+'\n')

if __name__=='__main__':main()
