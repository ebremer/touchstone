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
    decoy: 'The root container lists a decoy whose 401 names a realm that does not contain it: send it no token.',
  };

  async function call(method, path) {
    const response = await fetch(api + path, {
      method,
      headers: { Authorization: 'Bearer ' + key },
      cache: 'no-store',
    });
    if (response.status === 204) {
      return null;
    }
    const body = await response.json();
    if (!response.ok) {
      const error = new Error(body.message || ('HTTP ' + response.status));
      error.status = response.status;
      throw error;
    }
    return body;
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
    $('expires').textContent = new Date(session.expires).toLocaleString()
      + ' (after two idle hours, or a day at most)';
    const rows = $('identities');
    rows.replaceChildren();
    for (const [name, identity] of Object.entries(session.identities)) {
      const tr = el('tr');
      tr.append(el('th', name), el('td'), el('td', identity.role), el('td'));
      tr.children[0].scope = 'row';
      tr.children[1].append(el('code', identity.webid));
      const button = el('button', 'Get a token');
      button.type = 'button';
      button.addEventListener('click', () => token(name));
      tr.children[3].append(button);
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

  function relative(url) {
    return base && url.startsWith(base) ? url.slice(base.length - 1) : url;
  }

  const PRESENTED = { authorization: 'header', query: 'query string', form: 'form body' };

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
      el('td', a.presentation === 'none' ? '—'
        : (PRESENTED[a.presentation] || a.presentation) + (a.token ? ' · ' + a.token : '')),
      el('td', a.issued ? a.issuedVia : 'built by client', a.issued ? '' : 'built'),
    );
    const request = tr.children[2];
    request.append(el('span', exchange.method + ' ', 'method'), el('code', relative(exchange.url)));
    const detail = el('tr', null, 'detail');
    detail.hidden = true;
    const cell = el('td');
    cell.colSpan = 8;
    cell.append(el('pre', JSON.stringify({
      request: { method: exchange.method, url: exchange.url, headers: exchange.requestHeaders, body: exchange.requestBody },
      response: { status: exchange.status, headers: exchange.responseHeaders, body: exchange.responseBody },
      annotations: a,
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
