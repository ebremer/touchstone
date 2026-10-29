<?php

declare(strict_types=1);

namespace Example\LwsDriver;

require_once __DIR__ . '/src/AdapterError.php';
require_once __DIR__ . '/src/ClientInfo.php';
require_once __DIR__ . '/src/LwsDriverAdapter.php';
require_once __DIR__ . '/src/AbstractAdapter.php';
require_once __DIR__ . '/src/AdapterRunner.php';

/**
 * The starting point: an adapter that implements only configure, so every other operation
 * answers unsupported. It passes touchstone driver check as it is. Replace the client
 * name, keep your client in a property that configure builds, and implement the operations your
 * client has, one by one.
 */
final class StubAdapter extends AbstractAdapter
{
    public function client(): ClientInfo
    {
        return new ClientInfo('My LWS client (PHP)', '0.0.0');
    }

    public function auth(): array
    {
        return ['none'];
    }

    public function configure(\stdClass $args): array|\stdClass
    {
        $type = $args->auth->type ?? '';
        if (!in_array($type, $this->auth(), true)) {
            throw AdapterError::unsupported('authentication ' . $type);
        }
        // Build your client here, with the authentication $args->auth describes.
        return [];
    }
}

AdapterRunner::serve(new StubAdapter());
exit(0);
