# Cloud-owned IAM binding for generated CSV REST clients

The application supplies `org.pipelineframework.csv.iam.LambdaIamRequestSigning` in
`orchestrator-svc`, consumed by both ingress and native-action Lambda packages. It
uses the existing Quarkus 3.39.2 per-client provider registration seam. No generated
client, DTO, pipeline, native coordinator, worker signature or service mapper is replaced.

Cloud's authenticated provisioner output owns the endpoint/numbered-function-version/
Release/caller-role association. Function URLs target aliases (or `$LATEST`); Cloud must
independently verify the alias resolves to the pinned numeric version. The application
packages the following properties in each new invoking Lambda artifact:

```properties
csv.iam.region=us-east-2
csv.iam.max-body-bytes=1048576
csv.iam.endpoints.process-csv-payments-input=https://<input-id>.lambda-url.us-east-2.on.aws
csv.iam.endpoints.process-approved-payment-status=https://<approved-id>.lambda-url.us-east-2.on.aws
csv.iam.endpoints.process-unapproved-payment-status=https://<unapproved-id>.lambda-url.us-east-2.on.aws
csv.iam.endpoints.process-finalize-payment-output=https://<finalize-id>.lambda-url.us-east-2.on.aws
```

For **each** of those four generated config keys, set the existing client properties:

```properties
quarkus.rest-client.<key>.url=<same exact bound HTTPS origin>
quarkus.rest-client.<key>.providers=org.pipelineframework.csv.iam.LambdaIamRequestSigning
quarkus.rest-client.<key>.follow-redirects=false
```

Pinned Quarkus uses a comma-separated class-name string for `providers`. Cloud must
supply registration for every remote client; unconfigured local conformance clients
retain their existing transport. Registration is opt-in at that existing seam, and
Cloud must reject incomplete IAM bindings before activation. There is no unsigned
fallback inside the provider. The endpoint origin accepts no credentials, path prefix,
query, fragment or nondefault port. Its hostname must be a Lambda Function URL in the
explicit region, under `on.aws`. Only the exact generated POST operation path for the
invoked client method is admitted; the CSV input `/page` method uses the same policy.
Ordinary query parameters are canonicalized by the SDK. Existing application context,
cache and content headers are preserved and signed alongside the final serialized bytes.

Credentials are resolved per request through AWS SDK's environment provider, with no
file, metadata, container, STS or default-chain discovery. Lambda supplies its runtime
role's `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `AWS_SESSION_TOKEN`; long-lived
sessionless credentials are rejected. The provider neither stores credentials in config
nor changes credentials. Known expired/near-expiry identities are rejected when an identity
has expiration metadata. Lambda environment credentials do not expose expiry metadata,
so AWS's expired-token rejection is authoritative and propagates without unsigned retry.

The buffer defaults to 1 MiB and has a hard 6 MiB ceiling. This is an application request
body envelope, not a claim that a 6 MiB body fits Lambda's event envelope: Cloud must budget
base64 expansion and event/header overhead. Invalid limits and overflow fail before send.
No signing material is logged. Redirect following must remain disabled; a 3xx response is
rejected. All supported Quarkus 3.39.2 FQCN, simple-name, config-key, quoted-name,
MicroProfile `followRedirects`, and global enabling settings are rejected even if
another setting would shadow them. Deadlines, serializer behavior and generated response/cache handling remain owned
by their existing client/runtime configuration.

## Immutable configuration input

Select a Cloud-verified, nonsecret JSON export with the ordinary Maven property
`-Dcsv.cloud.binding-file=/absolute/path/transport-export.json` when building either
`ingress-lambda-svc` or `durable-action-lambda-svc`. Its exact schema is:

```json
{
  "schemaVersion": 1,
  "accountId": "123456789012",
  "region": "us-east-2",
  "organizationId": "org-id",
  "applicationId": "app-id",
  "environmentId": "env-id",
  "ownerId": "owner-id",
  "stateOwnerId": "state-owner-id",
  "bootstrapBusinessOwnerReceiptSha256": "<64 lowercase hexadecimal characters>",
  "operations": {
    "process-csv-payments-input": {"httpsOrigin": "https://input.lambda-url.us-east-2.on.aws"},
    "process-approved-payment-status": {"httpsOrigin": "https://approved.lambda-url.us-east-2.on.aws"},
    "process-unapproved-payment-status": {"httpsOrigin": "https://unapproved.lambda-url.us-east-2.on.aws"},
    "process-finalize-payment-output": {"httpsOrigin": "https://finalize.lambda-url.us-east-2.on.aws"}
  }
}
```

The build rejects unknown/duplicate fields, incomplete operations, malformed scope/digest,
and origins outside the explicit region. It normalizes an optional root slash away;
credentials, ports, other paths, queries and fragments are forbidden. The generator
validates syntax and projection; it **does not authenticate arbitrary local JSON**.
Trusted Cloud CI must first verify the actual bootstrap business owner receipt. Native
publication/deployment IDs and caller roles are assigned later and are not invented here.

The ordinary resource phase creates `META-INF/microprofile-config.properties` with all
four providers, URLs, disabled redirects and IAM endpoint/region settings. This standard
MicroProfile source overrides generated client defaults without a new runtime service,
Maven profile or framework source change. It contains no credentials. The package also
carries `META-INF/csv-cloud-transport-evidence.json`; its transport export/config/bootstrap
receipt digests are repeated in the module's `target/cloud-binding-evidence.json`.
Cloud's final binding associates these digests and the **new immutable package digest**
with actual release/publication/deployment IDs before physical success. Do not patch an
old ZIP or put these application keys in the native host's SDK environment parser.

An unselected build preserves local defaults. Every resource phase deletes only these
owned generated outputs before optionally regenerating them, including previous classpath
copies, so an ordinary rebuild without `clean` cannot retain an old Cloud binding.

## Worker caller identities after native creation

After native ActionRole and WorkerRole creation, Cloud observes their actual IAM `GetRole`
RoleIds and exact IAM role ARNs. Select a separate worker-only export with
`-Dcsv.cloud.worker-binding-file=/absolute/path/worker-export.json`:

```json
{"schemaVersion":1,"accountId":"123456789012","allowedRoles":[
  {"roleId":"<actual ActionRole RoleId>","roleArn":"arn:aws:iam::123456789012:role/native-action"},
  {"roleId":"<actual WorkerRole RoleId>","roleArn":"arn:aws:iam::123456789012:role/native-worker"}
]}
```

Exactly two distinct ID/ARN pairs are required. The generated standard MP file has
`config_ordinal=260`, above application.properties (250), sets the exact account, clears
the legacy principal, and supplies `csv.worker.allowed-role-arns.<RoleId>=<exact role ARN>`.
Cloud must remove the bootstrap principal environment setting, whose ordinal would otherwise
override the file, before publishing the final immutable business version. Worker input/config
digests are embedded in `META-INF/csv-cloud-worker-evidence.json` and repeated outside the ZIP.
Cloud ties those digests and the final worker code digest to the actual GetRole observations.
This export is syntax-validated, not an independent claim of ownership or IAM observation.

For the authenticated Quarkus IAM principal, the worker requires exact account, an STS
assumed-role ARN matching the configured partition/account/terminal role name, and `userId`
equal to the immutable RoleId plus `:` plus the **identical ARN session name**. There is no
role lookup or prefix-only acceptance. Ordinary principals receive 401, authenticated invalid
identities 403, and missing/malformed configuration 503. Quarkus rejects a request
context without `userId` at authentication with 401 before the application filter. The existing exact
`csv.worker.principal-arn` mode remains available for local proof. Setting both modes fails
closed. Post-native roles are never invented in the prepublication transport export.

The pinned AWS event API exposes `accountId`, `userArn`, `userId` and `callerId`, not a
dedicated stable role ARN. [Function URL request context](https://docs.aws.amazon.com/lambda/latest/dg/urls-invocation.html)
documents the caller ARN and unique ID; [STS GetCallerIdentity](https://docs.aws.amazon.com/STS/latest/APIReference/API_GetCallerIdentity.html)
documents assumed-role ARN and RoleId/session values. The cold proof uses synthetic AWS
authorizer contexts to verify the real packaged adapter and route authorization, both native
roles and distinct sessions, identity mismatches, denied control routes, and stale-resource
cleanup. Actual Function URL context for the deployed native roles remains a live gate;
missing expected fields must fail closed rather than weakening the binding.

## Reproduce transport proof

With JDK 21 and the repository-local Maven cache, using the ordinary published BOM:

```sh
JAVA_HOME=<JDK21> bash self-host/pilot/build-and-prove.sh
JAVA_HOME=<JDK21> bash self-host/pilot/build-and-prove-iam.sh
```

The proof cold-starts the actual packaged Lambda and selects its real generated CDI clients.
The deployment example uses Ohio (`us-east-2`). The external test fixture deliberately uses
`eu-west-1` to verify explicit region selection independently of the deployment example.
A genuine external HTTPS server independently validates SigV4 with deterministic temporary
test credentials. An HTTP CONNECT proxy routes the AWS-style test hostname to that server;
a fixture-only certificate/trust store preserves hostname and certificate verification.
No production host-policy or trust-all escape exists. The positive cold proof discovers
region, origins and provider registration from the packaged MicroProfile source; launch
properties supply only external TLS/proxy and existing runtime activation settings. The
script also rebuilds without a binding and without `clean`, then proves the actual generated
client sends an unsigned request and that both generated resources are absent. It restores
the fixture-bound package through another bound build, still without `clean`, before the final signed proof.
The script first preserves all three ordinary unbound packages, then always restores
their normal `target/function.zip` paths on exit, including failure. It removes proof-created
configuration copies under `target/classes`, `target/cloud-binding-resources`, and the
outside sidecar, restoring any preexisting legitimate resource bytes. Each run explicitly
selects AWS_DURABLE before action packaging and NATIVE before ingress packaging.
Fixture-bound ZIPs
and their metadata are retained only under `target/iam-fixture-bound-packages/`, uploaded
as explicitly labelled evidence. `target/iam-package-restoration.json` records both sets'
digests. Ordinary CI review artifacts therefore remain unbound; fixture-bound packages
are never presented as deployment bindings.
The fixture supplies external HTTP responses, never TPF
workflow/state semantics. The retained report is `target/iam-signing-proof/result.json`.

The retained unbound control is `target/iam-signing-unbound-proof/result.json`.
`IAM_PROOF_UNSIGNED=true` deliberately overrides registration on a selected test package
and must fail because its actual wire request is unsigned. The original unchanged published
client behavioral red is retained separately in implementation evidence. Unit tests exercise known
expiration, encoded foreign operation paths, preexisting authentication and missing generated
identity. Existing ingress authorization/route/contract tests remain mandatory.

This proves local transport integration only. It does not prove live AWS IAM policies,
actual runtime role/credentials, native Durable replay/fault conformance, remote SQS consumers
or Cloud ACTIVE. Cloud must supply and authorize those authentic deployment inputs separately.
