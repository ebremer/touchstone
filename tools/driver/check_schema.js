// Compiles driver/protocol.schema.json with Ajv 2020 in strict mode, as tools/definitions does for the
// definitions schema, then checks lines it must accept and lines it must reject. A schema that accepted
// everything would pass the first half; the rejected lines show it rejects anything.
// Run from the repository: node tools/driver/check_schema.js (after npm ci in tools/definitions).
const path = require('path');
const fs = require('fs');
const REPO = path.resolve(__dirname, '..', '..');
const NM = path.join(REPO, 'tools', 'definitions', 'node_modules');
const Ajv2020 = require(path.join(NM, 'ajv', 'dist', '2020'));
const addFormats = require(path.join(NM, 'ajv-formats'));
const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
const schema = JSON.parse(fs.readFileSync(path.join(REPO, 'driver', 'protocol.schema.json'), 'utf8'));
const validate = ajv.compile(schema);
const result = (op) => ajv.getSchema(schema.$id + '#/$defs/result-' + op);
const good = [
  {hello: {protocol: 'touchstone-driver/1', client: {name: 'lws-client-go', version: '0.1.0', homepage: 'https://github.com/ebremer/lws-client'}, language: 'go', auth: ['bearer', 'cid', 'openid'], operations: ['configure', 'discover_storage', 'read', 'some_future_op']}},
  {id: 7, op: 'read', args: {url: 'https://storage.example/notes/a.txt'}},
  {id: 8, op: 'read', args: {url: 'https://storage.example/notes/a.txt', accept: null}},
  {id: 1, op: 'configure', args: {auth: {type: 'cid', agent: 'https://s.example/id/alice', privateJwk: {kty: 'EC', crv: 'P-256'}}}},
  {id: 2, op: 'configure', args: {auth: {type: 'saml', assertion: 'PHNhbWw-PC9zYW1sPg'}}},
  {id: 3, op: 'create', args: {container: 'https://s.example/c/', body: {json: {name: 'Alice'}}, slug: 'p.json', types: ['https://schema.org/Person']}},
  {id: 4, op: 'update', args: {url: 'https://s.example/r', body: {text: 'x'}, links: [{href: 'https://schema.org/Person', rel: 'type'}]}},
  {id: 5, op: 'search', args: {serviceUrl: 'https://s.example/search', query: {type: [['https://schema.org/Person', 'http://xmlns.com/foaf/0.1/Person'], 'https://www.w3.org/ns/lws#DataResource']}}},
  {id: 6, op: 'shutdown', args: {}},
  {id: 7, ok: true, result: {metadata: {url: 'https://s.example/a', status: 200}, notModified: false}},
  {id: 7, ok: false, error: {kind: 'http', status: 404, message: 'GET … → 404'}},
  {id: null, ok: false, error: {kind: 'invalidArguments', message: 'not a JSON object'}},
];
const bad = {
  'hello of lws-driver/1': {hello: {protocol: 'lws-driver/1', language: 'go', library: 'x', operations: []}},
  'hello without client': {hello: {protocol: 'touchstone-driver/1', auth: [], operations: []}},
  'unknown op': {id: 1, op: 'linkset_url', args: {url: 'https://s.example/'}},
  'unknown argument': {id: 1, op: 'read', args: {url: 'https://s.example/', prefer: 'x'}},
  'missing argument': {id: 1, op: 'patch', args: {url: 'https://s.example/'}},
  'relative URL': {id: 1, op: 'read', args: {url: '/a.txt'}},
  'retired auth': {id: 1, op: 'configure', args: {auth: {type: 'didKey'}}},
  'bearer with realm': {id: 1, op: 'configure', args: {auth: {type: 'bearer', token: 't', realm: 'https://s.example/'}}},
  'two body forms': {id: 1, op: 'update', args: {url: 'https://s.example/r', body: {text: 'x', base64: 'AA=='}}},
  'http error without status': {id: 1, ok: false, error: {kind: 'http', message: 'x'}},
  'library error kind': {id: 1, ok: false, error: {kind: 'NotFoundError', status: 404, message: 'x'}},
  'id zero': {id: 0, op: 'shutdown', args: {}},
  'ok with error': {id: 1, ok: true, error: {kind: 'internal', message: 'x'}},
};
let failures = 0;
for (const g of good) if (!validate(g)) { failures++; console.log('REJECTED good', JSON.stringify(g).slice(0, 90), JSON.stringify(validate.errors.slice(0, 3))); }
for (const [name, b] of Object.entries(bad)) if (validate(b)) { failures++; console.log('ACCEPTED bad:', name); }
const r = result('read');
if (!r({metadata: {url: 'https://s.example/a', status: 200, extra: 1}, notModified: true})) { failures++; console.log('result-read rejected an open member'); }
if (r({notModified: true})) { failures++; console.log('result-read accepted a result without metadata'); }
console.log(`${good.length} good and ${Object.keys(bad).length} bad lines, ${failures} wrong`);
process.exit(failures ? 1 : 0);
