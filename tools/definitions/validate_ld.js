// Checks 1 and 3 of definitions/README.md, "Validating".
//
// Parses every definitions/lws10/**/*.yamlld as YAML 1.2 with the Core Schema, as YAML-LD
// requires. Writes the JSON form to build/json/ for the other checks. Converts each document
// to RDF with JSON-LD in safe mode, where a dropped or misspelt term is an error. The
// document loader serves only the repository's own contexts; it never touches the network
// (DECISIONS.md D-0026).
const fs = require('fs');
const path = require('path');
const YAML = require('yaml');
const jsonld = require('jsonld');

const REPO = path.resolve(__dirname, '..', '..');
const ROOT = path.join(REPO, 'definitions', 'lws10');
const OUT = path.join(__dirname, 'build', 'json');
// Only a base for resolving relative IRIs; nothing is fetched from it.
const BASE = 'https://touchstone.example/definitions/lws10/';

const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p); else if (e.name.endsWith('.yamlld')) files.push(p);
  }
})(ROOT);
files.sort();

const loader = async (url) => {
  if (!url.startsWith(BASE)) throw new Error('offline loader refused ' + url);
  const file = path.join(ROOT, url.slice(BASE.length));
  if (!fs.existsSync(file)) throw new Error('404 ' + url);
  const text = fs.readFileSync(file, 'utf8');
  const document = file.endsWith('.yamlld') ? YAML.parse(text, { version: '1.2', schema: 'core' }) : JSON.parse(text);
  return { contextUrl: null, documentUrl: url, document };
};

(async () => {
  fs.rmSync(OUT, { recursive: true, force: true });
  let failures = 0, triples = 0;
  for (const f of files) {
    const rel = path.relative(ROOT, f).replace(/\\/g, '/');
    const text = fs.readFileSync(f, 'utf8');
    let doc;
    try {
      const docs = YAML.parseAllDocuments(text, { version: '1.2', schema: 'core', uniqueKeys: true });
      if (docs.length !== 1) throw new Error(`expected exactly one YAML document, found ${docs.length}`);
      if (docs[0].errors.length) throw new Error(docs[0].errors.map(String).join('; '));
      if (text.includes('\t')) throw new Error('tab character present');
      doc = docs[0].toJS();
    } catch (e) { failures++; console.log(`YAML  FAIL ${rel}: ${e.message}`); continue; }
    const outFile = path.join(OUT, rel.replace(/\.yamlld$/, '.json'));
    fs.mkdirSync(path.dirname(outFile), { recursive: true });
    fs.writeFileSync(outFile, JSON.stringify(doc, null, 2));
    try {
      const nq = await jsonld.toRDF(doc, { base: BASE + rel, documentLoader: loader, format: 'application/n-quads', safe: true });
      const n = nq.trim().split('\n').filter(Boolean).length;
      triples += n;
      console.log(`OK    ${rel}  (${n} triples)`);
    } catch (e) {
      failures++;
      console.log(`LD    FAIL ${rel}: ${e.name}: ${e.message} ${e.details ? JSON.stringify(e.details).slice(0, 400) : ''}`);
    }
  }
  console.log(`\n${files.length} documents, ${triples} triples, ${failures} failure(s)`);
  process.exit(failures ? 1 : 0);
})();
