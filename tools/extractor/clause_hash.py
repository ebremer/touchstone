"""Compute and verify clauseHash values in catalog Turtle files.

Normalization rule (DECISIONS.md D-0008):
    text -> Unicode NFC -> collapse every whitespace run to one space -> trim
    clauseHash = "sha256-" + lowercase hex of sha256(utf8(text))

Usage:
    python clause_hash.py --update catalog/lws10-core.ttl [more.ttl ...]
    python clause_hash.py --check  catalog/lws10-core.ttl [more.ttl ...]

--update fills in entries whose clauseHash is the literal "PENDING".
--check recomputes every hash and exits 1 on any mismatch — the drift alarm
that will run against spec re-fetches (Phase 1) and in CI.

The parser relies on the catalog's controlled layout: a triple-quoted
touchstone:clauseText immediately followed by touchstone:clauseHash.
"""
import hashlib
import re
import sys
import unicodedata
from pathlib import Path

PAIR = re.compile(
    r'(touchstone:clauseText\s+"""(?P<text>.*?)"""\s*;\s*'
    r'touchstone:clauseHash\s+")(?P<hash>[^"]*)(")',
    re.DOTALL,
)


def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFC", text)).strip()


def digest(text: str) -> str:
    return "sha256-" + hashlib.sha256(normalize(text).encode("utf-8")).hexdigest()


def main() -> int:
    if len(sys.argv) < 3 or sys.argv[1] not in ("--update", "--check"):
        print(__doc__)
        return 2
    mode, files = sys.argv[1], sys.argv[2:]
    mismatches = 0
    for name in files:
        path = Path(name)
        source = path.read_text(encoding="utf-8")
        updated = 0

        def replace(m: re.Match) -> str:
            nonlocal updated, mismatches
            want = digest(m.group("text"))
            have = m.group("hash")
            if mode == "--update" and have == "PENDING":
                updated += 1
                return m.group(1) + want + m.group(4)
            if mode == "--check" and have != want:
                mismatches += 1
                print(f"{name}: MISMATCH")
                print(f"  stored:   {have}")
                print(f"  computed: {want}")
                print(f"  clause:   {normalize(m.group('text'))[:110]}...")
            return m.group(0)

        result = PAIR.sub(replace, source)
        total = len(PAIR.findall(source))
        if mode == "--update":
            if updated:
                path.write_text(result, encoding="utf-8")
            print(f"{name}: {updated} of {total} hashes filled in")
        else:
            print(f"{name}: {total} clauses checked")
    return 1 if mismatches else 0


if __name__ == "__main__":
    sys.exit(main())
