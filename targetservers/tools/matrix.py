#!/usr/bin/env python3
"""A requirements matrix of Touchstone runs, one column per server, as a standalone HTML page (run-all.sh writes it).

    matrix.py --server <name> <run-dir> <version> <language> [--server ...] > matrix.html

A run dir is a Touchstone report bundle (holding report.html); a version is "<label>" or "<label>|<url>". All runs
must cover the same requirements, as runs of one Touchstone do. The layout is that of the LWS conformance page this
was taken from: a summary per server, then each requirement with its tests and each server's result.
"""
import argparse
import html
import re
from datetime import datetime, timezone


RES = {'PASS': ('#e2f4e5', '#14601f'), 'FAIL': ('#fbe1e1', '#8f1616'),
       'INAPPLICABLE': ('#fdf3d8', '#7a5b0d'), 'UNCOVERED': ('#ededed', '#666'), 'NOT TESTED': ('#ededed', '#666')}
BADGE = {'passed': ('#e2f4e5', '#14601f'), 'failed': ('#fbe1e1', '#8f1616'),
         'canttell': ('#fbe1e1', '#8f1616'), 'inapplicable': ('#fdf3d8', '#7a5b0d'),
         'untested': ('#fdf3d8', '#7a5b0d')}
WORST_FIRST = ['failed', 'canttell', 'inapplicable', 'untested', 'passed']
TD = 'border:1px solid #d8d8d8;padding:0.35rem 0.55rem;text-align:left;vertical-align:top;'
TH = TD + 'background:#f2f2f2;'
# A server that is not conformant has its name on the FAIL colours, in the summary and over its column.
NOT_CONFORMANT = 'background:#fbe1e1;color:#8f1616;'
NAME_NOT_CONFORMANT = NOT_CONFORMANT + 'padding:0 0.3rem;border-radius:0.2rem;'
# The row of programming languages under the server names: light blue, label cell included.
LANGUAGE_ROW = 'background:#e3f0fb;color:#1c3d5a;'


def conformant(r):
    return r['verdict'].startswith('CONFORMANT')


def report(run_dir):
    src = open(f'{run_dir}/report.html', encoding='utf-8').read()
    m = re.search(r'run ([0-9a-f]+) &middot; (\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.\d+)?Z', src)
    run_id, started = m.group(1), datetime.fromisoformat(m.group(2)).replace(tzinfo=timezone.utc)
    t = re.search(r'(\d+) tests: (\d+) passed &middot; (\d+) failed &middot; (\d+) cantTell'
                  r' &middot; (\d+) inapplicable', src)
    verdict = re.search(r'<p class="verdict [a-z]+">\s*(.*?)\s*</p>', src, re.S).group(1)
    verdict = re.sub(r'\s+', ' ', verdict.replace('&mdash;', ':'))
    matrix = src[src.index('<h2>Requirements matrix</h2>'):src.index('<h2>Test results</h2>')]
    rows = {}
    for rid, body in re.findall(r'<tr id="(r-[^"]*)" class="row-[a-z]+">(.*?)</tr>', matrix, re.S):
        rows[rid] = [c.strip() for c in re.findall(r'<td>(.*?)</td>', body, re.S)]
    return dict(run=run_id, started=started, tests=t.groups(), verdict=verdict, rows=rows)


def outcome(cell, name):
    m = re.search(r'<a class="badge ([a-z]+)" href="[^"]*">' + re.escape(name) + '</a>', cell)
    return m.group(1) if m else None


def version_link(version):
    label, _, url = version.partition('|')
    return f'<a href="{html.escape(url)}">{html.escape(label)}</a>' if url else html.escape(label)


def summary(name, version, r):
    v = version_link(version)
    total, passed, failed, canttell, inapplicable = r['tests']
    when = r['started'].strftime('%Y-%m-%d %H:%M:%S UTC')
    cant = f', {canttell} cantTell' if canttell != '0' else ''
    if r['verdict'].startswith('CONFORMANT'):
        verdict = 'Conformant: no MUST test failed'
        if failed != '0':
            verdict += f' ({failed} SHOULD or MAY test{"" if failed == "1" else "s"} failed, which is advisory)'
        verdict += '.'
    else:
        n = re.search(r'(\d+) MUST', r['verdict'])
        k = n.group(1) if n else '?'
        verdict = f'Not conformant: {k} MUST test{"" if k == "1" else "s"} failed{" or ended cantTell" if canttell != "0" else ""}.'
    strong = '<strong>' if conformant(r) else f'<strong style="{NAME_NOT_CONFORMANT}">'
    return (f'<li>{strong}{name}</strong> [ {v} ] &mdash; tested {when} (Touchstone run {r["run"]}): '
            f'{total} tests, {passed} passed, {failed} failed{cant}, {inapplicable} inapplicable. '
            f'{verdict}</li>')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--server', nargs=4, action='append', required=True,
                        metavar=('NAME', 'RUN_DIR', 'VERSION', 'LANGUAGE'), help='a server and its Touchstone run')
    args = parser.parse_args()
    servers = [(name, version, report(run_dir), language) for name, run_dir, version, language in args.server]
    first = servers[0][2]
    for name, _, r, _ in servers[1:]:
        assert set(r['rows']) == set(first['rows']), f'{name} covers other requirements than {servers[0][0]}'

    def res(v):
        bg, fg = RES[v]
        return f'<td style="{TD}background:{bg};color:{fg};font-weight:600;">{v}</td>'

    def pill(name, outcomes):
        bg, fg = BADGE[min([o for o in outcomes if o], key=WORST_FIRST.index)]
        return (f'<span style="display:inline-block;margin:0.1rem 0;padding:0.05rem 0.45rem;'
                f'border-radius:0.6rem;font-size:0.8rem;font-weight:600;background:{bg};color:{fg};">'
                f'{name}</span>')

    rows = []
    for rid, h in first['rows'].items():
        cells = [r['rows'][rid] for _, _, r, _ in servers]
        names = re.findall(r'<a class="badge [a-z]+" href="[^"]*">([^<]*)</a>', h[2])
        badges = ' '.join(pill(n, [outcome(c[2], n) for c in cells]) for n in names)
        results = ''.join(res(c[3]) for c in cells)
        rows.append(f'<tr><td style="{TD}">{h[0]}</td><td style="{TD}">{h[1]}</td><td style="{TD}">{badges}</td>{results}</tr>')

    items = [summary(name, version, r) for name, version, r, _ in servers]
    heads = ''.join(f'<th style="{TH}{"" if conformant(r) else NOT_CONFORMANT}">{html.escape(n)}</th>' for n, _, r, _ in servers)
    languages = ''.join(f'<td style="{TD}{LANGUAGE_ROW}">{html.escape(lang)}</td>' for _, _, _, lang in servers)
    nl = chr(10)
    print(f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>LWS conformance matrix</title>
<style>body {{ font-family: system-ui, sans-serif; margin: 1.5rem; color: #222; background: #fff; }}</style>
</head>
<body>
<h1>LWS conformance matrix</h1>
<p>Results of the <a href="https://github.com/ebremer/touchstone">Touchstone</a> conformance harness for the
<a href="https://w3c.github.io/lws-protocol/">W3C Linked Web Storage (LWS)</a> protocol, run against the target servers.
Each row is a requirement from the LWS specifications, the tests that exercise it, and the result for each server.</p>
<ul>
{nl.join(items)}
</ul>
<p><small>PASS / FAIL: every test covering the requirement passed / at least one failed or ended cantTell. INAPPLICABLE:
the tests need a capability that the deployment did not provide, so there is no evidence either way. UNCOVERED: no
Touchstone test exercises the requirement yet. A test name is coloured by its worse outcome across the servers. The name
of a server that is not conformant is shown on red. Times are when each run started.</small></p>
<table style="border-collapse:collapse;width:100%;margin:1rem 0;font-size:0.9rem;">
<thead><tr><th style="{TH}">Requirement</th><th style="{TH}">Level</th><th style="{TH}">Tests</th>{heads}</tr>
<tr><th colspan="3" style="{TH}{LANGUAGE_ROW}text-align:right;font-weight:normal;font-style:italic;">Language</th>{languages}</tr></thead>
<tbody>
{nl.join(rows)}
</tbody>
</table>
</body>
</html>''')


if __name__ == '__main__':
    main()
