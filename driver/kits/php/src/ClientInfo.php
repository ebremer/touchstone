<?php

declare(strict_types=1);

namespace Example\LwsDriver;

/**
 * The client under test, as the hello names it (driver/PROTOCOL.md section 3): it becomes the
 * subject of the session's EARL report.
 */
final class ClientInfo
{
    /**
     * @param string      $name     the client's name
     * @param string|null $version  its version, or null
     * @param string|null $homepage an absolute http(s) URL, or null
     */
    public function __construct(
        public readonly string $name,
        public readonly ?string $version = null,
        public readonly ?string $homepage = null,
    ) {
    }
}
