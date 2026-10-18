#!/usr/bin/env python3
"""Genuine TLS/SigV4 validation fixture, never a TPF workflow implementation or live AWS proof."""
import argparse, sys, concurrent.futures, hashlib, hmac, http.server, json, os, pathlib, queue, select, shutil, socket, socketserver, ssl, subprocess, threading, urllib.parse, zipfile
if not __debug__:raise RuntimeError('This evidence proof requires assertions; remove Python -O/PYTHONOPTIMIZE')
ROOT=pathlib.Path(__file__).resolve().parents[2]
HOST='fixture.lambda-url.eu-west-1.on.aws'; ORIGIN='https://'+HOST
KEYS=['process-csv-payments-input','process-approved-payment-status','process-unapproved-payment-status','process-finalize-payment-output']
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--write-fixture-binding',type=pathlib.Path)
parser.add_argument('--expect-unbound',action='store_true')
args=parser.parse_args()
if args.write_fixture_binding:
 value={'schemaVersion':1,'accountId':'000000000000','region':'eu-west-1','organizationId':'external-tls-fixture-org','applicationId':'external-tls-fixture-app','environmentId':'external-tls-fixture-env','ownerId':'external-tls-fixture-owner','stateOwnerId':'external-tls-fixture-state','bootstrapBusinessOwnerReceiptSha256':hashlib.sha256(b'deterministic-external-TLS-fixture-not-Cloud-ownership-evidence').hexdigest(),'operations':{key:{'httpsOrigin':ORIGIN} for key in KEYS}}
 args.write_fixture_binding.parent.mkdir(parents=True,exist_ok=True)
 args.write_fixture_binding.write_text(json.dumps(value,sort_keys=True,indent=2)+'\n')
 print('Wrote external TLS fixture binding; this is not authenticated Cloud ownership evidence.')
 sys.exit(0)
OUT=ROOT/('target/iam-signing-unbound-proof' if args.expect_unbound else 'target/iam-signing-proof'); OUT.mkdir(parents=True,exist_ok=True)
JAVA=pathlib.Path(os.environ['JAVA_HOME'])/'bin'
ACCESS='ASIATPFDETERMINISTIC'; SECRET='deterministic-fixture-secret-not-an-aws-credential'; TOKEN='fixture-session-token+/='
PACKAGE=pathlib.Path(os.environ.get('IAM_PROOF_PACKAGE',str(ROOT/'ingress-lambda-svc/target/function.zip')))
UNPACK=OUT/'unpacked'
if UNPACK.exists():shutil.rmtree(UNPACK)
UNPACK.mkdir()
with zipfile.ZipFile(PACKAGE) as z:
 z.extractall(UNPACK)
 resource='META-INF/microprofile-config.properties'; receipt='META-INF/csv-cloud-transport-evidence.json'
 if args.expect_unbound:
  assert resource not in z.namelist() and receipt not in z.namelist(),'Unbound rebuild retained Cloud resources'
  embedded_evidence=None
 else:
  config_bytes=z.read(resource);embedded_evidence=json.loads(z.read(receipt))
  assert hashlib.sha256(config_bytes).hexdigest()==embedded_evidence['configSha256'],'Embedded configuration digest mismatch'
  embedded={}
  for line in config_bytes.decode().splitlines():
   if line and not line.startswith('#'):
    key,value=line.split('=',1);assert key not in embedded;embedded[key]=value
  assert embedded['csv.iam.region']=='eu-west-1','Proof requires the explicitly disclosed external TLS fixture binding'
  for key in KEYS:
   assert embedded['csv.iam.endpoints.'+key]==ORIGIN
   assert embedded['quarkus.rest-client.'+key+'.url']==ORIGIN
   assert embedded['quarkus.rest-client.'+key+'.providers']=='org.pipelineframework.csv.iam.LambdaIamRequestSigning'
   assert embedded['quarkus.rest-client.'+key+'.follow-redirects']=='false'

CP=f'{OUT}:{UNPACK}:{UNPACK}/lib/*'
def run(args,**kw):return subprocess.run([str(a) for a in args],check=True,text=True,capture_output=True,**kw)
run([JAVA/'javac','-cp',CP,'-d',OUT,ROOT/'self-host/pilot/InvokeSignedClients.java'])
run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',OUT/'key.pem','-out',OUT/'cert.pem','-days','1','-subj','/CN='+HOST,'-addext','subjectAltName=DNS:'+HOST])
INPUT={'filepath':'incoming/unicode-é-☕.csv','csvFolderPath':'incoming','sourceIdentity':'fixture-object'}
PAYMENT={'id':'11111111-1111-1111-1111-111111111111','csvId':'1','recipient':'café ☕','amount':12.34,'currency':'EUR','csvPaymentsInputFilePath':'incoming/input.csv'}
OUTPUT={'csvPaymentsOutputFilename':'result.csv','csvPaymentsInputFilePath':'incoming/input.csv','csvId':'1','recipient':'café ☕','amount':12.34,'currency':'EUR','conversationId':'11111111-1111-1111-1111-111111111111','status':200,'message':'ok','fee':0.25}
STATUS={'reference':'fixture','status':'approved','message':'fixture','fee':0.25,'conversationId':'11111111-1111-1111-1111-111111111111','statusCode':200,'paymentRecordId':'11111111-1111-1111-1111-111111111111','paymentRecord':PAYMENT}
proxy_targets=[]
records=[]; lock=threading.Lock(); mode={'tamper':False,'status':200,'barrier':None,'active':0,'peak':0}
def validate(method,path,headers,body):
 auth=headers.get('Authorization','')
 assert auth.startswith('AWS4-HMAC-SHA256 '),'unsigned request'
 parts=dict(p.split('=',1) for p in auth[len('AWS4-HMAC-SHA256 '):].split(', '))
 credential=parts['Credential'].split('/'); assert credential[0]==ACCESS and credential[2:]==['eu-west-1','lambda','aws4_request']
 assert headers.get('X-Amz-Security-Token')==TOKEN
 signed=parts['SignedHeaders'].split(';')
 for name in headers:
  if name.lower().startswith('x-pipeline-'):assert name.lower() in signed,'context header is not signed'
 canonical_headers=''.join(n+':'+ ' '.join(headers[n].strip().split())+'\n' for n in signed)
 parsed=urllib.parse.urlsplit(path)
 canonical_path=urllib.parse.quote(urllib.parse.quote(parsed.path or '/',safe='/-_.~'),safe='/-_.~')
 query='&'.join(k+'='+v for k,v in sorted((urllib.parse.quote(k,safe='-_.~'),urllib.parse.quote(v,safe='-_.~')) for k,v in urllib.parse.parse_qsl(parsed.query,keep_blank_values=True)))
 payload=hashlib.sha256(body).hexdigest()
 if headers.get('X-Amz-Content-Sha256'):assert headers['X-Amz-Content-Sha256']==payload
 canonical='\n'.join([method,canonical_path,query,canonical_headers,parts['SignedHeaders'],payload])
 scope='/'.join(credential[1:]); string='AWS4-HMAC-SHA256\n'+headers['X-Amz-Date']+'\n'+scope+'\n'+hashlib.sha256(canonical.encode()).hexdigest()
 def mac(key,value):return hmac.new(key,value.encode(),hashlib.sha256).digest()
 key=('AWS4'+SECRET).encode()
 for value in credential[1:]:key=mac(key,value)
 assert hmac.compare_digest(hmac.new(key,string.encode(),hashlib.sha256).hexdigest(),parts['Signature']),'signature/body/header mismatch'
class Boundary(http.server.BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def do_POST(self):
  body=self.rfile.read(int(self.headers.get('Content-Length',0)))
  if self.headers.get('Transfer-Encoding','').lower()=='chunked':
   chunks=[]
   while True:
    size=int(self.rfile.readline().split(b';',1)[0],16)
    if size==0:self.rfile.readline();break
    chunks.append(self.rfile.read(size));assert self.rfile.read(2)==b'\r\n'
   body=b''.join(chunks)
  try:validate('POST',self.path,self.headers,body+(b'tamper' if mode['tamper'] else b''));valid=True;error=None
  except Exception as failure:valid=False;error=str(failure)
  barrier=mode['barrier']
  if barrier is not None:
   with lock:mode['active']+=1;mode['peak']=max(mode['peak'],mode['active'])
   try:barrier.wait(timeout=12)
   except threading.BrokenBarrierError:valid=False;error='fixture did not observe 16 overlapping requests'
   finally:
    with lock:mode['active']-=1
  with lock:records.append({'path':self.path,'body':body.decode(),'valid':valid,'error':error,'transferEncoding':self.headers.get('Transfer-Encoding'),'contentLength':self.headers.get('Content-Length'),'signedPayloadHash':self.headers.get('X-Amz-Content-Sha256'),'context':self.headers.get('X-Pipeline-Version'),'authorizationPresent':bool(self.headers.get('Authorization'))})
  status=mode['status'] if valid else 403
  response=(b'' if '/page' in self.path else b'[]') if 'csv-payments-input-file' in self.path else json.dumps(OUTPUT).encode()
  self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(response)))
  if 300<=status<400:self.send_header('Location','https://foreign.invalid/steal')
  self.end_headers();self.wfile.write(response)
class HttpsBoundaryServer(http.server.ThreadingHTTPServer):
 request_queue_size=128
https=HttpsBoundaryServer(('127.0.0.1',0),Boundary)
context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(OUT/'cert.pem',OUT/'key.pem');https.socket=context.wrap_socket(https.socket,server_side=True)
threading.Thread(target=https.serve_forever,daemon=True).start()
class Tunnel(socketserver.StreamRequestHandler):
 def handle(self):
  request=self.rfile.readline().decode().strip()
  with lock:proxy_targets.append(request)
  while self.rfile.readline() not in (b'\r\n',b'\n',b''):pass
  if request!=f'CONNECT {HOST}:443 HTTP/1.1':self.wfile.write(b'HTTP/1.1 403 Forbidden\r\n\r\n');return
  with socket.create_connection(('127.0.0.1',https.server_port)) as target:
   self.wfile.write(b'HTTP/1.1 200 Connection established\r\n\r\n');self.wfile.flush()
   while True:
    ready,_,_=select.select([self.connection,target],[],[],20)
    if not ready:return
    for source in ready:
     try:data=source.recv(65536)
     except OSError:return
     if not data:return
     try:(target if source is self.connection else self.connection).sendall(data)
     except OSError:return
class ProxyServer(socketserver.ThreadingTCPServer):
 request_queue_size=128
proxy=ProxyServer(('127.0.0.1',0),Tunnel);proxy.daemon_threads=True
threading.Thread(target=proxy.serve_forever,daemon=True).start()
ENV={k:v for k,v in os.environ.items() if not k.startswith(('AWS_','QUARKUS_','CSV_','PIPELINE_','TPF_','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS'))}
ENV.update(AWS_ACCESS_KEY_ID=ACCESS,AWS_SECRET_ACCESS_KEY=SECRET,AWS_SESSION_TOKEN=TOKEN,AWS_REGION='eu-west-1',AWS_EC2_METADATA_DISABLED='true',PIPELINE_ORCHESTRATOR_RESUME_TOKEN_SECRET='fixture-resume-key-not-live',PIPELINE_CONFIG=str(ROOT/'config/pipeline.pilot.yaml'),PIPELINE_OBJECT_INGEST_AUTOSTART='false')
props={'quarkus.http.port':'0','quarkus.http.ssl-port':'0','quarkus.http.insecure-requests':'enabled','quarkus.otel.sdk.disabled':'true','pipeline.health.enabled':'false','pipeline.client.tls-configuration-name':'','pipeline.orchestrator.process-loops-disabled':'true','pipeline.orchestrator.mode':'QUEUE_ASYNC','quarkus.tls.iam-fixture.trust-store.pem.certs':str(OUT/'cert.pem'),'csv-payments.payment-provider.enabled':'false','csv-payments.payment-provider-sqs.enabled':'false','tpf.await.kafka.reactive-messaging.enabled':'false','tpf.await.sqs.poller.enabled':'false','quarkus.tls.pipeline-client.trust-store.jks.path':str(ROOT/'target/dev-certs/orchestrator-svc/client-truststore.jks'),'quarkus.http.ssl.certificate.key-store-file':str(ROOT/'target/dev-certs/orchestrator-svc/server-keystore.jks')}
for key in KEYS:
 props.update({'quarkus.rest-client.'+key+'.proxy-address':f'127.0.0.1:{proxy.server_address[1]}','quarkus.rest-client.'+key+'.tls-configuration-name':'iam-fixture'})
 if args.expect_unbound:props['quarkus.rest-client.'+key+'.url']=ORIGIN
if os.environ.get('IAM_PROOF_UNSIGNED') == 'true':
 for key in KEYS:props['quarkus.rest-client.'+key+'.providers']=''
# Preserve the established packaged proof's runtime activation settings.
import ast
source=ast.parse((ROOT/'self-host/pilot/run-local-proof.py').read_text())
node=next(n for n in source.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='props' for t in n.targets))
baseline=eval(compile(ast.Expression(node.value),'<existing-proof-settings>','eval'),{'ROOT':ROOT,'ENDPOINT':'http://127.0.0.1:4566'})
for k,v in baseline.items():props.setdefault(k,v)
class Client:
 def __init__(self,label,changes=None,envchanges=None):
  settings=props| (changes or {});environment=ENV.copy()
  for k,v in (envchanges or {}).items():
   if v is None:environment.pop(k,None)
   else:environment[k]=v
  self.process=subprocess.Popen([str(JAVA/'java'),*[f'-D{k}={v}' for k,v in settings.items()],'-cp',CP,'InvokeSignedClients'],cwd=ROOT,env=environment,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
  self.queue=queue.Queue();self.log=(OUT/('client-'+label+'.log')).open('w')
  def read():
   for line in self.process.stdout:
    self.log.write(line);self.log.flush()
    if line.startswith('SIGNING_RESULT='):self.queue.put(json.loads(line.split('=',1)[1]))
   if self.process.poll() not in (None,0):self.queue.put({'ok':False,'startupFailure':True})
  threading.Thread(target=read,daemon=True).start()
 def call(self,**command):
  self.process.stdin.write(json.dumps(command)+'\n');self.process.stdin.flush()
  try:return self.queue.get(timeout=45)
  except queue.Empty:raise AssertionError('Packaged client failed to respond; inspect retained log')
 def close(self):
  self.process.stdin.close()
  try:self.process.wait(timeout=15)
  except subprocess.TimeoutExpired:self.process.kill();self.process.wait()
  self.log.close()
results=[]
def check(name,condition,detail=None):
 results.append({'test':name,'pass':bool(condition),'detail':detail})
 (OUT/'result.json').write_text(json.dumps({'status':'RUNNING' if condition else 'FAIL','tests':results},indent=2)+'\n')
 (OUT/'result-wire.json').write_text(json.dumps(records,indent=2,ensure_ascii=False)+'\n')
 (OUT/'result-proxy-targets.json').write_text(json.dumps({'targets':proxy_targets,'foreignRedirectAttempts':sum(target!=f'CONNECT {HOST}:443 HTTP/1.1' for target in proxy_targets)},indent=2)+'\n')
 assert condition,(name,detail)
try:
 if args.expect_unbound:
  client=Client('unbound-generated-client')
  try:
   answer=client.call(input=INPUT)
   check('unbound-rebuild-retains-no-signer',not answer['ok'] and len(records)==1 and not records[0]['authorizationPresent'] and records[0]['error']=='unsigned request',answer)
   (OUT/'result.json').write_text(json.dumps({'status':'PASS','scope':'Unbound ordinary rebuild after a bound build; no embedded resources, no provider override, real generated client reaches external TLS unsigned.','packageSha256':hashlib.sha256(PACKAGE.read_bytes()).hexdigest(),'tests':results},indent=2)+'\n')
   print(json.dumps({'status':'PASS_UNBOUND_STALE_RESOURCE_CONTROL','evidence':str(OUT/'result.json')}))
  finally:client.close()
  sys.exit(0)
 client=Client('cold-generated-clients')
 try:
  for suffix in ['CsvPaymentsInput','ApprovedPaymentStatus','UnapprovedPaymentStatus','FinalizePaymentOutput']:
   before=len(records);answer=client.call(client=suffix,input=INPUT if suffix=='CsvPaymentsInput' else ({'discriminator':'approved','approved':OUTPUT,'unapproved':None} if suffix=='FinalizePaymentOutput' else STATUS))
   check('cold-config-only-'+suffix,answer['ok'] and len(records)==before+1 and records[-1]['valid'],answer)
  answer=client.call(page=True,input={'input':INPUT,'sourceIdentity':'fixture-object','maxRecords':2,'pipelineId':'csv-payments','contractVersion':'1','releaseVersion':'1','catalogFingerprint':'fixture'})
  check('actual-generated-page',answer['ok'] and records[-1]['path'].endswith('/page') and records[-1]['valid'],answer)
  mode['barrier']=threading.Barrier(16);mode['peak']=0
  before=len(records);answer=client.call(input=INPUT|{'filepath':'concurrent-é.csv'},concurrency=16)
  mode['barrier']=None;answer['peakOverlappingRequests']=mode['peak']
  check('concurrent-request-isolation',answer['ok'] and len(records)==before+16 and all(x['valid'] for x in records[before:]) and {x['context'] for x in records[before:]}=={f'fixture-v{i}' for i in range(16)} and len({x['body'] for x in records[before:]})==16 and mode['peak']==16,answer)
  mode['tamper']=True;answer=client.call(input=INPUT);check('body-tamper-rejected',not answer['ok'] and not records[-1]['valid'],answer);mode['tamper']=False
  mode['status']=302;before=len(records);answer=client.call(input=INPUT);check('redirect-rejected-without-follow',not answer['ok'] and len(records)==before+1,answer);mode['status']=200
  mode['status']=403;answer=client.call(input=INPUT);check('provider-403-propagates',not answer['ok'],answer);mode['status']=200
 finally:client.close()
 client=Client('canonical-query',{'quarkus.rest-client.'+KEYS[0]+'.providers':'InvokeSignedClients$AddQuery,org.pipelineframework.csv.iam.LambdaIamRequestSigning'})
 try:
  answer=client.call(input=INPUT);check('canonical-query-duplicate-encoded-values',answer['ok'] and records[-1]['valid'] and '?z=%2B&a=hello%20world&a=%C3%A9&empty=' in records[-1]['path'],answer)
 finally:client.close()
 expected_reasons={'missing-credentials':'Lambda IAM credentials are unavailable','sessionless-credentials':'Lambda IAM credentials are missing or expired','foreign-origin':'Target is outside the bound operation','foreign-region':'Binding is not a Lambda Function URL in the selected region','redirects-enabled':'IAM clients must disable redirects','body-overflow':'IAM request body exceeds limit'}
 full='org.pipelineframework.csv.service.pipeline.ProcessCsvPaymentsInputRestClient'
 redirect_aliases={
  'fqcn':f'quarkus.rest-client."{full}".follow-redirects',
  'simple':'quarkus.rest-client.ProcessCsvPaymentsInputRestClient.follow-redirects',
  'quoted-simple':'quarkus.rest-client."ProcessCsvPaymentsInputRestClient".follow-redirects',
  'quoted-key':f'quarkus.rest-client."{KEYS[0]}".follow-redirects',
  'mp-fqcn':full+'/mp-rest/followRedirects',
  'mp-key':KEYS[0]+'/mp-rest/followRedirects',
  'global':'quarkus.rest-client.follow-redirects'}
 for label,property in redirect_aliases.items():
  client=Client('redirect-'+label,{property:'true'})
  try:
   before=len(records);before_tunnels=len(proxy_targets);mode['status']=302
   answer=client.call(input=INPUT)
   check('redirect-'+label+'-before-send',not answer['ok'] and answer.get('type')=='jakarta.ws.rs.ProcessingException' and 'IAM clients must disable redirects' in answer.get('message','') and len(records)==before and len(proxy_targets)==before_tunnels,answer)
  finally:mode['status']=200;client.close()
 for label,changes,env in [
  ('missing-credentials',{}, {'AWS_ACCESS_KEY_ID':None,'AWS_SECRET_ACCESS_KEY':None,'AWS_SESSION_TOKEN':None}),
  ('sessionless-credentials',{}, {'AWS_SESSION_TOKEN':None}),
  ('foreign-origin',{'csv.iam.endpoints.'+KEYS[0]:'https://foreign.lambda-url.eu-west-1.on.aws'},{}),
  ('foreign-region',{'csv.iam.region':'us-east-1'},{}),
  ('redirects-enabled',{'quarkus.rest-client.'+KEYS[0]+'.follow-redirects':'true'},{}),
  ('body-overflow',{'csv.iam.max-body-bytes':'32'},{}),
 ]:
  client=Client(label,changes,env)
  try:
   before=len(records);answer=client.call(input=INPUT|{'filepath':'incoming/'+'x'*100+'.csv'})
   check(label+'-before-send',not answer['ok'] and len(records)==before and answer.get('type')=='jakarta.ws.rs.ProcessingException' and expected_reasons[label] in answer.get('message',''),answer)
  finally:client.close()
 (OUT/'result-wire.json').write_text(json.dumps(records,indent=2,ensure_ascii=False)+'\n')
 assert all(target==f'CONNECT {HOST}:443 HTTP/1.1' for target in proxy_targets),'Foreign redirect CONNECT attempted'
 (OUT/'result-proxy-targets.json').write_text(json.dumps({'targets':proxy_targets,'foreignRedirectAttempts':0},indent=2)+'\n')
 (OUT/'result.json').write_text(json.dumps({'status':'PASS','package':str(PACKAGE),'packageSha256':hashlib.sha256(PACKAGE.read_bytes()).hexdigest(),'embeddedConfiguration':embedded_evidence,'positiveLaunchOverrides':'External TLS/proxy/runtime fixture settings only; no IAM region/endpoints/client provider/URL/redirect properties.','scope':'Actual packaged generated CDI REST clients; genuine external TLS/CONNECT and independent deterministic SigV4 validator. No live AWS or business semantics simulated.','tests':results},indent=2)+'\n')
 print(json.dumps({'status':'PASS','tests':len(results),'evidence':str(OUT/'result.json')}))
finally:proxy.shutdown();https.shutdown()
