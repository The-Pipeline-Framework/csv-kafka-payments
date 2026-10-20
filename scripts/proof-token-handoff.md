# Supervised first-burn token handoff

This opt-in operational workflow reuses the existing org-scoped CSV proof M2M
credential in GitHub secret `TPF_OAUTH_PROOF_CLIENT_SECRET`. It exchanges that
credential for one signed access token, verifies its existing issuer, environment
audience, client, organization and exact `tpf:deploy` scope, and encrypts it to an
ephemeral operator key. It never creates or rotates WorkOS credentials, changes
scopes, authenticates a human, or exports the long-lived client secret. It does
not register a Release or deploy infrastructure.

Authority is pinned to the existing sandbox identity:

- issuer `https://royal-resonance-35-staging.authkit.app`;
- environment audience `client_01M31WC097KY09RS22DNEJF0JE`;
- M2M client `client_01M47828F4HY6G180Q07CKP23G`;
- organization `org_01M478284XJAR20SB6EQK6058Z`;
- canonical Cloud Organization `5245b6bda864febd1357a3f263375e5b6956ea8f9c53060a3244124b148e9892`.

Existing authority/source: `infra-tpf-cloud-onboarding/docs/release-proof-identity.md`,
`scripts/release-proof-ci.py`, onboarding's `CloudBearerPrincipalPolicy` and
`MachineContextResource`, and CLI `EnvironmentCredentialResolver`. Do not rerun
the issuance script: it intentionally refuses an existing credential/destination.
GitHub does not expose the existing secret plaintext through its read API.

The workflow runs only by manual dispatch on the exact reviewed main SHA and
first attempt. It checks out that SHA with no persisted Git credentials, has only
`contents: read`, grants no OIDC/status authority, and accepts no issuer, secret
name, scope or destination URL input. Repository write authority already permits
secret-consuming workflows; this handoff delegates a bearer for at most one hour,
so only the supervised operator should dispatch it and accept its recipient key.
Review the public-key SHA and fresh nonce before dispatch. Its public-key input is
not an authentication or authorization boundary by itself.

The official WorkOS Connect claims table and client-credentials JWT example document
`sub`, `client_id` and `jti`; Cloud already requires the matching client/subject
shape. No synthetic claim shape substitutes for that provider contract.

The helper uses built-in Node APIs: RSA3072 OAEP/SHA256 wraps a random AES256 key;
AES256GCM authenticates the token and complete context (repository, workflow SHA,
run ID/attempt, recipient fingerprint, nonce, identity and expiry). The operator
private key never leaves the local owner-only directory. JWT signatures are
verified against the fixed issuer JWKS on both ends. Tokens with lifetime greater
than 3600 seconds are rejected, not locally truncated. Network errors and response
bodies never become logs. The generated token is registered through GitHub's mask
control command and never emitted as ordinary output; only ciphertext is uploaded.
No cryptographic memory-erasure guarantee is made for immutable JavaScript strings.

## Supervised procedure, after review and merge

Requires Node 20 or newer and an authenticated GitHub operator authorized for this
repository. These commands are a procedure, not evidence that a dispatch occurred.

1. Read the actual merged main SHA containing the reviewed workflow/helper. Generate
   local material outside a checkout:

   ```sh
   node scripts/proof-token-handoff.cjs generate /private/tmp/tpf-proof-handoff ACTUAL_REVIEWED_MAIN_SHA
   ```

   It prints the created directory path only. Directory mode is 0700; private key
   and request file are 0600. `request.json` contains only four public dispatch
   inputs. Check its SHA, nonce and intended workflow commit. Keep the private key
   local; never upload that directory or use it as an artifact path.

2. Dispatch only the reviewed main workflow, with the generated public request:

   ```sh
   gh workflow run proof-token-handoff.yml --repo The-Pipeline-Framework/csv-kafka-payments --ref main --json < /private/tmp/ACTUAL_HANDOFF_DIRECTORY/request.json
   ```

   Verify the resulting run belongs to the exact repository and workflow, has
   event `workflow_dispatch`, branch `main`, the reviewed head SHA, attempt 1 and
   successful conclusion. Record its actual run ID. A failed or cancelled exchange
   is not an instruction to rotate credentials or automatically repeat issuance.

3. Download only artifact `proof-token-handoff-ACTUAL_RUN_ID-1` from that verified
   run into the generated directory. Confirm it contains only `sealed-token.json`.
   Set that ciphertext file's mode to 0600, then consume it:

   ```sh
   node scripts/proof-token-handoff.cjs receive /private/tmp/ACTUAL_HANDOFF_DIRECTORY /private/tmp/ACTUAL_HANDOFF_DIRECTORY/sealed-token.json ACTUAL_RUN_ID
   ```

   The receiver refuses symlink files, hard links, broad modes, wrong recipient,
   nonce, commit, run, identity, expiry, altered ciphertext and token signatures.
   It writes only `access-token` and a non-secret `token-receipt.json`, both 0600.
   It deletes the consumed local ciphertext and private key. Failed validation
   creates no bearer file. Review the authenticated GitHub run before receiving:
   matching metadata alone does not prove GitHub artifact provenance.

4. Use the existing bearer credential source: a logical CLI credential name
   `proof` resolves `TPF_CREDENTIAL_PROOF`. An operator helper may read the 0600
   `access-token` file and inject that value into a child process environment;
   never put it in command arguments, source, YAML, shell history or output.
   Before registration, GET the existing private `/api/machine-context` through
   the normal bearer path and require SERVICE, the exact canonical Organization,
   exact service principal client and deploy scope. The Cloud verifier remains
   authoritative; no session/tenant/role substitution is permitted.

5. Delete the local bearer and temporary directory when done or expired. Remove
   the encrypted GitHub artifact through its verified run after successful
   consumption; maximum artifact retention is one day. Ciphertext has no useful
   bearer authority without the local private key, but is still temporary data.
   No access-token revocation API or early-expiry guarantee is invented here.

## Checks and remaining gate

`node --test scripts/test-proof-token-handoff.cjs` exercises synthetic RS256 token
validation, the real hybrid crypto round trip, recipient/context/ciphertext
negatives, file controls and trusted workflow guards. These tests make no network
calls and use no actual credential. A separate unprivileged PR/main workflow runs
them automatically with no secret references. Syntax and source whitespace checks are cheap;
no application Maven build is required for this operational-only change.

Live secret exchange, GitHub mask/artifact behavior, authenticated Cloud admission
and registration are still separate supervised gates. GitHub secrets and WorkOS
identity readback establish inventory; they do not prove the stored credential is
currently usable. The actual first dispatch must retain only non-secret run,
identity, expiry and cleanup evidence.

Primary references checked 2026-10-21:
[WorkOS client credentials](https://workos.com/docs/reference/workos-connect/token#client-credentials-grant),
[WorkOS claims](https://workos.com/docs/authkit/connect/token-claims),
[GitHub secure use](https://docs.github.com/en/actions/reference/security/secure-use).
