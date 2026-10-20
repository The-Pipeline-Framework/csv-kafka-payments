# First-burn CSV Lambda packaging

Use the accepted normal published runtime64/BOM1033 cohort. The existing native
host ZIP stays unchanged. This application checkout's original head `c0b3908` has
the same source tree as accepted merge `182c5e6783132d48e0d04b32d08fafdf382e0468`;
new source corrections are recorded separately by the build driver.

## Secure ingress startup

Configure only these nonsecret ingress settings, using the **same resume-token
parameter ARN and version as the native deployment**:

```text
CSV_PILOT_RESUME_TOKEN_PARAMETER_ARN=<actual native ResumeTokenParameterArn>
CSV_PILOT_RESUME_TOKEN_PARAMETER_VERSION=1
CSV_PILOT_SIGNING_LIFECYCLE_VALIDATION=validated
PIPELINE_OBJECT_INGEST_AUTOSTART=false
```

The ARN must identify `/tpf/durable/<scope>/resume-token/g-<32..64 lowercase hex>`.
The application reads its exact `:1` with decryption; returned ARN, full physical
name, version, SecureString type, nonblank minimum 32 characters and maximum
4,096 UTF-8 bytes must all match. Key bytes are preserved. Provider errors exclude
response text and causes. The ConfigSource exists only in ingress, not a new host.

`validated` is an owner's attestation: independently verify Standard tier,
`aws/ssm`, current/retained access, generation immutability and actual obligations.
Runtime ingress needs only `ssm:GetParameter` on that exact ARN. It gets no
history/path/list/write/delete grants. Provisioning/retirement policy must deny
held-generation overwrite, deletion and recreation. The reader does not enforce
those external policies or replace native retention/recovery checks.

Do not configure `PIPELINE_ORCHESTRATOR_RESUME_TOKEN_SECRET` or the corresponding
MP property with SSM selected, including an explicitly empty value. Partial or
blank SSM settings fail closed. With **no SSM setting present**, the established
local proof can continue supplying its explicit fixture plaintext key. There is
no default key or selected-source fallback.

Ingress reuses the existing worker Lambda resource-removal pattern to exclude the
producer's resident `application.properties`, including workstation JKS defaults.
It retains producer classes and Compiled Truth. Resource removal causes Quarkus to
reindex that JAR, including its merged clients; ingress therefore consumes those
clients once from the main JAR rather than adding a duplicate client classifier. Lambda HTTP terminates inbound
TLS; generated outbound clients keep HTTPS, SigV4 and system TLS verification.
Existing localhost proofs keep their explicit fixture TLS settings unchanged.

## Staged builds from actual owner observations

`build-tools/package-first-burn.py` validates the existing export formats and
owner receipt hashes, then executes the established native-action and HTTP producer
variants. It never calls AWS, uploads code, changes aliases or claims deployment.

The frozen public cache receipt is the already authenticated manager proof
`cache-manifest.json`, SHA-256
`c5bba839509d176d7666fd2eba9a8de53cccb68b8367b1270c43b6b324399bbe`.
Copy its approved published `org/pipelineframework` cache entries into this
checkout's `.m2/repository`, retaining exact metadata/aliases. The driver verifies
all recorded bytes before and after each offline build; it permits no floating
resolution, source-framework reactor or arbitrary Maven argument override. It pins
BOM `26.10.1-20261018.162140-18` and release plugin
`26.10.1-20261018.170624-21` explicitly. Its reviewed offline settings map the
plugin repository to the authenticated cache repository ID, and its NUL argument
file carries those same settings into nested application bootstrap. The validated
owner export is frozen and rechecked before each build command. Source fingerprints
are verified before and after each command, excluding the driver-owned output
directory. Inherited JVM/Maven options are stripped alongside credentials.

After the bootstrap owner supplies its authenticated grouped-worker origin export:

```sh
python3 build-tools/package-first-burn.py --stage transport \
  --export /absolute/path/actual-transport-export.json \
  --owner-receipt /absolute/path/actual-bootstrap-receipt.json \
  --owner-receipt-sha256 <actual-receipt-sha256> \
  --baseline-receipt /absolute/path/authenticated-cache-manifest.json \
  --output /absolute/path/new-transport-build-directory
```

That builds action against AWS_DURABLE/no HTTP bridge, switches the producer back
to NATIVE/HTTP bridge, then builds ingress and the existing provider. Each Lambda
module runs `clean verify`. Producer warm-up follows the existing recipe. The
four transport keys must share one actual Ohio grouped-worker origin and approved
account `207828640987`. The export receipt hash must equal the actual input bytes.

After native resources exist, Cloud observes actual immutable ActionRole and
WorkerRole RoleIds/ARNs and supplies its existing worker export:

```sh
python3 build-tools/package-first-burn.py --stage worker \
  --export /absolute/path/actual-worker-export.json \
  --owner-receipt /absolute/path/actual-native-role-observation.json \
  --owner-receipt-sha256 <actual-observation-sha256> \
  --baseline-receipt /absolute/path/authenticated-cache-manifest.json \
  --output /absolute/path/new-worker-build-directory
```

`--plan-only` validates and emits commands without making packages; it is never
production artifact evidence. Owner receipt authentication, role/resource matching,
Function URL ownership, no-weight alias checks and final numeric-version/code/config
verification remain Cloud's responsibility. Receipt hashing is integrity, not
independent AWS authentication.

Build processes receive no AWS credentials or selected signing/bootstrap settings.
Fresh output directories retain command logs, source file fingerprints, embedded
binding evidence and actual ZIP byte counts/digests. No old ZIP is patched or
relabelled. Provider runtime response goes directly to actual native
AwaitCompletionQueueUrl; no additional consumer or response queue is introduced.

## Local checks and limits

```sh
python3 -m unittest discover -s build-tools -p 'test_*binding.py'
python3 -m unittest discover -s build-tools -p test_first_burn_packaging.py
./mvnw -o -f ingress-lambda-svc/pom.xml clean verify \
  -Dmaven.repo.local="$PWD/.m2/repository" -Dquarkus.container-image.build=false
python3 self-host/pilot/prove-signing-bootstrap.py
```

The cold proof uses the actual ZIP with a disclosed localhost SSM HTTP fixture and
synthetic credentials. It proves package startup/configuration and negative reads;
it does not prove live SSM IAM/tier/key/lifecycle, real origins/RoleIds, AWS delivery,
Cloud ACTIVE or business completion. The complete existing native worker/S3 and
IAM proofs remain unchanged and binding-specific live evidence stays required.
