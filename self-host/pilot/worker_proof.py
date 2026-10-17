"""External Lambda/SQS delivery fixtures. All workflow decisions remain in published TPF."""
import base64, csv, decimal, hashlib, http.server, io, json, pathlib, queue, shutil, subprocess, threading, time, zipfile

def cleanup_all(actions):
    """Attempt every fixture cleanup, returning failures without replacing the triggering error."""
    errors=[]
    for label,action in actions:
        try:action()
        except Exception as failure:errors.append(f'{label}: {type(failure).__name__}: {failure}')
    return errors

def completed_execution_lineage(admitted, completed):
    """Require the two original invocations and exactly one native Await item child each."""
    roots = {item['execution_id']['S']: item for item in admitted['Items']}
    records = {item['execution_id']['S']: item for item in completed['Items']}
    assert len(roots) == 2 and len(records) == len(completed['Items']) == 4, 'Unexpected native admission/child count'
    assert set(roots).issubset(records), 'An admitted root execution is missing'
    assert all(item['status']['S'] == 'SUCCEEDED' for item in records.values()), 'Native execution did not succeed'
    children = {identity: item for identity, item in records.items() if identity not in roots}
    lineage = []
    for identity, original in roots.items():
        root = records[identity]
        for field in ('execution_key', 'tenant_id', 'pipeline_id', 'release_version', 'contract_version', 'initial_input_payload_digest'):
            assert root[field] == original[field], (identity, 'admitted identity changed', field)
        # Published runtime61 ItemContinuationKey.executionKey defines this child namespace.
        prefix = original['execution_key']['S'] + ':await-item:'
        linked = [(child_id, child) for child_id, child in children.items()
                  if child['execution_key']['S'].startswith(prefix) and child['execution_key']['S'].endswith(':0')]
        assert len(linked) == 1, (identity, 'Expected exactly one native Await item child')
        child_id, child = linked[0]
        for field in ('tenant_id', 'pipeline_id', 'release_version', 'contract_version'):
            assert child[field] == root[field], (child_id, 'child scope changed', field)
        assert root['page_completion_consumed_records']['N'] == '1' and root['page_completion_exhausted']['BOOL'], root
        lineage.append({'admittedExecutionId': identity, 'awaitChildExecutionId': child_id, 'status': 'SUCCEEDED'})
    assert len({item['awaitChildExecutionId'] for item in lineage}) == 2, 'Await child reused between roots'
    return lineage

class WorkerProof:
    def __init__(self, root, evidence, java, env, run, aws, save, coordinator_props):
        self.root,self.evidence,self.java,self.env,self.run,self.aws,self.save=root,evidence,java,env,run,aws,save
        self.processes=[];self.logs=[];self.readers=[];self.calls=[];self.lock=threading.Lock()
        self.server=None;self.server_thread=None
        try:self.start(coordinator_props)
        except BaseException:
            try:self.close()
            except Exception as cleanup:print('Partial worker fixture cleanup failed: '+str(cleanup))
            raise

    def start(self,coordinator_props):
        root=self.root
        self.worker=self.launch('worker',root/'worker-lambda-svc/target/function.zip',{
            'csv.worker.account-id':'000000000000','csv.worker.principal-arn':'arn:aws:iam::000000000000:role/local-worker-fixture',
            'quarkus.http.host':'127.0.0.1','quarkus.http.port':'8201','quarkus.http.ssl-port':'0',
            'pipeline.orchestrator.sqs.region':'us-east-1','pipeline.orchestrator.sqs.endpoint-override':'http://127.0.0.1:4597',
            'pipeline.orchestrator.resume-token-secret':'local-pilot-resume-key-not-a-cloud-key',
            **{key:coordinator_props[key] for key in ('pipeline.orchestrator.pipeline-id','pipeline.orchestrator.release-version') if key in coordinator_props}})
        self.provider=self.launch('provider',root/'provider-lambda-svc/target/function.zip',{
            'quarkus.sqs.endpoint-override':'http://127.0.0.1:4597','quarkus.sqs.aws.region':'us-east-1',
            'quarkus.sqs.aws.credentials.type':'static','quarkus.sqs.aws.credentials.static-provider.access-key-id':'test',
            'quarkus.sqs.aws.credentials.static-provider.secret-access-key':'test',
            'csv-payments.payment-provider.provider-reject-probability':'0',
            'csv-payments.payment-provider.provider-timeout-probability':'0',
            'csv-payments.payment-provider.sqs.response-queue-url':'http://127.0.0.1:4597/000000000000/csv-payment-await-results'})
        owner=self
        class Bridge(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_POST(self):
                body=self.rfile.read(int(self.headers.get('Content-Length','0'))).decode()
                # A single worker JVM has one stdin/result queue. Keep invocation and
                # response consumption serialized so concurrent HTTP calls cannot swap replies.
                with owner.lock:
                    result=owner.http(self.path,body)
                    owner.calls.append({'path':self.path,'status':result.get('statusCode')})
                payload=(result.get('body') or '').encode()
                if result.get('isBase64Encoded'):payload=base64.b64decode(payload)
                self.send_response(result['statusCode'])
                for k,v in result.get('headers',{}).items():
                    if k.lower() not in ('content-length','transfer-encoding','connection'):self.send_header(k,v)
                self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
        self.server=http.server.ThreadingHTTPServer(('127.0.0.1',8199),Bridge)
        self.server_thread=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.server_thread.start()
        for key in ('process-csv-payments-input','process-approved-payment-status','process-unapproved-payment-status','process-finalize-payment-output'):
            coordinator_props['quarkus.rest-client.'+key+'.url']='http://127.0.0.1:8199'

    def launch(self,name,artifact,props):
        unpack=self.evidence/('unpacked-'+name)
        if unpack.exists():shutil.rmtree(unpack)
        unpack.mkdir()
        with zipfile.ZipFile(artifact) as z:z.extractall(unpack)
        cp=f'{self.evidence}:{unpack}:{unpack}/lib/*'
        self.run([str(self.java/'javac'),'-cp',cp,'-d',str(self.evidence),str(self.root/'self-host/pilot/InvokePackagedFunction.java')])
        log=(self.evidence/(name+'-function.log')).open('w');self.logs.append(log);responses=queue.Queue()
        # The ingress fixture also hosts the ordinary socket coordinator and supplies its
        # certificate paths. Lambda HTTP terminates TLS and must not inherit those paths.
        function_env={k:v for k,v in self.env.items() if not k.startswith(('SERVER_KEYSTORE_','SERVER_TRUSTSTORE_','CLIENT_TRUSTSTORE_'))}
        process=subprocess.Popen([str(self.java/'java'),*[f'-D{k}={v}' for k,v in props.items()],'-cp',cp,'InvokePackagedFunction'],cwd=self.root,env=function_env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
        self.processes.append(process)
        def read():
            for line in process.stdout:
                log.write(line);log.flush()
                if line.startswith('FUNCTION_RESULT='):responses.put(json.loads(line.split('=',1)[1]))
        reader=threading.Thread(target=read,daemon=True);self.readers.append(reader);reader.start()
        return process,responses

    def invoke(self,target,event):
        process,responses=target
        if process.poll() is not None:raise RuntimeError('Packaged function exited; see worker/provider function log')
        process.stdin.write(json.dumps(event)+'\n');process.stdin.flush()
        try:return responses.get(timeout=90)
        except queue.Empty:raise RuntimeError('Packaged function failed; see worker/provider function log')

    def http(self,path,body,identity='worker'):
        context={'accountId':'000000000000','apiId':'fixture','domainName':'localhost','requestId':'worker-fixture','routeKey':'$default','stage':'$default','timeEpoch':int(time.time()*1000),'http':{'method':'POST','path':path,'protocol':'HTTP/1.1','sourceIp':'127.0.0.1','userAgent':'worker-proof'}}
        if identity!='anonymous':context['authorizer']={'iam':{'accountId':'000000000000','userArn':'arn:aws:iam::000000000000:role/local-worker-fixture'+('' if identity=='worker' else '-foreign'),'userId':'worker-session'}}
        return self.invoke(self.worker,{'version':'2.0','routeKey':'$default','rawPath':path,'rawQueryString':'','headers':{'host':'localhost','content-type':'application/json','x-forwarded-proto':'https','x-user-id':'worker-session'},'requestContext':context,'body':body,'isBase64Encoded':False})

    def batch(self,queue_name,invoke):
        url='http://127.0.0.1:4597/000000000000/'+queue_name
        messages=self.aws('sqs','receive-message','--queue-url',url,'--max-number-of-messages','10','--visibility-timeout','30').get('Messages',[])
        if not messages:return 0
        event={'Records':[{'messageId':m['MessageId'],'body':m['Body'],'eventSource':'aws:sqs','eventSourceARN':'arn:aws:sqs:us-east-1:000000000000:'+queue_name,'awsRegion':'us-east-1','attributes':{},'messageAttributes':{}} for m in messages]}
        result=invoke(event);failed={x['itemIdentifier'] for x in result.get('batchItemFailures',[])}
        self.save('last-batch-'+queue_name,{'event':event,'response':result})
        assert not failed,(queue_name,result)
        for m in messages:self.aws('sqs','delete-message','--queue-url',url,'--receipt-handle',m['ReceiptHandle'])
        return len(messages)

    def check_access(self):
        for identity in ('anonymous','foreign'):
            denied=self.http('/api/v1/csv-payments-input-file/page','{}',identity)
            self.save('worker-denied-'+identity,denied);assert denied['statusCode'] in (401,403),denied
        privileged=self.http('/tpf/executions','{}');self.save('worker-privileged-denied',privileged);assert privileged['statusCode']==403,privileged
        # An authenticated allowed-namespace request reaches normal resource lookup.
        # Use an absent route so this authentication probe does not initiate application work.
        bound=self.http('/api/v1/__proof_unknown','{}');self.save('worker-bound-resource-lookup',bound);assert bound['statusCode']==404,bound
        traversal=[]
        for path in ('/api/v1/../tpf/executions','/api/v1/%252e%252e/tpf/executions'):
            denied=self.http(path,'{}');traversal.append({'path':path,'response':denied});assert denied['statusCode'] in (400,403),denied
        self.save('worker-traversal-denied',traversal)

    def finish(self,command):
        self.check_access()
        admitted=self.aws('dynamodb','scan','--table-name','tpf_execution','--consistent-read')
        assert len(admitted.get('Items',[])) == 2, 'Expected exactly two admitted roots before delivery'
        delivered={'work':0,'provider':0,'completion':0};deadline=time.monotonic()+180
        executions={'Items':[]};states=[]
        while time.monotonic()<deadline:
            delivered['work']+=self.batch('tpf-work',lambda e:command({'nativeWork':e}))
            delivered['provider']+=self.batch('csv-payment-await-requests',lambda e:self.invoke(self.provider,e))
            delivered['completion']+=self.batch('csv-payment-await-results',lambda e:command({'nativeCompletion':e}))
            executions=self.aws('dynamodb','scan','--table-name','tpf_execution','--consistent-read')
            states=[item.get('status',{}).get('S') for item in executions.get('Items',[])]
            if states and all(s=='SUCCEEDED' for s in states):break
            assert not any(s in ('FAILED','DLQ','REMOTE_OUTCOME_UNKNOWN') for s in states),[
                {key:item.get(key) for key in ('execution_id','status','error_code','error_message')}
                for item in executions.get('Items',[])]
            time.sleep(.2)
        self.save('business-executions',executions)
        assert states and all(state == 'SUCCEEDED' for state in states),f'Business completion deadline (180 seconds) expired; observed {states}'
        self.save('business-native-lineage', completed_execution_lineage(admitted, executions))
        objects=self.aws('s3api','list-objects-v2','--bucket','csv-payments-output').get('Contents',[])
        self.save('business-s3-inventory', objects)
        assert len(objects)==2,objects
        outputs=[]
        for obj in objects:
            text=self.run(['docker','compose','-f',str(self.root/'self-host/container/compose.finite-proof.yaml'),'exec','-T','localstack','awslocal','s3','cp','s3://csv-payments-output/'+obj['Key'],'-'])
            self.save('business-s3-object-'+str(len(outputs)), {'key':obj['Key'],'rawUtf8':text,'sha256':hashlib.sha256(text.encode()).hexdigest()})
            rows=list(csv.DictReader(io.StringIO(text),quotechar="'"));assert len(rows)==1,rows
            outputs.append({'key':obj['Key'],'sha256':hashlib.sha256(text.encode()).hexdigest(),'rows':rows})
        self.save('business-s3-results',outputs)
        assert {o['rows'][0]['RECIPIENT'] for o in outputs}=={'Pilot A','Pilot B'},outputs
        expected={'Pilot A':'11111111-1111-4111-8111-111111111111','Pilot B':'22222222-2222-4222-8222-222222222222'}
        for output in outputs:
            row=output['rows'][0]
            assert row['CSV ID']==expected[row['RECIPIENT']],row
            assert decimal.Decimal(row['AMOUNT'].replace(',',''))==decimal.Decimal('1.00'),row
            assert row['CURRENCY']=='EUR' and row['STATUS']=='1000' and row['MESSAGE']=='Mock response',row
            assert decimal.Decimal(row['FEE'].replace(',',''))==decimal.Decimal('1.01') and row['REFERENCE'],row
        assert delivered['provider']==2 and delivered['completion']==2,delivered
        self.save('business-delivery-fixtures',{'delivered':delivered,'workerCalls':self.calls,'boundary':'SQS event mapping/ack and Lambda HTTP IAM event delivery are fixtures. Published work/Await handlers, native state, generated workers, existing payment provider and S3 output adapter execute real application work. Not configured AWS_DURABLE host startup, live IAM, provider history replay, consumer restoration or Cloud ACTIVE.'})
        print('PASS: generated worker IAM boundary, real payment processing and two completed S3 CSV results')

    def close(self):
        actions=[]
        if self.server:
            if self.server_thread and self.server_thread.is_alive():actions.append(('worker HTTP shutdown',self.server.shutdown))
            actions.append(('worker HTTP close',self.server.server_close))
        for index,process in enumerate(self.processes):
            actions.append((f'function {index} kill',lambda p=process:p.kill() if p.poll() is None else None))
            actions.append((f'function {index} wait',lambda p=process:p.wait(timeout=10)))
        for index,reader in enumerate(self.readers):actions.append((f'function {index} reader join',lambda r=reader:r.join(timeout=10)))
        for index,log in enumerate(self.logs):actions.append((f'function {index} log close',log.close))
        errors=cleanup_all(actions)
        if errors:raise RuntimeError('Worker fixture cleanup failed: '+'; '.join(errors))
