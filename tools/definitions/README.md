# Checks and generators for `definitions/`

The YAML-LD test definitions are data, so they are checked as data. This directory holds
the checks listed in `definitions/README.md` ("Validating"), and the generators for the two
generated files, `definitions/lws10/vocab.yamlld` and `definitions/COVERAGE.md`. CI runs
them on every push and pull request.

## Running them

```sh
cd tools/definitions
npm ci                               # Node 22 or newer
pip install -r requirements.txt      # Python 3.12 or newer
python check.py                      # every check; exit 1 if any fails
python check.py --write              # the same, but regenerate the two generated files
```

`check.py` runs these in order:

| # | Script | Checks |
|---|---|---|
| 1 | `validate_ld.js` | Every `.yamlld` parses as YAML 1.2 (Core Schema), with no tabs, anchors or duplicate keys. Each converts to RDF with JSON-LD in safe mode, using only the repository's own contexts. It writes the JSON form to `build/json/` for the later checks. |
| 2 | `portable_yaml.py` | A YAML 1.1 parser reads every file the same way. |
| 3 | `validate_schema.js` | Every document validates against `definitions/schema/definitions.schema.json` in strict mode. 20 negative controls, real tests each with one deliberate defect, must all be rejected. |
| 4 | `lint_definitions.py` | The lint of `EXECUTION.md` section 2.5. |
| 5 | `gen_vocab.py --check` | `vocab.yamlld` is what the term table in the script generates, and it defines exactly the `lwst:` terms of `context.jsonld`. |
| 6 | `gen_coverage.py --check` | `COVERAGE.md` is what the definitions generate. |
| 7 | `export_dryrun.js` | The JSON-LD export loses nothing: each exported document gives the same canonical RDF as its YAML-LD source, minus the Touchstone-only terms. |

The lint covers what the schema cannot:
- unique names, and bound variables;
- known identities, with grants naming agents other than alice;
- catalog IRIs that exist and have not drifted;
- `source` anchors that exist in their dated snapshots;
- fixtures that exist, and no example hosts in executable values;
- `mirrors` and `supersedes` targets that exist.

Two of the files are generated, so edit their sources rather than the files:
- **`vocab.yamlld`:** edit the table in `gen_vocab.py`, then run `python check.py --write`
  and commit both.
- **`COVERAGE.md`:** its per-test notes live in `gen_coverage.py`.

## lws-test-suite

The lint confirms that every `mirrors` names a real lws-test-suite test, and `COVERAGE.md`
maps that suite's tests. Both need a checkout of `lws-contrib/lws-test-suite`. The scripts
find it in one of two places:
- the path in `$LWS_TEST_SUITE`, which is its `lws10` directory;
- otherwise, a sibling checkout at `../lws-test-suite/lws10`, relative to this repository.

CI checks out commit `b8cb134fd2a4d18e8f4272532cf3715c95180dba` (2026-09-20), the one
`definitions/COMPARISON.md` was measured on. Move that pin deliberately, and re-measure.

Without a checkout, the lint skips the `mirrors` check and says so. `gen_coverage.py` fails,
because it cannot produce the file.

## `anchors.json`

The lint checks each `source` anchor against the section and heading ids of the dated
snapshot it cites, recorded in `anchors.json`. Snapshots never change, so the file only
needs refreshing when a definition starts citing a new one. Run `python fetch_anchors.py`,
which fetches every cited snapshot from w3.org, and commit the result.

## Versions

- **Node:** `ajv` 8.20.0, `ajv-formats` 3.0.1, `jsonld` 9.0.0 and `yaml` 2.9.1. The exact
  tree is pinned by `package-lock.json`.
- **Python:** PyYAML 6.0.3 and rdflib 7.6.0.

All versions were checked against their registries on 2026-09-23.

`jsonld` 9 canonicalizes through `rdf-canonize` 5. That library caps deep comparisons as a
guard against hostile input, and the definitions' repeated step structures need a
`maxWorkFactor` of 2 (see `export_dryrun.js`). A negative control confirmed that the export
trial still reports a corrupted value.
