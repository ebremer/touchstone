"""Spec drift alarm: compare the catalog against a (re-)fetched spec draft.

Usage:
    python check_drift.py --spec <spec.html> <catalog.ttl> [...]

Re-extracts normative blocks from the given spec HTML and checks that every
catalog entry's clauseText still appears verbatim (normalized) inside some
block - seed entries may be trimmed sub-sentences, so containment, not
equality, is the criterion. Exits 1 when any stored clause has vanished or
changed ("spec moved" - DESIGN.md paragraph 8); uncatalogued normative blocks
are reported as info only.
"""
import re
import sys
import unicodedata
from pathlib import Path

from extract_clauses import Spec

ENTRY = re.compile(
    r"^req:([A-Za-z0-9-]+).*?touchstone:clauseText\s+\"\"\"(.*?)\"\"\"",
    re.M | re.S,
)


def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFC", text)).strip()


def main() -> int:
    args = sys.argv[1:]
    if "--spec" not in args:
        print(__doc__)
        return 2
    spec_path = Path(args[args.index("--spec") + 1])
    ttls = [Path(a) for a in args if a.endswith(".ttl")]

    parser = Spec()
    parser.feed(spec_path.read_text(encoding="utf-8"))
    parser.close()
    blocks = [(normalize(b["text"]), b["section"]) for b in parser.out]

    stored = []
    for ttl in ttls:
        stored += [(slug, normalize(text)) for slug, text in ENTRY.findall(ttl.read_text(encoding="utf-8"))]

    drifted = [(slug, text) for slug, text in stored
               if not any(text in block_text for block_text, _ in blocks)]
    covered_blocks = {i for i, (block_text, _) in enumerate(blocks)
                      if any(text in block_text for _, text in stored)}
    new = [(blocks[i][1], blocks[i][0]) for i in range(len(blocks)) if i not in covered_blocks]

    print(f"catalog entries checked: {len(stored)}; spec normative blocks: {len(blocks)}")
    if new:
        print(f"info: {len(new)} uncatalogued normative block(s):")
        for sec, text in new:
            print(f"  [{sec}] {text[:90]}...")
    if drifted:
        print(f"DRIFT: {len(drifted)} catalog clause(s) no longer present in the spec:")
        for slug, text in drifted:
            print(f"  req:{slug}")
            print(f"    {text[:100]}...")
        print("The spec moved - re-extract, re-curate, and deprecate/replace the affected entries.")
        return 1
    print("no drift: every catalog clause still appears in the spec")
    return 0


if __name__ == "__main__":
    sys.exit(main())
