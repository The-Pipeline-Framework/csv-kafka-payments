# CSV AWS Durable action package

This package composes the existing published `aws-durable-action` handler with the
compiler-generated CSV decoder and application metadata. It adds no handler,
coordinator, workflow store, consumer, or infrastructure implementation. It is
separate from the HTTP ingress because Quarkus Lambda HTTP and custom native
handlers cannot share one function artifact.

Build with JDK 21 and the repository-local Maven cache:

```sh
export PIPELINE_CONFIG="$PWD/config/pipeline.pilot.yaml" PIPELINE_TRANSPORT=REST
./build-pipeline-runtime.sh -DskipTests -DskipITs=true \
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

For REST orchestration the first generated client step takes a
`CsvPaymentsInputFileDto`. The existing generated Durable decoder correctly
returns that DTO. Its generated REST mapper restores the canonical domain value
at the service boundary. Do not replace this decoder with a domain-only decoder
based on the earlier local admission fixture: that fixture did not execute the
REST worker path.

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
