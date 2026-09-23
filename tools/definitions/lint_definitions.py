"""Check 4 of definitions/README.md, "Validating": the lint of EXECUTION.md section 2.5.

Reads the JSON form of the definitions (build/json/, written by validate_ld.js) and checks
what the schema cannot:
- test names are unique;
- variables are bound, by a prerequisite or an earlier capture;
- identities exist, and grants name agents other than alice;
- catalog IRIs exist and none has drifted;
- every `source` anchor exists in its dated snapshot (anchors.json);
- fixtures exist, and no executable value names an example host;
- `mirrors` and `supersedes` name real lws-test-suite tests and retired manifests/ tests.
"""
import glob
import json
import os
import re
import sys
from collections import Counter, defaultdict

import rdflib

from _paths import ANCHORS, JSON_OUT, LWS10, REPO, lts_manifests, lws_test_suite, retired_manifests

OUT = str(JSON_OUT)
DEF = str(LWS10)
TS = str(REPO)

errors, warnings = [], []
E = errors.append
W = warnings.append

# Section anchors of each dated snapshot a `source` may cite; fetch_anchors.py refreshes them.
anchors = {url: set(ids) for url, ids in json.load(open(ANCHORS, encoding="utf-8")).items()}
# Catalog clauses whose 21 Aug text changed substantively in the 21 Sep draft: never cite.
DRIFTED = {"put-unconditional-rejected-428", "etag-on-get-head-conditional-304",
           "delete-updates-parent-listing-etag", "update-success-new-etag",
           "status-201-etag-link-headers", "linkset-if-match-412-428", "linkset-concurrency-controls"}

g = rdflib.Graph()
for f in glob.glob(os.path.join(TS, "catalog", "*.ttl")):
    g.parse(f, format="turtle")
T = rdflib.Namespace("https://example.org/touchstone/vocab#")
catalog = {str(s) for s in g.subjects(rdflib.RDF.type, T.Requirement)}

# manifests/ is retired (D-0055); retired-manifests.txt keeps the ids supersedes may name.
ts_manifests = set(retired_manifests())
# `mirrors` is checked against lws-test-suite's own files when a checkout is available.
LTS = lws_test_suite()
lts_tests = None
if LTS is not None:
    lts_tests = {}
    for rel, path in lts_manifests(LTS):
        for e in json.load(open(path, encoding="utf-8"))["@graph"][0]["entries"]:
            lts_tests[f"{rel}#{e['name']}"] = e["name"]

reg = json.load(open(os.path.join(OUT, "identities.json"), encoding="utf-8"))
identities = {i["id"][1:]: i for i in reg["identities"]}
for name, i in identities.items():
    if "basis" in i and i["basis"] not in identities:
        E(f"identities: {name} basis {i['basis']} unknown")
    if "basis" in i and identities[i["basis"]]["kind"] != i["kind"]:
        E(f"identities: {name} kind differs from its basis")

BUILTIN = {"target.baseUrl", "run.root", "test.container", "uuid", "now", "storage",
           "as.uri", "as.realm", "as.metadataUrl", "as.issuer", "as.tokenEndpoint", "as.jwksUri"}
SERVICES = {"AccessGrantService", "AccessRequestService", "NotificationService"}
VAR = re.compile(r"\$\{([^}]*)\}")
# RFC 2606 / RFC 6761 example domains: a value naming one can never match a live server.
EXAMPLE_HOST = re.compile(r"https?://([^/:?#\"'\s]+)", re.I)


def example_host(host):
    h = host.lower().rstrip(".")
    return h == "example" or h.endswith(".example") or any(
        h == d or h.endswith("." + d) for d in ("example.com", "example.net", "example.org"))


def strings(node):
    if isinstance(node, str):
        yield node
    elif isinstance(node, dict):
        for v in node.values():
            yield from strings(v)
    elif isinstance(node, list):
        for v in node:
            yield from strings(v)


def captures_of(resp):
    caps = []
    def walk(n):
        if isinstance(n, dict):
            if "capture" in n:
                caps.append(n["capture"])
            for k, v in n.items():
                if k not in ("bodyJSON", "bodyForm"):
                    walk(v)
        elif isinstance(n, list):
            for v in n:
                walk(v)
    walk({k: v for k, v in resp.items() if k != "jwt"})
    return caps


names = Counter()
levels = Counter()
per_manifest = {}
mirrored = defaultdict(list)
superseded = defaultdict(list)
cited = Counter()
total = 0
for path in sorted(glob.glob(os.path.join(OUT, "**", "*.json"), recursive=True)):
    rel = os.path.relpath(path, OUT).replace("\\", "/")
    doc = json.load(open(path, encoding="utf-8"))
    if doc.get("type") != "Manifest":
        continue
    src_dir = os.path.dirname(os.path.join(DEF, rel))
    for inc in doc.get("include", []):
        if not os.path.isfile(os.path.join(src_dir, inc)):
            E(f"{rel}: include {inc} does not exist")
    entries = doc.get("entries", [])
    per_manifest[rel] = len(entries)
    for t in entries:
        total += 1
        n = t.get("name", "?")
        where = f"{rel}#{n}"
        names[n] += 1
        levels[t.get("level")] += 1
        if t.get("id") != "#" + n:
            E(f"{where}: id {t.get('id')} != #name")
        for s in t.get("source", []):
            base, _, frag = s.partition("#")
            base += "/" if not base.endswith("/") else ""
            if s.startswith("https://www.rfc-editor.org/rfc/"):
                if not re.match(r"^https://www\.rfc-editor\.org/rfc/rfc\d+#section-[\d.]+$", s):
                    E(f"{where}: malformed RFC source {s}")
            elif base in anchors:
                if frag not in anchors[base]:
                    E(f"{where}: anchor #{frag} not in {base}")
            else:
                E(f"{where}: source {s} is not an RFC or a dated snapshot in anchors.json (run fetch_anchors.py)")
        for r in t.get("requirements", []):
            cited[r] += 1
            if r not in catalog:
                E(f"{where}: requirement {r} not in catalog")
            if r.rsplit("/", 1)[-1] in DRIFTED:
                E(f"{where}: cites drifted requirement {r}")
        ms = t.get("mirrors", [])
        for m in ([ms] if isinstance(ms, str) else ms):
            if lts_tests is not None and m not in lts_tests:
                E(f"{where}: mirrors unknown lws-test-suite test {m}")
            mirrored[m].append(n)
        for s in t.get("supersedes", []):
            if s not in ts_manifests:
                E(f"{where}: supersedes {s}, which is not a retired manifests/ test")
            superseded[s].append(n)
        test_as = t.get("as")
        reqs = set(t.get("requires", []))
        bound = set()
        uses_as_vars = False
        has_4xx = False
        if ("steps" in t) == ("request" in t):
            E(f"{where}: needs exactly one of steps or request/response")
        steps = t["steps"] if "steps" in t else [{"label": t.get("label"), "request": t.get("request", {}), "response": t.get("response", {})}]
        # executable values must not name example hosts (requirements, mirrors, labels and comments are prose or touchstone-only)
        for key in ("prereqs", "steps", "request", "response"):
            for sv in strings(t.get(key, {})):
                for host in EXAMPLE_HOST.findall(sv):
                    if example_host(host):
                        E(f"{where}: {key} names the example host {host}, which no live server can match")
        containers = set()
        for j, e in enumerate((t.get("prereqs") or {}).get("hierarchy", [])):
            pw = f"{where} prereq {j + 1}"
            kind = "container" if "container" in e else "dataResource"
            cap = e.get(kind)
            if cap in bound or cap in BUILTIN:
                E(f"{pw}: {kind} {cap} rebinds an existing variable")
            parent = e.get("in")
            if parent is not None and parent not in containers:
                E(f"{pw}: in {parent} is not an earlier container entry")
            if "bodyURL" in e and not os.path.isfile(os.path.join(src_dir, e["bodyURL"])):
                E(f"{pw}: bodyURL missing {e['bodyURL']}")
            for sv in strings({k: v for k, v in e.items() if k in ("body", "bodyJSON")}):
                for v in VAR.findall(sv):
                    if not (v in BUILTIN or v in bound):
                        E(f"{pw}: unbound variable ${{{v}}} in content")
            auth = e.get("authorization", {})
            if auth and "Authentication" not in reqs:
                E(f"{pw}: grants access without requires Authentication (grants mean nothing on an open target)")
            for act, whos in auth.items():
                for who in whos:
                    if who not in identities:
                        E(f"{pw}: {act} granted to unknown identity {who}")
                    elif identities[who]["kind"] not in ("NoCredential", "StorageAccessToken") or "fault" in identities[who]:
                        E(f"{pw}: {act} granted to {who}, which is not an agent a grant can name")
                    elif who == "alice":
                        E(f"{pw}: {act} granted to alice, who creates the resource and needs no grant")
            if e.get("absent") is not True and kind == "container":
                containers.add(cap)
            bound.add(cap)
        for i, st in enumerate(steps):
            sw = f"{where} step {i + 1}"
            ident = st.get("as", test_as) or "alice"
            if ident not in identities:
                E(f"{sw}: as {ident} unknown")
            elif identities[ident]["kind"] == "SubjectCredential":
                E(f"{sw}: as {ident} is a SubjectCredential")
            if ident in ("bob",) or ident.startswith("alice-"):
                if "Authentication" not in reqs:
                    E(f"{sw}: uses {ident} without requires Authentication")
            req, resp = st.get("request", {}), st.get("response", {})
            hdrs = {h["headerName"].lower() for h in req.get("otherHeaders", [])}
            if "authorization" in hdrs and ident != "anonymous":
                E(f"{sw}: explicit Authorization header needs as: anonymous")
            if "ifMatch" in req and req.get("method") not in ("PUT", "PATCH", "DELETE", "POST"):
                E(f"{sw}: ifMatch on {req.get('method')}")
            if "bodyURL" in req and not os.path.isfile(os.path.join(src_dir, req["bodyURL"])):
                E(f"{sw}: request bodyURL missing {req['bodyURL']}")
            if "bodyURL" in resp and not os.path.isfile(os.path.join(src_dir, resp["bodyURL"])):
                E(f"{sw}: response bodyURL missing {resp['bodyURL']}")
            sc = resp.get("statusCode")
            scs = sc if isinstance(sc, list) else [sc]
            if scs and all((isinstance(x, int) and 400 <= x < 500) or x == "4xx" for x in scs):
                has_4xx = True
            here = set(captures_of(resp))
            jwt_scope = bound | here
            for label, scope, node in (("request", bound, req), ("response", bound, {k: v for k, v in resp.items() if k != "jwt"}),
                                       ("jwt", jwt_scope, resp.get("jwt", {}))):
                for s in strings(node):
                    for v in VAR.findall(s):
                        if v in BUILTIN or v in scope:
                            if v.startswith("as."):
                                uses_as_vars = True
                            continue
                        m = re.match(r"^service\.([A-Za-z]+)$", v)
                        if m:
                            if m.group(1) not in SERVICES:
                                E(f"{sw}: unknown service {v}")
                            continue
                        m = re.match(r"^identity\.([a-z0-9-]+)\.webid$", v)
                        if m:
                            if m.group(1) not in identities:
                                E(f"{sw}: unknown identity in {v}")
                            continue
                        m = re.match(r"^credential\.([a-z0-9-]+)$", v)
                        if m:
                            nm = m.group(1)
                            if nm not in identities:
                                E(f"{sw}: unknown credential identity {v}")
                            elif identities[nm]["kind"] != "SubjectCredential":
                                E(f"{sw}: {v} is not a SubjectCredential")
                            else:
                                need = set(identities[nm].get("requires", [])) | set(identities.get(identities[nm].get("basis", ""), {}).get("requires", []))
                                if not need <= reqs:
                                    E(f"{sw}: {v} needs requires {sorted(need)}")
                            continue
                        E(f"{sw}: unbound variable ${{{v}}} in {label}")
            for c in here:
                if c in bound:
                    E(f"{sw}: capture {c} rebinds an existing variable")
            bound |= here
        if uses_as_vars and "Authentication" not in reqs:
            E(f"{where}: uses ${{as.*}} without requires Authentication")
        if t.get("type") == "NegativeTest" and not has_4xx:
            E(f"{where}: NegativeTest with no step expecting only 4xx")
        if t.get("type") == "ValidationTest" and has_4xx and t.get("level") == "MUST":
            W(f"{where}: ValidationTest with a 4xx-only step (fine if it is a check, not the point)")

for n, c in names.items():
    if c > 1:
        E(f"test name {n} used {c} times")
unmirrored = sorted(set(lts_tests) - set(mirrored)) if lts_tests is not None else None
unsuperseded = sorted(ts_manifests - set(superseded))

print(f"tests: {total} in {len(per_manifest)} manifests; levels {dict(levels)}")
for m, c in per_manifest.items():
    print(f"   {m:40s} {c}")
print(f"catalog requirements cited: {len(cited)} distinct")
if lts_tests is None:
    print("lws-test-suite: no checkout found, so mirrors were not checked (set LWS_TEST_SUITE; see README.md)")
else:
    print(f"lws-test-suite tests mirrored: {len(set(lts_tests) & set(mirrored))}/{len(lts_tests)}; not mirrored: {unmirrored}")
print(f"retired manifests superseded: {len(ts_manifests & set(superseded))}/{len(ts_manifests)}; not superseded: {unsuperseded}")
for w in warnings:
    print("WARN ", w)
for e in errors:
    print("ERROR", e)
print(f"{len(errors)} error(s), {len(warnings)} warning(s)")
sys.exit(1 if errors else 0)
