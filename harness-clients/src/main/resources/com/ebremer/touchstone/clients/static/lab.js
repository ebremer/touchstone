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
    return { clientUnderTest: client, areas };
  }

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
