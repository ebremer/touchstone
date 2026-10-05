(() => {
  'use strict';
  const button = document.getElementById('start');
  const message = document.getElementById('message');
  const sessionsUrl = new URL('sessions', document.baseURI).href;
  document.getElementById('sessions-url').textContent = sessionsUrl;
  button.addEventListener('click', async () => {
    button.disabled = true;
    message.textContent = 'Starting a session…';
    try {
      const response = await fetch(sessionsUrl, { method: 'POST' });
      const body = await response.json();
      if (!response.ok) {
        message.textContent = body.message || ('The service refused: ' + response.status);
        button.disabled = false;
        return;
      }
      location.assign(body.pageWithKey);
    } catch (e) {
      message.textContent = 'The service could not be reached.';
      button.disabled = false;
    }
  });
})();
