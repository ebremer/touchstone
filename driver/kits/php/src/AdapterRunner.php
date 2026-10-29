<?php

declare(strict_types=1);

namespace Example\LwsDriver;

/**
 * The protocol, so that your adapter is only your client (driver/PROTOCOL.md sections 2 and 3): it
 * writes the hello, reads one request a line from stdin, calls the method of {@see LwsDriverAdapter}
 * for its operation, and writes one response a line to stdout. stdout carries protocol lines only:
 * log to STDERR. While it serves, echo, print and PHP's own warnings go to STDERR too, so that they
 * cannot break the protocol.
 *
 *     AdapterRunner::serve(new MyAdapter());
 *     exit(0);
 *
 * Copy it as it is: it follows touchstone-driver/1, and nothing in it is particular to a client.
 */
final class AdapterRunner
{
    public const PROTOCOL = 'touchstone-driver/1';

    /** Every operation of section 5 but shutdown, with the interface method each calls. */
    private const METHODS = [
        'configure' => 'configure',
        'discover_storage' => 'discoverStorage',
        'head' => 'head',
        'read' => 'read',
        'list_container' => 'listContainer',
        'create' => 'create',
        'create_container' => 'createContainer',
        'update' => 'update',
        'patch' => 'patch',
        'delete' => 'delete',
        'read_linkset' => 'readLinkset',
        'update_linkset' => 'updateLinkset',
        'patch_linkset' => 'patchLinkset',
        'move' => 'move',
        'request_access' => 'requestAccess',
        'get_access_request' => 'getAccessRequest',
        'list_access_requests' => 'listAccessRequests',
        'cancel_access_request' => 'cancelAccessRequest',
        'grant_access' => 'grantAccess',
        'get_access_grant' => 'getAccessGrant',
        'list_access_grants' => 'listAccessGrants',
        'revoke_access_grant' => 'revokeAccessGrant',
        'subscribe' => 'subscribe',
        'list_subscriptions' => 'listSubscriptions',
        'get_subscription' => 'getSubscription',
        'unsubscribe' => 'unsubscribe',
        'verify_notification' => 'verifyNotification',
        'list_types' => 'listTypes',
        'search' => 'search',
    ];

    private const JSON_FLAGS = JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PRESERVE_ZERO_FRACTION
        | JSON_THROW_ON_ERROR;

    /** Serves the driver on stdin and stdout until end of input or shutdown. */
    public static function serve(LwsDriverAdapter $adapter): void
    {
        ini_set('display_errors', 'stderr');
        ob_start(static function (string $buffer): string {
            fwrite(STDERR, $buffer);
            return '';
        }, 1);
        self::write(self::hello($adapter));
        while (($line = fgets(STDIN)) !== false) {
            if (trim($line) === '') {
                continue;
            }
            try {
                $request = json_decode($line, false, 512, JSON_THROW_ON_ERROR);
            } catch (\JsonException) {
                $request = null;
            }
            if (!$request instanceof \stdClass) {
                self::write(self::failure(null, AdapterError::invalidArguments('a request is a JSON object')));
                continue;
            }
            $id = $request->id ?? null;
            $op = is_string($request->op ?? null) ? $request->op : '';
            if ($op === 'shutdown') {
                self::write(self::success($id, new \stdClass()));
                return;
            }
            try {
                $method = self::METHODS[$op] ?? throw AdapterError::unsupported($op);
                $args = ($request->args ?? null) instanceof \stdClass ? $request->args : new \stdClass();
                $response = self::success($id, self::result($adapter->$method($args)));
            } catch (AdapterError $e) {
                $response = self::failure($id, $e);
            } catch (\InvalidArgumentException $e) {
                $response = self::failure($id, AdapterError::invalidArguments($e->getMessage()));
            } catch (\Throwable $e) {
                // A bug in the adapter or the client: report it, and keep running.
                fwrite(STDERR, $e . "\n");
                $response = self::failure($id, new AdapterError('internal', null, get_class($e) . ': ' . $e->getMessage()));
            }
            self::write($response);
        }
    }

    /**
     * The hello: the client, its authentication, and every operation the adapter implements.
     *
     * @return array<string, mixed>
     */
    public static function hello(LwsDriverAdapter $adapter): array
    {
        $info = $adapter->client();
        $client = ['name' => $info->name];
        if ($info->version !== null) {
            $client['version'] = $info->version;
        }
        if ($info->homepage !== null) {
            $client['homepage'] = $info->homepage;
        }
        $operations = [];
        foreach (self::METHODS as $op => $method) {
            if (self::implemented($adapter, $method)) {
                $operations[] = $op;
            }
        }
        return ['hello' => [
            'protocol' => self::PROTOCOL,
            'client' => $client,
            'language' => 'php',
            'auth' => array_values(array_unique($adapter->auth())),
            'operations' => $operations,
        ]];
    }

    /** Whether the adapter's class implements a method itself, rather than leaving AbstractAdapter's default. */
    private static function implemented(LwsDriverAdapter $adapter, string $method): bool
    {
        return (new \ReflectionMethod($adapter, $method))->getDeclaringClass()->getName() !== AbstractAdapter::class;
    }

    /**
     * A result as the protocol writes it: a JSON object, so [] becomes {}, and a list is a bug.
     *
     * @param array<array-key, mixed>|\stdClass $result
     * @return array<array-key, mixed>|\stdClass
     */
    private static function result(array|\stdClass $result): array|\stdClass
    {
        if ($result === []) {
            return new \stdClass();
        }
        if (is_array($result) && array_is_list($result)) {
            throw new \UnexpectedValueException('a result is a JSON object, not a list');
        }
        return $result;
    }

    /** @return array<string, mixed> */
    private static function success(mixed $id, array|\stdClass $result): array
    {
        return ['id' => $id, 'ok' => true, 'result' => $result];
    }

    /** @return array<string, mixed> */
    private static function failure(mixed $id, AdapterError $e): array
    {
        $error = ['kind' => $e->kind];
        if ($e->status !== null) {
            $error['status'] = $e->status;
        }
        $error['message'] = $e->getMessage() === '' ? $e->kind : $e->getMessage();
        return ['id' => $id, 'ok' => false, 'error' => $error];
    }

    /**
     * Writes one line. A response that JSON cannot encode, such as one with invalid UTF-8, becomes an
     * internal error.
     *
     * @param array<string, mixed> $message
     */
    private static function write(array $message): void
    {
        try {
            $line = json_encode($message, self::JSON_FLAGS);
        } catch (\JsonException $e) {
            fwrite(STDERR, 'the response is not JSON: ' . $e->getMessage() . "\n");
            $error = new AdapterError('internal', null, 'the response is not JSON: ' . $e->getMessage());
            $line = json_encode(self::failure($message['id'] ?? null, $error), self::JSON_FLAGS);
        }
        fwrite(STDOUT, $line . "\n");
        fflush(STDOUT);
    }
}
