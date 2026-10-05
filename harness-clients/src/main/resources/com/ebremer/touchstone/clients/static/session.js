(() => {
  'use strict';
  const key = new URLSearchParams(location.hash.slice(1)).get('key');
  const api = location.pathname.replace(/\/page$/, '');
  const $ = (id) => document.getElementById(id);
  const status = $('status');
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

  function show(session) {
    base = session.storage.replace(/storage\/$/, '');
    $('storage').textContent = session.storage;
    $('issuer').textContent = session.authorizationServer.issuer;
    $('op-issuer').textContent = session.openidProvider.issuer;
    showClients(session.openidProvider.clients);
    $('expires').textContent = new Date(session.expires).toLocaleString()
      + ' (after two idle hours, or a day at most)';
    const rows = $('identities');
    rows.replaceChildren();
    for (const [name, identity] of Object.entries(session.identities)) {
      const tr = el('tr');
      tr.append(el('th', name), el('td'), el('td', identity.role), el('td'), el('td'));
      tr.children[0].scope = 'row';
      tr.children[1].append(el('code', identity.webid));
      const reveal = el('button', 'Show');
      reveal.type = 'button';
      reveal.addEventListener('click', () => secrets(name));
      tr.children[3].append(reveal);
      const button = el('button', 'Get a token');
      button.type = 'button';
      button.addEventListener('click', () => token(name));
      tr.children[4].append(button);
      rows.append(tr);
    }
    const traps = $('traps');
    traps.replaceChildren();
    for (const [name, text] of Object.entries(TRAPS)) {
      if (session.traps[name]) {
        traps.append(el('li', text));
      }
    }
    if (session.traps.indexLagSeconds > 0) {
      traps.append(el('li', 'The type index and search catch up with writes after '
        + session.traps.indexLagSeconds + ' s: do not assume read-your-writes.'));
    }
    $('connect').hidden = false;
    $('rules').hidden = false;
    $('traffic').hidden = false;
  }

  async function token(name) {
    try {
      const body = await call('POST', '/tokens/' + name);
      $('token-for').textContent = name;
      $('token').value = body.access_token;
      $('token-box').hidden = false;
    } catch (e) {
      status.textContent = 'Could not get a token: ' + e.message;
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
      status.textContent = 'Could not get the credentials: ' + e.message;
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
      status.textContent = 'Registered ' + made.client_id + '.';
      showClients((await call('GET', '/clients')).clients);
    } catch (e) {
      status.textContent = 'Could not register the client: ' + e.message;
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
    return code >= 500 ? 's5' : code >= 400 ? 's4' : code >= 300 ? 's3' : 's2';
  }

  function row(exchange) {
    const a = exchange.annotations;
    const tr = el('tr', null, 'exchange');
    tr.tabIndex = 0;
    tr.append(
      el('td', exchange.seq, 'num'),
      el('td', new Date(exchange.at).toLocaleTimeString(), 'num'),
      el('td'),
      el('td', exchange.status, 'num ' + statusClass(exchange.status)),
      el('td', a.limit ? 'refused: ' + a.limit : a.role),
      el('td', a.identity || '—'),
      el('td', presented(a)),
      el('td', a.issued ? a.issuedVia : 'built by client' + (a.builtBy ? ' (by ' + a.builtBy + ')' : ''),
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
    request.append(el('span', exchange.method + ' ', 'method'), el('code', relative(exchange.url)));
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
    return [tr, detail];
  }

  const OUTCOMES = {
    passed: 'passed', failed: 'failed', cantTell: 'cannot tell', untested: 'untested', inapplicable: 'inapplicable',
  };

  function showResults(results) {
    $('verdict').textContent = results.verdict.text.charAt(0).toUpperCase() + results.verdict.text.slice(1) + '.';
    const rows = $('rule-rows');
    rows.replaceChildren();
    for (const r of results.rules) {
      const tr = el('tr');
      const label = el('td');
      label.append(el('div', r.label), el('code', r.rule, 'note'));
      if (r.task) {
        const task = el('div', null, 'task');
        const start = el('button', 'Start task', 'copy');
        start.type = 'button';
        start.addEventListener('click', () => startTask(r.rule));
        task.append(el('span', 'Task: ' + r.task.prompt + ' '), start);
        label.append(task);
      }
      const evidence = el('td');
      if (r.evidence) {
        evidence.append(el('div', '#' + r.evidence.seq + ' ' + r.evidence.method + ' ' + relative(r.evidence.url)),
          el('div', r.evidence.term + ': expected ' + r.evidence.expected + '; was ' + r.evidence.actual, 'note'),
          el('div', r.guidance));
      }
      tr.append(label, el('td', r.level), el('td', OUTCOMES[r.outcome] || r.outcome, 'o-' + r.outcome),
        el('td', r.trials, 'num'), evidence);
      rows.append(tr);
    }
  }

  async function startTask(rule) {
    try {
      await call('POST', '/tasks/' + encodeURIComponent(rule));
      status.textContent = 'Task started: do what it says with your client now.';
    } catch (e) {
      status.textContent = 'Could not start the task: ' + e.message;
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
      showResults(await call('GET', '/results'));
      $('counts').textContent = body.recorded + ' recorded'
        + (body.dropped ? ', the oldest ' + body.dropped + ' dropped' : '') + '.';
      status.textContent = 'Session ' + api.split('/').pop() + ' is live.';
      timer = setTimeout(poll, body.exchanges.length === 200 ? 100 : 2000);
    } catch (e) {
      if (e.status === 404 || e.status === 401) {
        status.textContent = 'This session has ended.';
        return;
      }
      status.textContent = 'Lost touch with the service; retrying…';
      timer = setTimeout(poll, 5000);
    }
  }

  for (const button of document.querySelectorAll('button.copy')) {
    button.addEventListener('click', async () => {
      const source = $(button.dataset.copy);
      const text = source.value !== undefined ? source.value : source.textContent;
      try {
        await navigator.clipboard.writeText(text);
        button.textContent = 'Copied';
        setTimeout(() => { button.textContent = 'Copy'; }, 1500);
      } catch (e) {
        status.textContent = 'Copy failed; select the text instead.';
      }
    });
  }

  $('reset').addEventListener('click', async () => {
    try {
      await call('POST', '/reset');
      showResults(await call('GET', '/results'));
    } catch (e) {
      status.textContent = 'Could not reset the results: ' + e.message;
    }
  });

  $('end').addEventListener('click', async () => {
    if (!confirm('End this session? Its storage and log are deleted.')) {
      return;
    }
    try {
      await call('DELETE', '');
    } catch (e) {
      // already gone
    }
    clearTimeout(timer);
    $('connect').hidden = true;
    status.textContent = 'This session has ended.';
  });

  (async () => {
    if (!key) {
      status.textContent = 'This page needs the session key in its address (…/page#key=…).';
      return;
    }
    try {
      show(await call('GET', ''));
      poll();
    } catch (e) {
      status.textContent = e.status === 401 ? 'The session key in this address is wrong.'
        : e.status === 404 ? 'This session has ended.' : 'The service could not be reached.';
    }
  })();
})();
