"""Has W3C published anything newer than the drafts the catalog is baselined on?

Usage:
    python check_published.py [--fetch-dir DIR] <catalog.ttl>...

For each catalog file, reads the dated "this-version" URL from its header, fetches the
document's latest-version URL (https://www.w3.org/TR/<shortname>/) and compares:

- the dated version W3C now serves as latest, and its maturity (Working Draft,
  Discontinued Draft, Candidate Recommendation, ...), against the baseline; and
- every catalogued clause and section anchor against the latest text, with the
  same check check_drift.py makes.

Exits 1 when any document moved (a new dated version, or drift), 0 when the catalog is
current, and 2 when a document could not be fetched or read. It is the scheduled half of
the "spec moved" alarm (DESIGN.md paragraph 8); check_drift.py is the manual half. The
fetched HTML is written to --fetch-dir, when given, so a re-baseline can start from it.
"""
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

THIS_VERSION = re.compile(r"^#\s+this-version:\s+(https://www\.w3\.org/TR/(\d{4})/([A-Z]+)-(.+)-(\d{8})/)\s*$", re.M)
U_URL = re.compile(r'class="u-url"\s+href="(https://www\.w3\.org/TR/\d{4}/([A-Z]+)-[^"]+-(\d{8})/)"')
STATE = re.compile(r'<p id="w3c-state">\s*<a[^>]*>([^<]+)</a>\s*<time[^>]*datetime="([0-9-]+)"')
HERE = Path(__file__).resolve().parent


def fetch(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "touchstone-spec-drift"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def main() -> int:
    args = sys.argv[1:]
    fetch_dir = None
    if "--fetch-dir" in args:
        i = args.index("--fetch-dir")
        fetch_dir = Path(args[i + 1])
        fetch_dir.mkdir(parents=True, exist_ok=True)
        del args[i:i + 2]
    catalogs = [Path(a) for a in args if a.endswith(".ttl")]
    if not catalogs:
        print(__doc__)
        return 2
    moved = broken = False
    for catalog in catalogs:
        header = THIS_VERSION.search(catalog.read_text(encoding="utf-8"))
        if header is None:
            print(f"{catalog}: no '#   this-version:' line in the header")
            broken = True
            continue
        baseline, shortname = header.group(1), header.group(4)
        latest_url = f"https://www.w3.org/TR/{shortname}/"
        try:
            html = fetch(latest_url)
        except Exception as e:  # noqa: BLE001 - any failure to fetch is reported, not raised
            print(f"{shortname}: could not fetch {latest_url}: {e}")
            broken = True
            continue
        this = U_URL.search(html)
        state = STATE.search(html)
        if this is None:
            print(f"{shortname}: {latest_url} names no dated version")
            broken = True
            continue
        label = f"{state.group(1)} of {state.group(2)}" if state else this.group(2)
        if fetch_dir is not None:
            (fetch_dir / f"{shortname}.html").write_text(html, encoding="utf-8")
        if this.group(1) == baseline:
            print(f"{shortname}: current ({label}, {baseline})")
        else:
            print(f"{shortname}: NEW VERSION - W3C now serves {this.group(1)} ({label}); "
                  f"the catalog is baselined on {baseline}")
            moved = True
        spec = (fetch_dir / f"{shortname}.html") if fetch_dir else None
        if spec is None:
            spec = HERE / "build" / f"{shortname}.html"
            spec.parent.mkdir(exist_ok=True)
            spec.write_text(html, encoding="utf-8")
        drift = subprocess.run(
            [sys.executable, str(HERE / "check_drift.py"), "--spec", str(spec), str(catalog)],
            capture_output=True, text=True, encoding="utf-8")
        if drift.returncode != 0:
            moved = True
            print("  " + drift.stdout.strip().replace("\n", "\n  "))
    if broken:
        return 2
    if moved:
        print("The spec moved - re-baseline the catalog (docs/catalog.md, 'Tracking the draft').")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
