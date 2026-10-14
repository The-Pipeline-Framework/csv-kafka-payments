# G0 CSV SQS FUNCTION compiler replay (diagnostic, 2026-10-14 UTC)

Source is `csv-kafka-payments` `67cf4ec748ae02fb03d21ddd0d10befd90d06834` with unchanged `config/pipeline.container-sqs.yaml`. Compiler candidate is `pipelineframework-compiler:26.10.1-pr.32.f74471e71563`, PR 32 source `f74471e715636e47847ea0276a7842cfa4da64b2`, attested by `/tmp/tpf-rest-union-candidate-manifest/candidate-manifest.json`. Its JAR SHA-256 is `eb4385abb15a65a22c1eb5dbf5c75f242e0fbd83d4b3b38868573907a097e98a` and POM SHA-256 is `47f67c2981ead2b545bb09bf895fd1b6345ff9b00c290f2c2fde897a8e9bf4d9`.

The archived published BOM `pipelineframework-bom:26.10.1-SNAPSHOT`, timestamp `20261012.224422-15`, has SHA-256 `728f20edd2140c6747f0b4e970e32984c439553c8d390d5306096e7d09a80541`. For this offline diagnostic only, a local synthetic BOM `26.10.1-g0-f74471e` (SHA-256 `cc80c9011eb5162c1164f92c918eccdbe4e83b1183664e554ce74929ad31d7e6`) changes only the BOM's own version and `pipelineframework.compiler.version` to the attested candidate. All 218 original framework-component files (excluding `org/pipelineframework/csv`, which holds locally rebuilt application modules) match the frozen baseline SHA-256 manifest `/tmp/tpf-csv-g0.Vhy5YD/original-framework-hashes.sha256` (SHA-256 `bf5b10f1b2107bb38de46edb1d2b4b93ed21996520bc7857a64ae9dd748f174f`). The unfiltered pre-build inventory, including app-local artifacts, is `/tmp/tpf-csv-g0.Vhy5YD/original-tpf-hashes.sha256`. All other TPF component bytes came from the frozen original cache, not current `main`. Java was Corretto 21.0.9. Both builds used repository-local `.m2`, `-o -nsu`, and `-DskipTests`; `.mvn/maven.config` includes `-U` but offline mode prevented snapshot refresh.

Unchanged-source replay in `/Users/mari/mokapot/tpf-csv-sqs-function`:

```sh
JAVA_HOME=/Users/mari/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home \
PATH=/Users/mari/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home/bin:/usr/bin:/bin:/opt/homebrew/bin \
PIPELINE_CONFIG=/Users/mari/mokapot/tpf-csv-sqs-function/config/pipeline.container-sqs.yaml \
PIPELINE_TRANSPORT=REST \
MAVEN_ARGS='-Dmaven.repo.local=/Users/mari/mokapot/tpf-csv-sqs-function/.m2/repository -o -nsu' \
./build-pipeline-runtime.sh -DskipTests -Dquarkus.container-image.build=false \
  -Dtpf.build.platform=FUNCTION -Dpipelineframework.bom.version=26.10.1-g0-f74471e
```

Exit 1, log `/tmp/tpf-csv-g0.Vhy5YD/compiler-candidate-offline-replay.log` SHA-256 `7097acbdae0c5190e412593d756e0aaeebcf1e0ec0f04466d5d1f931971ee74b`. It passed the prior missing `PaymentStatusDto` and `PaymentOutputBranchDto` failures: both were generated, and `PaymentStatusDto.class` compiled. It next failed at `persistence-svc` because generated FUNCTION persistence handlers import `com.amazonaws.services.lambda.runtime.RequestHandler` and `Context`, absent from that module's classpath. A focused `-X` build of `common,payments-processing-svc` with the same config exited 0; its log `/tmp/tpf-csv-g0.Vhy5YD/compiler-candidate-selected-debug.log` SHA-256 `cc0f311083acb62bc40bf54d31423e5f1131b9d0b8252d3edac2d3ac78a53f22` explicitly shows the candidate JAR in the compiler classpath.

Application dependency fix replay in sibling `/Users/mari/mokapot/tpf-csv-sqs-provider-function` (same source base, uncommitted): parent POM adds Quarkus-BOM-managed `com.amazonaws:aws-lambda-java-core` with `tpf.build.lambda.scope=provided` by default; FUNCTION build sets `compile`. The exact third-party 1.4.0 JAR/POM were copied from local cache after verifying their SHA-1 against Maven Central; SHA-256 JAR `575c24393cc1ad645e83e618956eabd89ffe8cc09efae2cb62bc6589154d68ed`, POM `f3941a3557a358b2b9d7ee7f44347de3f748c3a557add332564222f6632a20bc`. The grouped build excluded the new provider module by the repository's existing default project selection:

```sh
JAVA_HOME=/Users/mari/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home \
PATH=/Users/mari/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home/bin:/usr/bin:/bin:/opt/homebrew/bin \
PIPELINE_CONFIG=/Users/mari/mokapot/tpf-csv-sqs-provider-function/config/pipeline.container-sqs.yaml \
PIPELINE_TRANSPORT=REST \
MAVEN_ARGS='-Dmaven.repo.local=/Users/mari/mokapot/tpf-csv-sqs-provider-function/.m2/repository -o -nsu' \
./build-pipeline-runtime.sh -DskipTests -Dquarkus.container-image.build=false \
  -Dtpf.build.platform=FUNCTION -Dtpf.build.lambda.scope=compile \
  -Dpipelineframework.bom.version=26.10.1-g0-f74471e
```

Exit 1, log `/tmp/tpf-csv-g0.Vhy5YD/compiler-candidate-lambda-core-replay.log` SHA-256 `86904897906a2892ee3b6b0b2cf99c520cce9552b88fc27bc04fa4bfc9554b65`. Persistence Lambda imports compiled. The next failure is generated `pipeline-runtime-svc` REST server: `ProcessCsvPaymentsInputResource` cannot resolve `ProcessCsvPaymentsInputServiceBlockingReactiveBridge`, and `ProcessCsvPaymentsInputFunctionHandler` supplies `resource::process` returning `Uni<List<PaymentRecordDto>>` to a `LocalOneToManyFunctionInvokeAdapter` requiring `Multi<PaymentRecordDto>`. The bridge source and compiled class do exist under `pipeline-runtime-svc/target/generated-sources/pipeline/pipeline-server/` and `target/classes-pipeline/pipeline-server/`. The parent `pom.xml` separately compiles pipeline-server to `classes-pipeline/pipeline-server` at lines 466–478 and rest-server to `classes-pipeline/rest-server` at lines 508–520; this is a generated-role classpath boundary to inspect against the matching runtime/compiler cohort. No source semantic workaround was applied.

This replay is a controlled compiler regression check against an older frozen runtime/connector snapshot, not a cohesive current-main compatibility result or Hosted pilot acceptance. The official `tpf/system-tests` candidate closure remains required. No cloud resources, source TPF builds, commits, or publications occurred.

## Follow-up compiler candidate and provider package (2026-10-15 UTC)

The later attested compiler candidate is `26.10.1-pr.32.db2e04ba2956`, source `db2e04ba2956`, manifest `/tmp/tpf-db2-candidate-manifest/candidate-manifest.json`, JAR SHA-256 `68bbf161900b68d20cb302571fcbedbc86bf6fa261c98a02b96062ca06dfa7ec`, POM SHA-256 `995e282f25cda3418c57cf08eeb0fa710b3d077a31769a6c59df1268fe2582d3`. Its union DTO and unary-streaming delegate corrections were tested against the same frozen older peers using a diagnostic BOM `26.10.1-g0-db2e04b` (SHA-256 `f39a65aab8338061c3ed153e17f00cbae2f26e14d3a8b9b8ed408bf6e7f29bca`), changing only the BOM's own version and compiler property. This BOM and its cache are **local diagnostics, not release coordinates**. The original 218 framework-component hashes still match `/tmp/tpf-csv-g0.Vhy5YD/original-framework-hashes.sha256`.

In sibling worktree `/Users/mari/mokapot/tpf-csv-sqs-provider-function`, the app build also adds the BOM-managed Lambda core dependency and a supported javac `-sourcepath` for the generated pipeline-server bridge referenced by the separately compiled REST server role. With unchanged SQS pipeline YAML, this full grouped FUNCTION/REST replay exited 0 (tests skipped):

```sh
JAVA_HOME=/Users/mari/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home \
PATH=/Users/mari/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home/bin:/usr/bin:/bin:/opt/homebrew/bin \
PIPELINE_CONFIG=/Users/mari/mokapot/tpf-csv-sqs-provider-function/config/pipeline.container-sqs.yaml \
PIPELINE_TRANSPORT=REST \
MAVEN_ARGS='-Dmaven.repo.local=/Users/mari/mokapot/tpf-csv-sqs-provider-function/.m2/repository -o -nsu' \
./build-pipeline-runtime.sh -DskipTests -Dquarkus.container-image.build=false \
  -Dtpf.build.platform=FUNCTION -Dtpf.build.lambda.scope=compile \
  -Dpipelineframework.bom.version=26.10.1-g0-db2e04b
```

Log `/tmp/tpf-csv-g0.Vhy5YD/compiler-db2-full-function-replay.log` SHA-256 `082fe923f9190a8d51b36517753d83980126c73b34e55d4b57eb1ca7dbfe4bb7`. Pipeline-server and REST-server compiled bridge class bytes were identical (SHA-256 `8077236e7c9127f14d24e29ee7ef2692563f7813e447dba99b52946302841a13`). The same app build edit preserved grouped COMPUTE/GRPC SQS compilation: `/tmp/tpf-csv-g0.Vhy5YD/compiler-candidate-compute-rest-sourcepath-control.log` SHA-256 `3535a34d9a215fa89065271dfce5b9eec4098c55e0aff453f9b88e702c34f727`, exit 0.

The standalone provider child is built after the FUNCTION/REST processing artifacts. Its exact offline test/packaging command was:

```sh
JAVA_HOME=/Users/mari/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home \
PATH=/Users/mari/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home/bin:/usr/bin:/bin:/opt/homebrew/bin \
./mvnw -o -nsu -Dmaven.repo.local=/Users/mari/mokapot/tpf-csv-sqs-provider-function/.m2/repository \
  -f provider-lambda-svc/pom.xml -Dquarkus.container-image.build=false \
  -Dquarkus.profile=lambda -Dtpf.build.platform=FUNCTION -Dtpf.build.transport=REST \
  -Dtpf.build.lambda.scope=compile -Dpipelineframework.bom.version=26.10.1-g0-db2e04b clean verify
```

Exit 0; six provider tests passed, none skipped. Log `/tmp/tpf-csv-g0.Vhy5YD/provider-lambda-db2-child-verify-package.log` SHA-256 `1eeccff66ac66a94e722def0810dead68d7a75e795ff1b09ad57217ea11cbf54`. `provider-lambda-svc/target/function.zip` SHA-256 `3a8cfa29ac16d49663710d1a9325f7fd28c8db9db826a724e465be5d24d92bd0` includes the named raw handler and provider config. Existing `PaymentProviderSqsAwaitMockTest` ran separately in the processing module: three passed, none skipped; `/tmp/tpf-csv-g0.Vhy5YD/payment-provider-sqs-poller-test-db2.log` SHA-256 `f095ed14feeca5f4ffe98be1c36c9a89b5e25fcbae95bdfac51d6b86cec06838`. The checked-in `build-provider-lambda.sh` now runs both test groups and packages the child; it accepts a selected BOM coordinate and contains no synthetic default.

For a packaged startup/invocation check, a temporary Java driver called the official `QuarkusStreamHandler::handleRequest` from the built runner/`lib` classpath with an empty SQS batch, dummy AWS credentials/queue URL, and no reachable cloud endpoints. It returned `{"batchItemFailures":[]}` and exited 0. Log `/tmp/tpf-csv-g0.Vhy5YD/provider-lambda-db2-streamhandler-final.log` SHA-256 `0a4089d9db097bb70c0a2f341839a676d143253d673d41a3bcc3d613d6259c7c`; the log shows the `lambda` profile, both Kafka channels disabled, and no listener. Provider package config excludes both inherited resident provider beans and disables HTTP/gRPC listeners and default OTLP export. Quarkus's documented Lambda Java handler/name selection applies; the temporary driver is `/tmp/tpf-csv-g0.Vhy5YD/InvokeProviderLambda.java`. No SQS or AWS call was made by the empty-batch probe; successful response sends and retry behavior were unit tested with a mocked SQS client. Third-party Quarkus/Lambda/Surefire dependencies were copied into the isolated repo cache after SHA-1 sidecar verification; no framework component was sourced or installed locally.

The official matching framework cohort remains unavailable because current `tpf/system-tests` materialization is blocked by an unrelated examples fixture. This controlled replay proves neither that cohort nor AWS deployment, durable recovery, object ingestion, persistence service selection, or whole Hosted pilot acceptance.
