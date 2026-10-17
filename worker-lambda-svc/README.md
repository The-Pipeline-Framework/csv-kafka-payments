# CSV payment workers for Lambda HTTP

This standalone package hosts the existing grouped runtime and compiler-generated REST resources. It adds no workflow, SQS consumer or infrastructure implementation. The first burn uses S3 input/results and native semantic state, without SQL history. Ordinary application topology and TLS validation remain intact.

Build with JDK 21 and normal published TPF dependencies:

```sh
export PIPELINE_CONFIG="$PWD/config/pipeline.pilot.yaml" PIPELINE_TRANSPORT=REST
./build-pipeline-runtime.sh \
  -pl common,payments-processing-svc,pipeline-runtime-svc,orchestrator-svc \
  -DskipTests -DskipITs=true -Dtpf.build.platform=FUNCTION \
  -Dtpf.build.function.http-bridge=true -Dtpf.build.lambda.scope=compile \
  -Dquarkus.container-image.build=false \
  -Dtpf.await.kafka.reactive-messaging.enabled=false \
  -Dcsv-payments.payment-provider.enabled=false \
  -Dcsv-payments.payment-provider-sqs.enabled=true
for module in ingress-lambda-svc worker-lambda-svc provider-lambda-svc; do
  ./mvnw -B -ntp -f "$module/pom.xml" clean verify \
    -Dquarkus.container-image.build=false -Dmaven.repo.local="$PWD/.m2/repository"
done
python3 self-host/pilot/run-local-proof.py --execute-workers --probe-part-limit
```

Cloud must supply the actual IAM account and caller ARN in `csv.worker.account-id` and `csv.worker.principal-arn`. They are deployment inputs, never caller headers. Bind the exact identity AWS delivers for the invoking role/session; do not guess a role ARN or broaden matching to an arbitrary prefix. Missing bindings fail closed. Only authenticated POST operation routes are exposed. Cloud must also restrict the Function URL policy to the authorized caller and use authenticated transport; this package does not implement a REST client SigV4 signer. Search's public `NONE` Function URL policy is not suitable for this pilot.

After route matching and the existing IAM authorization, the worker lazily activates its declared `csv-input-s3` and `csv-output-s3` bindings through the published `ConnectorBindingRegistry` and its native injected runtime context. Activation uses the Cloud-owned connection/secret configuration and fails closed when unavailable. Native registry activation and shutdown retain provider lifetime ownership; no object-ingest poller is started.

Lambda HTTP terminates TLS before delivering the event, so only this package excludes the ordinary socket host's certificate validator. It retains native Lambda IAM identity checks; no application certificate or authentication bypass is added to the ordinary runtime.

The local proof uses actual packaged HTTP ingress, workers, existing payment-provider Lambda, published native work/Await handlers, Dynamo semantic stores and S3 connector publication. Only AWS identity delivery, Lambda transport/provider history and SQS event mapping/acknowledgement are fixtures. The native handlers are assembled into the existing local control-plane fixture; this is not configured AWS_DURABLE host startup, deployed IAM/SigV4, native provider-history replay, live consumer restoration, Cloud ACTIVE or full conformance. Native deployment preflight is unchanged and is not attested by this proof.

A complete CSV result proof is reported only when both native executions are SUCCEEDED and their actual S3 CSV rows are read and verified. Admission or an HTTP success alone does not establish that result. The Cloud-owned graph, immutable function/release pins, real authenticated worker binding, authorized account/test lane and workload/cost envelope remain required before live deployment.

The proof entrypoint freezes the signed 15 October native host bytes by timestamp and SHA-256. That dated snapshot is a qualification input, not a permanent artifact-retention guarantee. A missing snapshot must fail the proof; do not silently substitute a new host or treat a CI cache as its source of record. Long-lived pilot delivery still needs retained immutable framework/application artifacts or a compatible released baseline. This change does not publish a release, create a mirror or change the application's BOM policy.

Admission and hard-restart checks require exactly the original two root executions. Completed paged Await work also creates one native item continuation per one-row input. The completion proof requires those exact two roots plus exactly two linked native children, all SUCCEEDED with tenant/release/contract scope preserved, before reading both S3 CSV results. Extra roots, missing children and changed release identity fail the proof.
