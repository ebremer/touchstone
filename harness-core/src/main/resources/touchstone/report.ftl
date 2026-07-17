<#ftl output_format="HTML">
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Touchstone report ${run.runId}</title>
<style>
  body { font-family: system-ui, -apple-system, "Segoe UI", sans-serif; margin: 2rem auto; max-width: 72rem; padding: 0 1rem; color: #1a1a1a; }
  h1, h2, h3 { line-height: 1.2; }
  table { border-collapse: collapse; width: 100%; margin: 1rem 0; font-size: 0.9rem; }
  th, td { border: 1px solid #d8d8d8; padding: 0.35rem 0.55rem; text-align: left; vertical-align: top; }
  th { background: #f2f2f2; }
  tr:nth-child(even) { background: #fafafa; }
  .meta { color: #555; }
  .badge { display: inline-block; padding: 0.05rem 0.45rem; border-radius: 0.6rem; font-size: 0.8rem; font-weight: 600; text-decoration: none; }
  .badge.passed, .row-pass td:last-child { background: #e2f4e5; color: #14601f; }
  .badge.failed, .badge.error, .row-fail td:last-child { background: #fbe1e1; color: #8f1616; }
  .badge.skipped, .row-skipped td:last-child { background: #fdf3d8; color: #7a5b0d; }
  .row-uncovered td:last-child { background: #ededed; color: #666; }
  .verdict { font-weight: 700; padding: 0.5rem 0.8rem; border-radius: 0.4rem; display: inline-block; }
  .verdict.ok { background: #e2f4e5; color: #14601f; }
  .verdict.bad { background: #fbe1e1; color: #8f1616; }
  pre { background: #f6f6f6; border: 1px solid #e0e0e0; padding: 0.7rem; overflow-x: auto; font-size: 0.8rem; }
  .test { border-top: 1px solid #e4e4e4; padding-top: 0.6rem; margin-top: 0.8rem; }
  small { color: #666; font-weight: 400; }
  a { color: #0b5fa5; }
</style>
</head>
<body>
<h1>Touchstone conformance report</h1>
<p class="meta">target <b>${run.targetId}</b> (${run.targetBaseUrl}) &middot; run ${run.runId} &middot; ${run.startedAt}</p>
<p class="verdict ${run.conformant?string('ok','bad')}">
  <#if run.conformant>No MUST-level failures<#else>NON-CONFORMANT &mdash; ${run.mustFailures} MUST-level failure(s)</#if>
</p>
<p>${run.passed} passed &middot; ${run.failed} failed &middot; ${run.errors} errors &middot; ${run.skipped} skipped
   <small>(verdict per DESIGN.md &sect;5.1: MUST failures decide conformance; SHOULD/MAY are advisory)</small></p>

<h2>Coverage by level</h2>
<table>
  <tr><th>Level</th><th>Requirements</th><th>Covered by tests</th><th>With failures</th></tr>
  <#list levels as l>
  <tr><td>${l.level}</td><td>${l.total}</td><td>${l.covered}</td><td>${l.failed}</td></tr>
  </#list>
</table>

<h2>Requirements matrix</h2>
<table>
  <tr><th>Requirement</th><th>Level</th><th>Tests</th><th>Result</th></tr>
  <#list requirements as r>
  <tr id="r-${r.slug}" class="row-${r.result?lower_case}">
    <td>
      <#if r.section?? && r.section?has_content>
        <a href="${r.section}" title="${r.summary}">${r.slug}</a>
      <#else>
        <span title="${r.summary}">${r.slug}</span>
      </#if>
    </td>
    <td>${r.level!''}</td>
    <td><#list r.tests as t><a class="badge ${t.outcome?lower_case}" href="#t-${t.anchor}">${t.id}</a><#sep> </#list></td>
    <td>${r.result}</td>
  </tr>
  </#list>
</table>

<h2>Test results</h2>
<#list tests as t>
<div class="test" id="t-${t.anchor}">
  <h3><span class="badge ${t.outcome?lower_case}">${t.outcome}</span> ${t.id} <small>${t.durationMillis} ms</small></h3>
  <p>verifies:
    <#list t.requirements as r><a href="#r-${r.slug}">${r.slug}</a><#sep>, </#list>
  </p>
  <#if t.detail?has_content><pre>${t.detail}</pre></#if>
</div>
</#list>
</body>
</html>
