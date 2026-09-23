"""Refresh anchors.json: the section anchors of every dated snapshot the definitions cite.

The lint checks that each `source` anchor exists in the snapshot it names. Snapshots are
immutable, so anchors.json only needs refreshing when a definition starts citing a new one:

    python tools/definitions/fetch_anchors.py

It collects the dated snapshot URLs from every `source` and `specification` in
definitions/lws10, fetches each from w3.org, and records the id of every section and heading
(h1 to h6). Those are the fragments a reader can link to.
"""
import json
import re
import urllib.request
from html.parser import HTMLParser

import yaml

from _paths import ANCHORS, LWS10

DATED = re.compile(r"^(https://www\.w3\.org/TR/20[0-9]{2}/WD-lws10-[a-z-]+-[0-9]{8}/)")


def cited_snapshots():
    urls = set()

    def walk(node, key=None):
        if isinstance(node, dict):
            for k, v in node.items():
                walk(v, k)
        elif isinstance(node, list):
            for v in node:
                walk(v, key)
        elif isinstance(node, str) and key in ("source", "specification"):
            m = DATED.match(node)
            if m:
                urls.add(m.group(1))

    for f in sorted(LWS10.rglob("*.yamlld")):
        with open(f, encoding="utf-8") as fh:
            walk(yaml.safe_load(fh))
    return sorted(urls)


class SectionIds(HTMLParser):
    TAGS = {"section", "h1", "h2", "h3", "h4", "h5", "h6"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.ids = set()

    def handle_starttag(self, tag, attrs):
        if tag in self.TAGS:
            ident = dict(attrs).get("id")
            if ident:
                self.ids.add(ident)


def main():
    out = {}
    for url in cited_snapshots():
        req = urllib.request.Request(url, headers={"User-Agent": "touchstone-definitions-checks"})
        with urllib.request.urlopen(req, timeout=60) as resp:
            html = resp.read().decode("utf-8")
        parser = SectionIds()
        parser.feed(html)
        out[url] = sorted(parser.ids)
        print(f"{len(parser.ids):4d} anchors  {url}")
    with open(ANCHORS, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(out, fh, indent=1)
        fh.write("\n")
    print(f"wrote {ANCHORS.name}: {len(out)} snapshots")


if __name__ == "__main__":
    main()
