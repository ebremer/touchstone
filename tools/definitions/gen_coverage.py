"""Check 6 of definitions/README.md, "Validating": the coverage report.

Generates definitions/COVERAGE.md from the JSON form of the definitions (build/json/,
written by validate_ld.js). It maps every lws-test-suite test and every retired manifests/ test
to its counterparts, lists every definition by module, counts the catalog's requirements by the
role they bind, and lists the ones client sessions will answer for (CLIENT-TESTING.md). It
needs a lws-test-suite checkout
(README.md). COVERAGE.md is generated, so edit the definitions or the notes below, not the
file.

    python gen_coverage.py           regenerate COVERAGE.md
    python gen_coverage.py --check   fail if COVERAGE.md is not what the definitions generate
"""
import glob
import json
import os
import sys
from collections import Counter, defaultdict

import rdflib

from _paths import DEFS, JSON_OUT, REPO, lts_manifests, lws_test_suite, retired_manifests

OUT = str(JSON_OUT)
DST = DEFS / "COVERAGE.md"
TS = str(REPO)
LTS = lws_test_suite()
if LTS is None:
    print("COVERAGE.md maps lws-test-suite's tests, so it needs a checkout: set LWS_TEST_SUITE (README.md)")
    sys.exit(1)

# What changed relative to lws-test-suite, per mirrored test (hand-written, reviewed against the WD).
LTS_NOTES = {
    "discovery-unauthorized-response-headers": "Split. The 401 challenge (MUST) is getContainer-private-unauthorized; the Link on the 401 became SHOULD, with rel lws#storage replacing storageDescription.",
    "discovery-storage-description": "application/lws+cid instead of lws+json. The URL comes from the lws#storage link, not /alice/description. Asserts the WD data model (CID @context array, StorageRoot service) instead of an exact body.",
    "discovery-get-links-storageDescription": "Asserts rel lws#storage to the storage URI, on GET and HEAD.",
    "getContainer": "Owner read: ETag, linkset/up/type/storage links, container properties. The public-read half is getContainer-public-read, via an access grant.",
    "getContainer-private-unauthorized": "Challenge parsed per RFC 9110 (any order, token or quoted values) and scheme Bearer checked.",
    "getContainer-authenticated-owner": "Essentially unchanged.",
    "createDataResource": "No Slug-derived Location, .meta URL or Content-Length 47; Location and linkset are read from the response. rel=type (a SHOULD) is split out.",
    "createDataResource-unauthorized": "Also checks that nothing was created.",
    "readDataResource": "Adds ETag, rel=type and the storage link; the body is byte-exact against a fixture whose size is measured, not assumed. The public half is readDataResource-public-read.",
    "updateDataResource": "Accepts 200 or 204, sends If-Match, verifies by reading back; no Link assertion on the PUT response.",
    "deleteDataResource": "204 (the WD's MUST) instead of 200; checks the resource is gone and delisted.",
    "deleteDataResource-unauthorized": "Also checks that the resource survives.",
    "getLinkset": "Follows rel=linkset instead of GETting /.meta; checks media type, ETag and linkset shape.",
    "getContainer-containmentIntegrity": "Matches the listing by content (some), not against a fixture listing ideas/ that no test created.",
    "authz-server-metadata-well-known": "AS discovered from the 401 challenge; asserts RFC 8414 members instead of an exact fixture carrying the id-token typo.",
    "authz-token-exchange-valid": "Uses a did:key credential with the jwt token type; asserts RFC 6749/8693 response members (issued_token_type, no-store) and the RFC 9068 access token claims.",
    "authz-token-exchange-invalid-resource": "Accepts invalid_target (RFC 8693's SHOULD) or invalid_request.",
    "authz-expired-token-rejected": "Identity alice-expired instead of an Authorization header alongside alice's own credentials.",
    "createContainer": "No body and no Content-Type (the WD's example); the name is read from Location.",
    "authn-didkey-valid-credential": "W3C discontinued the did:key suite (29 September 2026) in favour of the CID suite, so this is a CID test with a did:key subject: token type jwt (the CID suite's MUST) instead of id_token, and a kid naming the DID document's verification method; checks the access token's sub.",
    "authn-didkey-invalid-signature": "A CID test with a did:key subject, as above; token type jwt, so the refusal is for the signature.",
    "authn-didkey-missing-credential": "Moved to core as authz-token-exchange-missing-subject-token (suite-independent).",
    "authn-oidc-valid-id-token": "The harness hosts the subject's CID document and its own OP, so the trust path is exercised end to end.",
    "authn-oidc-expired-id-token": "As above, with an expired ID Token.",
    "authn-oidc-server-metadata-discovery": "Duplicate of the anonymous-401 test; folded into getContainer-private-unauthorized.",
    "authn-saml-valid-assertion": "Gated on SamlTrust; the fixture paths that were broken are gone.",
    "authn-saml-invalid-signature": "As above.",
}
TS_NOTES = {
    "core/patch-merge-patch-baseline": "None. The 5 October 2026 draft made JSON Patch the baseline patch format in place of JSON Merge Patch; `core/data_resources#patch-json-patch-baseline` tests the new one.",
    "core/put-unconditional-428": "None. The 21 September 2026 draft removed the 428 MUST (\"Clients SHOULD use conditional requests\"), so this test failed conforming servers.",
}


def load():
    """Every entry, server tests and client rules (ObservationTest) alike, in file order."""
    tests = []
    for path in sorted(glob.glob(os.path.join(OUT, "**", "*.json"), recursive=True)):
        rel = os.path.relpath(path, OUT).replace("\\", "/")
        doc = json.load(open(path, encoding="utf-8"))
        if doc.get("type") != "Manifest":
            continue
        for t in doc.get("entries", []):
            m = t.get("mirrors", [])
            t["_mirrors"] = [m] if isinstance(m, str) else m
            t["_manifest"] = rel[: -len(".json")]
            tests.append(t)
    return tests


def load_catalog():
    T = rdflib.Namespace("https://example.org/touchstone/vocab#")
    g = rdflib.Graph()
    for path in sorted(glob.glob(os.path.join(TS, "catalog", "*.ttl"))):
        g.parse(path, format="turtle")
    out = {}
    for s in g.subjects(rdflib.RDF.type, T.Requirement):
        out[str(s)] = {
            "level": str(g.value(s, T.level)),
            "module": str(g.value(s, T.specModule)),
            "summary": str(g.value(s, T.summary) or ""),
            "roles": sorted((str(o).rsplit("#", 1)[1] for o in g.objects(s, T.appliesTo)), key=role_rank),
        }
    return out


ROLES = ["Server", "AuthorizationServer", "Client", "IdentityProvider", "Receiver", "Specification"]


def role_rank(r):
    return ROLES.index(r) if r in ROLES else len(ROLES)


entries = load()
tests = [t for t in entries if t["type"] != "ObservationTest"]
rules = [t for t in entries if t["type"] == "ObservationTest"]
catalog = load_catalog()
by_name = {t["name"]: t for t in tests}
levels = Counter(t["level"] for t in tests)
types = Counter(t["type"] for t in tests)
cited = {r for t in tests for r in t.get("requirements", [])}

lts_order = []
for rel, path in lts_manifests(LTS):
    for e in json.load(open(path, encoding="utf-8"))["@graph"][0]["entries"]:
        lts_order.append((f"{rel}#{e['name']}", e["name"]))
mirrors_of = defaultdict(list)
for t in tests:
    for m in t["_mirrors"]:
        mirrors_of[m].append(t)
ts_manifests = sorted(retired_manifests())
superseded_by = defaultdict(list)
for t in tests:
    for s in t.get("supersedes", []):
        superseded_by[s].append(t)


def link(t):
    return f"`{t['_manifest']}#{t['name']}`"


L = []
L.append("# Coverage of the LWS 1.0 test definitions")
L.append("")
L.append("Generated from the definitions; do not edit by hand. Baseline: LWS Protocol 1.0 WD")
L.append("2026-09-21, the OpenID Connect and SAML suites of 2026-08-03, and the CID suite of")
L.append("2026-09-21. The did:key suite was discontinued on 2026-09-29; its tests run under the CID suite.")
L.append("")
L.append("## Summary")
L.append("")
L.append(f"- **{len(tests)} tests**: {levels['MUST']} MUST, {levels['SHOULD']} SHOULD, {levels['MAY']} MAY; "
         f"{types['ValidationTest']} validation tests, {types['NegativeTest']} negative tests.")
server_side = {i for i, r in catalog.items() if {"Server", "AuthorizationServer"} & set(r["roles"])}
rule_levels = Counter(t["level"] for t in rules)
client_side_ids = {i for i, r in catalog.items() if {"Client", "Receiver"} & set(r["roles"])}
judged = {r for t in rules for r in t.get("requirements", [])}
L.append(f"- **{len(rules)} client rules** (`clients/`, judged by client sessions; OBSERVATION.md): "
         f"{rule_levels['MUST']} MUST, {rule_levels['SHOULD']} SHOULD, {rule_levels['MAY']} MAY. They cite "
         f"{len(judged & client_side_ids)} of the {len(client_side_ids)} requirements that bind a client or a receiver (sections 4 and 5).")
L.append(f"- **{len(cited)} catalog requirements** cited, {len(cited & server_side)} of the {len(server_side)} that bind a "
         "server or an authorization server (section 4). For comparison, the retired `manifests/` covered 48 of 232.")
covered = sum(1 for k, _ in lts_order if k in mirrors_of)
L.append(f"- **lws-test-suite:** all {covered} of {len(lts_order)} tests are accounted for "
         "(table 1). The definitions change what those tests assert wherever it contradicts the "
         "5 October draft.")
sup = sum(1 for m in ts_manifests if m in superseded_by)
L.append(f"- **manifests/ (retired, D-0055):** {sup} of its {len(ts_manifests)} tests have a successor; the "
         f"other {len(ts_manifests) - sup} were dropped because the specification no longer says what "
         "they tested (table 2).")
L.append("")
L.append("| Module | Tests | MUST | SHOULD | MAY |")
L.append("|---|---:|---:|---:|---:|")
mods = defaultdict(Counter)
for t in tests:
    mods[t["_manifest"]][t["level"]] += 1
for m in sorted(mods):
    c = mods[m]
    L.append(f"| `{m}` | {sum(c.values())} | {c['MUST']} | {c['SHOULD']} | {c['MAY']} |")
L.append("")
L.append("## 1. lws-test-suite → definitions")
L.append("")
L.append("| lws-test-suite test | Definition(s) | What changed |")
L.append("|---|---|---|")
for key, name in lts_order:
    defs = ", ".join(link(t) for t in mirrors_of.get(key, []))
    L.append(f"| `{name}` | {defs} | {LTS_NOTES.get(name, '')} |")
L.append("")
L.append("Definitions with no lws-test-suite counterpart extend it. That is every test in table 3 with an empty *Mirrors* column.")
L.append("")
L.append("## 2. Retired manifests/ → definitions")
L.append("")
L.append("`touchstone run` executed these until the YAML-LD engine replaced them (D-0055).")
L.append("")
L.append("| Retired manifest | Superseded by |")
L.append("|---|---|")
for m in ts_manifests:
    defs = ", ".join(link(t) for t in superseded_by.get(m, [])) or TS_NOTES.get(m, "")
    L.append(f"| `{m}` | {defs} |")
L.append("")
L.append("## 3. All definitions")
L.append("")
for m in sorted(mods):
    L.append(f"### `{m}`")
    L.append("")
    L.append("| Test | Type | Level | Requires | Mirrors |")
    L.append("|---|---|---|---|---|")
    for t in tests:
        if t["_manifest"] != m:
            continue
        req = ", ".join(t.get("requires", []))
        mir = ", ".join(x.split("#")[1] for x in t["_mirrors"])
        L.append(f"| `{t['name']}` | {t['type'].replace('Test', '')} | {t['level']} | {req} | {mir} |")
    L.append("")
L.append("## 4. Requirements by role")
L.append("")
L.append("Each catalog requirement names the roles it binds (`touchstone:appliesTo`, D-0076). One that binds")
L.append("several roles is counted in each. Server runs answer for the Server and AuthorizationServer rows;")
L.append("client sessions ([CLIENT-TESTING.md](../CLIENT-TESTING.md)) answer for the Client and Receiver rows.")
L.append("")
L.append("| Role | Requirements | MUST | SHOULD | MAY | Cited by a test | Cited by a client rule |")
L.append("|---|---:|---:|---:|---:|---:|---:|")
for role in ROLES:
    ids = [i for i, r in catalog.items() if role in r["roles"]]
    lv = Counter(catalog[i]["level"] for i in ids)
    L.append(f"| {role} | {len(ids)} | {lv['MUST']} | {lv['SHOULD']} | {lv['MAY']} | {sum(1 for i in ids if i in cited)} "
             f"| {sum(1 for i in ids if i in judged)} |")
L.append("")
L.append("### Client and receiver requirements")
L.append("")
L.append("What a client session can judge: the inventory of CLIENT-TESTING.md section 11. *Also binds*")
L.append("names the other roles of a clause that binds more than one; *Cited by* names the server tests that")
L.append("cite it, as a premise or for its server half; *Judged by* names the client rules that cite it.")
L.append("")
L.append("| Requirement | Level | Also binds | Summary | Cited by | Judged by |")
L.append("|---|---|---|---|---|---|")
citing = defaultdict(list)
for t in tests:
    for r in t.get("requirements", []):
        citing[r].append(t["name"])
judging = defaultdict(list)
for t in rules:
    for r in t.get("requirements", []):
        judging[r].append(t["name"])
client_side = sorted((i for i, r in catalog.items() if {"Client", "Receiver"} & set(r["roles"])),
                     key=lambda i: (catalog[i]["module"], i))
for i in client_side:
    r = catalog[i]
    also = ", ".join(x for x in r["roles"] if x not in ("Client", "Receiver"))
    by = ", ".join(f"`{n}`" for n in sorted(citing.get(i, [])))
    rule_names = ", ".join(f"`{n}`" for n in sorted(judging.get(i, [])))
    summary = r["summary"].replace("|", "\\|")
    L.append(f"| `{r['module']}/{i.rsplit('/', 1)[1]}` | {r['level']} | {also} | {summary} | {by} | {rule_names} |")
L.append("")
L.append("## 5. Client rules")
L.append("")
L.append("Each rule judges the exchanges an LWS client sends to a client session (definitions/OBSERVATION.md);")
L.append("*Area* is what a developer may declare out of scope.")
L.append("")
rule_mods = []
for t in rules:
    if t["_manifest"] not in rule_mods:
        rule_mods.append(t["_manifest"])
for m in rule_mods:
    L.append(f"### `{m}`")
    L.append("")
    L.append("| Rule | Level | Area | Requirements |")
    L.append("|---|---|---|---|")
    for t in rules:
        if t["_manifest"] != m:
            continue
        reqs = ", ".join(f"`{x.rsplit('/', 1)[1]}`" for x in t.get("requirements", []))
        L.append(f"| `{t['name']}` | {t['level']} | {t['area']} | {reqs} |")
    L.append("")
text = "\n".join(L).rstrip() + "\n"
if "--check" in sys.argv:
    current = DST.read_text(encoding="utf-8") if DST.exists() else ""
    if current != text:
        print(f"COVERAGE.md is out of date: run python {__file__} and commit it")
        sys.exit(1)
    print("COVERAGE.md is up to date")
else:
    with open(DST, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
print(f"COVERAGE.md: {len(tests)} tests, {len(rules)} client rules, {len(lts_order)} lws-test-suite rows, {len(ts_manifests)} manifest rows")
missing_notes = [n for _, n in lts_order if n not in LTS_NOTES]
print("lws-test-suite tests without a note:", missing_notes or "none")
