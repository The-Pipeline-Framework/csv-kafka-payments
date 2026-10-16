# Restricted CSV IAM upload and native Durable ingress integration

App Lead implementation contract, updated 16 October 2026. The AWS Durable host is an existing published framework capability. This module adds application admission policy and composes that host; it does not introduce a host, consumer, workflow ledger or infrastructure graph. The deployment operator owns credentials, resource lifecycle and the actual SQS consumer. TPF Cloud's product acceptance and existing-capability handoff remain in its own documentation; this OSS application does not need that checkout to build or run the local proof.

## Native boundaries and narrow policy

Reuse the generated delegated S3 part/complete/abort routes and native OwnedPayloadTransfer. A thin application policy derives tenant/scope from Cloud-owned deployment configuration, checks the native Lambda HTTP IAMPrincipal against the invited account and exact principal ARN, and rejects identity headers as authority. Direct initiation and privileged native HTTP routes are blocked on the pilot surface. AWS permissions must allow invocation only through the authenticated Function URL; direct InvokeFunction must not let an external caller fabricate authorizer metadata.

For the first burn test, the operator assigns exactly two immutable native idempotency keys, A and B, and a fixed admission window no longer than ten minutes. The generated boundary rejects a declared upload above 1 MiB. The existing connector signs the exact reserved part length; the strict local S3 fixture rejects a 2 MiB PUT against an 83-byte reservation without storing a part. Live AWS signature rejection remains a separate obligation. Native S3 reservation CAS owns same-key/intent retry conflicts and concurrency. The window closes before any fifteen-minute native reservation can expire, preventing expiry from creating additional retained objects. Retain the same policy scope/window across cold starts; do not extend it or reuse the scope for another trial. These are upload bounds, not a global CSV-row, business-operation, request or spending cap. A general renewable quota is not implemented or claimed.

Invocation verifies native owned-reference provenance through the S3 provider, maps the canonical ObjectSnapshot with the existing CSV mapper, derives ObjectIdentity's stable key and supplies compiled pipeline/contract plus server-owned release pins. No client path, generation, release or function selection is accepted.

The existing custom Durable host handlers cannot be packaged with Quarkus Lambda HTTP: the first build correctly rejects multiple handlers. The dedicated `ingress-lambda-svc` HTTP artifact therefore consumes only the public coordination-model artifact and invokes the existing AwsDurableIngressHandler at a pinned numeric function version. That unchanged native host calls AwsDurableExecutionStarter to start the pinned coordinator. This is host composition, not a replacement starter or coordinator. A provider start receipt is not semantic admission, business completion, replay, or Cloud ACTIVE.

## Verification obligations

- Missing IAM, a plain forged principal/header, foreign account/ARN, wrong tenant/scope and out-of-window requests fail before writes/invocation.
- Upload A then B while A remains retained. Repeat initiation/part/complete and reject changed intent; reject C and oversized input. Concurrent same-key initiation returns one reservation.
- Restore the process, repeat with the unchanged scope/window, and retain object references/bytes and native provider execution names/pins. Forged references and direct native initiation/admin access fail closed.
- Exercise actual packaged Quarkus Lambda HTTP and unchanged published native ingress. LocalStack and synthetic AWS event/provider transport are explicit external fixtures. They cannot establish real SigV4 enforcement, AWS Durable history/replay or full business completion.

## Baseline and remote inputs

CSV base fd98e616e7898369eead0895dc3a246a6b3e8815; compiler 26.10.1-20261015.205235-18; runtime source 0d9a84655de41992976e38e1bbf806f133c73711. The contracts metadata/legacy-wire correction is merged at 7bd043af309b757a3ac1d3189596e7edf7f36021, public snapshot 26.10.1-20261016.102843-16. The connector part-length correction is merged at dd6c1de1c9e973f5a6c8c341899eb174c29c4925, public snapshot 26.10.1-20261016.112849-14. Application builds use the normal BOM and release-plugin defaults; no framework source installation or candidate override. This prototype is not an admitted or published application Release.

Remote integration still requires Cloud's actual immutable artifacts, ingress/coordinator/action function versions, endpoints, semantic tables/indexes, key versions/CMKs, real SQS consumer identity and bounded pause/restore controls, isolated deployment and authorized evidence lane. Adapt ownership checks to that mechanism without weakening guards. The Search account/region is a preparation target, not deployment/spend authorization. No resource apply is performed here.

## Reproduce without AWS credentials

Use JDK 21, Docker Compose and the normal public dependency repositories. Every Maven command uses this checkout's `.m2/repository`. The CI `Verify FUNCTION upload ingress` job records the producer build and adapter tests; it does not deploy resources or run live AWS acceptance.

```sh
export PIPELINE_CONFIG="$PWD/config/pipeline.pilot.yaml" PIPELINE_TRANSPORT=REST
./build-pipeline-runtime.sh -DskipTests -DskipITs=true \
  -Dtpf.build.platform=FUNCTION -Dtpf.build.lambda.scope=compile \
  -Dquarkus.container-image.build=false \
  -Dtpf.await.kafka.reactive-messaging.enabled=false \
  -Dcsv-payments.payment-provider.enabled=false \
  -Dcsv-payments.payment-provider-sqs.enabled=true
./mvnw -B -ntp -f ingress-lambda-svc/pom.xml clean verify \
  -Dquarkus.container-image.build=false -Dmaven.repo.local="$PWD/.m2/repository"
mkdir -p target
curl --fail --location --output target/pilot-native-host.jar \
  https://central.sonatype.com/repository/maven-snapshots/org/pipelineframework/pipelineframework-aws-durable-coordination-host/26.10.1-SNAPSHOT/pipelineframework-aws-durable-coordination-host-26.10.1-20261015.182717-11.jar
python3 self-host/pilot/run-local-proof.py --probe-part-limit \
  --host-jar target/pilot-native-host.jar \
  --host-sha256 1c6e89433cd7687c301487638cb46ce85d94a5714683b9b865eb349240e31b6b
```

The external host JAR is a verified normal public artifact, outside the production HTTP Lambda package to preserve handler separation. The proof pins its bytes and refuses Python optimization. It owns one local fixture lane on ports 4597, 8197 and 8198, sanitizes AWS/runtime environment variables and uses dummy credentials with loopback endpoints only. It refuses an existing lane and cleans up its own containers/volumes. Local Release bootstrap calls the unchanged native admin service in-process; every admin HTTP route is blocked with pilot admission disabled or enabled. `target/pilot-local-proof/` retains the result, native records, provider fixture outcomes and before/after process logs. IAM event delivery and Lambda provider history are explicit fixtures: this is local native admission/restart evidence, not live authentication, provider replay, business completion or Cloud ACTIVE.
