// The export of definitions/README.md, "Export to lws-test-suite (JSON-LD)", run as a check.
//
// Converts every YAML-LD document to the JSON-LD a contribution would carry: it drops the
// touchstone-only terms and touchstone.jsonld, and rewrites .yamlld includes to .jsonld. The
// result goes to build/export/. It then proves nothing was lost: the exported document,
// read with context.jsonld alone, must give exactly the RDF graph of the YAML-LD document
// minus its touchstone: triples, compared as RDFC-1.0 canonical N-Quads.
const fs = require('fs');
const path = require('path');
const YAML = require('yaml');
const jsonld = require('jsonld');

const REPO = path.resolve(__dirname, '..', '..');
const ROOT = path.join(REPO, 'definitions', 'lws10');
const EXPORT = path.join(__dirname, 'build', 'export', 'lws10');
// Only a base for resolving relative IRIs; nothing is fetched from it.
const BASE = 'https://lws-tests.example/lws10/';
const TOUCHSTONE_ONLY = new Set(['requirements', 'mirrors', 'supersedes', 'note']);
// Values that are JSON literals, copied as they are.
const OPAQUE = new Set(['bodyJSON', 'bodyForm', 'equals', 'hasValue', 'credentialHeader', 'credentialClaims',
  'identityDocument', 'samlAssertion']);

const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p); else if (e.name.endsWith('.yamlld')) files.push(p);
  }
})(ROOT);
files.sort();

function strip(node) {
  if (Array.isArray(node)) return node.map(strip);
  if (node && typeof node === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(node)) {
      if (TOUCHSTONE_ONLY.has(k)) continue;
      if (k === '@context') { out[k] = (Array.isArray(v) ? v : [v]).filter(c => typeof c !== 'string' || !c.endsWith('touchstone.jsonld')); continue; }
      if (k === 'include') { out[k] = v.map(i => i.replace(/\.yamlld$/, '.jsonld')); continue; }
      out[k] = OPAQUE.has(k) ? v : strip(v);
    }
    return out;
  }
  return node;
}

// RDFC-1.0 canonical N-Quads. rdf-canonize caps its deep comparisons at n^maxWorkFactor for n
// non-unique blank nodes, a guard against hostile input. The default of 1 is too low for
// these documents, whose steps repeat structurally identical blank nodes; they are the
// repository's own files, so 2 is safe.
const canon = (nquads) => jsonld.canonize(nquads, {
  inputFormat: 'application/n-quads',
  canonizeOptions: { algorithm: 'RDFC-1.0', format: 'application/n-quads', maxWorkFactor: 2 },
});

const loader = (allowTouchstone) => async (url) => {
  const rel = url.startsWith(BASE) ? url.slice(BASE.length) : null;
  if (rel === 'context.jsonld' || (allowTouchstone && rel === 'touchstone.jsonld')) {
    return { contextUrl: null, documentUrl: url, document: JSON.parse(fs.readFileSync(path.join(ROOT, rel), 'utf8')) };
  }
  throw new Error('export loader refused ' + url);
};

(async () => {
  fs.rmSync(path.dirname(EXPORT), { recursive: true, force: true });
  let ok = 0, bad = 0;
  for (const f of files) {
    const stem = path.relative(ROOT, f).replace(/\\/g, '/').replace(/\.yamlld$/, '');
    const src = YAML.parse(fs.readFileSync(f, 'utf8'), { version: '1.2', schema: 'core' });
    const exported = strip(src);
    const outFile = path.join(EXPORT, stem + '.jsonld');
    fs.mkdirSync(path.dirname(outFile), { recursive: true });
    fs.writeFileSync(outFile, JSON.stringify(exported, null, 2) + '\n');
    // The same (extensionless) base for both, so IRIs differ only in include targets.
    const base = BASE + stem;
    const a = await jsonld.toRDF(src, { base, documentLoader: loader(true), format: 'application/n-quads', safe: true });
    const b = await jsonld.toRDF(JSON.parse(fs.readFileSync(outFile, 'utf8')), { base, documentLoader: loader(false), format: 'application/n-quads', safe: true });
    const isTouchstone = l => l.includes('<https://example.org/touchstone/vocab#');
    const aKept = a.split('\n').filter(l => l && !isTouchstone(l)).join('\n').replace(/\.yamlld>/g, '.jsonld>') + '\n';
    const ca = await canon(aKept);
    const cb = await canon(b);
    const dropped = a.split('\n').filter(isTouchstone).length;
    if (ca === cb) { ok++; console.log(`SAME  ${stem}.jsonld  (${cb.trim().split('\n').length} quads; ${dropped} touchstone quads dropped)`); }
    else { bad++; console.log(`DIFF  ${stem}.jsonld`); }
  }
  console.log(`\nexport dry-run: ${ok} identical, ${bad} different`);
  process.exit(bad ? 1 : 0);
})().catch(e => { console.error('ERROR', e.name, e.message); process.exit(1); });
