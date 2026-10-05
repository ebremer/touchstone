(() => {
  'use strict';
  const form = document.getElementById('start-form');
  const button = document.getElementById('start');
  const message = document.getElementById('message');
  const sessionsUrl = new URL('sessions', document.baseURI).href;
  document.getElementById('sessions-url').textContent = sessionsUrl;

  function settings() {
    const client = {};
    for (const field of ['name', 'version', 'homepage']) {
      const value = document.getElementById(field).value.trim();
      if (value) {
        client[field] = value;
      }
    }
    const areas = [...document.querySelectorAll('input[name=area]:checked')].map((box) => box.value);
    const body = { clientUnderTest: client, areas };
    const server = document.querySelector('input[name=server]:checked');
    if (server && server.value) {
      body.proxy = server.value;
    }
    return body;
  }

  // The proxy targets this service fronts, if any, offered beside its own storage.
  (async () => {
    try {
      const response = await fetch(new URL('proxies', document.baseURI).href, { cache: 'no-store' });
      const { proxies } = await response.json();
      if (!proxies || proxies.length === 0) {
        return;
      }
      const choices = document.getElementById('proxy-choices');
      for (const p of proxies) {
        const label = document.createElement('label');
        label.className = 'check';
        const radio = document.createElement('input');
        radio.type = 'radio';
        radio.name = 'server';
        radio.value = p.id;
        radio.disabled = p.held;
        const code = document.createElement('code');
        code.textContent = p.id;
        label.append(radio, ' The proxy target ', code, ', at ' + p.storage + (p.held ? ' (another session holds it)' : ''));
        choices.append(label);
      }
      document.getElementById('servers').hidden = false;
    } catch (e) {
      // no proxy targets to offer
    }
  })();

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const body = settings();
    if (body.areas.length === 0) {
      message.textContent = 'Choose at least one area to test.';
      return;
    }
    button.disabled = true;
    message.textContent = 'Starting a session…';
    try {
      const response = await fetch(sessionsUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      const answer = await response.json();
      if (!response.ok) {
        message.textContent = answer.message || ('The service refused: ' + response.status);
        button.disabled = false;
        return;
      }
      location.assign(answer.pageWithKey);
    } catch (e) {
      message.textContent = 'The service could not be reached.';
      button.disabled = false;
    }
  });
})();
