"""Extract BCP14 clause candidate blocks from a W3C ReSpec-rendered spec HTML file.

Usage: python extract_clauses.py <spec.html> <out.json>

Emits one JSON entry per text block (p/li/dt/dd/td/th) that either contains
ReSpec rfc2119 keyword markup or matches a BCP14 keyword regex. Each entry
records the enclosing section id stack, heading, and normalized text.
"""
import json
import re
import sys
import unicodedata
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path

KW = re.compile(
    r"\b(MUST NOT|MUST|SHALL NOT|SHALL|SHOULD NOT|SHOULD|MAY|"
    r"NOT RECOMMENDED|RECOMMENDED|REQUIRED|OPTIONAL)\b"
)
BLOCKS = {"p", "li", "dt", "dd", "td", "th"}
HEADINGS = {"h1", "h2", "h3", "h4", "h5", "h6"}
SKIP = {"style", "script", "svg", "title"}
VOID = {"br", "hr", "img", "meta", "link", "input", "area", "base",
        "col", "embed", "source", "track", "wbr"}


def norm(s: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFC", s)).strip()


class Spec(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.elems = []       # stack of (tag, is_note, is_skip)
        self.note_depth = 0
        self.skip_depth = 0
        self.sections = []    # stack of {"id":..., "heading":...}
        self.blocks = []      # stack of {"tag":..., "parts":[], "norm":bool}
        self.heading = None
        self.out = []
        self.idx = 0

    @staticmethod
    def attr(attrs, name):
        for k, v in attrs:
            if k == name:
                return v or ""
        return ""

    def handle_starttag(self, tag, attrs):
        if tag in VOID:
            return
        cls = self.attr(attrs, "class")
        is_skip = tag in SKIP
        is_note = tag == "aside" or (
            tag == "div" and re.search(r"\b(note|example|issue)\b", cls)
        )
        self.elems.append((tag, is_note, is_skip))
        if is_skip:
            self.skip_depth += 1
        if is_note:
            self.note_depth += 1
        if self.skip_depth:
            return
        if tag == "section":
            self.sections.append({"id": self.attr(attrs, "id"), "heading": None})
        elif tag in HEADINGS:
            self.heading = []
        elif tag in BLOCKS:
            self.blocks.append({"tag": tag, "parts": [], "norm": False})
        elif tag == "em" and "rfc2119" in cls and self.blocks:
            self.blocks[-1]["norm"] = True

    def handle_startendtag(self, tag, attrs):
        pass

    def handle_endtag(self, tag):
        if tag in VOID:
            return
        # pop until matching tag (tolerate stray end tags)
        while self.elems:
            t, is_note, is_skip = self.elems.pop()
            if is_note:
                self.note_depth = max(0, self.note_depth - 1)
            if is_skip:
                self.skip_depth = max(0, self.skip_depth - 1)
            if t == tag:
                break
        if tag in HEADINGS and self.heading is not None:
            text = norm("".join(self.heading))
            self.heading = None
            if self.sections and self.sections[-1]["heading"] is None:
                self.sections[-1]["heading"] = text
        elif tag in BLOCKS and self.blocks:
            b = self.blocks.pop()
            text = norm("".join(b["parts"]))
            if text and (b["norm"] or KW.search(text)):
                self.idx += 1
                self.out.append({
                    "i": self.idx,
                    "section": self.sections[-1]["id"] if self.sections else "",
                    "heading": self.sections[-1]["heading"] if self.sections else "",
                    "path": [s["id"] for s in self.sections],
                    "tag": b["tag"],
                    "rfc2119_markup": b["norm"],
                    "in_note": self.note_depth > 0,
                    "keywords": sorted(set(m for m in KW.findall(text))),
                    "text": text,
                })
        elif tag == "section" and self.sections:
            self.sections.pop()

    def handle_data(self, data):
        if self.skip_depth:
            return
        if self.heading is not None:
            self.heading.append(data)
        if self.blocks:
            self.blocks[-1]["parts"].append(data)


def main():
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    p = Spec()
    p.feed(src.read_text(encoding="utf-8"))
    p.close()
    out.write_text(json.dumps(p.out, indent=1, ensure_ascii=False), encoding="utf-8")
    normative = sum(1 for e in p.out if e["rfc2119_markup"] and not e["in_note"])
    print(f"blocks with BCP14 keywords: {len(p.out)}; "
          f"rfc2119-marked outside notes: {normative}")
    top = Counter((e["path"][0] if e["path"] else "?") for e in p.out)
    for k, v in top.most_common():
        print(f"  {k}: {v}")


if __name__ == "__main__":
    main()
