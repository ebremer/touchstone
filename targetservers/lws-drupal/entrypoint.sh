#!/bin/bash
# Starts the lws-drupal target: settings.php from the environment, the site installed and its storage made at the
# first start, the modules' database updates applied at every start, then PHP-FPM and nginx (../README.md).
#
#   LWS_PUBLIC_URL          the public base URL, e.g. https://host.example/targets/lws-drupal (lws.settings:base_url,
#                           set at every start). The proxy strips its path. The authorization server's issuer is
#                           this URL, and the storage is LWS_PUBLIC_URL/lws/<LWS_DRUPAL_STORAGE>/.
#   LWS_OWNER               the agent with full control of the storage (its controller), set when it is made.
#   LWS_PAGE_SIZE           members per page of its container listings (default 4), set when it is made.
#   LWS_DRUPAL_STORAGE      the storage's slug (default touchstone).
#   LWS_DRUPAL_OUTBOUND_ALLOWLIST
#                           origins the site may fetch from although they are private or plain HTTP, space separated
#                           ($settings['lws_outbound_allowlist']). Empty by default.
#   LWS_EXTRA_CA            a PEM file of certificates to trust besides the system's (a private test CA).
#   LWS_DRUPAL_DB_PASSWORD  the database password (required); LWS_DRUPAL_DB_HOST, _PORT, _NAME and _USER default to
#                           lws-drupal-db, 3306, drupal and drupal.
set -eu
: "${LWS_PUBLIC_URL:?LWS_PUBLIC_URL must be the public base URL, e.g. https://host.example/targets/lws-drupal}"
: "${LWS_OWNER:?LWS_OWNER must name the agent that controls the storage}"
slug=${LWS_DRUPAL_STORAGE:-touchstone}
drush() { runuser -u www-data -- /opt/drupal/vendor/bin/drush --root=/opt/drupal/web --uri="${LWS_PUBLIC_URL%/}" "$@"; }

if [ -n "${LWS_EXTRA_CA:-}" ]; then
  cp "$LWS_EXTRA_CA" /usr/local/share/ca-certificates/lws-extra-ca.crt && update-ca-certificates >/dev/null
fi
mkdir -p /data/private /data/public /data/config/sync
[ -s /data/hash_salt ] || php -r 'echo bin2hex(random_bytes(32)), "\n";' > /data/hash_salt
chown -R www-data:www-data /data
chmod 700 /data/private
rm -rf /opt/drupal/web/sites/default/files
ln -s /data/public /opt/drupal/web/sites/default/files
php /usr/local/lib/lws-drupal/write-settings.php

# Wait for the database.
tries=0
until php -r '
  $h = getenv("LWS_DRUPAL_DB_HOST") ?: "lws-drupal-db"; $p = getenv("LWS_DRUPAL_DB_PORT") ?: "3306";
  $n = getenv("LWS_DRUPAL_DB_NAME") ?: "drupal"; $u = getenv("LWS_DRUPAL_DB_USER") ?: "drupal";
  new PDO("mysql:host=$h;port=$p;dbname=$n", $u, getenv("LWS_DRUPAL_DB_PASSWORD"));' 2>/dev/null; do
  tries=$((tries + 1))
  [ "$tries" -lt 90 ] || { echo "the database at ${LWS_DRUPAL_DB_HOST:-lws-drupal-db} does not answer" >&2; exit 1; }
  sleep 2
done

if [ "$(drush status --field=bootstrap 2>/dev/null)" != Successful ]; then
  echo "installing Drupal and the LWS modules"
  drush site:install minimal -y --site-name=lws-drupal --account-name=admin \
    --account-pass="$(php -r 'echo bin2hex(random_bytes(16));')"
  drush pm:install -y lws lws_authz lws_storage lws_index lws_notify
  drush config:set -y --input-format=yaml lws_index.settings content_types.enabled true
fi
# A site an earlier image made gets what newer code needs (tables, settings); a new one has nothing to update.
drush updatedb -y
drush config:set -y lws.settings base_url "${LWS_PUBLIC_URL%/}"
if ! drush lws:storage:list --format=csv --fields=slug 2>/dev/null | grep -qx "$slug"; then
  drush lws:storage:create "$slug" --controller="$LWS_OWNER" --page-size="${LWS_PAGE_SIZE:-4}"
fi
# The authorization server's first signing key, made now rather than at the first token, so that it can be read
# (for HarnessIssuedTokens) before any token exchange.
if ! ls /data/private/lws_authz/keys/*.jwk >/dev/null 2>&1; then
  drush lws:key:rotate
fi
drush cache:rebuild

php-fpm -F &
nginx -g 'daemon off;' &
# Either one ending ends the container, so that it is restarted whole.
wait -n
exit 1
