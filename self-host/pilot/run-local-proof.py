#!/usr/bin/env python3
"""No real AWS: packaged HTTP IAM adapter + pinned native Durable ingress/actions + local S3/Dynamo.
AWS event identity delivery and Lambda provider behavior are explicit external fixtures, not IAM/replay certification.
"""
import sys
from worker_proof import cleanup_all
import argparse, fcntl, tempfile, base64, concurrent.futures, datetime, hashlib, http.server, json, os, pathlib, queue, shutil, subprocess, threading, time, urllib.error, urllib.parse, urllib.request, zipfile
if not __debug__:raise RuntimeError('This evidence proof requires assertions; remove Python -O/PYTHONOPTIMIZE')
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--uploads-only',action='store_true',help='Independent upload proof; never claims Durable admission/replay');parser.add_argument('--probe-part-limit',action='store_true',help='Local-only bounded two-MiB part probe, then restore the declared part');parser.add_argument('--host-jar',type=pathlib.Path,help='Exact published native host JAR for this qualification');parser.add_argument('--host-sha256',help='Expected host digest from the publication or compatibility receipt');parser.add_argument('--execute-workers',action='store_true',help='Execute packaged REST workers and payment provider with native SQS handlers; external delivery remains a fixture');args=parser.parse_args()
if args.execute_workers and args.uploads_only:parser.error('--execute-workers requires native admission')
ROOT=pathlib.Path(__file__).resolve().parents[2]
java_home=os.environ.get('JAVA_HOME');javac=shutil.which('javac')
if not java_home and not javac:raise RuntimeError('A JDK 21 installation is required; set JAVA_HOME')
JAVA=pathlib.Path(java_home)/'bin' if java_home else pathlib.Path(javac).parent
EVIDENCE=ROOT/'target/pilot-local-proof';EVIDENCE.mkdir(parents=True,exist_ok=True)
COMPOSE=['docker','compose','-f',str(ROOT/'self-host/container/compose.finite-proof.yaml')]
ENDPOINT='http://127.0.0.1:4597';LAMBDA='http://127.0.0.1:8198'
HOST=ROOT/'.m2/repository/org/pipelineframework/pipelineframework-aws-durable-coordination-host/26.10.1-SNAPSHOT/pipelineframework-aws-durable-coordination-host-26.10.1-20261015.182717-11.jar'
if args.host_jar:
 assert args.host_sha256 and len(args.host_sha256)==64,'An explicit host digest is required'
 HOST=args.host_jar.resolve()
if hashlib.sha256(HOST.read_bytes()).hexdigest()!=(args.host_sha256 or '1c6e89433cd7687c301487638cb46ce85d94a5714683b9b865eb349240e31b6b'):
 raise RuntimeError('Native host bytes do not match the pinned SHA-256')
ZIP=ROOT/'ingress-lambda-svc/target/function.zip';UNPACK=EVIDENCE/'unpacked'
CP=f'{EVIDENCE}:{UNPACK}:{UNPACK}/lib/*:{HOST}'
ENV={k:v for k,v in os.environ.items() if not k.startswith(('AWS_','QUARKUS_','CSV_','PIPELINE_','TPF_','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS'))}
ENV.update(AWS_ACCESS_KEY_ID='test',AWS_SECRET_ACCESS_KEY='test',AWS_REGION='us-east-1',AWS_DEFAULT_REGION='us-east-1',AWS_EC2_METADATA_DISABLED='true',AWS_ENDPOINT_URL_S3=ENDPOINT,AWS_ENDPOINT_URL_DYNAMODB=ENDPOINT,AWS_ENDPOINT_URL_LAMBDA=LAMBDA,TPF_OBJECT_REFERENCE_HMAC_KEY=base64.b64encode(b'0123456789abcdef0123456789abcdef').decode(),PIPELINE_ORCHESTRATOR_RESUME_TOKEN_SECRET='local-pilot-resume-key-not-a-cloud-key',TPF_WORKER_SECRET='local-pilot-worker-key-not-a-cloud-key',TPF_ADMIN_TOKEN='local-pilot-admin',TPF_CONTROL_PLANE_TOKEN='local-pilot-control',PIPELINE_CONFIG=str(ROOT/'config/pipeline.pilot.yaml'),PIPELINE_OBJECT_INGEST_AUTOSTART='false',TPF_AWS_DURABLE_FUNCTION='published-coordinator',TPF_AWS_DURABLE_QUALIFIER='7')

def run(args,**kw):
 r=subprocess.run(args,cwd=ROOT,env=ENV,text=True,capture_output=True,**kw)
 if r.returncode:raise RuntimeError(f'{args[:3]} failed\n{r.stdout[-5000:]}\n{r.stderr[-1500:]}')
 return r.stdout

def aws(service,*args):return json.loads(run(COMPOSE+['exec','-T','localstack','awslocal',service,*args,'--output','json']) or '{}')
def save(n,d): (EVIDENCE/(n+'.json')).write_text(json.dumps(d,indent=2)+'\n');return d
# One local evidence lane owns the fixed fixture project and ports across checkouts.
lock_path=pathlib.Path(tempfile.gettempdir())/f'tpf-csv-native-proof-{os.getuid()}.lock'
lane_fd=os.open(lock_path,os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
try:fcntl.flock(lane_fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
except BlockingIOError:raise RuntimeError('Another CSV native proof owns the local evidence lane')
assert not run(COMPOSE+['ps','--quiet']).strip(),'Existing proof container; refuse takeover'
(EVIDENCE/'result.json').unlink(missing_ok=True)
java_version=subprocess.run([str(JAVA/'java'),'-version'],text=True,capture_output=True,check=True)
assert 'version "21.' in java_version.stderr,'Set JAVA_HOME to JDK 21 for the pinned proof'
if UNPACK.exists():shutil.rmtree(UNPACK)
with zipfile.ZipFile(ZIP) as z:z.extractall(UNPACK)
for name in ('InvokePilot','InvokeNativeIngress'):
 run([str(JAVA/'javac'),'-cp',CP,'-d',str(EVIDENCE),str(ROOT/'self-host/pilot'/f'{name}.java')])
opened=datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(seconds=2);closed=opened+datetime.timedelta(minutes=10)
props={'quarkus.http.host':'127.0.0.1','quarkus.http.port':'8197','quarkus.http.ssl-port':'0','quarkus.http.insecure-requests':'enabled','quarkus.otel.enabled':'false','quarkus.otel.sdk.disabled':'true','quarkus.micrometer.export.prometheus.enabled':'false','pipeline.health.enabled':'false','pipeline.client.tls-configuration-name':'','pipeline.orchestrator.mode':'QUEUE_ASYNC','pipeline.orchestrator.process-loops-disabled':'true','pipeline.orchestrator.state-provider':'dynamo','pipeline.orchestrator.dispatcher-provider':'sqs','pipeline.orchestrator.dlq-provider':'sqs','pipeline.orchestrator.queue-url':ENDPOINT+'/000000000000/tpf-work','pipeline.orchestrator.dlq-url':ENDPOINT+'/000000000000/tpf-execution-dlq','pipeline.orchestrator.sqs.local-loopback':'false','pipeline.orchestrator.sqs.region':'us-east-1','pipeline.orchestrator.sqs.endpoint-override':ENDPOINT,'pipeline.orchestrator.dynamo.region':'us-east-1','pipeline.orchestrator.dynamo.endpoint-override':ENDPOINT,'pipeline.orchestrator.releases.registry.provider':'dynamo','pipeline.orchestrator.releases.storage.provider':'s3','pipeline.orchestrator.releases.storage.s3.bucket':'tpf-release-artifacts','pipeline.orchestrator.releases.storage.s3.region':'us-east-1','pipeline.orchestrator.releases.storage.s3.endpoint-override':ENDPOINT,'pipeline.orchestrator.releases.storage.s3.path-style-access':'true','pipeline.orchestrator.control-plane.enabled':'true','pipeline.orchestrator.control-plane.admin-token-ref':'env:TPF_CONTROL_PLANE_TOKEN','pipeline.orchestrator.control-plane.require-remote-worker':'false','pipeline.orchestrator.admin.enabled':'true','pipeline.orchestrator.admin.admin-token-ref':'env:TPF_ADMIN_TOKEN','pipeline.await-admission.enabled':'true','pipeline.await-admission.store':'dynamo','tpf.await.kafka.reactive-messaging.enabled':'false','mp.messaging.outgoing.tpf-await-kafka-requests.enabled':'false','mp.messaging.incoming.tpf-await-kafka-responses.enabled':'false','tpf.await.sqs.poller.enabled':'false','csv-payments.payment-provider.enabled':'false','csv-payments.payment-provider-sqs.enabled':'false','server.keystore.path':str(ROOT/'target/dev-certs/orchestrator-svc/server-keystore.jks'),'client.truststore.path':str(ROOT/'target/dev-certs/orchestrator-svc/client-truststore.jks')}
for suffix,table in {'execution':'tpf_execution','execution-key':'tpf_execution_key','execution-payload':'tpf_execution_payload','await-unit':'tpf_await_unit','await-interaction':'tpf_await_interaction','await-interaction-key':'tpf_await_interaction_key','await-completion-lifecycle':'tpf_await_completion_lifecycle','await-admission':'tpf_await_admission','release':'tpf_release_registry','worker':'tpf_worker_registry'}.items():props['pipeline.orchestrator.dynamo.'+suffix+'-table']=table
with zipfile.ZipFile(ROOT/'orchestrator-svc/target/orchestrator-svc-26.9.4-SNAPSHOT.jar') as z:contract=json.loads(z.read('META-INF/pipeline/pipeline-contract.json'))
release='pilot-local-'+contract['contractVersion'];props.update({'pipeline.orchestrator.pipeline-id':contract['pipelineId'],'pipeline.orchestrator.release-version':release})
props.update({'csv.pilot.enabled':'false','csv.pilot.account-id':'000000000000','csv.pilot.principal-arn':'arn:aws:iam::000000000000:user/invited','csv.pilot.tenant':'pilot-tenant','csv.pilot.scope':'pilot-trial-1','csv.pilot.opened-at':opened.isoformat(),'csv.pilot.closes-at':closed.isoformat(),'csv.pilot.ingress-function':'native-ingress','csv.pilot.ingress-version':'3','csv.pilot.release-version':release})
ENV['SERVER_KEYSTORE_PATH']=str(ROOT/'target/dev-certs/orchestrator-svc/server-keystore.jks');ENV['CLIENT_TRUSTSTORE_PATH']=str(ROOT/'target/dev-certs/orchestrator-svc/client-truststore.jks')
provider={};provider_calls=[];lock=threading.Lock()
class LambdaFixture(http.server.BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_POST(self):
  body=self.rfile.read(int(self.headers['Content-Length']));path=urllib.parse.unquote(self.path)
  if '/functions/native-ingress/' in path:
   value=run([str(JAVA/'java'),'-cp',CP,'InvokeNativeIngress'],input=body.decode(),timeout=30).strip().splitlines()[-1].encode();status=200
  elif '/functions/published-coordinator/' in path:
   payload=json.loads(body);name=self.headers.get('X-Amz-Durable-Execution-Name')
   if not name:raise RuntimeError('Durable execution name was not supplied: '+str(dict(self.headers)))
   with lock:
    provider_calls.append({'name':name,'path':path,'input':payload});duplicate=name in provider
    if not duplicate:provider[name]=payload
   if duplicate:
    status=409;value=json.dumps({'Type':'User','message':'Existing named execution','__type':'DurableExecutionAlreadyStartedException'}).encode()
   else:status=202;value=b''
  else:status=404;value=b'{}'
  self.send_response(status);self.send_header('Content-Type','application/json')
  if status==409:self.send_header('x-amzn-ErrorType','DurableExecutionAlreadyStartedException')
  self.send_header('Content-Length',str(len(value)));self.end_headers();self.wfile.write(value)
server=http.server.ThreadingHTTPServer(('127.0.0.1',8198),LambdaFixture);threading.Thread(target=server.serve_forever,daemon=True).start()
process=None;lines=None;logs=[];workers=None
def start(label):
 global process,lines
 lines=queue.Queue();f=(EVIDENCE/(label+'.log')).open('w');logs.append(f)
 process=subprocess.Popen([str(JAVA/'java'),*[f'-D{k}={v}' for k,v in props.items()],'-cp',CP,'InvokePilot'],cwd=ROOT,env=ENV,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
 def read(p,q):
  for line in p.stdout:
   f.write(line);f.flush()
   if line.startswith('PILOT_RESULT='):q.put(json.loads(line.split('=',1)[1]))
 threading.Thread(target=read,args=(process,lines),daemon=True).start()
def stop():
 global process
 if process:process.kill();process.wait(timeout=10);process=None

def command(c):
 process.stdin.write(json.dumps(c)+'\n');process.stdin.flush()
 try:
  result=lines.get(timeout=90)
  assert not result.get('fixtureCommandFailed'),result
  return result
 except queue.Empty:raise RuntimeError('Invocation failed or timed out; see process log')

def call(path,body,identity='invited',headers=None,method='POST'):
 iam={'accountId':'000000000000','userArn':'arn:aws:iam::000000000000:user/invited','userId':'invited-user-id'}
 if identity=='foreign':iam['userArn']='arn:aws:iam::000000000000:user/foreign'
 event={'version':'2.0','routeKey':'$default','rawPath':path,'rawQueryString':'','headers':{'host':'localhost','content-type':'application/json','x-forwarded-proto':'https',**(headers or {})},'requestContext':{'accountId':'000000000000','apiId':'local','domainName':'localhost','requestId':'local-pilot','routeKey':'$default','stage':'$default','timeEpoch':int(time.time()*1000),'http':{'method':method,'path':path,'protocol':'HTTP/1.1','sourceIp':'127.0.0.1','userAgent':'local-proof'}},'body':json.dumps(body),'isBase64Encoded':False}
 if identity!='anonymous':event['requestContext']['authorizer']={'iam':iam}
 result=command(event);raw=result.get('body','')
 if result.get('isBase64Encoded'):raw=base64.b64decode(raw).decode()
 try:result['decoded']=json.loads(raw) if raw else None
 except ValueError:result['decoded']=raw
 return result

def same_reservation(first, repeated):
 # Native token claims serialize expiry in whole seconds; the first response retains subsecond precision.
 assert first['token']==repeated['token'] and first['partSizeBytes']==repeated['partSizeBytes'],(first,repeated)
 first_expiry=datetime.datetime.fromisoformat(first['expiresAt'].replace('Z','+00:00'))
 repeat_expiry=datetime.datetime.fromisoformat(repeated['expiresAt'].replace('Z','+00:00'))
 assert int(first_expiry.timestamp())==int(repeat_expiry.timestamp()) and repeat_expiry<=first_expiry,(first,repeated)

def require_status(label,result,status):
 save(label,result);assert result.get('statusCode')==status,(label,result);return result.get('decoded')
base='/tpf/payloads/csv-input-upload/upload/'
owner={'x-tenant-id':'pilot-tenant','x-scope-id':'pilot-trial-1'}
started=False
try:
 assert not run(COMPOSE+['ps','--quiet']).strip(),'Existing proof container; refuse takeover'
 started=True
 run(['bash',str(ROOT/'self-host/container/bootstrap-finite-localstack.sh')],timeout=120)
 for bucket in ('csv-payments-input','csv-payments-output'):aws('s3api','create-bucket','--bucket',bucket)
 start('setup')
 # Initialize actual packaged runtime; no admin endpoint is exposed over HTTP, even during bootstrap.
 if args.execute_workers:
  from worker_proof import WorkerProof
  workers=WorkerProof(ROOT,EVIDENCE,JAVA,ENV,run,aws,save,props)
  stop();start('worker-bound-ingress')
 require_status('pilot-disabled',call('/pilot/uploads',{}),503)
 require_status('disabled-admin-blocked',call('/tpf/admin/tenants/pilot-tenant/pipelines/x/releases/register',{}),403)
 if not args.uploads_only:
  descriptor=EVIDENCE/'release.json'
  run(['python3',str(ROOT/'self-host/container/demo-client.py'),'create-release','--artifact-path',str(ROOT/'orchestrator-svc/target/orchestrator-svc-26.9.4-SNAPSHOT.jar'),'--pipeline-id',contract['pipelineId'],'--release-version',release,'--output',str(descriptor)])
  bootstrap=save('native-release-bootstrap',command({'bootstrapRelease':{'tenant':'pilot-tenant','pipeline':contract['pipelineId'],'descriptor':str(descriptor),'release':release}}))
  assert bootstrap['registeredStatus']==200 and bootstrap['activatedStatus']==200,bootstrap
 stop();props['csv.pilot.enabled']='true';start('before-restart')
 data={s:('id,recipient,amount,currency\n'+('11111111-1111-4111-8111-111111111111' if s=='A' else '22222222-2222-4222-8222-222222222222')+',Pilot '+s+',1.00,EUR\n').encode() for s in ('A','B')}
 intent={s:{'contentType':'text/csv','bytes':len(b),'sha256':hashlib.sha256(b).hexdigest(),'idempotencyKey':s} for s,b in data.items()}
 denied=call('/pilot/uploads',intent['A'],'anonymous',{'x-user-id':'invited-user-id','x-tenant-id':'pilot-tenant'});save('anonymous-forged-headers',denied);assert denied['statusCode'] in (401,403),denied
 require_status('foreign-principal',call('/pilot/uploads',intent['A'],'foreign'),403)
 require_status('third-slot',call('/pilot/uploads',{**intent['A'],'idempotencyKey':'C'}),403)
 require_status('oversized',call('/pilot/uploads',{**intent['A'],'bytes':1048577}),413)
 require_status('direct-native-initiate-blocked',call(base+'initiate',intent['A'],headers=owner),403)
 assert aws('s3api','list-multipart-uploads','--bucket','csv-payments-input').get('Uploads',[])==[]
 references={};names={};native={};reservations={}
 for slot in ('A','B'):
  reservation=require_status('reservation-'+slot,call('/pilot/uploads',intent[slot]),200)
  duplicate=require_status('reservation-repeat-'+slot,call('/pilot/uploads',intent[slot]),200);same_reservation(reservation,duplicate);reservations[slot]=reservation
  token=reservation['token']
  require_status('wrong-tenant-'+slot,call(base+'part',{'token':token,'partNumber':1},headers={**owner,'x-tenant-id':'other'}),403)
  part=require_status('part-'+slot,call(base+'part',{'token':token,'partNumber':1},headers=owner),200)
  save('part-capability-shape-'+slot,{'keys':list(part)})
  repeated_part=require_status('part-repeat-'+slot,call(base+'part',{'token':token,'partNumber':1},headers=owner),200)
  first_url=urllib.parse.urlparse(part['url']);repeat_url=urllib.parse.urlparse(repeated_part['url'])
  assert first_url.path==repeat_url.path and urllib.parse.parse_qs(first_url.query)['uploadId']==urllib.parse.parse_qs(repeat_url.query)['uploadId']
  assert urllib.parse.parse_qs(repeat_url.query)['partNumber']==['1']
  url=part.get('url') or part.get('uri')
  if args.probe_part_limit and slot=='A':
   probe=urllib.request.Request(url,data=b'x'*(2*1048576),method='PUT',headers={**part.get('headers',{}),'Content-Length':str(2*1048576)})
   try:
    with urllib.request.urlopen(probe,timeout=30) as response:probe_status=response.status
   except urllib.error.HTTPError as response:probe_status=response.code
   parsed=urllib.parse.urlparse(url);query=urllib.parse.parse_qs(parsed.query);object_path=parsed.path.lstrip('/').split('/',1)
   retained=aws('s3api','list-parts','--bucket',object_path[0],'--key',object_path[1],'--upload-id',query['uploadId'][0])
   save('temporary-part-byte-bound',{'declaredBytes':len(data[slot]),'configuredMaximumBytes':1048576,'probeBytes':2*1048576,'providerStatus':probe_status,'storedPartBytes':[p['Size'] for p in retained.get('Parts',[])],'signedHeaders':query.get('X-Amz-SignedHeaders',[]),'signatureValidation':'Enabled in the pinned local S3 fixture','scope':'Local S3 provider observation only; not live AWS signature enforcement. Correct declared part is uploaded immediately afterward.'})
   if 'content-length' in query.get('X-Amz-SignedHeaders',[''])[0]:
    assert probe_status==403 and not retained.get('Parts',[]),'Reserved-length signature was not enforced by the local S3 fixture'
  request=urllib.request.Request(url,data=data[slot],method='PUT',headers=part.get('headers',{}))
  with urllib.request.urlopen(request,timeout=30) as response:assert response.status==200
  reference=require_status('complete-'+slot,call(base+'complete',{'token':token},headers=owner),200)['contentRef'];references[slot]=reference
  # A duplicate completion must return the same native reference.
  completed_again=require_status('complete-repeat-'+slot,call(base+'complete',{'token':token},headers=owner),200)['contentRef'];assert completed_again==reference
  if not args.uploads_only:
   if slot=='A':
    prior_calls=len(provider_calls)
    anonymous=call('/pilot/invocations',{'contentRef':reference},'anonymous');save('invoke-anonymous',anonymous);assert anonymous['statusCode'] in (401,403)
    require_status('invoke-foreign-principal',call('/pilot/invocations',{'contentRef':reference},'foreign'),403)
    for label,mutate in [('checksum',lambda ref:ref.update(checksum='0'*64)),('tenant',lambda ref:ref['metadata'].update({'tpf.payload.owner.tenant':'other'})),('bucket',lambda ref:ref.update(container='another-bucket'))]:
     forged=json.loads(json.dumps(reference));mutate(forged)
     require_status('invoke-forged-'+label,call('/pilot/invocations',{'contentRef':forged}),403)
    assert len(provider_calls)==prior_calls
    assert aws('dynamodb','scan','--table-name','tpf_execution','--consistent-read')['Count']==0
   receipt=require_status('invoke-'+slot,call('/pilot/invocations',{'contentRef':reference}),200);assert receipt['statusCode']==202;names[slot]=receipt['durableExecutionName']
   accepted=command({'nativeSubmit':provider[names[slot]]});save('native-submit-'+slot,accepted);native[slot]=accepted
   duplicate_receipt=require_status('invoke-repeat-'+slot,call('/pilot/invocations',{'contentRef':reference}),200);assert duplicate_receipt==receipt
 require_status('changed-intent',call('/pilot/uploads',{**intent['A'],'sha256':intent['B']['sha256']}),403)
 for route in ('/tpf/admin/tenants/pilot-tenant/pipelines/x/releases','/tpf/executions'):
  assert call(route,{})['statusCode'] in (403,404)
 before=save('before-restart-executions',aws('dynamodb','scan','--table-name','tpf_execution','--consistent-read'))
 assert before['Count']==(0 if args.uploads_only else 2),before
 stop();start('after-restart')
 for slot in ('A','B'):
  same_reservation(reservations[slot],require_status('reservation-after-restart-'+slot,call('/pilot/uploads',intent[slot]),200))
  if not args.uploads_only:
   receipt=require_status('invoke-after-restart-'+slot,call('/pilot/invocations',{'contentRef':references[slot]}),200);assert receipt['durableExecutionName']==names[slot]
   replay=command({'nativeSubmit':provider[names[slot]]});save('native-replay-'+slot,replay)
   assert replay['checkpoint']==native[slot]['checkpoint'],(native[slot],replay)
  ref=references[slot];read=aws('s3api','head-object','--bucket',ref['container'],'--key',ref['key']);assert read['ContentLength']==len(data[slot])
  restored=run(COMPOSE+['exec','-T','localstack','awslocal','s3','cp','s3://'+ref['container']+'/'+ref['key'],'-']).encode();assert restored==data[slot]
  save('restored-object-'+slot,{'bytes':len(restored),'sha256':hashlib.sha256(restored).hexdigest(),'matchesOriginalBytes':True})
 after=save('after-restart-executions',aws('dynamodb','scan','--table-name','tpf_execution','--consistent-read'))
 assert sorted(before['Items'],key=lambda x:json.dumps(x,sort_keys=True))==sorted(after['Items'],key=lambda x:json.dumps(x,sort_keys=True))
 assert len(provider)==len(set(names.values()))==(0 if args.uploads_only else 2)
 if workers:
  try:workers.finish(command)
  except Exception:
   try:save('business-executions',aws('dynamodb','scan','--table-name','tpf_execution','--consistent-read'))
   except Exception as diagnostic:print('Business failure diagnostic could not be collected: '+str(diagnostic))
   raise
 save('provider-outcomes',provider_calls)
 save('result',{'status':'PASSED_UPLOADS_ONLY' if args.uploads_only else 'PASSED','localNativeDurableAdmissionProven':not args.uploads_only,'localWorkerBusinessResultProven':args.execute_workers,'liveAwsDurableInvocationProven':False,'applicationBase':run(['git','rev-parse','HEAD']).strip(),'packageSha256':hashlib.sha256(ZIP.read_bytes()).hexdigest(),'nativeHostSha256':hashlib.sha256(HOST.read_bytes()).hexdigest(),'hostArtifactSource':'explicit pinned artifact; provenance in qualification receipt' if args.host_jar else 'normal public snapshot','nativeProviderNames':names,'actualNativeExecutionRecords':0 if args.uploads_only else 2,'restartRecordsUnchanged':True,'scope':('Uploads-only: packaged HTTP IAM adapter with synthetic authorizer events and native local S3; no native Durable ingress/action invoked.' if args.uploads_only else 'Packaged HTTP IAM adapter, published native ingress/starter and SUBMIT action with local Dynamo/S3; AWS identity and Lambda transport are fixtures. '+('Worker business completion and S3 readback are proved locally. ' if args.execute_workers else 'Business completion remains unproved. ')+'No live SigV4, Durable history/replay or Cloud ACTIVE.'),'cloudResourcesCreated':False})
 print('PASS: uploads only; Durable invocation was not exercised' if args.uploads_only else 'PASS: authenticated native upload A/B, retries, native Durable ingress/action admission and hard process restart')
except Exception as failure:
 save('result',{'status':'FAILED','localWorkerBusinessResultProven':False,'requestedWorkerExecution':args.execute_workers,'failureType':type(failure).__name__,'failure':str(failure),'applicationBase':run(['git','rev-parse','HEAD']).strip(),'packageSha256':hashlib.sha256(ZIP.read_bytes()).hexdigest(),'nativeHostSha256':hashlib.sha256(HOST.read_bytes()).hexdigest(),'cloudResourcesCreated':False})
 raise
finally:
 original_failure=sys.exc_info()[0] is not None
 cleanup_actions=[('ingress stop',stop),('Lambda fixture shutdown',server.shutdown),('Lambda fixture close',server.server_close)]
 if workers:cleanup_actions.append(('worker fixture close',workers.close))
 cleanup_actions.extend((f'ingress log {index} close',log.close) for index,log in enumerate(logs))
 if started:cleanup_actions.append(('local fixture removal',lambda:run(COMPOSE+['down','--volumes','--remove-orphans'])))
 cleanup_errors=cleanup_all(cleanup_actions)
 if cleanup_errors:
  print('Fixture cleanup failures: '+'; '.join(cleanup_errors))
  try:
   save('cleanup-failures',cleanup_errors)
   if not original_failure:
    result=json.loads((EVIDENCE/'result.json').read_text());result.update(status='FAILED_CLEANUP',cleanupFailures=cleanup_errors);save('result',result)
  except Exception as diagnostic:print('Cleanup diagnostic could not be retained: '+str(diagnostic))
  if not original_failure:raise RuntimeError('Fixture cleanup failed; see cleanup-failures.json')
