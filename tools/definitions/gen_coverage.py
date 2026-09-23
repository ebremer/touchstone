"""Check 6 of definitions/README.md, "Validating": the coverage report.

Generates definitions/COVERAGE.md from the JSON form of the definitions (build/json/,
written by validate_ld.js). It maps every lws-test-suite test and every retired manifests/ test
to its counterparts, and lists every definition by module. It needs a lws-test-suite checkout
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
    "authn-didkey-valid-credential": "Token type jwt (the suite's MUST) instead of id_token; checks the access token's sub.",
    "authn-didkey-invalid-signature": "Token type jwt, so the refusal is for the signature.",
    "authn-didkey-missing-credential": "Moved to core as authz-token-exchange-missing-subject-token (suite-independent).",
    "authn-oidc-valid-id-token": "The harness hosts the subject's CID document and its own OP, so the trust path is exercised end to end.",
    "authn-oidc-expired-id-token": "As above, with an expired ID Token.",
    "authn-oidc-server-metadata-discovery": "Duplicate of the anonymous-401 test; folded into getContainer-private-unauthorized.",
    "authn-saml-valid-assertion": "Gated on SamlTrust; the fixture paths that were broken are gone.",
    "authn-saml-invalid-signature": "As above.",
}
TS_NOTES = {
    "core/put-unconditional-428": "None. The 21 September 2026 draft removed the 428 MUST (\"Clients SHOULD use conditional requests\"), so this test failed conforming servers.",
}


def load():
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


tests = load()
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
L.append("2026-09-21, the did:key, OpenID Connect and SAML suites of 2026-08-03, and the CID suite")
L.append("of 2026-09-21.")
L.append("")
L.append("## Summary")
L.append("")
L.append(f"- **{len(tests)} tests**: {levels['MUST']} MUST, {levels['SHOULD']} SHOULD, {levels['MAY']} MAY; "
         f"{types['ValidationTest']} validation tests, {types['NegativeTest']} negative tests.")
L.append(f"- **{len(cited)} catalog requirements** cited. For comparison, the retired `manifests/` covered 48 of 232.")
covered = sum(1 for k, _ in lts_order if k in mirrors_of)
L.append(f"- **lws-test-suite:** all {covered} of {len(lts_order)} tests are accounted for "
         "(table 1). The definitions change what those tests assert wherever it contradicts the "
         "21 September draft.")
sup = sum(1 for m in ts_manifests if m in superseded_by)
L.append(f"- **manifests/ (retired, D-0055):** {sup} of its {len(ts_manifests)} tests have a successor; the "
         "other one was dropped because its clause left the specification (table 2).")
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
print(f"COVERAGE.md: {len(tests)} tests, {len(lts_order)} lws-test-suite rows, {len(ts_manifests)} manifest rows")
missing_notes = [n for _, n in lts_order if n not in LTS_NOTES]
print("lws-test-suite tests without a note:", missing_notes or "none")
