<?php

declare(strict_types=1);

namespace Example\LwsDriver;

/**
 * An operation's outcome that is not a result (driver/PROTOCOL.md section 4.2): it is reported to the
 * driver, and the adapter keeps running. A 409 from deleting a non-empty container is exactly what a
 * drive may be looking for.
 */
class AdapterError extends \Exception
{
    /**
     * @param string   $kind   http, authentication, protocol, verification, transport, invalidArguments,
     *                         unsupported or internal
     * @param int|null $status the HTTP status of the response that caused it, or null; required for http
     */
    public function __construct(
        public readonly string $kind,
        public readonly ?int $status,
        string $message,
    ) {
        parent::__construct($message);
    }

    /** A response with a status the operation does not succeed with. */
    public static function http(int $status, string $message): self
    {
        return new self('http', $status, $message);
    }

    /** The operation, or an argument it was given, is not available in this client. */
    public static function unsupported(string $what): self
    {
        return new self('unsupported', null, $what . ' is not available in this client');
    }

    /** The request's arguments are missing or malformed. */
    public static function invalidArguments(string $message): self
    {
        return new self('invalidArguments', null, $message);
    }

    /** No response: connection refused, TLS failure, timeout. */
    public static function transport(string $message): self
    {
        return new self('transport', null, $message);
    }
}
