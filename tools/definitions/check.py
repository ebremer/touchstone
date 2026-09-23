"""Run every check on definitions/, in order (definitions/README.md, "Validating").

    python tools/definitions/check.py            check everything; exit 1 if anything fails
    python tools/definitions/check.py --write    also regenerate vocab.yamlld and COVERAGE.md

Needs Node with this directory's packages (npm ci) and the Python packages in
requirements.txt. The lws-test-suite checks need a checkout of that repository (README.md).
"""
import subprocess
import sys
import time

from _paths import HERE

WRITE = "--write" in sys.argv
GEN = [] if WRITE else ["--check"]
STEPS = [
    ("YAML 1.2 parse and JSON-LD in safe mode", ["node", "validate_ld.js"]),
    ("YAML 1.1 reads the same", [sys.executable, "portable_yaml.py"]),
    ("JSON Schema and negative controls", ["node", "validate_schema.js"]),
    ("lint", [sys.executable, "lint_definitions.py"]),
    ("vocab.yamlld " + ("regenerated" if WRITE else "is current"), [sys.executable, "gen_vocab.py", *GEN]),
    ("COVERAGE.md " + ("regenerated" if WRITE else "is current"), [sys.executable, "gen_coverage.py", *GEN]),
    ("JSON-LD export loses nothing", ["node", "export_dryrun.js"]),
]

failed, ran = [], 0
for i, (title, cmd) in enumerate(STEPS, 1):
    print(f"\n=== {i}. {title}", flush=True)
    start = time.monotonic()
    code = subprocess.run(cmd, cwd=HERE).returncode
    ran += 1
    print(f"--- {'ok' if code == 0 else 'FAILED'} ({time.monotonic() - start:.1f} s)", flush=True)
    if code != 0:
        failed.append(title)
        if i == 1:
            print("The other checks read the JSON this step writes, so they cannot run.")
            break
print(f"\n{ran} of {len(STEPS)} checks run, {len(failed)} failed{': ' + '; '.join(failed) if failed else ''}")
sys.exit(1 if failed else 0)
