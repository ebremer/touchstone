"""Checks the starter kits in driver/kits/ against the frozen driver protocol (CLIENT-TESTING.md section 14.4).

Every kit has a README.md, and its sources name every operation of driver/protocol.schema.json, in the
kit's own case (discover_storage, discoverStorage, DiscoverStorage). So when touchstone-driver/1 gains an
operation, the check fails until every kit has a method for it. Whether a kit's stub speaks the protocol is
what `touchstone driver check` says, in the kits' CI job.

    python tools/driver/check_kits.py
"""
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
KITS = REPO / "driver" / "kits"
LANGUAGES = ["java", "kotlin", "js", "python", "go", "rust", "csharp", "swift", "php", "cpp"]
SOURCES = {".java", ".kt", ".mjs", ".js", ".ts", ".py", ".go", ".rs", ".cs", ".swift", ".php", ".hpp", ".h", ".cpp", ".cc"}
SKIP = {"target", "build", "bin", "obj", ".build", "node_modules", "__pycache__", "vendor"}

operations = json.loads((REPO / "driver" / "protocol.schema.json").read_text(encoding="utf-8"))["$defs"]["operation"]["enum"]
problems = []
for language in LANGUAGES:
    kit = KITS / language
    if not kit.is_dir():
        problems.append(f"{language}: no kit at driver/kits/{language}")
        continue
    if not (kit / "README.md").is_file():
        problems.append(f"{language}: no README.md")
    text = ""
    for path in kit.rglob("*"):
        if path.is_file() and path.suffix in SOURCES and not SKIP & set(path.relative_to(kit).parts):
            text += path.read_text(encoding="utf-8", errors="replace")
    folded = text.lower().replace("_", "")
    missing = [op for op in operations if op != "shutdown" and op.replace("_", "") not in folded]
    if missing:
        problems.append(f"{language}: names no {', '.join(missing)}")
extra = sorted(p.name for p in KITS.iterdir() if p.is_dir() and p.name not in LANGUAGES)
if extra:
    problems.append(f"kits for no listed language: {', '.join(extra)}")
for p in problems:
    print("ERROR", p)
print(f"{len(LANGUAGES)} kits checked against {len(operations)} operations, {len(problems)} problem(s)")
sys.exit(1 if problems else 0)
