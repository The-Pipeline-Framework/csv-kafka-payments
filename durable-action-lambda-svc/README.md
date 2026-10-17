# CSV AWS Durable action package

The first burn retains S3 results and native Durable state, without the optional SQL history aspect. The ordinary application retains its SQL path.

This package composes the existing published `aws-durable-action` handler with the
compiler-generated CSV decoder and application metadata. It adds no handler,
coordinator, workflow store, consumer, or infrastructure implementation. It is
separate from the HTTP ingress because Quarkus Lambda HTTP and custom native
handlers cannot share one function artifact.

Build with JDK 21 and the repository-local Maven cache:

```sh
export PIPELINE_CONFIG="$PWD/config/pipeline.pilot.yaml" PIPELINE_TRANSPORT=REST
./build-pipeline-runtime.sh \
  -pl common,payments-processing-svc,pipeline-runtime-svc,orchestrator-svc \
  -DskipTests -DskipITs=true \
  -Dtpf.build.platform=FUNCTION -Dtpf.build.coordination.host=AWS_DURABLE \
  -Dtpf.build.lambda.scope=compile -Dquarkus.container-image.build=false \
  -Dtpf.await.kafka.reactive-messaging.enabled=false \
  -Dcsv-payments.payment-provider.enabled=false \
  -Dcsv-payments.payment-provider-sqs.enabled=true
./mvnw -B -ntp -f durable-action-lambda-svc/pom.xml clean verify \
  -Dquarkus.container-image.build=false -Dmaven.repo.local="$PWD/.m2/repository"
```

The output is `durable-action-lambda-svc/target/function.zip`. Build the HTTP
ingress with the ordinary `NATIVE` coordination selection separately; each
package consumes its own producer's generated classes and metadata.

Canonical values are retained through durable state and the generated client step.
The corrected compiler maps to REST DTOs only at the HTTP boundary. The generated
Durable decoder restores the canonical input; its test checks both the actual
client-step signature and a round trip through the published strict durable codec.
The prior DTO-only seam assertion did not exercise durable persistence and is
superseded by the worker integration regression.

Verification exercises the emitted decoder against the actual generated first
step's input type and the canonical mapper, and Quarkus packages and validates
the CDI composition. These checks do not boot a configured deployed host or run
business work. The earlier attempted boot with an inline local signing key was
correctly rejected by the published host's preflight guard; no validation bypass
or inline signing key is included here.

Cloud must supply the complete validated native configuration, semantic stores,
pinned signing-key versions, function and Release identities, worker endpoints,
and the real consumer before startup/invocation acceptance. Preserve the native
preflight and ownership guards. The existing AWS Durable graph and proof suite
remain authoritative; this ZIP is not an immutable application Release, a
deployed endpoint, or live conformance evidence.
