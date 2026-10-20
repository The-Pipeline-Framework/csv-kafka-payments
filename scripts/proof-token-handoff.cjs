#!/usr/bin/env node
'use strict';
const crypto = require('node:crypto');
const fs = require('node:fs');
const https = require('node:https');
const path = require('node:path');
const ISSUER = 'https://royal-resonance-35-staging.authkit.app';
const CLIENT = 'client_01M47828F4HY6G180Q07CKP23G';
const ORG = 'org_01M478284XJAR20SB6EQK6058Z';
const AUDIENCE = 'client_01M31WC097KY09RS22DNEJF0JE';
const REPOSITORY = 'The-Pipeline-Framework/csv-kafka-payments';
const MAX_TTL = 3600;
function requireValue(ok) { if (!ok) throw new Error('rejected'); }
function decode64(text, max = 65536) {
  requireValue(typeof text === 'string' && text.length > 0 && text.length <= max && /^[A-Za-z0-9_-]+$/.test(text));
  const bytes = Buffer.from(text, 'base64url');
  requireValue(bytes.toString('base64url') === text); return bytes;
}
function recipient(encoded, fingerprint) {
  requireValue(typeof encoded === 'string' && encoded.length < 2048 && /^[A-Za-z0-9+/]+={0,2}$/.test(encoded));
  const der = Buffer.from(encoded, 'base64');
  requireValue(der.toString('base64') === encoded && /^[a-f0-9]{64}$/.test(fingerprint));
  requireValue(crypto.createHash('sha256').update(der).digest('hex') === fingerprint);
  const key = crypto.createPublicKey({key: der, type: 'spki', format: 'der'});
  requireValue(key.asymmetricKeyType === 'rsa' && key.asymmetricKeyDetails.modulusLength === 3072 && key.asymmetricKeyDetails.publicExponent === 65537n);
  return key;
}
function validateToken(response, jwks, now, maxAge = 120) {
  requireValue(response && response.token_type?.toLowerCase() === 'bearer' && Number.isSafeInteger(response.expires_in) && response.expires_in > 0 && response.expires_in <= MAX_TTL);
  requireValue(typeof response.access_token === 'string' && response.access_token.length <= 16384 && !('refresh_token' in response));
  const parts = response.access_token.split('.'); requireValue(parts.length === 3);
  const header = JSON.parse(decode64(parts[0], 4096).toString('utf8'));
  const claims = JSON.parse(decode64(parts[1], 16384).toString('utf8'));
  requireValue(header.alg === 'RS256' && typeof header.kid === 'string' && !header.crit && !header.jku && !header.x5u);
  requireValue(Array.isArray(jwks?.keys) && jwks.keys.length <= 32);
  const keys = jwks.keys.filter(k => k.kid === header.kid && k.kty === 'RSA' && (!k.use || k.use === 'sig') && (!k.alg || k.alg === 'RS256'));
  requireValue(keys.length === 1);
  const key = crypto.createPublicKey({key: keys[0], format: 'jwk'});
  requireValue(key.asymmetricKeyDetails.modulusLength >= 2048);
  requireValue(crypto.verify('RSA-SHA256', Buffer.from(parts[0] + '.' + parts[1]), key, decode64(parts[2], 2048)));
  requireValue(claims.iss === ISSUER && claims.aud === AUDIENCE && claims.sub === CLIENT && claims.client_id === CLIENT && claims.org_id === ORG && claims.scope === 'tpf:deploy' && !('sid' in claims));
  requireValue(Number.isSafeInteger(claims.iat) && Number.isSafeInteger(claims.exp) && claims.iat <= now + 30 && claims.iat >= now - maxAge && claims.exp > now && claims.exp - claims.iat > 0 && claims.exp - claims.iat <= MAX_TTL && claims.exp - now <= MAX_TTL);
  requireValue(!('nbf' in claims) || Number.isSafeInteger(claims.nbf) && claims.nbf <= now + 30);
  requireValue(typeof claims.jti === 'string' && claims.jti.length > 0 && claims.jti.length <= 256);
  requireValue(Math.abs(claims.exp - now - response.expires_in) <= 60);
  return claims;
}
function requestJson(method, pathname, body) {
  return new Promise((resolve, reject) => {
    const req = https.request(ISSUER + pathname, {method, minVersion: 'TLSv1.2', rejectUnauthorized: true, timeout: 20000, headers: body ? {'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(body)} : {}}, res => {
      let size = 0; const chunks = [];
      res.on('data', chunk => {size += chunk.length; if (size > 65536) res.destroy(new Error('rejected')); else chunks.push(chunk);});
      res.on('end', () => {try {requireValue(res.statusCode === 200); resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));} catch (_) {reject(new Error('rejected'));}});
      res.on('error', () => reject(new Error('rejected')));
    });
    req.on('timeout', () => req.destroy(new Error('rejected')));
    req.on('error', () => reject(new Error('rejected')));
    req.end(body);
  });
}
function seal(token, key, header) {
  const sessionKey = crypto.randomBytes(32), iv = crypto.randomBytes(12);
  try {
    const cipher = crypto.createCipheriv('aes-256-gcm', sessionKey, iv);
    cipher.setAAD(Buffer.from(JSON.stringify(header)));
    const ciphertext = Buffer.concat([cipher.update(token, 'utf8'), cipher.final()]);
    const wrappedKey = crypto.publicEncrypt({key, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256'}, sessionKey);
    const envelope = {header, wrappedKey: wrappedKey.toString('base64url'), iv: iv.toString('base64url'), tag: cipher.getAuthTag().toString('base64url'), ciphertext: ciphertext.toString('base64url')};
    requireValue(Buffer.byteLength(JSON.stringify(envelope)) <= 32768); return envelope;
  } finally {sessionKey.fill(0);}
}
async function exchange() {
  const env = process.env;
  requireValue(env.GITHUB_REPOSITORY === REPOSITORY && env.GITHUB_REF === 'refs/heads/main' && env.GITHUB_EVENT_NAME === 'workflow_dispatch' && env.GITHUB_RUN_ATTEMPT === '1');
  requireValue(/^[a-f0-9]{40}$/.test(env.EXPECTED_COMMIT) && env.EXPECTED_COMMIT === env.GITHUB_SHA && /^[0-9]{1,20}$/.test(env.GITHUB_RUN_ID) && /^[a-f0-9]{64}$/.test(env.HANDOFF_NONCE));
  const key = recipient(env.RECIPIENT_PUBLIC_KEY, env.RECIPIENT_SHA256);
  requireValue(typeof env.PROOF_CLIENT_SECRET === 'string' && env.PROOF_CLIENT_SECRET.length > 0 && env.PROOF_CLIENT_SECRET.length <= 4096);
  const body = new URLSearchParams({grant_type: 'client_credentials', client_id: CLIENT, client_secret: env.PROOF_CLIENT_SECRET, scope: 'tpf:deploy'}).toString();
  delete env.PROOF_CLIENT_SECRET;
  const response = await requestJson('POST', '/oauth2/token', body);
  // Runner mask control command; the value is never ordinary log output.
  requireValue(typeof response?.access_token === 'string' && !/[\r\n]/.test(response.access_token) && response.access_token.length <= 16384);
  process.stdout.write('::add-mask::' + response.access_token + '\n');
  const jwks = await requestJson('GET', '/oauth2/jwks');
  const now = Math.floor(Date.now() / 1000), claims = validateToken(response, jwks, now);
  const header = {version: 1, algorithm: 'RSA-OAEP-256+A256GCM', repository: REPOSITORY, workflowCommit: env.GITHUB_SHA, runId: env.GITHUB_RUN_ID, runAttempt: 1, recipientSha256: env.RECIPIENT_SHA256, nonce: env.HANDOFF_NONCE, issuer: ISSUER, client: CLIENT, organization: ORG, audience: AUDIENCE, scope: 'tpf:deploy', issuedAt: claims.iat, expiresAt: claims.exp};
  const envelope = seal(response.access_token, key, header);
  delete response.access_token;
  const directory = path.join(env.RUNNER_TEMP, 'proof-token-handoff');
  fs.mkdirSync(directory, {mode: 0o700});
  fs.writeFileSync(path.join(directory, 'sealed-token.json'), JSON.stringify(envelope), {flag: 'wx', mode: 0o600});
  process.stdout.write('Sealed one scoped proof token; lifetime at most 3600 seconds.\n');
}
function headerMatches(header, context, runId, now) {
  requireValue(header && Object.keys(header).join(',') === 'version,algorithm,repository,workflowCommit,runId,runAttempt,recipientSha256,nonce,issuer,client,organization,audience,scope,issuedAt,expiresAt');
  requireValue(header.version === 1 && header.algorithm === 'RSA-OAEP-256+A256GCM' && header.repository === REPOSITORY && header.workflowCommit === context.expected_commit && header.runId === runId && header.runAttempt === 1 && header.recipientSha256 === context.recipient_sha256 && header.nonce === context.nonce && header.issuer === ISSUER && header.client === CLIENT && header.organization === ORG && header.audience === AUDIENCE && header.scope === 'tpf:deploy');
  requireValue(Number.isSafeInteger(header.issuedAt) && Number.isSafeInteger(header.expiresAt) && header.issuedAt <= now + 30 && header.expiresAt > now && header.expiresAt - header.issuedAt > 0 && header.expiresAt - header.issuedAt <= MAX_TTL && header.expiresAt - now <= MAX_TTL);
}
function unseal(envelope, privateKey, context, runId, now) {
  requireValue(envelope && Object.keys(envelope).join(',') === 'header,wrappedKey,iv,tag,ciphertext' && Buffer.byteLength(JSON.stringify(envelope)) <= 32768);
  requireValue(/^[0-9]{1,20}$/.test(runId));
  headerMatches(envelope.header, context, runId, now);
  const publicDer = crypto.createPublicKey(privateKey).export({type: 'spki', format: 'der'});
  requireValue(crypto.createHash('sha256').update(publicDer).digest('hex') === context.recipient_sha256);
  const wrapped = decode64(envelope.wrappedKey, 1024), iv = decode64(envelope.iv, 32), tag = decode64(envelope.tag, 32);
  requireValue(wrapped.length === 384 && iv.length === 12 && tag.length === 16);
  const sessionKey = crypto.privateDecrypt({key: privateKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256'}, wrapped);
  try {
    requireValue(sessionKey.length === 32);
    const cipher = crypto.createDecipheriv('aes-256-gcm', sessionKey, iv);
    cipher.setAAD(Buffer.from(JSON.stringify(envelope.header))); cipher.setAuthTag(tag);
    return Buffer.concat([cipher.update(decode64(envelope.ciphertext, 24000)), cipher.final()]).toString('utf8');
  } finally {sessionKey.fill(0);}
}
function privateDirectory(directory) {
  const absolute = path.resolve(directory), stat = fs.lstatSync(absolute);
  requireValue(stat.isDirectory() && !stat.isSymbolicLink() && stat.uid === process.getuid() && (stat.mode & 0o777) === 0o700);
  return absolute;
}
function privateRead(file, maxBytes = 65536) {
  const fd = fs.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
  try {
    const stat = fs.fstatSync(fd);
    requireValue(stat.isFile() && stat.uid === process.getuid() && (stat.mode & 0o777) === 0o600 && stat.size > 0 && stat.size <= maxBytes && stat.nlink === 1);
    return fs.readFileSync(fd);
  } finally {fs.closeSync(fd);}
}
function writePrivate(file, value) {
  const fd = fs.openSync(file, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_NOFOLLOW, 0o600);
  try {fs.writeFileSync(fd, value); fs.fsyncSync(fd);} finally {fs.closeSync(fd);}
}
function generate(directory, expectedCommit) {
  requireValue(typeof expectedCommit === 'string' && /^[a-f0-9]{40}$/.test(expectedCommit));
  // mkdtemp avoids replacing a directory or following a caller-supplied final symlink.
  const root = fs.mkdtempSync(path.resolve(directory) + '-'); fs.chmodSync(root, 0o700);
  const pair = crypto.generateKeyPairSync('rsa', {modulusLength: 3072, publicExponent: 65537});
  const der = pair.publicKey.export({type: 'spki', format: 'der'});
  const context = {expected_commit: expectedCommit, recipient_public_key: der.toString('base64'), recipient_sha256: crypto.createHash('sha256').update(der).digest('hex'), nonce: crypto.randomBytes(32).toString('hex')};
  writePrivate(path.join(root, 'private-key.pem'), pair.privateKey.export({type: 'pkcs8', format: 'pem'}));
  writePrivate(path.join(root, 'request.json'), JSON.stringify(context));
  process.stdout.write('Operator-only handoff directory: ' + root + '\n');
}
async function receive(directory, ciphertextFile, runId) {
  const root = privateDirectory(directory), cipherPath = path.resolve(ciphertextFile);
  requireValue(path.dirname(cipherPath) === root && path.basename(cipherPath) === 'sealed-token.json');
  const context = JSON.parse(privateRead(path.join(root, 'request.json')).toString('utf8'));
  recipient(context.recipient_public_key, context.recipient_sha256);
  requireValue(/^[a-f0-9]{40}$/.test(context.expected_commit) && /^[a-f0-9]{64}$/.test(context.nonce));
  const key = crypto.createPrivateKey(privateRead(path.join(root, 'private-key.pem')));
  const envelope = JSON.parse(privateRead(cipherPath, 32768).toString('utf8'));
  const now = Math.floor(Date.now() / 1000), token = unseal(envelope, key, context, runId, now);
  // A valid recipient can still receive forged public-key ciphertext: verify issuer signature too.
  const jwks = await requestJson('GET', '/oauth2/jwks');
  const claims = validateToken({access_token: token, token_type: 'bearer', expires_in: envelope.header.expiresAt - now}, jwks, now, MAX_TTL);
  requireValue(claims.exp === envelope.header.expiresAt && claims.iat === envelope.header.issuedAt);
  writePrivate(path.join(root, 'access-token'), token);
  writePrivate(path.join(root, 'token-receipt.json'), JSON.stringify({runId, workflowCommit: context.expected_commit, recipientSha256: context.recipient_sha256, issuer: ISSUER, organization: ORG, client: CLIENT, scope: 'tpf:deploy', expiresAt: claims.exp}));
  fs.unlinkSync(cipherPath); fs.unlinkSync(path.join(root, 'private-key.pem'));
  process.stdout.write('Owner-only short-lived bearer file: ' + path.join(root, 'access-token') + '\n');
}
module.exports = {ISSUER, CLIENT, ORG, AUDIENCE, REPOSITORY, recipient, validateToken, seal, unseal, privateRead, privateDirectory, writePrivate};
if (require.main === module) {
  const args = process.argv.slice(2);
  Promise.resolve().then(() => {
    if (args.length === 1 && args[0] === 'exchange') return exchange();
    if (args.length === 3 && args[0] === 'generate') return generate(args[1], args[2]);
    if (args.length === 4 && args[0] === 'receive') return receive(args[1], args[2], args[3]);
    throw new Error('rejected');
  }).catch(() => {process.stderr.write('Proof token handoff rejected; no credential or response details logged.\n'); process.exitCode = 1;});
}
