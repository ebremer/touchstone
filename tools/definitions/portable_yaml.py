"""Check 1 of definitions/README.md, "Validating": the portable YAML subset.

YAML-LD requires YAML 1.2, but many libraries still implement YAML 1.1, which reads `yes`,
`on` and `2026-09-21` differently. The definitions are written so that both readings agree.
This checks that PyYAML (YAML 1.1) produces exactly the JSON that validate_ld.js wrote from
its YAML 1.2 Core Schema parse.
"""
import json
import sys

import yaml

from _paths import JSON_OUT, LWS10

bad = 0
for src in sorted(LWS10.rglob("*.yamlld")):
    rel = src.relative_to(LWS10)
    with open(src, encoding="utf-8") as fh:
        y11 = yaml.safe_load(fh)
    with open(JSON_OUT / rel.with_suffix(".json"), encoding="utf-8") as fh:
        y12 = json.load(fh)
    try:
        same = json.dumps(y11, sort_keys=True) == json.dumps(y12, sort_keys=True)
    except TypeError:  # a YAML 1.1 date or time is not JSON-serializable
        same = False
    if not same:
        bad += 1
        print(f"DIVERGES {rel.as_posix()}: YAML 1.1 and YAML 1.2 readings differ")

        def walk(a, b, p=""):
            if type(a) is not type(b):
                print(f"   {p}: 1.1={a!r} ({type(a).__name__})  1.2={b!r} ({type(b).__name__})")
                return
            if isinstance(a, dict):
                for k in set(a) | set(b):
                    walk(a.get(k), b.get(k), f"{p}/{k}")
            elif isinstance(a, list):
                for i, (x, z) in enumerate(zip(a, b)):
                    walk(x, z, f"{p}/{i}")
            elif a != b:
                print(f"   {p}: 1.1={a!r}  1.2={b!r}")

        walk(y11, y12)
print(f"portable-subset check: {bad} divergent document(s)")
sys.exit(1 if bad else 0)
