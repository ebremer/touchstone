(() => {
  'use strict';
  const key = new URLSearchParams(location.hash.slice(1)).get('key');
  const api = location.pathname.replace(/\/page$/, '');
  const $ = (id) => document.getElementById(id);
  const status = $('status');
  let said = 0;
  let last = 0;
  let base = '';
  let timer = null;

  const TRAPS = {
    opaquePageUrls: 'Container pages are opaque URLs: follow first, next, prev and last links.',
    flatResourceUris: 'Resource URLs do not nest under their container: use rel="up" and listings, not paths.',
    opaqueLinksetUrls: 'A linkset is found only through its rel="linkset" link.',
    putOnlyForText: 'Binary resources do not support PUT: check Allow before replacing one.',
    linksetPutOnlyForDataResources: 'Linksets of data resources accept PUT, linksets of containers do not: check Allow first.',
    decoy: 'The root container lists a decoy whose 401 names a realm that does not contain it: send it no token.',
  };

  const AREAS = {
    core: 'Core', authentication: 'Authentication', notifications: 'Notifications', index: 'Index',
  };


  async function call(method, path, body) {
    const headers = { Authorization: 'Bearer ' + key };
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    const response = await fetch(api + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: 'no-store',
    });
    if (response.status === 204) {
      return null;
    }
    const answer = await response.json();
    if (!response.ok) {
      const error = new Error(answer.message || ('HTTP ' + response.status));
      error.status = response.status;
      throw error;
    }
    return answer;
  }

  /** Shows a message in the status line, which the live status then leaves alone for a while. */
  function say(text) {
    status.textContent = text;
    said = Date.now();
  }

  function el(tag, text, className) {
    const node = document.createElement(tag);
    if (text !== undefined && text !== null) {
      node.textContent = String(text);
    }
    if (className) {
      node.className = className;
    }
    return node;
  }

  function button(text, className, onClick) {
    const b = el('button', text, className);
    b.type = 'button';
    b.addEventListener('click', onClick);
    return b;
  }

  function show(session) {
    base = session.storage.replace(/storage\/$/, '');
    $('storage').textContent = session.storage;
    $('guide-storage').textContent = session.storage;
    const proxy = session.proxy;
    $('proxy-box').hidden = !proxy;
    if (proxy) {
      $('proxy-target').textContent = proxy.target;
      $('proxy-faults').textContent = proxy.faults.join(', ');
      $('issuer').textContent = proxy.issuer || 'the server\'s own';
      const auth = $('guide-auth');
      auth.replaceChildren(el('strong', 'Authenticate'), document.createTextNode(' as the server behind the proxy '
        + 'expects: it issues the tokens. alice and bob work if it trusts their identity documents and the '
        + 'session\'s OpenID Provider.'));
    } else {
      $('issuer').textContent = session.authorizationServer.issuer;
    }
    $('op-issuer').textContent = session.openidProvider.issuer;
    const saml = session.samlIdentityProvider;
    $('saml-term').hidden = !saml;
    $('saml-entity').hidden = !saml;
    if (saml) {
      $('saml-entity').replaceChildren(el('code', saml.entityId), el('br'), el('span',
        'Its assertions go to the authorization server as subject_token; the server trusts its key.', 'note'));
    }
    showClients(session.openidProvider.clients);
    showSettings(session);
    $('expires').textContent = new Date(session.expires).toLocaleString()
      + ' (after two idle hours, or a day at most)';
    const rows = $('identities');
    rows.replaceChildren();
    for (const [name, identity] of Object.entries(session.identities)) {
      const tr = el('tr');
      tr.append(el('th', name), el('td'), el('td', identity.role), el('td'), el('td'), el('td'));
      tr.children[0].scope = 'row';
      tr.children[1].append(el('code', identity.webid));
      tr.children[3].append(button('Show', '', () => secrets(name)));
      if (proxy) {
        tr.children[4].append(el('span', 'none', 'note'));
        tr.children[5].append(el('span', 'from the server', 'note'));
      } else {
        tr.children[4].append(button('Get an assertion', '', () => assertion(name)));
        tr.children[5].append(button('Get a token', '', () => token(name)));
      }
      rows.append(tr);
    }
    const traps = $('traps');
    traps.replaceChildren();
    if (session.traps.none) {
      traps.append(el('li', 'None: a proxy session leaves the server as it is.'));
    }
    for (const [name, text] of Object.entries(TRAPS)) {
      if (session.traps[name]) {
        traps.append(el('li', text));
      }
    }
    if (session.traps.indexLagSeconds > 0) {
      traps.append(el('li', 'The type index and search catch up with writes after '
        + session.traps.indexLagSeconds + ' s: do not assume read-your-writes.'));
    }
    $('export-curl').textContent = ['earl', 'junit', 'json'].map((f) => 'curl -sOJ -H "Authorization: Bearer $KEY" \\\n  \''
      + session.exports[f] + '\'').join('\n');
    for (const id of ['guide', 'connect', 'about', 'checklist', 'rules', 'export', 'traffic', 'end-session']) {
      $(id).hidden = false;
    }
  }

  function showSettings(session) {
    const c = session.clientUnderTest || {};
    $('client-title').textContent = c.name ? '· ' + c.name + (c.version ? ' ' + c.version : '') : '';
    $('set-name').value = c.name || '';
    $('set-version').value = c.version || '';
    $('set-homepage').value = c.homepage || '';
    for (const box of document.querySelectorAll('input[name=set-area]')) {
      box.checked = session.areas.includes(box.value);
    }
  }

  $('settings').addEventListener('submit', async (event) => {
    event.preventDefault();
    const client = {};
    for (const [field, id] of [['name', 'set-name'], ['version', 'set-version'], ['homepage', 'set-homepage']]) {
      const value = $(id).value.trim();
      if (value) {
        client[field] = value;
      }
    }
    const areas = [...document.querySelectorAll('input[name=set-area]:checked')].map((box) => box.value);
    if (areas.length === 0) {
      say('Choose at least one area to test.');
      return;
    }
    try {
      showSettings(await call('PATCH', '', { clientUnderTest: client, areas }));
      say('Saved.');
      showResults(await call('GET', '/results'));
    } catch (e) {
      say('Could not save: ' + e.message);
    }
  });

  async function token(name) {
    try {
      const body = await call('POST', '/tokens/' + name);
      $('token-for').textContent = name;
      $('token').value = body.access_token;
      $('token-box').hidden = false;
    } catch (e) {
      say('Could not get a token: ' + e.message);
    }
  }

  async function assertion(name) {
    try {
      const body = await call('POST', '/assertions/' + name);
      $('assertion-for').textContent = name;
      $('assertion').value = body.assertion;
      $('assertion-box').hidden = false;
    } catch (e) {
      say('Could not get an assertion: ' + e.message);
    }
  }

  let download = null;

  async function secrets(name) {
    try {
      const body = await call('GET', '/credentials/' + name);
      $('secret-user').textContent = body.username;
      $('secret-password').textContent = body.password;
      $('secret-kid').textContent = body.verificationMethod;
      const jwk = JSON.stringify(body.privateKeyJwk, null, 2);
      $('secret-key').value = jwk;
      if (download) {
        URL.revokeObjectURL(download);
      }
      download = URL.createObjectURL(new Blob([jwk], { type: 'application/jwk+json' }));
      $('secret-download').href = download;
      $('secret-download').download = name + '.jwk.json';
      $('secrets-box').hidden = false;
    } catch (e) {
      say('Could not get the credentials: ' + e.message);
    }
  }

  function showClients(clients) {
    const list = $('clients');
    list.replaceChildren();
    for (const c of clients) {
      const item = el('li');
      item.append(el('code', c.client_id), el('span', ' → '), el('code', c.redirect_uris.join(', ')));
      list.append(item);
    }
  }

  $('register').addEventListener('submit', async (event) => {
    event.preventDefault();
    const uris = $('redirect-uris').value.split('\n').map((u) => u.trim()).filter((u) => u);
    const request = { redirect_uris: uris };
    if ($('client-id').value.trim()) {
      request.client_id = $('client-id').value.trim();
    }
    try {
      const made = await call('POST', '/clients', request);
      say('Registered ' + made.client_id + '.');
      showClients((await call('GET', '/clients')).clients);
    } catch (e) {
      say('Could not register the client: ' + e.message);
    }
  });

  function relative(url) {
    return base && url.startsWith(base) ? url.slice(base.length - 1) : url;
  }

  const PRESENTED = {
    bearer: 'Bearer header', otherScheme: 'other scheme', query: 'query string', form: 'form body',
    otherHeader: 'another header',
  };

  function presented(a) {
    const places = a.presentation || [];
    if (places.length === 0 || (places.length === 1 && places[0] === 'none')) {
      return '—';
    }
    return places.map((p) => PRESENTED[p] || p).join(' + ') + (a.token ? ' · ' + a.token : '');
  }

  function statusClass(code) {
    return !code ? 's5' : code >= 500 ? 's5' : code >= 400 ? 's4' : code >= 300 ? 's3' : 's2';
  }

  const SIGNATURES = {
    unpublishedKey: 'forged: unpublished key', alteredBody: 'forged: body altered',
    keyidWithoutFragment: 'forged: keyid without fragment', foreignKeyDocument: 'forged: foreign key document',
  };

  function addressed(a) {
    if (a.role === 'delivery') {
      const what = a.deliverySignature === 'genuine' ? 'notification' : SIGNATURES[a.deliverySignature] || a.deliverySignature;
      return a.limit ? what + ', not sent (' + a.limit + ')' : what;
    }
    return a.limit ? 'refused: ' + a.limit : a.role;
  }

  /** The traffic log's rows by exchange number, to show the exchange a failure points at. */
  const exchangeRows = new Map();

  function row(exchange) {
    const a = exchange.annotations;
    const delivery = a.role === 'delivery';
    const tr = el('tr', null, 'exchange');
    tr.tabIndex = 0;
    tr.append(
      el('td', exchange.seq, 'num'),
      el('td', new Date(exchange.at).toLocaleTimeString(), 'num'),
      el('td'),
      el('td', exchange.status || '—', 'num ' + statusClass(exchange.status)),
      el('td', addressed(a)),
      el('td', delivery ? 'the session' : a.identity || '—'),
      el('td', presented(a)),
      delivery ? el('td', 'your inbox')
        : el('td', a.issued ? a.issuedVia : 'built by client' + (a.builtBy ? ' (by ' + a.builtBy + ')' : ''),
          a.issued ? '' : 'built'),
      el('td'),
    );
    const verdicts = tr.children[8];
    const failed = (exchange.rules || []).filter((v) => v.outcome === 'failed');
    const passed = (exchange.rules || []).length - failed.length;
    for (const v of failed) {
      verdicts.append(el('div', '✗ ' + v.rule, 'o-failed'));
    }
    if (passed > 0) {
      verdicts.append(el('div', '✓ ' + passed + ' passed', 'o-passed'));
    }
    const request = tr.children[2];
    request.append(el('span', (delivery ? '→ ' : '') + exchange.method + ' ', 'method'), el('code', relative(exchange.url)));
    const detail = el('tr', null, 'detail');
    detail.hidden = true;
    const cell = el('td');
    cell.colSpan = 9;
    cell.append(el('pre', JSON.stringify({
      request: { method: exchange.method, url: exchange.url, headers: exchange.requestHeaders, body: exchange.requestBody },
      response: { status: exchange.status, headers: exchange.responseHeaders, body: exchange.responseBody },
      annotations: a,
      rules: exchange.rules,
    }, null, 2)));
    detail.append(cell);
    const toggle = () => { detail.hidden = !detail.hidden; };
    tr.addEventListener('click', toggle);
    tr.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        toggle();
      }
    });
    exchangeRows.set(exchange.seq, [tr, detail]);
    return [tr, detail];
  }

  function reveal(seq) {
    const found = exchangeRows.get(seq);
    if (!found || !found[0].isConnected) {
      say('Exchange #' + seq + ' is no longer in the log.');
      return;
    }
    found[1].hidden = false;
    found[0].scrollIntoView({ block: 'center' });
    found[0].focus();
  }

  const OUTCOMES = {
    passed: 'passed', failed: 'failed', cantTell: 'cannot tell', untested: 'untested', inapplicable: 'inapplicable',
  };

  /** Each rule's row in the results table and its task in the checklist, built once and updated in place. */
  const ruleViews = new Map();

  function slug(iri) {
    return iri.slice(iri.lastIndexOf('/') + 1);
  }

  function buildRules(results) {
    const rows = $('rule-rows');
    const tasks = $('tasks');
    rows.replaceChildren();
    tasks.replaceChildren();
    for (const area of Object.keys(AREAS)) {
      const inArea = results.rules.filter((r) => r.area === area);
      if (inArea.length === 0) {
        continue;
      }
      const head = el('tr', null, 'area');
      const th = el('th', AREAS[area]);
      th.colSpan = 5;
      th.scope = 'rowgroup';
      head.append(th);
      rows.append(head);
      const withTasks = inArea.filter((r) => r.task);
      let list = null;
      if (withTasks.length > 0) {
        tasks.append(el('h3', AREAS[area]));
        list = el('ul', null, 'tasks');
        tasks.append(list);
      }
      for (const r of inArea) {
        const view = { head };
        const tr = el('tr');
        const label = el('td');
        label.append(el('div', r.label), el('code', r.rule, 'note'));
        const refs = el('div', null, 'refs');
        for (const req of r.requirements) {
          refs.append(el('span', slug(req), 'req'));
        }
        r.source.forEach((url, i) => {
          const a = el('a', r.source.length > 1 ? 'spec ' + (i + 1) : 'spec');
          a.href = url;
          a.target = '_blank';
          a.rel = 'noopener noreferrer';
          refs.append(a);
        });
        label.append(refs);
        view.outcome = el('td');
        view.trials = el('td', null, 'num');
        view.evidence = el('td');
        tr.append(label, el('td', r.level), view.outcome, view.trials, view.evidence);
        rows.append(tr);
        view.row = tr;
        if (r.task) {
          const item = el('li', null, 'task');
          const text = el('div');
          text.append(el('span', r.task.prompt));
          if (r.task.arm) {
            const arms = el('div', 'Arms the fault ', 'note');
            arms.append(el('code', r.task.arm));
            text.append(arms);
          }
          const meta = el('div', null, 'note');
          view.taskOutcome = el('span');
          meta.append(el('span', r.level + ' · ' + r.label + ' · '), view.taskOutcome);
          if (r.inapplicableBecause === 'proxy') {
            item.append(el('span', 'Not here', 'note'), text, meta);
            text.append(el('div', 'A proxy session cannot judge this rule, so its task does nothing here.', 'note'));
          } else {
            item.append(button('Start task', 'copy', () => startTask(r.rule)), text, meta);
          }
          list.append(item);
          view.task = item;
        }
        view.shown = '';
        ruleViews.set(r.rule, view);
      }
    }
  }

  function showResults(results) {
    if (ruleViews.size !== results.rules.length || !results.rules.every((r) => ruleViews.has(r.rule))) {
      ruleViews.clear();
      buildRules(results);
    }
    $('verdict').textContent = results.verdict.text.charAt(0).toUpperCase() + results.verdict.text.slice(1) + '.';
    const c = results.counts;
    $('tally').textContent = c.passed + ' passed · ' + c.failed + ' failed · ' + c.untested + ' untested'
      + (c.cantTell ? ' · ' + c.cantTell + ' undecided' : '') + (c.inapplicable ? ' · ' + c.inapplicable + ' inapplicable' : '')
      + ' · results since ' + new Date(results.since).toLocaleTimeString() + '.';
    const filter = $('filter').value;
    const visibleAreas = new Set();
    for (const r of results.rules) {
      const view = ruleViews.get(r.rule);
      const shown = JSON.stringify([r.outcome, r.trials, r.failed, r.evidence]);
      if (shown !== view.shown) {
        view.shown = shown;
        view.outcome.replaceChildren(el('span', OUTCOMES[r.outcome] || r.outcome, 'o-' + r.outcome));
        view.trials.textContent = r.trials;
        view.evidence.replaceChildren();
        if (r.evidence) {
          const ev = r.evidence;
          const link = button('#' + ev.seq + ' ' + ev.method + ' ' + relative(ev.url) + (ev.status ? ' → ' + ev.status : ''),
            'evidence', () => reveal(ev.seq));
          view.evidence.append(link,
            el('div', ev.term + ': expected ' + ev.expected + '; was ' + ev.actual, 'note'),
            el('div', r.guidance));
          if (r.failed > 1) {
            view.evidence.append(el('div', r.failed + ' of ' + r.trials + ' trials failed.', 'note'));
          }
        }
        if (view.taskOutcome) {
          view.taskOutcome.replaceChildren(el('span', OUTCOMES[r.outcome] || r.outcome, 'o-' + r.outcome));
        }
      }
      const visible = filter === 'all' || r.outcome === filter || (filter === 'failed' && r.outcome === 'cantTell');
      view.row.hidden = !visible;
      if (visible) {
        visibleAreas.add(view.head);
      }
    }
    for (const view of ruleViews.values()) {
      view.head.hidden = !visibleAreas.has(view.head);
    }
  }

  let latest = null;

  $('filter').addEventListener('change', () => {
    if (latest) {
      showResults(latest);
    }
  });

  async function startTask(rule) {
    try {
      await call('POST', '/tasks/' + encodeURIComponent(rule));
      say('Task started: do what it says with your client now.');
    } catch (e) {
      say('Could not start the task: ' + e.message);
    }
  }

  async function poll() {
    try {
      const body = await call('GET', '/exchanges?after=' + last + '&limit=200');
      const log = $('log');
      for (const exchange of body.exchanges) {
        const [tr, detail] = row(exchange);
        log.prepend(detail);
        log.prepend(tr);
      }
      last = body.last;
      latest = await call('GET', '/results');
      showResults(latest);
      $('counts').textContent = body.recorded + ' recorded'
        + (body.dropped ? ', the oldest ' + body.dropped + ' dropped' : '') + '.';
      if (Date.now() - said > 8000) {
        status.textContent = 'Session ' + api.split('/').pop() + ' is live.';
      }
      timer = setTimeout(poll, body.exchanges.length === 200 ? 100 : 2000);
    } catch (e) {
      if (e.status === 404 || e.status === 401) {
        say('This session has ended.');
        return;
      }
      say('Lost touch with the service; retrying…');
      timer = setTimeout(poll, 5000);
    }
  }

  async function exportResults(format) {
    try {
      const response = await fetch(api + '/results?format=' + format, {
        headers: { Authorization: 'Bearer ' + key },
        cache: 'no-store',
      });
      if (!response.ok) {
        throw new Error('HTTP ' + response.status);
      }
      const disposition = response.headers.get('Content-Disposition') || '';
      const named = /filename="([^"]+)"/.exec(disposition);
      const url = URL.createObjectURL(await response.blob());
      const a = el('a');
      a.href = url;
      a.download = named ? named[1] : 'touchstone-results.' + format;
      document.body.append(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
      say('Exported ' + a.download + '.');
    } catch (e) {
      say('Could not export: ' + e.message);
    }
  }

  for (const b of document.querySelectorAll('button[data-export]')) {
    b.addEventListener('click', () => exportResults(b.dataset.export));
  }

  // In-page links scroll without touching the address, whose fragment holds the session key.
  for (const a of document.querySelectorAll('a[href^="#"]')) {
    a.addEventListener('click', (event) => {
      event.preventDefault();
      const target = $(a.getAttribute('href').slice(1));
      if (target) {
        target.scrollIntoView({ block: 'start' });
      }
    });
  }

  for (const b of document.querySelectorAll('button.copy[data-copy]')) {
    b.addEventListener('click', async () => {
      const source = $(b.dataset.copy);
      const text = source.value !== undefined ? source.value : source.textContent;
      try {
        await navigator.clipboard.writeText(text);
        b.textContent = 'Copied';
        setTimeout(() => { b.textContent = 'Copy'; }, 1500);
      } catch (e) {
        say('Copy failed; select the text instead.');
      }
    });
  }

  $('reset').addEventListener('click', async () => {
    try {
      await call('POST', '/reset');
      latest = await call('GET', '/results');
      showResults(latest);
      say('Results reset: the storage and the log stay.');
    } catch (e) {
      say('Could not reset the results: ' + e.message);
    }
  });

  $('end').addEventListener('click', async () => {
    if (!confirm('End this session? Its storage, log and results are deleted.')) {
      return;
    }
    try {
      await call('DELETE', '');
    } catch (e) {
      // already gone
    }
    clearTimeout(timer);
    for (const id of ['guide', 'connect', 'about', 'checklist', 'export', 'end-session']) {
      $(id).hidden = true;
    }
    say('This session has ended.');
  });

  (async () => {
    if (!key) {
      say('This page needs the session key in its address (…/page#key=…).');
      return;
    }
    try {
      show(await call('GET', ''));
      poll();
    } catch (e) {
      say(e.status === 401 ? 'The session key in this address is wrong.'
        : e.status === 404 ? 'This session has ended.' : 'The service could not be reached.');
    }
  })();
})();
