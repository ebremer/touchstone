"""Locations shared by the definitions checks.

Everything is found relative to the repository, except lws-test-suite, which is a separate
checkout (README.md, "lws-test-suite").
"""
import os
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
DEFS = REPO / "definitions"
LWS10 = DEFS / "lws10"
BUILD = HERE / "build"
JSON_OUT = BUILD / "json"
ANCHORS = HERE / "anchors.json"
RETIRED = HERE / "retired-manifests.txt"


def retired_manifests():
    """The ids of the retired manifests/ tests, which a definition's supersedes may name."""
    lines = RETIRED.read_text(encoding="utf-8").splitlines()
    return [line.strip() for line in lines if line.strip() and not line.startswith("#")]


def lws_test_suite():
    """lws-test-suite's lws10 directory: $LWS_TEST_SUITE, else a sibling checkout, else None."""
    candidates = []
    if os.environ.get("LWS_TEST_SUITE"):
        candidates.append(Path(os.environ["LWS_TEST_SUITE"]))
    candidates.append(REPO.parent / "lws-test-suite" / "lws10")
    for c in candidates:
        if (c / "manifest.jsonld").is_file():
            return c
    return None


def lts_manifests(root):
    """(logical path, file) for each of lws-test-suite's four manifests.

    Its authentication manifests physically live under mnt/user_data/..., reached through
    auth/<suite> links that a checkout may not materialise (a Windows checkout does not, and
    one of the three is a plain file in git), so the physical path is the fallback.
    """
    out = [("lws10/manifest.jsonld", root / "manifest.jsonld")]
    for suite in ("did_key", "oidc", "saml"):
        logical = root / "auth" / suite / "manifest.jsonld"
        physical = root / "mnt" / "user_data" / "outputs" / "lws_tests" / "auth" / suite / "manifest.jsonld"
        out.append((f"lws10/auth/{suite}/manifest.jsonld", logical if logical.is_file() else physical))
    return out
