// Check 2 of definitions/README.md, "Validating": strict JSON Schema validation.
//
// Validates the JSON form of every document (build/json/, written by validate_ld.js) against
// definitions/schema/definitions.schema.json with Ajv in strict mode. Then it runs negative
// controls: copies of real tests with one deliberate defect each, which the schema must
// reject. A schema that accepts everything would pass the first half; the controls are what
// show it rejects anything.
const fs = require('fs');
const path = require('path');
const Ajv2020 = require('ajv/dist/2020');
const addFormats = require('ajv-formats');

const REPO = path.resolve(__dirname, '..', '..');
const OUT = path.join(__dirname, 'build', 'json');
const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
const validate = ajv.compile(JSON.parse(fs.readFileSync(path.join(REPO, 'definitions', 'schema', 'definitions.schema.json'), 'utf8')));

// The vocabulary is checked as JSON-LD only (validate_ld.js); everything else must validate.
const docs = {};
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith('.json') && e.name !== 'vocab.json') docs[path.relative(OUT, p).replace(/\\/g, '/')] = JSON.parse(fs.readFileSync(p, 'utf8'));
  }
})(OUT);

let bad = 0;
for (const [rel, doc] of Object.entries(docs)) {
  if (!validate(doc)) {
    bad++;
    console.log('INVALID', rel);
    for (const er of validate.errors.slice(0, 8)) console.log('   ', er.instancePath, er.message, JSON.stringify(er.params));
  }
}
console.log(`${Object.keys(docs).length} documents checked against the schema, ${bad} invalid`);

// Negative controls: the schema must reject every one of these.
const find = (pred) => {
  for (const [file, d] of Object.entries(docs)) {
    const i = (d.entries || []).findIndex(pred);
    if (i >= 0) return [file, i];
  }
  throw new Error('no test matches a control\'s precondition');
};
const control = (pred, mutate) => {
  const [file, i] = find(pred);
  const d = JSON.parse(JSON.stringify(docs[file]));
  mutate(d.entries[i]);
  return validate(d);
};
const flow = t => Array.isArray(t.steps);
const short = t => 'request' in t;
const pre = t => 'prereqs' in t;
const dataPre = t => pre(t) && 'dataResource' in t.prereqs.hierarchy[0];
const chal = t => short(t) && 'authenticationChallenge' in t.response;
const controls = {
  'typo key statusCod': [flow, t => { t.steps[0].response.statusCod = 200; }],
  'malformed template ${test.container': [flow, t => { t.steps[0].request.url = '${test.container'; }],
  'two bodies': [flow, t => { t.steps[0].request.body = 'x'; t.steps[0].request.bodyJSON = {}; }],
  'unknown trait': [flow, t => { t.traits.push('Webhooks'); }],
  'undated source': [flow, t => { t.source.push('https://www.w3.org/TR/lws10-core/#metadata'); }],
  'neither steps nor request': [flow, t => { delete t.steps; }],
  'steps and request together': [flow, t => { t.request = t.steps[0].request; t.response = t.steps[0].response; }],
  'request without response': [short, t => { delete t.response; }],
  'short-form typo reponse': [short, t => { t.reponse = t.response; delete t.response; }],
  'prerequisite that is both kinds': [pre, t => { const e = t.prereqs.hierarchy[0]; e.container = 'x'; e.dataResource = 'y'; }],
  'prerequisite of neither kind': [pre, t => { const e = t.prereqs.hierarchy[0]; delete e.container; delete e.dataResource; }],
  'data resource without content': [dataPre, t => { const e = t.prereqs.hierarchy[0]; delete e.body; delete e.bodyURL; delete e.bodyJSON; }],
  'data resource without contentType': [dataPre, t => { delete t.prereqs.hierarchy[0].contentType; }],
  'container with a body': [pre, t => { t.prereqs.hierarchy = [{ container: 'box', body: 'x' }]; }],
  'absent entry with a grant': [pre, t => { t.prereqs.hierarchy = [{ container: 'box', absent: true, authorization: { read: ['anonymous'] } }]; }],
  'grant with an action the draft does not define (write)': [pre, t => { t.prereqs.hierarchy = [{ container: 'box', authorization: { write: ['bob'] } }]; }],
  'empty authorization': [pre, t => { t.prereqs.hierarchy = [{ container: 'box', authorization: {} }]; }],
  'challenge without wwwAuthenticate': [chal, t => { delete t.response.authenticationChallenge.wwwAuthenticate; }],
  'challenge with the 0.1.0 scheme key': [chal, t => { const c = t.response.authenticationChallenge; c.scheme = c.wwwAuthenticate; delete c.wwwAuthenticate; }],
  'asUri with an unknown operator': [chal, t => { t.response.authenticationChallenge.asUri = { equals: 'x' }; }],
};
let accepted = 0;
for (const [label, [pred, mutate]] of Object.entries(controls)) {
  const ok = control(pred, mutate);
  if (ok) accepted++;
  console.log(`control ${label}: ${ok ? 'ACCEPTED (bad)' : 'rejected (good)'}`);
}
console.log(`${Object.keys(controls).length} negative controls, ${accepted} wrongly accepted`);
process.exitCode = bad || accepted ? 1 : 0;
