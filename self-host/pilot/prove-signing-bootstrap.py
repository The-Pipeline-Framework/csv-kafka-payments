#!/usr/bin/env python3
"""Cold-start the real ingress ZIP against an explicitly local SSM HTTP fixture."""
import hashlib,http.server,json,os,pathlib,subprocess,tempfile,threading,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[2]
ARN='arn:aws:ssm:us-east-2:123456789012:parameter/tpf/durable/cold-fixture/resume-token/g-'+'a'*32
KEY='synthetic retained signing key only for offline cold bootstrap'
package=ROOT/'ingress-lambda-svc/target/function.zip'
OUT=ROOT/'target/ingress-signing-cold-proof';OUT.mkdir(parents=True,exist_ok=True)
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
   if 'META-INF/services/io.smallrye.config.ConfigSourceFactory' not in archive.namelist():raise ValueError('Package lacks signing bootstrap provider')
   archive.extractall(root)
  source=root/'SigningCold.java';source.write_text('''import java.io.*;import java.lang.reflect.Proxy;import com.amazonaws.services.lambda.runtime.Context;import io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler;import org.eclipse.microprofile.config.ConfigProvider;
public class SigningCold {public static void main(String[] args)throws Exception {var context=(Context)Proxy.newProxyInstance(Context.class.getClassLoader(),new Class[]{Context.class},(p,m,a)->{if(m.getReturnType()==String.class)return "offline-fixture";if(m.getReturnType()==int.class)return 60000;return null;});var handler=new QuarkusStreamHandler();String event="{\\"version\\":\\"2.0\\",\\"rawPath\\":\\"/not-a-route\\",\\"requestContext\\":{\\"http\\":{\\"method\\":\\"GET\\",\\"path\\":\\"/not-a-route\\"}}}";handler.handleRequest(new ByteArrayInputStream(event.getBytes()),new ByteArrayOutputStream(),context);var value=ConfigProvider.getConfig().getOptionalValue("pipeline.orchestrator.resume-token-secret",String.class);if(!value.orElse("").equals(System.getenv("FIXTURE_EXPECTED_KEY")))throw new AssertionError("Cold signing key differs");System.out.println("COLD_KEY_MATCH");System.exit(0);}}
''')
  cp=str(root)+':'+str(root/'lib/*');java=pathlib.Path(os.environ['JAVA_HOME'])/'bin'
  subprocess.run([str(java/'javac'),'-cp',cp,str(source)],check=True,capture_output=True)
  env={k:v for k,v in os.environ.items() if not k.startswith(('AWS_','CSV_PILOT_')) and k not in ['PIPELINE_ORCHESTRATOR_RESUME_TOKEN_SECRET','TPF_AWS_DURABLE_DEPLOYMENT_VALIDATION']}
  env.update(AWS_ACCESS_KEY_ID='offline-fixture',AWS_SECRET_ACCESS_KEY='offline-fixture',AWS_REGION='us-east-2',AWS_EC2_METADATA_DISABLED='true',AWS_ENDPOINT_URL_SSM='http://127.0.0.1:'+str(server.server_port),PIPELINE_OBJECT_INGEST_AUTOSTART='false',CSV_PILOT_RESUME_TOKEN_PARAMETER_ARN=ARN,CSV_PILOT_RESUME_TOKEN_PARAMETER_VERSION='1',CSV_PILOT_SIGNING_LIFECYCLE_VALIDATION='validated',FIXTURE_EXPECTED_KEY=KEY,PIPELINE_CONFIG=str(ROOT/'config/pipeline.pilot.yaml'))
  results=[]
  for case in ['valid','wrong-version','wrong-identity','wrong-name','wrong-type','oversize','mixed','mixed-empty','missing-version','blank-version','blank-arn','blank-validation','local-plaintext']:
   mode['name']=case;case_env=dict(env);before=len(reads)
   if case in ['mixed','mixed-empty']:case_env['PIPELINE_ORCHESTRATOR_RESUME_TOKEN_SECRET']='forbidden mixed plaintext' if case=='mixed' else ''
   for label,key in [('blank-version','CSV_PILOT_RESUME_TOKEN_PARAMETER_VERSION'),('blank-arn','CSV_PILOT_RESUME_TOKEN_PARAMETER_ARN'),('blank-validation','CSV_PILOT_SIGNING_LIFECYCLE_VALIDATION')]:
    if case==label:case_env[key]=''
   if case=='missing-version':case_env.pop('CSV_PILOT_RESUME_TOKEN_PARAMETER_VERSION')
   if case=='local-plaintext':
    case_env={k:v for k,v in case_env.items() if not k.startswith('CSV_PILOT_')};case_env['PIPELINE_ORCHESTRATOR_RESUME_TOKEN_SECRET']=KEY
   process=subprocess.run([str(java/'java'),'-cp',cp,'SigningCold'],env=case_env,cwd=ROOT,text=True,capture_output=True,timeout=45)
   log=process.stdout+process.stderr;(OUT/(case+'.log')).write_text(log)
   expected=case in ['valid','local-plaintext'];passed=(process.returncode==0 and 'COLD_KEY_MATCH' in log) if expected else process.returncode!=0
   response_cases=['wrong-version','wrong-identity','wrong-name','wrong-type','oversize']
   expected_reads=1 if case in ['valid']+response_cases else 0
   passed=passed and len(reads)-before==expected_reads
   if case in response_cases:passed=passed and 'CSV ingress signing response has invalid identity, version, type or size' in log
   if case in ['mixed','mixed-empty']:passed=passed and 'Mixed CSV ingress signing sources are forbidden' in log
   if case in ['missing-version','blank-version','blank-arn','blank-validation']:passed=passed and 'CSV ingress requires an immutable validated resume parameter at version 1' in log
   if KEY in log:raise ValueError('Cold bootstrap exposed signing fixture material')
   results.append({'case':case,'passed':passed,'exit':process.returncode,'ssmReads':len(reads)-before,'logSha256':hashlib.sha256(log.encode()).hexdigest()})
   if not passed:raise AssertionError('Cold case failed: '+case+'; inspect retained nonsecret log')
  receipt={'status':'OFFLINE_REAL_ZIP_COLD_BOOTSTRAP_PASS','awsActions':False,'externalBoundary':'Local SSM HTTP fixture, not AWS IAM/tier/lifecycle evidence','packageSha256':hashlib.sha256(package.read_bytes()).hexdigest(),'results':results}
  (OUT/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))
finally:server.shutdown()
