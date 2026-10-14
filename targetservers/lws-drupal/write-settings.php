<?php

/**
 * @file
 * Writes sites/default/settings.php from the environment, at every start.
 *
 * PHP-FPM clears the environment of its workers, so the values are written in
 * rather than read with getenv() at run time.
 */

declare(strict_types=1);

$env = static function (string $name, ?string $default = NULL): string {
  $value = getenv($name);
  if ($value === FALSE || $value === '') {
    if ($default === NULL) {
      fwrite(STDERR, "$name must be set\n");
      exit(1);
    }
    return $default;
  }
  return $value;
};

$public = $env('LWS_PUBLIC_URL');
$host = parse_url($public, PHP_URL_HOST);
if (!is_string($host) || $host === '') {
  fwrite(STDERR, "LWS_PUBLIC_URL has no host: $public\n");
  exit(1);
}
$database = [
  'driver' => 'mysql',
  'namespace' => 'Drupal\\mysql\\Driver\\Database\\mysql',
  'autoload' => 'core/modules/mysql/src/Driver/Database/mysql/',
  'host' => $env('LWS_DRUPAL_DB_HOST', 'lws-drupal-db'),
  'port' => $env('LWS_DRUPAL_DB_PORT', '3306'),
  'database' => $env('LWS_DRUPAL_DB_NAME', 'drupal'),
  'username' => $env('LWS_DRUPAL_DB_USER', 'drupal'),
  'password' => $env('LWS_DRUPAL_DB_PASSWORD'),
  'prefix' => '',
  'collation' => 'utf8mb4_general_ci',
  'isolation_level' => 'READ COMMITTED',
];
$salt = trim((string) file_get_contents('/data/hash_salt'));
$hosts = ['^' . preg_quote($host) . '$', '^localhost$'];

$php = "<?php\n\n// Written by the container's entrypoint at every start, from its environment; edits here are lost.\n\n"
  . '$databases[\'default\'][\'default\'] = ' . var_export($database, TRUE) . ";\n"
  . '$settings[\'hash_salt\'] = ' . var_export($salt, TRUE) . ";\n"
  . "\$settings['config_sync_directory'] = '/data/config/sync';\n"
  . "\$settings['file_private_path'] = '/data/private';\n"
  . "\$settings['file_temp_path'] = '/tmp';\n"
  . '$settings[\'trusted_host_patterns\'] = ' . var_export($hosts, TRUE) . ";\n"
  // The router, on the compose network: its X-Forwarded-* headers are trusted.
  . "\$settings['reverse_proxy'] = TRUE;\n"
  . "\$settings['reverse_proxy_addresses'] = ['10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'];\n"
  . "\$settings['update_free_access'] = FALSE;\n"
  . "\$settings['skip_permissions_hardening'] = TRUE;\n"
  // Origins it may fetch from although they are private or plain HTTP (OutboundHttp), space separated.
  . '$settings[\'lws_outbound_allowlist\'] = ' . var_export(preg_split('/\s+/', $env('LWS_DRUPAL_OUTBOUND_ALLOWLIST', ' '), -1, PREG_SPLIT_NO_EMPTY), TRUE) . ";\n";

file_put_contents('/opt/drupal/web/sites/default/settings.php', $php);
chmod('/opt/drupal/web/sites/default/settings.php', 0444);
