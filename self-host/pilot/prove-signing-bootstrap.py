#!/usr/bin/env python3
"""Cold-start actual CSV Lambda packages, using only explicit local external fixtures."""
import argparse,hashlib,http.server,io,json,os,pathlib,subprocess,tempfile,threading,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[2]
ARN='arn:aws:ssm:us-east-2:123456789012:parameter/tpf/durable/cold-fixture/resume-token/g-'+'a'*32
KEY='synthetic retained signing key only for offline cold bootstrap'
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--package',type=pathlib.Path,default=ROOT/'ingress-lambda-svc/target/function.zip')
parser.add_argument('--role',choices=('ingress','worker','provider'),default='ingress')
parser.add_argument('--output',type=pathlib.Path,default=ROOT/'target/ingress-signing-cold-proof')
args=parser.parse_args()
package=args.package.resolve()
OUT=args.output;OUT.mkdir(parents=True,exist_ok=True)
mode={'name':'valid'};reads=[]
class Ssm(http.server.BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_POST(self):
  request=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
  if request!={'Name':ARN+':1','WithDecryption':True}:raise ValueError('Unexpected local SSM request')
  reads.append(request)
  value={'ARN':ARN,'Name':ARN.split(':parameter',1)[1],'Version':1,'Type':'SecureString','Value':KEY}
  if mode['name']=='wrong-version':value['Version']=2
  if mode['name']=='wrong-identity':value['ARN']=ARN+'other'
  if mode['name']=='wrong-name':value['Name']+='/other'
  if mode['name']=='wrong-type':value['Type']='String'
  if mode['name']=='oversize':value['Value']='é'*2049
  body=json.dumps({'Parameter':value}).encode();self.send_response(200);self.send_header('Content-Type','application/x-amz-json-1.1');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Ssm);threading.Thread(target=server.serve_forever,daemon=True).start()
try:
 with tempfile.TemporaryDirectory(prefix='csv-signing-cold-') as directory:
  root=pathlib.Path(directory)
  with zipfile.ZipFile(package) as archive:
   if args.role!='provider':
    service='META-INF/services/io.smallrye.config.ConfigSourceFactory'
    registrations=[]
    if service in archive.namelist():registrations.append(archive.read(service))
    for name in archive.namelist():
     if name.startswith('lib/') and name.endswith('.jar'):
      with zipfile.ZipFile(io.BytesIO(archive.read(name))) as dependency:
       if service in dependency.namelist():registrations.append(dependency.read(service))
    if not any(b'org.pipelineframework.csv.pilot.PilotSigningConfigSourceFactory' in value for value in registrations):raise ValueError('Package lacks shared signing bootstrap provider')
   archive.extractall(root)
  source=root/'SigningCold.java';source.write_text('''import java.io.*;import java.lang.reflect.Proxy;import com.amazonaws.services.lambda.runtime.Context;import io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler;import org.eclipse.microprofile.config.ConfigProvider;
public class SigningCold {public static void main(String[] args)throws Exception {var context=(Context)Proxy.newProxyInstance(Context.class.getClassLoader(),new Class[]{Context.class},(p,m,a)->{if(m.getReturnType()==String.class)return "offline-fixture";if(m.getReturnType()==int.class)return 60000;return null;});var handler=new QuarkusStreamHandler();boolean provider="provider".equals(System.getenv("FIXTURE_PACKAGE_ROLE"));String event=provider?"{\\"Records\\":[]}":"{\\"version\\":\\"2.0\\",\\"rawPath\\":\\"/not-a-route\\",\\"requestContext\\":{\\"http\\":{\\"method\\":\\"GET\\",\\"path\\":\\"/not-a-route\\"}}}";var output=new ByteArrayOutputStream();handler.handleRequest(new ByteArrayInputStream(event.getBytes()),output,context);if(provider){var failures=new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.toByteArray()).path("batchItemFailures");if(!failures.isArray()||!failures.isEmpty())throw new AssertionError("Empty provider batch did not acknowledge");System.out.println("COLD_PROVIDER_EMPTY_BATCH");}else{var value=ConfigProvider.getConfig().getOptionalValue("pipeline.orchestrator.resume-token-secret",String.class);if(!value.orElse("").equals(System.getenv("FIXTURE_EXPECTED_KEY")))throw new AssertionError("Cold signing key differs");System.out.println("COLD_KEY_MATCH");}System.exit(0);}}
''')
  cp=str(root)+':'+str(root/'lib/*');java=pathlib.Path(os.environ['JAVA_HOME'])/'bin'
  subprocess.run([str(java/'javac'),'-cp',cp,str(source)],check=True,capture_output=True)
  # Never replace the package's config with checkout YAML or suppress its ingest startup.
  env={k:os.environ[k] for k in ('PATH','HOME','LANG','TMPDIR') if k in os.environ}
  env.update(AWS_ACCESS_KEY_ID='offline-fixture',AWS_SECRET_ACCESS_KEY='offline-fixture',AWS_REGION='us-east-2',AWS_EC2_METADATA_DISABLED='true',AWS_ENDPOINT_URL_SSM='http://127.0.0.1:'+str(server.server_port),CSV_PILOT_RESUME_TOKEN_PARAMETER_ARN=ARN,CSV_PILOT_RESUME_TOKEN_PARAMETER_VERSION='1',CSV_PILOT_SIGNING_LIFECYCLE_VALIDATION='validated',FIXTURE_EXPECTED_KEY=KEY)
  env['FIXTURE_PACKAGE_ROLE']=args.role
  if args.role=='provider':
   env={k:v for k,v in env.items() if not k.startswith('CSV_PILOT_')}
   env['CSV_PAYMENTS_PAYMENT_PROVIDER_SQS_RESPONSE_QUEUE_URL']='http://127.0.0.1:1/123456789012/offline-response'
  env.update(AWS_EXECUTION_ENV='AWS_Lambda_java21',AWS_LAMBDA_FUNCTION_NAME='offline-cold-bootstrap',AWS_LAMBDA_FUNCTION_VERSION='offline')
  results=[]
  cases=['provider-empty'] if args.role=='provider' else ['valid','wrong-version','wrong-identity','wrong-name','wrong-type','oversize','mixed','mixed-empty','missing-version','blank-version','blank-arn','blank-validation','local-plaintext']
  for case in cases:
   mode['name']=case;case_env=dict(env);before=len(reads)
   if case in ['mixed','mixed-empty']:case_env['PIPELINE_ORCHESTRATOR_RESUME_TOKEN_SECRET']='forbidden mixed plaintext' if case=='mixed' else ''
   for label,key in [('blank-version','CSV_PILOT_RESUME_TOKEN_PARAMETER_VERSION'),('blank-arn','CSV_PILOT_RESUME_TOKEN_PARAMETER_ARN'),('blank-validation','CSV_PILOT_SIGNING_LIFECYCLE_VALIDATION')]:
    if case==label:case_env[key]=''
   if case=='missing-version':case_env.pop('CSV_PILOT_RESUME_TOKEN_PARAMETER_VERSION')
   if case=='local-plaintext':
    case_env={k:v for k,v in case_env.items() if not k.startswith('CSV_PILOT_')};case_env['PIPELINE_ORCHESTRATOR_RESUME_TOKEN_SECRET']=KEY
   process=subprocess.run([str(java/'java'),'-cp',cp,'SigningCold'],env=case_env,cwd=ROOT,text=True,capture_output=True,timeout=45)
   log=process.stdout+process.stderr;(OUT/(case+'.log')).write_text(log)
   expected=case in ['valid','local-plaintext','provider-empty'];marker='COLD_PROVIDER_EMPTY_BATCH' if case=='provider-empty' else 'COLD_KEY_MATCH';passed=(process.returncode==0 and marker in log and 'started in' in log) if expected else process.returncode!=0
   response_cases=['wrong-version','wrong-identity','wrong-name','wrong-type','oversize']
   expected_reads=1 if case in ['valid']+response_cases else 0
   passed=passed and len(reads)-before==expected_reads
   if case in response_cases:passed=passed and 'CSV ingress signing response has invalid identity, version, type or size' in log
   if case in ['mixed','mixed-empty']:passed=passed and 'Mixed CSV ingress signing sources are forbidden' in log
   if case in ['missing-version','blank-version','blank-arn','blank-validation']:passed=passed and 'CSV ingress requires an immutable validated resume parameter at version 1' in log
   if KEY in log:raise ValueError('Cold bootstrap exposed signing fixture material')
   results.append({'case':case,'passed':passed,'exit':process.returncode,'ssmReads':len(reads)-before,'logSha256':hashlib.sha256(log.encode()).hexdigest()})
   if not passed:raise AssertionError('Cold case failed: '+case+'; inspect retained nonsecret log')
  receipt={'status':'OFFLINE_REAL_ZIP_COLD_BOOTSTRAP_PASS','awsActions':False,'externalBoundary':'Local SSM HTTP fixture, not AWS IAM/tier/lifecycle evidence','packageSha256':hashlib.sha256(package.read_bytes()).hexdigest(),'role':args.role,'packagedConfigurationOnly':True,'proofScriptSha256':hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),'results':results}
  (OUT/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))
finally:server.shutdown()
