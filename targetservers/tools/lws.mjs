#!/usr/bin/env node
// Helpers for run-all.sh, run in a Node.js container (no packages needed): keys, did:keys, access tokens, the
// container a run goes in, and the summary of a set of runs.
//
//   lws.mjs keys <dir>                         alice.jwk, bob.jwk (P-256), saml-idp.jwk + .pem (RSA), db-password
//   lws.mjs did <jwk-file>                     the did:key of a P-256 key
//   lws.mjs key <jwk|css-jwks> < file          a private JWK on one line (css-jwks: CSS's key file, kid = thumbprint)
//   lws.mjs mint <jwk> <iss> <aud> <sub> <client-id>
//                                              an RFC 9068 access token signed with the authorization server's key
//   lws.mjs exchange <jwk-file> <issuer> <storage>
//                                              an access token to <storage> for the did:key of <jwk-file>, by token
//                                              exchange at the authorization server <issuer> (RFC 8414 metadata)
//   lws.mjs samlidps <entity-id> <pem-file>    the JSON object {entity: PEM} of a trusted SAML identity provider
//   lws.mjs ensure <token> <container-url>     make the container (POST to its parent, with a Slug) if it is not there
//   lws.mjs wait <url> [seconds]               wait until <url> answers anything but a gateway error
//   lws.mjs summary <runs-dir>                 a Markdown table of the report bundles in <runs-dir>
import { generateKeyPairSync, createHash, createPrivateKey, createPublicKey, sign, randomUUID, randomBytes } from 'node:crypto'
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const b64u = (data) => Buffer.from(data).toString('base64url')
const die = (message) => { console.error(message); process.exit(2) }
const readJwk = (arg) => JSON.parse(arg.trim().startsWith('{') ? arg : readFileSync(arg, 'utf8'))

// RFC 7638 thumbprint.
function thumbprint (jwk) {
  const members = jwk.kty === 'EC' ? { crv: jwk.crv, kty: jwk.kty, x: jwk.x, y: jwk.y } : { e: jwk.e, kty: jwk.kty, n: jwk.n }
  return createHash('sha256').update(JSON.stringify(members)).digest('base64url')
}

const ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'
function base58 (bytes) {
  let n = BigInt('0x' + Buffer.from(bytes).toString('hex'))
  let out = ''
  while (n > 0n) { out = ALPHABET[Number(n % 58n)] + out; n /= 58n }
  for (const b of bytes) { if (b !== 0) break; out = '1' + out }
  return out
}

// did:key of a P-256 key: multicodec p256-pub (0x1200, varint 80 24) and the compressed point, base58btc.
function didKey (jwk) {
  const x = Buffer.from(jwk.x, 'base64url')
  const y = Buffer.from(jwk.y, 'base64url')
  return 'did:key:z' + base58(Buffer.concat([Buffer.from([0x80, 0x24, (y[y.length - 1] & 1) ? 0x03 : 0x02]), x]))
}

function jwt (header, claims, jwk) {
  const key = createPrivateKey({ key: jwk, format: 'jwk' })
  const input = b64u(JSON.stringify(header)) + '.' + b64u(JSON.stringify(claims))
  const alg = header.alg
  const signature = alg.startsWith('ES')
    ? sign('sha256', Buffer.from(input), { key, dsaEncoding: 'ieee-p1363' })
    : sign('sha256', Buffer.from(input), key)
  return input + '.' + b64u(signature)
}

const now = () => Math.floor(Date.now() / 1000)

function mint (jwk, iss, aud, sub, clientId) {
  const header = { alg: 'ES256', typ: 'at+jwt' }
  if (jwk.kid) header.kid = jwk.kid
  return jwt(header, { iss, aud, sub, client_id: clientId, iat: now(), exp: now() + 300, jti: randomUUID() }, jwk)
}

// RFC 8414 §3.1: the well-known segment goes between the host and the issuer's path.
function metadataUrl (issuer) {
  const u = new URL(issuer)
  return `${u.origin}/.well-known/lws-configuration${u.pathname.replace(/\/$/, '')}`
}

async function exchange (jwk, issuer, storage) {
  const metadata = await (await fetch(metadataUrl(issuer))).json()
  const did = didKey(jwk)
  const credential = jwt({ alg: 'ES256', typ: 'JWT', kid: did + '#' + did.slice('did:key:'.length) },
    { iss: did, sub: did, client_id: did, aud: [metadata.issuer], iat: now(), exp: now() + 300, jti: randomUUID() }, jwk)
  const response = await fetch(metadata.token_endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:token-exchange',
      resource: storage,
      subject_token: credential,
      subject_token_type: 'urn:ietf:params:oauth:token-type:jwt',
    }),
  })
  const body = await response.json().catch(() => ({}))
  if (!body.access_token) die(`token exchange at ${metadata.token_endpoint} answered ${response.status}: ${JSON.stringify(body)}`)
  return body.access_token
}

async function ensure (token, url) {
  const auth = { Authorization: 'Bearer ' + token }
  const got = await fetch(url, { headers: { ...auth, Accept: 'application/lws+json' } })
  if (got.status !== 404) return `${url}: ${got.status}, there`
  const parent = url.replace(/[^/]+\/$/, '')
  const slug = decodeURIComponent(url.slice(parent.length, -1))
  const made = await fetch(parent, {
    method: 'POST',
    headers: { ...auth, Slug: slug, Link: '<https://www.w3.org/ns/lws#Container>; rel="type"' },
  })
  const location = made.headers.get('location') || ''
  if (made.status !== 201) die(`making ${url}: POST ${parent} answered ${made.status}`)
  if (location && new URL(location, parent).href !== url) die(`making ${url}: the server made ${location} instead`)
  return `${url}: made`
}

async function wait (url, seconds) {
  const until = Date.now() + seconds * 1000
  let last = 'no answer'
  while (Date.now() < until) {
    try {
      const r = await fetch(url, { signal: AbortSignal.timeout(5000) })
      if (![502, 503, 504].includes(r.status)) return `${url}: ${r.status}`
      last = String(r.status)
    } catch (e) { last = e.cause?.code || e.message }
    await new Promise((resolve) => setTimeout(resolve, 2000))
  }
  die(`${url} did not come up in ${seconds} s (last: ${last})`)
}

function summary (dir) {
  const rows = []
  for (const name of readdirSync(dir).sort()) {
    const file = join(dir, name, 'report.json')
    if (!existsSync(file)) continue
    const { run } = JSON.parse(readFileSync(file, 'utf8'))
    const conformant = Number(run.mustFailures) === 0
    rows.push(`| ${run.targetId} | ${run.tests} | ${run.passed} | ${run.failed} | ${run.cantTell} | ${run.inapplicable} | ` +
      `${conformant ? 'conformant' : `**not conformant** (${run.mustFailures} MUST)`} | \`${name}\` |`)
  }
  return ['| Target | Tests | Passed | Failed | cantTell | Inapplicable | Verdict | Report |',
    '|---|---|---|---|---|---|---|---|', ...rows].join('\n')
}

const [command, ...args] = process.argv.slice(2)
switch (command) {
  case 'keys': {
    const dir = args[0]
    const ec = () => { const k = generateKeyPairSync('ec', { namedCurve: 'P-256' }).privateKey.export({ format: 'jwk' }); return { ...k, kid: thumbprint(k) } }
    for (const name of ['alice', 'bob']) {
      const file = join(dir, name + '.jwk')
      if (!existsSync(file)) writeFileSync(file, JSON.stringify(ec()), { mode: 0o600 })
    }
    if (!existsSync(join(dir, 'saml-idp.jwk'))) {
      const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
      const jwk = privateKey.export({ format: 'jwk' })
      writeFileSync(join(dir, 'saml-idp.jwk'), JSON.stringify({ ...jwk, kid: thumbprint(jwk) }), { mode: 0o600 })
      writeFileSync(join(dir, 'saml-idp.pem'), createPublicKey(privateKey).export({ type: 'spki', format: 'pem' }))
    }
    if (!existsSync(join(dir, 'db-password'))) writeFileSync(join(dir, 'db-password'), randomBytes(24).toString('hex'), { mode: 0o600 })
    break
  }
  case 'did':
    console.log(didKey(readJwk(args[0])))
    break
  case 'key': {
    const text = readFileSync(0, 'utf8')
    let jwk = JSON.parse(text)
    if (args[0] === 'css-jwks') jwk = jwk.payload.keys[0]
    if (!jwk.kid) jwk.kid = thumbprint(jwk)
    console.log(JSON.stringify(jwk))
    break
  }
  case 'mint':
    console.log(mint(readJwk(args[0]), ...args.slice(1)))
    break
  case 'exchange':
    console.log(await exchange(readJwk(args[0]), args[1], args[2]))
    break
  case 'samlidps':
    console.log(JSON.stringify({ [args[0]]: readFileSync(args[1], 'utf8') }))
    break
  case 'ensure':
    console.log(await ensure(args[0], args[1]))
    break
  case 'wait':
    console.log(await wait(args[0], Number(args[1] || 300)))
    break
  case 'summary':
    console.log(summary(args[0]))
    break
  default:
    die('usage: see the comment at the top of lws.mjs')
}
