#!/usr/bin/env node
// Every client against every server: drives each lws-client (github.com/ebremer/lws-client), through the MCP tools
// of its driver, through one scenario against each target server, and writes which combinations work. No packages
// needed (Node.js 20 or later). interop-all.sh runs it in local mode; a deployment can run it against its own targets.
//
//   node interop.mjs --config <file, or - for standard input> --out <dir> [--languages java,js] [--targets css,lwsd]
//
// The configuration, JSON:
//   driver     {"url": the driver's MCP endpoint, "token": its bearer token}
//   key        the private JWK (EC P-256) of the agent that owns every target's storage
//   assignee   another agent, to whom the scenario grants access (and revokes it at once)
//   insecure   true when the authorization servers are on plain HTTP (a private network); the clients refuse that
//              otherwise
//   targets    [{"name", "label", "language", "version", "source", "base", "storage", "issuer", "agent"}]
//              base: the container the clients work in, made if it is missing; storage: the storage, which access
//              tokens name as their audience; issuer: its authorization server; agent: the owner, whose
//              controlled identifier document lists the public half of key as <agent>#key
//   lwsClient  optional: {"version", "source"} of the clients, for the reports
//
// Each client signs in as the agent with a self-signed credential (the CID suite) and makes its own container
// (interop-<language>-<time>) in base, which it deletes at the end. The targets run at once, the languages of each
// one after another. Writes results.json, summary.md and matrix.html into --out. Exit status 0 when every
// combination works, 1 when one does not, 2 when the run could not be made.
import { createPrivateKey, randomUUID, sign } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { parseArgs } from 'node:util'

const { values: opts } = parseArgs({
  options: {
    config: { type: 'string' },
    out: { type: 'string' },
    languages: { type: 'string' },
    targets: { type: 'string' },
    verbose: { type: 'boolean', default: false },
  },
})
const die = (message) => { console.error(message); process.exit(2) }
if (!opts.config || !opts.out) die('usage: interop.mjs --config <file|-> --out <dir> [--languages a,b] [--targets x,y]')
const config = JSON.parse(readFileSync(opts.config === '-' ? 0 : opts.config, 'utf8'))
const PERSON = 'https://schema.org/Person'
const DESCRIBEDBY = 'https://example.org/shapes/person'
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const hasType = (types, term) => (types ?? []).some((t) => t === term || t.endsWith(`#${term}`) || t === `lws:${term}`)

// The scenario, in order. A step that cannot go on (no container to work in) leaves the later ones skipped.
const STEPS = [
  { id: 'discover', label: 'Discover the storage', detail: 'discover_storage on the container: its storage description and root' },
  { id: 'container', label: 'Sign in, create a container', detail: 'the first write: 401, the authorization server, token exchange of a self-signed credential, then create_container' },
  { id: 'create-read', label: 'Create and read a resource', detail: 'create text/plain, read it back with its ETag' },
  { id: 'conditional', label: 'Conditional requests', detail: 'If-None-Match read (304), If-Match update, and a stale If-Match refused (412)' },
  { id: 'json-patch', label: 'JSON Patch', detail: 'create JSON with a type, PATCH it with If-Match (application/json-patch+json), read it back' },
  { id: 'linkset', label: 'Linkset', detail: 'read the linkset, add a describedby link (PATCH, or PUT where PATCH is refused), read it back' },
  { id: 'listing', label: 'Container listing', detail: 'seven members, read through every page of the listing' },
  { id: 'type-search', label: 'Type index and search', detail: 'list_types names the type, search_all finds the resource (where the storage offers both services)' },
  { id: 'notifications', label: 'Notifications', detail: 'subscribe a webhook inbox, update, receive a delivery the client verifies, unsubscribe (where offered)' },
  { id: 'access', label: 'Access requests and grants', detail: 'request access for itself, read it; grant another agent access, read it, revoke it; cancel the request (where the storage offers both services)' },
  { id: 'delete', label: 'Delete recursively', detail: 'delete the container with everything in it; the resource is then gone' },
]

// ------------------------------------------------------------------------------------------------ MCP client

class Mcp {
  constructor (url, token) { Object.assign(this, { url, token, id: 1, session: undefined, version: undefined }) }

  async connect () {
    const result = await this.request('initialize', {
      protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'touchstone-interop', version: '1' },
    })
    this.version = result.protocolVersion
    await this.post({ jsonrpc: '2.0', method: 'notifications/initialized' })
  }

  /** A tool's structured result; a tool error (the driver could not do it) throws. */
  async call (name, args = {}) {
    const result = await this.request('tools/call', { name, arguments: args })
    const text = (result.content ?? []).find((c) => c.type === 'text')?.text ?? ''
    if (result.isError) throw new Error(`${name}: ${text}`)
    return result.structuredContent ?? JSON.parse(text)
  }

  async close () {
    if (this.session) await fetch(this.url, { method: 'DELETE', headers: this.headers() }).catch(() => {})
  }

  async request (method, params) {
    const id = this.id++
    const message = await this.post({ jsonrpc: '2.0', id, method, params }, id)
    if (message.error) throw new Error(`${method}: ${message.error.message} (${message.error.code})`)
    return message.result
  }

  headers () {
    const h = { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' }
    if (this.token) h.Authorization = `Bearer ${this.token}`
    if (this.session) h['Mcp-Session-Id'] = this.session
    if (this.version) h['MCP-Protocol-Version'] = this.version
    return h
  }

  async post (body, id) {
    const response = await fetch(this.url, {
      method: 'POST', headers: this.headers(), body: JSON.stringify(body), signal: AbortSignal.timeout(180_000),
    })
    const session = response.headers.get('mcp-session-id')
    if (session) this.session = session
    if (id === undefined) { await response.arrayBuffer(); return undefined }
    if (!response.ok) throw new Error(`${body.method}: HTTP ${response.status} ${await response.text()}`)
    const type = response.headers.get('content-type') ?? ''
    if (type.startsWith('application/json')) return response.json()
    if (!type.startsWith('text/event-stream')) throw new Error(`${body.method}: unexpected content type ${type}`)
    const decoder = new TextDecoder()
    let buffer = ''
    for await (const chunk of response.body) {
      buffer += decoder.decode(chunk, { stream: true })
      let end
      while ((end = buffer.search(/\r?\n\r?\n/)) >= 0) {
        const event = buffer.slice(0, end)
        buffer = buffer.slice(end).replace(/^\r?\n\r?\n/, '')
        const data = event.split(/\r?\n/).filter((l) => l.startsWith('data:')).map((l) => l.slice(5).trimStart()).join('\n')
        if (!data) continue
        const message = JSON.parse(data)
        if (message.id === id) { response.body.cancel().catch(() => {}); return message }
      }
    }
    throw new Error(`the event stream ended without an answer to ${body.method}`)
  }
}

// ------------------------------------------------------------- the agent's own access, to make the base container

const b64u = (data) => Buffer.from(data).toString('base64url')
const now = () => Math.floor(Date.now() / 1000)

function credential (agent, key, audience) {
  const input = b64u(JSON.stringify({ alg: 'ES256', typ: 'JWT', kid: `${agent}#key` })) + '.' +
    b64u(JSON.stringify({ iss: agent, sub: agent, client_id: agent, aud: [audience], iat: now(), exp: now() + 300, jti: randomUUID() }))
  const signature = sign('sha256', Buffer.from(input), { key: createPrivateKey({ key, format: 'jwk' }), dsaEncoding: 'ieee-p1363' })
  return `${input}.${b64u(signature)}`
}

async function accessToken (target) {
  const issuer = new URL(target.issuer)
  // RFC 8414 section 3.1: the well-known segment goes between the host and the issuer's path.
  const metadataUrl = `${issuer.origin}/.well-known/lws-configuration${issuer.pathname.replace(/\/$/, '')}`
  const metadata = await (await fetch(metadataUrl)).json()
  const response = await fetch(metadata.token_endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:token-exchange',
      resource: target.storage,
      subject_token: credential(target.agent, config.key, metadata.issuer),
      subject_token_type: 'urn:ietf:params:oauth:token-type:jwt',
    }),
  })
  const body = await response.json().catch(() => ({}))
  if (!body.access_token) throw new Error(`token exchange at ${metadata.token_endpoint}: ${response.status} ${JSON.stringify(body)}`)
  return body.access_token
}

async function ensureBase (target) {
  const auth = { Authorization: `Bearer ${await accessToken(target)}` }
  const got = await fetch(target.base, { headers: { ...auth, Accept: 'application/lws+json' } })
  if (got.status !== 404) return
  const parent = target.base.replace(/[^/]+\/$/, '')
  const made = await fetch(parent, {
    method: 'POST',
    headers: { ...auth, Slug: decodeURIComponent(target.base.slice(parent.length, -1)), Link: '<https://www.w3.org/ns/lws#Container>; rel="type"' },
  })
  if (made.status !== 201) throw new Error(`making ${target.base}: POST ${parent} answered ${made.status}`)
}

// --------------------------------------------------------------------------------------------- the scenario

class Inapplicable extends Error {}
class Failure extends Error {}

/** One client against one target. */
async function cell (mcp, target, language) {
  const steps = Object.fromEntries(STEPS.map((s) => [s.id, { outcome: 'skipped', detail: '' }]))
  const result = { target: target.name, language, library: '', steps }
  const log = (line) => console.log(`[${target.name} ${language}] ${line}`)
  let client, inbox
  try {
    const started = await mcp.call('start_client', {
      language, authType: 'selfSigned', agent: target.agent, privateJwk: config.key, kid: `${target.agent}#key`,
      allowInsecureHttp: config.insecure === true, label: `interop ${target.name}`,
    })
    client = started.client
    result.library = started.library ?? ''
    inbox = (await mcp.call('open_inbox', { client })).inbox
  } catch (e) {
    result.error = `the client did not start: ${e.message}`
    log(result.error)
    return finish(result)
  }

  // An operation: its result, or a Failure naming the error the client reported.
  const op = async (name, args) => {
    const r = await mcp.call(name, { client, ...args })
    if (opts.verbose) log(`${name} -> ${r.ok ? 'ok' : `${r.error.kind} ${r.error.status ?? ''}`}`)
    if (!r.ok) {
      const e = r.error
      throw Object.assign(new Failure(`${name}: ${e.kind}${e.status ? ` ${e.status}` : ''}${e.message ? `: ${e.message}` : ''}`), { kind: e.kind })
    }
    return r.result
  }
  // An operation that must fail with one of the error kinds given.
  const refused = async (name, args, ...kinds) => {
    const r = await mcp.call(name, { client, ...args })
    if (r.ok) throw new Failure(`${name} succeeded; expected ${kinds.join(' or ')}`)
    if (!kinds.includes(r.error.kind)) throw new Failure(`${name}: ${r.error.kind} ${r.error.status ?? ''}; expected ${kinds.join(' or ')}`)
    return r.error
  }
  const check = (ok, message) => { if (!ok) throw new Failure(message) }
  let stop = false
  const step = async (id, fn, { fatal = false } = {}) => {
    if (stop) return
    try {
      const detail = await fn()
      steps[id] = { outcome: 'passed', detail: detail ?? '' }
    } catch (e) {
      const outcome = e instanceof Inapplicable ? 'inapplicable' : e.kind === 'Unsupported' ? 'unsupported' : 'failed'
      steps[id] = { outcome, detail: e.message }
      if (outcome !== 'inapplicable') log(`${id}: ${outcome}: ${e.message}`)
      if (fatal && outcome !== 'inapplicable') stop = true
    }
  }

  const slug = `interop-${language}-${Date.now()}`
  let description, container, hello, profile
  const service = (type) => description?.services?.find((s) => hasType(s.types, type))?.serviceEndpoint
  const etagOf = async (url) => (await op('head', { url })).etag

  await step('discover', async () => {
    description = await op('discover_storage', { url: target.base })
    check(description.storageRoot, 'the storage description names no storage root')
    const offered = ['TypeIndexService', 'TypeSearchService', 'NotificationService', 'AccessRequestService', 'AccessGrantService']
      .filter((t) => service(t))
    return `storage ${description.id}; services: ${offered.join(', ') || 'none'}`
  })
  await step('container', async () => {
    container = (await op('create_container', { parent: target.base, slug })).location
    return container
  }, { fatal: true })
  await step('create-read', async () => {
    hello = (await op('create', { container, body: { text: 'Hello, LWS!' }, contentType: 'text/plain', slug: 'hello.txt' })).location
    const read = await op('read', { url: hello })
    check(read.body?.text === 'Hello, LWS!', `read back ${JSON.stringify(read.body)}`)
    check(read.metadata?.etag, 'no ETag')
  }, { fatal: true })
  await step('conditional', async () => {
    const etag = await etagOf(hello)
    const unchanged = await op('read', { url: hello, ifNoneMatch: etag })
    check(unchanged.notModified, 'If-None-Match with the current ETag did not answer 304')
    await op('update', { url: hello, body: { text: 'Hello again' }, contentType: 'text/plain', ifMatch: etag })
    await refused('update', { url: hello, body: { text: 'stale' }, contentType: 'text/plain', ifMatch: etag }, 'PreconditionFailedError')
  })
  await step('json-patch', async () => {
    profile = (await op('create', { container, body: { json: { name: 'Alice', age: 30 } }, slug: 'profile.json', types: [PERSON] })).location
    await op('patch', { url: profile, ifMatch: await etagOf(profile),
      patch: [{ op: 'replace', path: '/age', value: 31 }, { op: 'add', path: '/city', value: 'Boston' }] })
    const read = await op('read', { url: profile })
    const json = JSON.parse(read.body?.text ?? 'null')
    check(json?.age === 31 && json?.city === 'Boston', `read back ${read.body?.text}`)
  })
  await step('linkset', async () => {
    check(profile, 'no resource (JSON Patch step)')
    const linkset = await op('read_linkset', { url: profile })
    const add = [{ op: 'add', path: '/linkset/0/describedby', value: [{ href: DESCRIBEDBY }] }]
    let how = 'PATCH'
    try {
      await op('patch_linkset', { linksetUrl: linkset.url, patch: add, ifMatch: linkset.etag })
    } catch (e) {
      if (!['MethodNotAllowedError', 'UnsupportedMediaTypeError'].includes(e.kind)) throw e
      const doc = structuredClone(linkset.linkset)
      doc.linkset[0].describedby = [{ href: DESCRIBEDBY }]
      try {
        await op('update_linkset', { linksetUrl: linkset.url, linkset: doc, ifMatch: linkset.etag })
      } catch (put) {
        throw Object.assign(new Failure(`${e.message}; then ${put.message}`), { kind: put.kind })
      }
      how = 'PUT (PATCH refused)'
    }
    const again = await op('read_linkset', { url: profile })
    const links = (again.linkset?.linkset ?? []).flatMap((c) => c.describedby ?? [])
    check(links.some((l) => l.href === DESCRIBEDBY), 'the describedby link is not in the linkset read back')
    return how
  })
  await step('listing', async () => {
    for (let i = 0; i < 5; i++) {
      await op('create', { container, body: { json: { name: `Person ${i}` } }, slug: `person-${i}.json`, types: [PERSON] })
    }
    const { items } = await op('list_container', { url: container })
    const ids = new Set(items.map((i) => i.id))
    const names = () => items.map((i) => i.id.slice(container.length) || i.id).join(', ')
    check(ids.size === 7, `${ids.size} members listed, not 7: ${names()}`)
    check(ids.has(hello) && (!profile || ids.has(profile)), 'the listing lacks a resource made earlier')
    const page = await op('read_container', { url: container })
    return page.next ? `paged (${page.items.length} on the first page)` : 'one page'
  })
  await step('type-search', async () => {
    const index = service('TypeIndexService')
    const search = service('TypeSearchService')
    if (!index || !search) throw new Inapplicable('the storage offers no type index and type search services')
    check(profile, 'no typed resource (JSON Patch step)')
    let types = []
    let found = []
    for (let i = 0; i < 10; i++) {   // an index may take a moment to catch up
      types = (await op('list_types', { serviceUrl: index })).types ?? []
      found = (await op('search_all', { serviceUrl: search, query: { type: [PERSON] } })).items ?? []
      if (types.some((t) => (t.type ?? t.id ?? t) === PERSON) && found.some((i) => i.id === profile)) return ''
      await sleep(1000)
    }
    check(types.some((t) => (t.type ?? t.id ?? t) === PERSON), 'list_types does not name the type')
    check(found.some((i) => i.id === profile), 'search_all does not find the resource')
  })
  await step('notifications', async () => {
    const notifications = service('NotificationService')
    if (!notifications) throw new Inapplicable('the storage offers no notification service')
    const subscription = (await op('subscribe', { serviceUrl: notifications, topics: [container], inbox })).subscription
    try {
      await op('update', { url: hello, body: { text: 'Hello, notifications' }, contentType: 'text/plain', ifMatch: await etagOf(hello) })
      let delivery
      for (let i = 0; i < 60 && !delivery; i++) {
        const { deliveries } = await mcp.call('inbox_deliveries', { client, after: 0 })
        delivery = deliveries.find((d) => d.verified)
        if (!delivery) {
          if (deliveries.length) throw new Failure(`a delivery arrived but did not verify (answered ${deliveries[0].answer})`)
          await sleep(500)
        }
      }
      check(delivery, 'no notification arrived in 30 s')
    } finally {
      await op('unsubscribe', { url: subscription })
    }
  })
  await step('access', async () => {
    const requests = service('AccessRequestService')
    const grants = service('AccessGrantService')
    if (!requests || !grants) throw new Inapplicable('the storage offers no access request and grant services')
    // An agent asks for access for itself; the owner grants it to another agent.
    const doc = (type, assignee) => ({
      '@context': ['https://www.w3.org/ns/lws/v1'], type: [type], storage: description.id, inbox,
      access: [{
        type: ['AccessPolicy'], action: ['read'], assignee, target: { type: 'StorageResource', value: [container] },
        constraint: [{ leftOperand: 'purpose', operator: 'eq', rightOperand: 'https://purpose.example/interop' }],
      }],
    })
    const request = (await op('request_access', { serviceUrl: requests, request: doc('AccessRequest', target.agent) })).location
    await op('get_access_request', { url: request })
    const grant = (await op('grant_access', { serviceUrl: grants, grant: doc('AccessGrant', config.assignee) })).location
    await op('get_access_grant', { url: grant })
    await op('revoke_access_grant', { url: grant })
    await op('cancel_access_request', { url: request })
  })
  await step('delete', async () => {
    await op('delete', { url: container, recursive: true })
    await refused('read', { url: hello }, 'NotFoundError', 'GoneError')
    container = undefined
  })

  if (container) await mcp.call('delete', { client, url: container, recursive: true }).catch(() => {})
  await mcp.call('stop_client', { client }).catch(() => {})
  return finish(result)
}

function finish (result) {
  const counted = Object.values(result.steps).filter((s) => s.outcome !== 'inapplicable')
  result.applicable = counted.length
  result.passed = counted.filter((s) => s.outcome === 'passed').length
  result.works = !result.error && result.passed === result.applicable
  return result
}

// ------------------------------------------------------------------------------------------------- reports

const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c])
const symbol = { passed: '✓', failed: '✗', unsupported: '✗', skipped: '–', inapplicable: 'n/a' }

function summaryMd (run) {
  const head = `| Client | ${run.targets.map((t) => t.label).join(' | ')} |`
  const rule = `|---|${run.targets.map(() => '---').join('|')}|`
  const rows = run.languages.map((l) => `| ${l.language} | ${run.targets.map((t) => {
    const c = run.cells.find((x) => x.target === t.name && x.language === l.language)
    return c.error ? 'error' : `${c.works ? '✓' : '✗'} ${c.passed}/${c.applicable}`
  }).join(' | ')} |`)
  const failures = run.cells.filter((c) => !c.works).map((c) => `- **${c.language} → ${c.target}**: ${c.error ??
    Object.entries(c.steps).filter(([, s]) => ['failed', 'unsupported'].includes(s.outcome)).map(([id, s]) => `${id} (${s.detail})`).join('; ')}`)
  return [`# Every client against every server, ${run.started}`, '', head, rule, ...rows, '',
    ...(failures.length ? ['Not working:', '', ...failures] : ['Every combination works.']), ''].join('\n')
}

function matrixHtml (run) {
  const cellOf = (t, l) => run.cells.find((c) => c.target === t.name && c.language === l.language)
  const klass = (c) => c.error ? 'bad' : c.works ? 'ok' : c.passed > 0 ? 'part' : 'bad'
  const title = (c) => c.error ?? (Object.entries(c.steps).filter(([, s]) => ['failed', 'unsupported', 'skipped'].includes(s.outcome))
    .map(([id, s]) => `${id}: ${s.outcome}${s.detail ? ` (${s.detail})` : ''}`).join('\n') || 'every step passed')
  const top = `<tr><th>Client</th>${run.targets.map((t) => `<th>${esc(t.label)}<br><small>${esc(t.language)}${t.version ? ` · ${esc(t.version)}` : ''}</small></th>`).join('')}</tr>`
  const rows = run.languages.map((l) => `<tr><th>${esc(l.language)}<br><small>${esc(l.library)}</small></th>${run.targets.map((t) => {
    const c = cellOf(t, l)
    return `<td class="${klass(c)}" title="${esc(title(c))}">${c.error ? 'error' : `${c.works ? '✓' : '✗'} ${c.passed}/${c.applicable}`}</td>`
  }).join('')}</tr>`).join('\n')
  const details = run.targets.map((t) => `<h2>${esc(t.label)}</h2><table><tr><th>Step</th>${run.languages.map((l) => `<th>${esc(l.language)}</th>`).join('')}</tr>${
    STEPS.map((s) => `<tr><th title="${esc(s.detail)}">${esc(s.label)}</th>${run.languages.map((l) => {
      const st = cellOf(t, l).steps[s.id]
      return `<td class="s-${st.outcome}" title="${esc(st.detail)}">${symbol[st.outcome]}</td>`
    }).join('')}</tr>`).join('')}</table>`).join('\n')
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>LWS client-server interop</title><style>
:root{--ok:#d8f0dc;--part:#fbeccd;--bad:#f6d5d5;--line:#ccc;--fg:#1d1d1d;--bg:#fff}
@media (prefers-color-scheme:dark){:root{--ok:#1f4a2a;--part:#5a4615;--bad:#5a2323;--line:#555;--fg:#eee;--bg:#161616}}
body{font:14px/1.4 system-ui,sans-serif;color:var(--fg);background:var(--bg);margin:16px}
table{border-collapse:collapse;margin:8px 0 24px}th,td{border:1px solid var(--line);padding:4px 8px;text-align:center}
th:first-child{text-align:left}small{font-weight:normal;opacity:.75}
.ok,.s-passed{background:var(--ok)}.part{background:var(--part)}.bad,.s-failed,.s-unsupported{background:var(--bad)}
.s-skipped,.s-inapplicable{opacity:.6}</style></head><body>
<h1>Every LWS client against every LWS server</h1>
<p>${esc(run.started)}. Each cell: the scenario's steps that passed, of those the server offers (hover for what failed).
Clients: lws-client ${esc(run.lwsClient?.version ?? '')}.</p>
<table>${top}${rows}</table>
${details}
<p>Steps: ${STEPS.map((s) => `<b>${esc(s.label)}</b>: ${esc(s.detail)}`).join('; ')}.</p>
</body></html>
`
}

// ------------------------------------------------------------------------------------------------------ main

const only = (list, names) => names ? list.filter((x) => names.split(',').includes(x.name ?? x)) : list
const targets = only(config.targets, opts.targets)
if (!targets.length) die('no targets')
const mcp = new Mcp(config.driver.url, config.driver.token)
try {
  await mcp.connect()
} catch (e) {
  die(`cannot reach the driver at ${config.driver.url}: ${e.message}`)
}
const ready = (await mcp.call('list_languages', {})).languages.filter((l) => l.available).map((l) => l.language)
const languages = opts.languages ? opts.languages.split(',') : ready
const started = new Date().toISOString()
mkdirSync(opts.out, { recursive: true })

const cells = []
await Promise.all(targets.map(async (target) => {
  try {
    await ensureBase(target)
  } catch (e) {
    console.log(`[${target.name}] cannot make ${target.base}: ${e.message}`)
    for (const language of languages) cells.push(finish({ target: target.name, language, library: '', error: e.message, steps: Object.fromEntries(STEPS.map((s) => [s.id, { outcome: 'skipped', detail: '' }])) }))
    return
  }
  for (const language of languages) {
    const c = await cell(mcp, target, language)
    console.log(`[${target.name} ${language}] ${c.error ? 'error' : `${c.works ? 'works' : 'does not work'}: ${c.passed}/${c.applicable}`}`)
    cells.push(c)
  }
}))
await mcp.close()

const libraries = Object.fromEntries(languages.map((l) => [l, cells.find((c) => c.language === l && c.library)?.library ?? '']))
const run = {
  started, finished: new Date().toISOString(), driver: config.driver.url, lwsClient: config.lwsClient,
  steps: STEPS,
  languages: languages.map((language) => ({ language, library: libraries[language] })),
  targets: targets.map(({ name, label, language, version, source, base }) => ({ name, label: label ?? name, language, version, source, base })),
  cells,
}
writeFileSync(join(opts.out, 'results.json'), JSON.stringify(run, null, 2))
writeFileSync(join(opts.out, 'summary.md'), summaryMd(run))
writeFileSync(join(opts.out, 'matrix.html'), matrixHtml(run))
console.log('\n' + summaryMd(run))
process.exit(cells.every((c) => c.works) ? 0 : 1)
